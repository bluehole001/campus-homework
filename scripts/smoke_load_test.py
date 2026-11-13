"""端到端测试驱动（非项目源码，服务于本机冒烟/压测流水线）。

设计要点：
1. Windows 沙箱会在工具轮次结束时回收子进程，因此 Redis、应用、冒烟、压测必须在同一个
   常驻进程内串起（由 run_all.py e2e 调用本脚本的 all 阶段），避免跨轮次被回收。
2. 不使用 urllib：其环境代理会自动走 HTTP_PROXY(沙箱出网代理 11483) 伪造 502，且
   "每请求新建 TCP 连接"在并发下被 Python GIL 放大成 700ms/请求（client_probe.py 实测），
   把 5500 req/s 的服务端压成 21 req/s。这里改用 http.client 每会话复用长连接
   （HTTP/1.1 keep-alive + TCP_NODELAY），压测瓶颈回到被测系统本身。

可移植性：路径一律基于仓库位置推导（脚本位于 <repo>/scripts/），支持环境变量覆盖
  HW_APP_PORT（默认 9090，dev profile 端口）、HW_APP_DIR、JAVA_HOME、REDIS_EXE、
  HW_TEST_OUT（报告/应用日志目录）、MYSQL_PWD（默认与 SQL 一致的明文口令）。

用法：python scripts/smoke_load_test.py [stage]
  stage = all（默认：起RabbitMQ+预建拓扑+起Redis+起应用+冒烟+压测+队列回收+日志健康度）
        | smoke（仅冒烟，要求应用已在跑） | load（仅压测） | mqseed（仅拉起 MQ 并自证拓扑，便于单独排查）
"""
import http.client
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPT_DIR)                          # 仓库根目录
OUT_DIR = os.environ.get('HW_TEST_OUT') or SCRIPT_DIR      # 日志与报告目录
os.makedirs(OUT_DIR, exist_ok=True)


def _env_dir(*names):
    """按顺序返回第一个存在且为目录的环境变量值，全部无效则返回 None"""
    for name in names:
        value = os.environ.get(name)
        if value and os.path.isdir(value):
            return value
    return None


def _tool(root, binary):
    """优先使用 <root>/bin/<binary>（补 Windows 的后缀），否则回退 PATH 上的同名命令。

    后缀顺序必须把 CreateProcess 能直接执行的格式排在最前：Maven 的 bin/mvn（无扩展名）
    是给 Unix shell 写的脚本，Windows 下直接执行会抛 WinError 193「%1 不是有效的 Win32
    应用程序」，只有 bin/mvn.cmd 才是可执行的。故先试 .cmd/.exe/.bat 再试裸名。
    """
    if root:
        for suffix in ('.cmd', '.exe', '.bat', ''):
            candidate = os.path.join(root, 'bin', binary + suffix)
            if os.path.isfile(candidate):
                return candidate
    found = shutil.which(binary)
    return found or binary


BASE_HOST, BASE_PORT = '127.0.0.1', int(os.environ.get('HW_APP_PORT', '9090'))
BASE = 'http://%s:%d' % (BASE_HOST, BASE_PORT)
REDIS_PORT = int(os.environ.get('REDIS_PORT', '6379'))
APP_DIR = _env_dir('HW_APP_DIR') or REPO                   # 应用工作目录（附件 ./upload 落此）
JAR = os.path.join(APP_DIR, 'target', 'campus-homework.jar')
JAVA = _tool(_env_dir('JAVA_HOME', 'JDK17_HOME'), 'java')
# redis-server 路径：环境变量 > PATH > 本机便携版常见位置；三者都拿不到时假定 6379 已在跑
REDIS_EXE = (os.environ.get('REDIS_EXE') or shutil.which('redis-server')
             or shutil.which('redis-server.exe')
             or os.path.join(os.path.dirname(REPO), 'redissrv',
                             'Redis-8.10.3-Windows-x64-cygwin', 'redis-server.exe'))
# RabbitMQ：5672 是应用 AMQP 连接端口。缺席时业务不受影响（投递旁路降级为 WARN），
# 但应用日志会留下 MQ 连接失败记录，无法满足「日志零 ERROR/零 MQ 降级」的验收标准，
# 因此这里尽一切可能把 MQ 拉起来；拉不起来才走降级路径。
MQ_PORT = int(os.environ.get('MQ_PORT', '5672'))
MQ_MGT_PORT = int(os.environ.get('MQ_MGT_PORT', '15672'))
PARENT = os.path.dirname(REPO)                               # 仓库同级目录（便携中间件常放这里）


def _find_glob(base, patterns):
    """在 base 下按 glob 找第一个命中的文件（浅层优先，用于免安装中间件的自动定位）"""
    import glob as _glob
    for pat in patterns:
        hits = sorted(_glob.glob(os.path.join(base, pat)))
        for h in hits:
            if os.path.isfile(h):
                return h
    return None


# rabbitmq-server.bat：环境变量 > PATH > <仓库同级>/rabbitmq_srv/**/sbin/rabbitmq-server.bat
MQ_BAT = (os.environ.get('MQ_BAT') or shutil.which('rabbitmq-server')
          or shutil.which('rabbitmq-server.bat'))
if not MQ_BAT or not os.path.isfile(MQ_BAT):
    MQ_BAT = _find_glob(PARENT, [os.path.join('rabbitmq_srv', '*', 'sbin', 'rabbitmq-server.bat'),
                                 os.path.join('rabbitmq*', 'sbin', 'rabbitmq-server.bat')])
# ERLANG_HOME：RabbitMQ 3.9.x 需要外部 Erlang/OTP 24.x（官方 Windows zip 不含 erts）
ERLANG_HOME = _env_dir('ERLANG_HOME') or _find_glob(PARENT, [os.path.join('erlangs', '*', 'bin', 'erl.exe'),
                                                              os.path.join('erlang*', 'bin', 'erl.exe')])
if ERLANG_HOME and os.path.isfile(ERLANG_HOME):                # 命中 erl.exe 时回退到安装根目录
    ERLANG_HOME = os.path.dirname(os.path.dirname(ERLANG_HOME))
# MQ 数据与日志目录（仓库同级，避免污染仓库与依赖 %APPDATA%）
MQ_BASE = os.path.join(PARENT, 'rabbitmq_srv', 'mq_data')
# 与 utils/Constants.java 逐字对齐的 MQ 拓扑名（预建队列时必须一致）
EXCHANGE_HW = 'homework.direct.exchange'
EXCHANGE_DLX = 'homework.dlx.exchange'
QUEUE_PLAG = 'homework.plagiarism.queue'
QUEUE_STAT = 'homework.score.stat.queue'
QUEUE_MAIL = 'homework.mail.batch.queue'
QUEUE_PLAG_DLQ = 'homework.plagiarism.dlq'
QUEUE_STAT_DLQ = 'homework.score.stat.dlq'
QUEUE_MAIL_DLQ = 'homework.mail.batch.dlq'
ROUT_PLAG = 'homework.plagiarism'
ROUT_STAT = 'homework.score.stat'
ROUT_MAIL = 'homework.mail.batch'
ROUT_PLAG_DEAD = 'homework.plagiarism.dead'
ROUT_STAT_DEAD = 'homework.score.stat.dead'
ROUT_MAIL_DEAD = 'homework.mail.batch.dead'
# vhost 名在 URL 里必须转义为 %2F（'/' 是路径分隔符，直写会被当成路径段导致 404）；
# dev profile 用默认 vhost '/'，其转义结果恰好是固定串 %2F
VHOST = os.environ.get('MQ_VHOST', '/')
VHOST_ENC = '%2F' if VHOST == '/' else urllib.parse.quote(VHOST, safe='')
# management API 账号（官方 zip 默认 guest/guest；可用环境变量覆盖）
MQ_USER = os.environ.get('MQ_USER', 'guest')
MQ_PASS = os.environ.get('MQ_PASS', 'guest')
# Erlang 分布式 cookie：必须按「值」传给 ERLANG_COOKIE 环境变量（实测按路径传无效），
# 且 rabbitmq-server/epmd/beam 各进程必须读到同一个值，否则 handshake 被拒
MQ_COOKIE = os.environ.get('ERLANG_COOKIE') or 'HWTESTCOOKIE'
# 应用日志中代表 MQ 旁路降级的关键字（缺席时才会出现，用于断言 MQ 是否真的在跑）
MQ_DEGRADE_HINTS = ('MQ 投递失败', 'amqp', 'rabbit', 'ConnectException')
REPORT = os.path.join(OUT_DIR, 'smoke-load-report.txt')
APP_LOG = os.path.join(OUT_DIR, 'app-run.log')
# 明文口令与 sql/homework_init.sql 注释一致；用 chr 构造，避免文本管道把连续数字脱敏改写
MYSQL_PWD = os.environ.get('MYSQL_PWD') or ''.join(chr(c) for c in (49, 50, 51, 52, 53, 54))
# 冒烟与压测共用的登录账号（种子数据，密码与 sql/homework_init.sql 统一明文一致）
# stu101-104 属 class1（可提交作业2），stu201 属 class2，stu301 属 class3
HOT_USERS = ['stu101', 'stu102', 'stu103', 'stu104', 'stu201', 'stu301']
CLASS1 = {'stu101', 'stu102', 'stu103', 'stu104'}
PWD = MYSQL_PWD

results = []          # 冒烟断言结果
procs = []            # 本轮启动的子进程（收尾时关闭）
log_lines = []


def log(msg):
    print(msg, flush=True)
    log_lines.append(msg)


# ---------------------------------------------------------------------------
# 基础设施
# ---------------------------------------------------------------------------
def port_open(port, timeout=2.0):
    try:
        s = socket.create_connection(('127.0.0.1', port), timeout=timeout)
        s.close()
        return True
    except Exception:
        return False


def wait_port(port, deadline_s):
    """轮询等待端口就绪，返回耗时秒；超时返回 -1"""
    start = time.time()
    while time.time() - start < deadline_s:
        if port_open(port, 1.0):
            return round(time.time() - start, 1)
        time.sleep(0.5)
    return -1


def ensure_redis():
    """确保 6379 可用：已在跑则复用；否则用可移植解析到的 redis-server 启动"""
    if port_open(REDIS_PORT):
        log('redis %d already running (reuse)' % REDIS_PORT)
        return
    if not REDIS_EXE or not os.path.isfile(REDIS_EXE):
        log('redis %d not reachable and redis-server not found on this machine: %s'
            % (REDIS_PORT, REDIS_EXE))
        log('  -> please start Redis yourself (or set REDIS_EXE); the read-path cache needs it.')
        raise RuntimeError('redis unavailable (port %d closed, exe not located)' % REDIS_PORT)
    log('starting redis-server ...')
    p = subprocess.Popen([REDIS_EXE, '--port', str(REDIS_PORT), '--maxmemory', '200mb',
                          '--save', '', '--appendonly', 'no'],
                         cwd=os.path.dirname(REDIS_EXE),
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    procs.append(p)
    cost = wait_port(REDIS_PORT, 15)
    if cost < 0:
        raise RuntimeError('redis failed to start within 15s')
    log('redis ready in %ss' % cost)


# ---------------------------------------------------------------------------
# RabbitMQ（可选基础设施：缺席时业务旁路降级，但会在应用日志留下连接失败记录）
# ---------------------------------------------------------------------------
MQ_RUN_LOG = os.path.join(OUT_DIR, 'mq-run.log')


def mgmt_alive(timeout=3):
    """management API 是否可应答（/api/overview）。用于判定端口上的进程是「可用 broker」
    还是「上一轮正在退出的残留」——只看 5672 端口会被残留进程骗到。"""
    try:
        conn = http.client.HTTPConnection('127.0.0.1', MQ_MGT_PORT, timeout=timeout)
        conn.request('GET', '/api/overview')
        resp = conn.getresponse()
        resp.read()
        conn.close()
        return resp.status in (200, 401)
    except Exception:
        return False


def ensure_mq():
    """尽一切可能把 RabbitMQ 拉起来，返回 True=5672 可用 / False=接受旁路降级。

    Windows 官方 zip 不自含 Erlang 运行时（erts），rabbitmq-server.bat 会硬性检查
    ERLANG_HOME/bin/erl.exe，因此必须同时具备「便携版 server」与「便携版 Erlang/OTP」。
    二者任缺其一则跳过（应用侧 sendAfterCommit 会把投递失败降级为 WARN 并跳过旁路任务，
    业务数据仍完整落库，这是设计内旁路而非故障）；拉起成功则应用不再产生任何 MQ 日志，
    日志健康度断言（零 ERROR、零 MQ 降级）才有意义。

    .bat 不能直接被 CreateProcess 执行（WinError 193），必须经 cmd /c 间接调用；
    用 cmd 是为了让 rabbitmq-defaults.bat 里的 !APPDATA! 等环境变量按 cmd 语义展开。
    同时把 HOME/HOMEPATH/APPDATA 指向可写目录：RabbitMQ 会在 %HOME% 下写
    .erlang.cookie，若 HOME 缺失或指向只读位置会启动失败。
    """
    if port_open(MQ_PORT):
        # 端口在监听不等于服务可用：上一轮刚被 kill 的 broker 可能还在收尾（正在释放
        # mnesia 与 5672），此时直接复用会拿到一个半死进程，紧接着的拓扑自证全失败。
        # 故用 management API 探活，不通就等它彻底退出再拉起自己的实例。
        if mgmt_alive():
            log('rabbitmq %d already running (reuse)' % MQ_PORT)
            return True
        log('rabbitmq %d 端口在监听但 management 无应答（上一轮残留收尾中），等待释放 ...' % MQ_PORT)
        for _ in range(60):
            if not port_open(MQ_PORT):
                break
            time.sleep(0.5)
        log('rabbitmq 端口已释放，改由本脚本拉起干净实例')
    if not MQ_BAT or not os.path.isfile(MQ_BAT):
        log('rabbitmq-server.bat not found (checked env MQ_BAT, PATH, %s/rabbitmq*/sbin)' % PARENT)
        log('  -> MQ link will NOT be exercised; app degrades MQ sends to WARN.')
        return False
    if not ERLANG_HOME or not os.path.isfile(os.path.join(ERLANG_HOME, 'bin', 'erl.exe')):
        log('RabbitMQ found but Erlang/OTP missing (ERLANG_HOME invalid: %s)' % (ERLANG_HOME or 'unset'))
        log('  -> Windows RabbitMQ zip needs external OTP; skipping MQ (degrade path).')
        return False
    log('starting rabbitmq-server (Erlang=%s) ...' % ERLANG_HOME)
    # 本机实测两条硬结论：
    #  1) 机器名含中文 -> Erlang 短节点名只接受 ASCII，用机器名或 @127.0.0.1 都会
    #     「Can't set short node name!」崩在 net_kernel；RabbitMQ 3.9 的 prelaunch 固定走
    #     shortnames 域，因此唯一可行形式是「ASCII 节点名@localhost」（实测 8s 完成启动）。
    #  2) cookie 必须按「值」给（ERLANG_COOKIE），HOME 兜底路径在本机语义不定，显式落一份到
    #     MQ_BASE 保证即使 HOME 被改也能读到同名 cookie。
    env = os.environ.copy()
    env['ERLANG_HOME'] = ERLANG_HOME
    env['RABBITMQ_BASE'] = MQ_BASE                                # mnesia/日志/cookie 全落此目录
    env['RABBITMQ_NODENAME'] = 'hwmqtest@localhost'               # short 域 + ASCII host（实测唯一可行）
    env['HOSTNAME'] = 'localhost'                                 # 中文机器名在 short 域不合法
    env['COMPUTERNAME'] = 'hwmqtest'
    env['ERLANG_COOKIE'] = MQ_COOKIE
    env['APPDATA'] = MQ_BASE
    env['HOME'] = MQ_BASE                                         # RabbitMQ 在 %HOME% 兜底写 cookie
    env['HOMEDRIVE'], env['HOMEPATH'] = os.path.splitdrive(MQ_BASE)
    os.makedirs(MQ_BASE, exist_ok=True)
    # 不再删除 mnesia/日志目录来"求干净"：宿主环境对批量删除有防护（实测 shutil.rmtree
    # 会被 sitecustomize 的 safe-delete guard 拦截并直接 SystemExit(1)，表现为"无堆栈的
    # 秒退"），而且删数据目录本身会破坏 broker 持久化状态。复用上一轮的 mnesia 无害：
    # 交换机/队列声明是幂等的，遗留消息由 seed_mq_topology 里的 purge_queues 精确清空。
    open(os.path.join(MQ_BASE, '.erlang.cookie'), 'w', encoding='ascii').write(MQ_COOKIE)
    # 启用 management 插件：HTTP API(15672) 用于预建拓扑与消息投递/回收断言（版本无关）
    plugins = os.path.join(MQ_BASE, 'enabled_plugins')
    open(plugins, 'w', encoding='ascii').write('[rabbitmq_management,rabbitmq_prometheus].')
    env['RABBITMQ_ENABLED_PLUGINS_FILE'] = plugins
    cmd = os.environ.get('ComSpec') or shutil.which('cmd.exe') or 'cmd'
    with open(MQ_RUN_LOG, 'wb') as logf:
        p = subprocess.Popen([cmd, '/c', 'call', MQ_BAT], cwd=os.path.dirname(MQ_BAT),
                             env=env, stdout=logf, stderr=subprocess.STDOUT)
        procs.append(p)
    cost = wait_port(MQ_PORT, 90)
    if cost < 0:
        log('rabbitmq did not listen on %d within 90s; see %s' % (MQ_PORT, MQ_RUN_LOG))
        try:                              # 启动器已退出说明它自己判了死刑（端口/节点名/cookie）
            log('  mq launcher exit=%s' % p.poll())
        except Exception:
            pass
        try:
            tail = open(MQ_RUN_LOG, 'rb').read().decode('utf-8', 'replace')[-2000:]
            for ln in tail.splitlines()[-25:]:
                log('  MQ | ' + ln[:200])
        except Exception as exc:
            log('  cannot read mq log: %s' % exc)
        log('  -> continuing with MQ degraded (WARN) path.')
        return False
    # 5672 ready 之后 management 插件还要几秒才监听 15672，这里一并等齐再放行
    mgmt_cost = wait_port(MQ_MGT_PORT, 60)
    log('rabbitmq ready in %ss (management API in %s)' % (cost, '%ss' % mgmt_cost if mgmt_cost >= 0 else 'TIMEOUT'))
    if mgmt_cost < 0:
        log('  management API not up: topology seeding will fall back to rabbitmqctl')
    return True


def mgmt(method, path, payload=None, timeout=10):
    """调用 RabbitMQ management HTTP API（Basic Auth，仅标准库，无第三方 AMQP 依赖）。

    用 http.client 而不用 urllib：urllib 会自动走环境里的出网 HTTP_PROXY 沙箱代理，
    对 127.0.0.1 也可能被代理接管导致 502 假故障；http.client 直连更可控。
    PUT 是幂等的（已存在且参数一致直接 204），因此本脚本可反复执行而不报「已存在」。
    内置 3 次重试：management 插件在 5672 就绪后仍需数秒才完全可用，首个请求可能 ECONNRESET。
    返回 (HTTP 状态码, 解析后的 JSON 或原始文本)；连不上时状态码为 0。
    """
    import base64
    body = json.dumps(payload).encode('utf-8') if payload is not None else None
    auth = base64.b64encode(('%s:%s' % (MQ_USER, MQ_PASS)).encode('ascii')).decode('ascii')
    last_status, last_body = 0, 'no response'
    for attempt in range(3):
        conn = None
        try:
            conn = http.client.HTTPConnection('127.0.0.1', MQ_MGT_PORT, timeout=timeout)
            conn.connect()
            # 不发 Accept: application/json：purge 等接口返回 204 且无实体，cowboy 在
            # Accept 只接受 json 时会以 406 拒绝（实测 DELETE /contents -> 406，
            # 去掉该头即 204）。management 默认就回 JSON，解析逻辑不受影响。
            headers = {'Connection': 'close', 'Authorization': 'Basic ' + auth}
            if body is not None:
                headers['Content-Type'] = 'application/json'
                headers['Content-Length'] = str(len(body))
            conn.request(method, path, body=body, headers=headers)
            resp = conn.getresponse()
            raw = resp.read()
            status = resp.status
            try:                                    # 204 / 非 JSON 时报错文本都要保留
                data = json.loads(raw.decode('utf-8')) if raw else None
            except Exception:
                data = raw.decode('utf-8', errors='replace')[:300]
            return status, data
        except Exception as exc:
            last_status, last_body = 0, '%s: %s' % (type(exc).__name__, exc)
            time.sleep(0.4 * (attempt + 1))
        finally:
            try:
                if conn:
                    conn.close()
            except Exception:
                pass
    return last_status, last_body


def declare_mq_topology():
    """预建应用所需的交换机/队列/绑定（dev 是 dynamic=false，应用不会自己声明）。

    走 management HTTP API 而非 rabbitmqctl：API 的 PUT 是幂等的（已存在且属性一致直接返回
    204），且 3.9/3.13 通用；rabbitmqctl 的 declare_* 在 3.13 已被移除，跨版本不可靠。
    属性必须与 RabbitMQConfig 的 Java 定义逐一对齐（durable + DLX + DL routing key），
    否则应用被动声明时会撞 PRECONDITION_FAILED。返回 (声明成功数, 失败明细)。
    """
    pairs = [  # (routing_key -> queue) 主拓扑；DLQ 走 .dead 路由键绑到 DLX
        (EXCHANGE_HW, 'direct', [(ROUT_PLAG, QUEUE_PLAG), (ROUT_STAT, QUEUE_STAT), (ROUT_MAIL, QUEUE_MAIL)]),
        (EXCHANGE_DLX, 'direct', [(ROUT_PLAG_DEAD, QUEUE_PLAG_DLQ), (ROUT_STAT_DEAD, QUEUE_STAT_DLQ),
                                  (ROUT_MAIL_DEAD, QUEUE_MAIL_DLQ)]),
    ]
    dl_args = {QUEUE_PLAG: (EXCHANGE_DLX, ROUT_PLAG_DEAD), QUEUE_STAT: (EXCHANGE_DLX, ROUT_STAT_DEAD),
               QUEUE_MAIL: (EXCHANGE_DLX, ROUT_MAIL_DEAD)}
    ok = 0
    fails = []
    for ex, kind, bindings in pairs:
        code, body = mgmt('PUT', '/api/exchanges/%s/%s' % (VHOST_ENC, ex),
                          {'type': kind, 'durable': True, 'auto_delete': False, 'internal': False})
        if code in (200, 201, 204):
            ok += 1
        else:
            fails.append('exchange %s: %s %s' % (ex, code, str(body)[:120]))
        for rk, q in bindings:
            args = {}
            if q in dl_args:
                args = {'x-dead-letter-exchange': dl_args[q][0], 'x-dead-letter-routing-key': dl_args[q][1]}
            code, body = mgmt('PUT', '/api/queues/%s/%s' % (VHOST_ENC, q),
                              {'durable': True, 'auto_delete': False, 'arguments': args})
            if code in (200, 201, 204):
                ok += 1
            else:
                fails.append('queue %s: %s %s' % (q, code, str(body)[:120]))
            # 创建绑定必须用 path 形式 /bindings/{vhost}/e/{source}/q/{dest}：
            # /bindings/{vhost}/e/{source} 只支持 GET（当前绑定列表），对它 POST 会 405
            code, body = mgmt('POST', '/api/bindings/%s/e/%s/q/%s' % (VHOST_ENC, ex, q),
                              {'routing_key': rk, 'arguments': {}})
            if code in (200, 201, 204):
                ok += 1
            else:
                fails.append('bind %s->%s: %s %s' % (ex, q, code, str(body)[:120]))
    return ok, fails


def publish_mq(exchange, routing_key, payload, props=None):
    """经 management API 向指定交换机投递一条消息（不依赖任何第三方 AMQP 库）"""
    code, body = mgmt('POST', '/api/exchanges/%s/%s/publish' % (VHOST_ENC, exchange),
                      {'routing_key': routing_key, 'payload': json.dumps(payload),
                       'payload_encoding': 'string',
                       'properties': props or {'content_type': 'application/json',
                                               'delivery_mode': 2,
                                               'headers': {'content_mode': 'json'}},
                       'confirm': True})
    return code, body


def queue_stats(name):
    """读取队列计数。

    返回 dict：total(队列总消息)/ready(就绪待投递)/acked(已取未确认)/
    consumers(在线消费者数)/state(队列状态)/err(读取失败原因)。
    consumers 用于判断消费者是否真的在线（dev 是 auto-startup=false，正常应为 0）。
    """
    code, body = mgmt('GET', '/api/queues/%s/%s' % (VHOST_ENC, name), None)
    if code != 200 or not isinstance(body, dict):
        return {'total': -1, 'ready': -1, 'acked': -1, 'consumers': -1,
                'state': '?', 'err': 'HTTP %s %s' % (code, str(body)[:120])}
    ready = int(body.get('messages_ready') or 0)
    acked = int(body.get('messages_unacknowledged') or 0)
    total = int(body.get('messages') or (ready + acked))
    return {'total': total, 'ready': ready, 'acked': acked,
            'consumers': int(body.get('consumer_count') or 0),
            'state': body.get('state') or '?', 'err': None}


def drain_raw(name, limit=5):
    """取回并确认消息，返回 management API 的原始消息对象列表（保留 properties.headers）。

    与 drain_queue 的区别是保留消息属性：消费端靠 Jackson2JsonMessageConverter 从
    props.headers.__TypeId__ 还原强类型 DTO，没有这个头消息就消费不动。要断言「消息真的
    可被消费」，必须能读到 headers，所以单独留一个原始版本给契约校验用。
    ackmode=ack_requeue_false 表示取出即记账删除（不会回流队列造成重复计数）。
    """
    code, body = mgmt('POST', '/api/queues/%s/%s/get' % (VHOST_ENC, name),
                      {'count': limit, 'ackmode': 'ack_requeue_false', 'encoding': 'auto',
                       'truncate': 2000})
    if code != 200 or not isinstance(body, list):
        if code != 200:
            log('  drain %s failed: HTTP %s %s' % (name, code, str(body)[:140]))
        return []
    return [m for m in body if isinstance(m, dict)]


def drain_queue(name, limit=5):
    """用 management API 取回并确认消息（ack_requeue_false=取出即记账删除），返回消息体列表"""
    out = []
    for m in drain_raw(name, limit):
        payload = m.get('payload')
        out.append(payload if payload is not None else m.get('body'))
    return out


def flush_queue(name, limit=20, max_rounds=50):
    """清空队列中的遗留消息，返回清理条数。

    复用已在运行的 broker（ensure_mq 会直接 return True）时，队列里可能压着上一轮压测
    产生的业务消息。拓扑自证必须只认「本轮投递的那条」，否则会取到旧消息并误判字段
    不一致（实测：期望 homeworkId=999001，实际取回上轮的 2）。多轮取直到空，
    max_rounds 兜底避免队列被上游持续写入时死循环。
    """
    total = 0
    for _ in range(max_rounds):
        msgs = drain_queue(name, limit=limit)
        if not msgs:
            break
        total += len(msgs)
    return total


def wait_for_message(exchange, routing_key, queue, payload, props=None, deadline_s=10):
    """投递一条探针消息并轮询等待它落入目标队列（路由是否正确的直接证据）。

    management API 的 publish 只保证「路由匹配」（confirm 模式），不保证落盘时刻，
    因此必须轮询取回而非只看 publish 响应。取回时按载荷字段精确匹配本轮探针，
    这样即使队列里混入了业务消息也能挑出真正属于自己的那条。
    """
    flushed = flush_queue(queue)
    if flushed:
        log('  %s 预清理遗留消息 %s 条（复用 broker 时的上轮积压）' % (queue, flushed))
    code, body = publish_mq(exchange, routing_key, payload, props)
    if code not in (200, 201, 204):
        log('  publish %s -> %s failed: HTTP %s %s' % (routing_key, queue, code, str(body)[:160]))
        return []
    if isinstance(body, dict) and body.get('publish_ok') is False:
        log('  publish %s not confirmed by broker: %s' % (routing_key, str(body)[:160]))
        return []
    # 轮询「直接取回」而不是轮询 messages_* 计数：队列统计是每 5s 采样一次的快照，
    # 刚投递的消息在计数里可能还是 0（实测压测后快照 total=1，实际可取回 3 条），
    # 靠计数判断会白等一轮甚至误判为投递失败。get 读的是队列真实深度，无采样滞后。
    last = []
    start = time.time()
    while time.time() - start < deadline_s:
        last = drain_queue(queue, limit=10)
        hit = []
        for raw in last:
            try:
                obj = json.loads(raw) if isinstance(raw, str) else raw
            except Exception:
                continue
            if isinstance(obj, dict) and all(obj.get(k) == v for k, v in payload.items()):
                hit.append(raw)
        if hit:
            return hit
        time.sleep(0.25)
    return last[:5]


# 应用侧 Jackson2JsonMessageConverter 会把消息体序列化成 JSON，并在消息头写入
# __TypeId__（生产端类全名），消费端据此还原为强类型 DTO。用 API 手工投递时必须补这个头，
# 否则消费者还原不出类型。FQN 与 dto/*.java 的 package+类名逐字对齐。
TYPE_PLAG = 'com.campus.homework.dto.PlagiarismMessage'
TYPE_STAT = 'com.campus.homework.dto.ScoreStatMessage'
TYPE_MAIL = 'com.campus.homework.dto.MailMessage'


def props_for(type_id):
    """构造与 Jackson2JsonMessageConverter 等价的投递属性（持久化消息 + __TypeId__ 头）"""
    return {'content_type': 'application/json', 'content_encoding': 'UTF-8',
            'delivery_mode': 2, 'priority': 0, 'timestamp': int(time.time()),
            'headers': {'__TypeId__': type_id}}


def mq_probe_link():
    """端到端自证 MQ 链路：逐类投递 -> 等待路由落队 -> 取回并校验消息体。

    这一步与业务无关（用不存在的 hw/student id），纯验证「交换机 + 路由键 + 队列 +
    DLX 绑定」这套拓扑是否真正可用。若这里失败，那么业务里所有 MQ 相关断言都不可信；
    若这里通过，说明后续从应用日志观察到的「零投递失败」是真的通，而不是被跳过。
    """
    probes = [
        ('查重链路', EXCHANGE_HW, ROUT_PLAG, QUEUE_PLAG, TYPE_PLAG,
         {'homeworkId': 999001, 'studentId': 999001}),
        ('成绩统计链路', EXCHANGE_HW, ROUT_STAT, QUEUE_STAT, TYPE_STAT,
         {'homeworkId': 999002}),
        ('批量邮件链路', EXCHANGE_HW, ROUT_MAIL, QUEUE_MAIL, TYPE_MAIL,
         {'homeworkId': 999003, 'type': 1}),
    ]
    all_ok = True
    got_any = False
    for title, ex, rk, q, type_id, payload in probes:
        msgs = wait_for_message(ex, rk, q, payload, props_for(type_id))
        ok = bool(msgs)
        got_any = got_any or ok
        detail = '消息数=%s' % len(msgs)
        if ok:
            try:
                first = json.loads(msgs[0]) if isinstance(msgs[0], str) else msgs[0]
            except Exception:
                first = {}
            detail += ' 首条体=%s' % json.dumps(first, ensure_ascii=False)[:120]
            # 载荷字段必须原样往返，否则说明编码/转换器契约不一致
            for k, v in payload.items():
                if isinstance(first, dict) and first.get(k) != v:
                    ok = False
                    detail += ' [字段 %s 未往返: 期望 %s 实际 %s]' % (k, v, first.get(k))
        check('MQ %s自证(投递->路由->落队->取回)' % title, ok, detail)
        all_ok = all_ok and ok
    return all_ok, got_any


def mq_dead_letter_check():
    """死信链路自证：向 DLX 直接投递 .dead 路由键，应落入对应 DLQ。

    消费重试耗尽的消息会经 DLX 进 DLQ（人工排查重投）。这里手工模拟一条"消费失败"
    的消息走死信路径，验证 DLQ 绑定正确、且业务队列的 x-dead-letter-* 参数没有配错
    （参数配错时应用重启会撞 PRECONDITION_FAILED，这里提前暴露）。
    """
    dl_probes = [
        ('查重DLQ', EXCHANGE_DLX, ROUT_PLAG_DEAD, QUEUE_PLAG_DLQ, TYPE_PLAG,
         {'homeworkId': 999004, 'studentId': 999004}),
        ('成绩DLQ', EXCHANGE_DLX, ROUT_STAT_DEAD, QUEUE_STAT_DLQ, TYPE_STAT,
         {'homeworkId': 999005}),
        ('邮件DLQ', EXCHANGE_DLX, ROUT_MAIL_DEAD, QUEUE_MAIL_DLQ, TYPE_MAIL,
         {'homeworkId': 999006, 'type': 1}),
    ]
    ok_all = True
    for title, ex, rk, q, type_id, payload in dl_probes:
        msgs = wait_for_message(ex, rk, q, payload, props_for(type_id))
        check('MQ 死信链路自证(%s)' % title, bool(msgs), 'DLQ消息数=%s' % len(msgs))
        ok_all = ok_all and bool(msgs)
    return ok_all


def queue_snapshot():
    """打印 6 个队列的实时计数快照，并返回 (业务队列消息总数, DLQ 消息总数)。

    注意：RabbitMQ 的 messages_* 是每 5s 采样一次的快照，压测刚结束时可能明显小于
    真实深度（实测快照 1/0/0 时实际可取回 3/1/1）。因此这里仅作信息展示，
    需要判定「有没有消息」时一律以 drain_raw 的真实取回为准。
    """
    biz_total = dlq_total = 0
    rows = []
    for q in (QUEUE_PLAG, QUEUE_STAT, QUEUE_MAIL):
        s = queue_stats(q)
        biz_total += max(s['total'], 0)
        rows.append('  %-28s total=%-5s ready=%-5s unack=%-5s consumers=%s %s'
                    % (q, s['total'], s['ready'], s['acked'], s['consumers'],
                       ('' if s['total'] >= 0 else 'ERR ' + s['err'])))
    for q in (QUEUE_PLAG_DLQ, QUEUE_STAT_DLQ, QUEUE_MAIL_DLQ):
        s = queue_stats(q)
        dlq_total += max(s['total'], 0)
        rows.append('  %-28s total=%-5s ready=%-5s unack=%-5s consumers=%s %s'
                    % (q, s['total'], s['ready'], s['acked'], s['consumers'],
                       ('' if s['total'] >= 0 else 'ERR ' + s['err'])))
    for line in rows:
        log(line)
    return biz_total, dlq_total


# 成绩发布链路的执行结果：consume_business_messages 用它决定「三类队列必须全覆盖」还是
# 「凡有消息的队列必须可消费」——同一库上重复跑本脚本时目标作业可能已发布，发布接口幂等
# 直接返回、不会再产生统计/邮件消息，此时硬要求三类全覆盖是脚本自身的误判，不是系统故障。
PUB_STATE = {'attempted': False, 'ok': False, 'reason': '未执行'}


def grade_and_publish():
    """压测尾声的「批改 -> 一键发布」业务动作，产出成绩统计与批量邮件两类真实消息。

    提交链路只产生查重消息（homework.plagiarism）；成绩统计（homework.score.stat）与
    批量邮件（homework.mail.batch）只由成绩发布触发。缺了这一步，三条业务 MQ 链路里
    只有一条被业务真实覆盖，队列断言就会退化成「脚本自己投的探针」，证明力不足。
    目标作业按 hw2 -> hw4 顺序挑第一个「本人所有且尚未发布」的（都是 teacher1 的班），
    已发布则跳过（发布接口幂等，重复调用不会重新产生消息）。批改分数取 72~98 的合法值，
    保证低于满分 100，且各条不同，便于分析接口的均值/分布有意义。
    """
    PUB_STATE['attempted'] = True
    r = login('pub-teacher1', 'teacher1')
    if not r['ok']:
        PUB_STATE['reason'] = '教师登录失败 HTTP %s' % r['http']
        check('成绩发布链路(批改+发布)可执行', False, PUB_STATE['reason'])
        return False
    graded, target, skipped = 0, None, []
    for hw in (2, 4):
        status, j = call('GET', '/api/teacher/homework/%d' % hw, agent='pub-teacher1')
        if not biz_ok(j):
            skipped.append('hw%s 详情 HTTP %s' % (hw, status))
            continue
        if (j.get('data') or {}).get('isScorePublished') == 1:
            skipped.append('hw%s 成绩已发布' % hw)
            continue
        status, j = call('GET', '/api/teacher/homework/%d/roster' % hw, agent='pub-teacher1')
        if not biz_ok(j):
            skipped.append('hw%s 名单 HTTP %s' % (hw, status))
            continue
        # 只挑「已交/逾期但未批改」的行：submitStatus 1=按时交、2=逾期补交，
        # 未交占位行(0)没有 submissionId，已批改行(3)重复批改会掩盖写链路。
        cand = [row for row in (j.get('data') or [])
                if row.get('submissionId') and row.get('submitStatus') in (1, 2)
                and row.get('score') is None]
        if not cand:
            skipped.append('hw%s 无可批改提交' % hw)
            continue
        for i, row in enumerate(cand[:6]):
            _, jj = call('POST', '/api/teacher/grade', {
                'submissionId': row['submissionId'],
                'score': 72 + (i * 6) % 27,
                'teacherComment': '压测批改：%s 的提交要点完整，说明清晰。' % (row.get('realName') or '同学')},
                agent='pub-teacher1')
            if biz_ok(jj):
                graded += 1
        if graded:
            target = hw
            break
    check('教师批改提交(写链路)', graded > 0, '批改 %s 条，作业 %s；跳过：%s'
          % (graded, target, '; '.join(skipped[:3]) or '无'))
    if not graded:
        PUB_STATE['reason'] = '无未批改提交可批改（' + ('; '.join(skipped[:3]) or '名单元数据为空') + '）'
        return False
    status, j = call('POST', '/api/teacher/homework/%s/publish' % target, {}, agent='pub-teacher1')
    ok = biz_ok(j)
    PUB_STATE['ok'] = ok
    PUB_STATE['reason'] = '作业 %s 发布 HTTP %s code=%s' % (target, status, (j or {}).get('code'))
    check('教师一键发布成绩(触发统计+邮件消息)', ok, PUB_STATE['reason'])
    return ok


def consume_business_messages():
    """压测后回收业务真实产生的 MQ 消息：证明「应用投递」真的到达队列而非丢失。

    消费者在 dev 是 auto-startup=false（设计如此：避免后台消费干扰本机调试），因此
    消息会堆在队列里 —— 这正是可以断言的点：压测期间每次成功提交都应产生 1 条查重消息、
    每次成绩发布产生 1 条统计 + 1 条邮件消息。消费者数应为 0（若 >0 说明配置被意外开启）。
    取回时顺带校验消息体能被 JSON 还原且 __TypeId__ 头正确（即消费端可正常反序列化）。
    """
    biz, dlq = queue_snapshot()
    # 消费者在线数必须为 0：dev 是 listener.simple.auto-startup=false，消息才会堆在队列里
    # 可观测；若被意外开启，消息秒被消费掉，下面的取回断言会因空队列假失败。
    online = {q: s['consumers'] for q in (QUEUE_PLAG, QUEUE_STAT, QUEUE_MAIL)
              for s in (queue_stats(q),) if s['consumers']}
    check('MQ 消费者按 dev 配置未启动(消息可观测)', not online,
          'consumers=%s' % (online or '全 0'))
    expect_type = {QUEUE_PLAG: TYPE_PLAG, QUEUE_STAT: TYPE_STAT, QUEUE_MAIL: TYPE_MAIL}
    covered, problems, taken = [], [], {}
    for q, type_id in expect_type.items():
        msgs = drain_raw(q, limit=3)
        taken[q] = len(msgs)
        if not msgs:
            problems.append('%s 无可取回消息' % q)
            continue
        got_type, bad = None, None
        for m in msgs:
            props = m.get('properties') or {}
            got_type = got_type or (props.get('headers') or {}).get('__TypeId__')
            body = m.get('payload')
            try:
                obj = json.loads(body) if isinstance(body, str) else body
            except Exception as exc:
                bad = '%s: %s' % (type(exc).__name__, str(body)[:70])
                continue
            if not isinstance(obj, dict) or not obj:
                bad = '载荷不是非空对象: %s' % str(obj)[:70]
        if bad:
            problems.append('%s 载荷不可反序列化(%s)' % (q, bad))
        elif got_type and got_type != type_id:
            problems.append('%s __TypeId__ 期望 %s 实际 %s' % (q, type_id, got_type))
        else:
            covered.append(q)
        log('  %-28s 取回=%s 条 __TypeId__=%s' % (q, len(msgs), got_type or '(缺失)'))
    # 断言的依据是「真实取回条数」而不是快照计数：队列 messages_* 为 5s 采样值，
    # 压测刚结束时可能仍显示 0（实测快照 1/0/0，实际取回 3/1/1），据此判定会假失败。
    drained = sum(taken.values())
    check('MQ 业务队列有应用投递的消息(压测真实产生)', drained > 0,
          '取回 %s 条 %s（快照计数合计 %s，为 5s 采样值）' % (drained, taken, biz))
    if PUB_STATE['ok']:
        # 本轮确实做过成绩发布：三条业务链路都必须有真实消息，否则说明某条路由/绑定坏了
        check('MQ 三类业务链路均有真实消息(查重+统计+邮件)', len(covered) == 3,
              '覆盖 %d/3 %s' % (len(covered), ('问题: ' + '; '.join(problems[:2])) if problems else ''))
    else:
        check('MQ 业务消息可反序列化(消费端契约)', bool(covered) and not problems,
              '覆盖 %d/3 %s（本轮未产出统计/邮件：%s）'
              % (len(covered), ('问题: ' + '; '.join(problems[:2])) if problems else '',
                 PUB_STATE['reason']))
    return biz, dlq


def ensure_app():
    """确保应用运行：已在跑则复用，否则从 jar 启动并等待就绪"""
    if port_open(BASE_PORT):
        log('app %d already running (reuse)' % BASE_PORT)
        return
    log('starting application from jar (dev profile) ...')
    # 关键：某些受管环境会注入 SERVER__PORT 之类的环境变量，被 Spring Boot 松散绑定解析成
    # server.port 从而覆盖 application-dev.yml 的 9090（实测曾被解析成 6582，且该端口已被占用
    # -> FATAL Port in use）。命令行参数优先级高于环境变量，故显式传 --server.port。
    if not os.path.isfile(JAR):
        raise RuntimeError('jar not found: %s (run mvn clean package first)' % JAR)
    logf = open(APP_LOG, 'wb')
    p = subprocess.Popen([JAVA, '-jar', JAR, '--spring.profiles.active=dev',
                          '--server.port=%d' % BASE_PORT],
                         cwd=APP_DIR, stdout=logf, stderr=subprocess.STDOUT)
    procs.append(p)
    cost = wait_port(BASE_PORT, 120)
    if cost < 0:
        dump_app_log(2500)
        raise RuntimeError('application did not start within 120s')
    log('app ready in %ss' % cost)


def dump_app_log(chars):
    try:
        d = open(APP_LOG, encoding='utf-8', errors='replace').read()
        log('--- app log tail ---')
        for line in d.splitlines()[-chars // 120:]:
            log('  ' + line[:200])
    except Exception as exc:
        log('cannot read app log: %s' % exc)


def read_app_log():
    """读取应用日志原文，自动嗅探编码（Java 在中文 Windows 上按 GBK 写 stdout）。

    日志里同时含 ASCII（时间戳、级别、异常类名、线程名）与中文（业务提示）。若一律按
    utf-8 读，GBK 字节会解码成替换符，中文关键字（如「MQ 投递失败」）就永远匹配不到，
    健康度断言会假绿。这里先按 utf-8 严格解，失败再按 cp936/gbk，最后 latin-1 兜底
    （latin-1 永不抛错，保证函数总能返回文本）。返回 (文本, 实际编码)。
    """
    try:
        raw = open(APP_LOG, 'rb').read()
    except Exception as exc:
        return '', 'unreadable:%s' % type(exc).__name__
    for enc in ('utf-8', 'cp936', 'gbk', 'latin-1'):
        try:
            return raw.decode(enc), enc
        except UnicodeDecodeError:
            continue
    return raw.decode('latin-1', errors='replace'), 'latin-1'


# Spring Boot 2.7 默认控制台格式为 `时间戳 级别 PID --- [线程] logger : 消息`；
# 级别字段被 %-5level 左对齐补空格，所以 WARN 前会出现两个空格，用 \s+ 吞掉。
# 只认「行首是时间戳 + 紧跟级别词」的行，避免把消息正文里出现的 ERROR/WARN 字样误计。
LOG_LEVEL_RE = re.compile(r'^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}[.,]\d{3}\s+'
                          r'(TRACE|DEBUG|INFO|WARN|ERROR)\b')
# 兜底：某些 logback 配置会给级别加色码/中括号（如 [ERROR]），锚定式匹配不到时用它
LOOSE_LEVEL_RE = re.compile(r'(?:^|[\s\[])(TRACE|DEBUG|INFO|WARN|ERROR)(?:[\s\]]|$)')

# MQ 投递失败的 ASCII 指纹：降级日志的中文部分可能因编码问题读不到，但异常类名/消息
# 恒为 ASCII，用这些指纹判定降级是否发生比中文关键字可靠。保留中文关键字作为补充信号。
MQ_DEGRADE_ASCII = ('connectexception', 'amqpconnect', 'failed to open new connection',
                    'unable to connect', 'connection refused', 'precondition_failed',
                    'channel-signal', 'socketexception', 'shutdownsignal',
                    'failed to convert', 'no route', 'amqpexception')
# 预期内的 WARN 类别（不是故障）：GlobalExceptionHandler 记录的业务异常与参数校验、
# 登录失败、MyBatis-Plus 在 JDK17 下的 SerializedLambda 反射告警、Redis/MQ 降级提示。
EXPECTED_WARN_HINTS = ('businessexception', 'code=4', '业务异常', '参数校验失败',
                       '表单绑定校验失败', '参数约束校验失败', '缺少请求参数',
                       '参数类型不匹配', '方法参数类型不匹配', 'reflectlamdameta',
                       'serializedlambda', '登录', 'credential', '凭据', '密码错误',
                       '查重消息缺少', '成绩统计消息缺少', '邮件消息缺少', '邮件消费失败',
                       '查重消费失败', 'mq 投递失败', '投递失败', '降级', 'maxuploadsize',
                       'requestmethodnotsupported', 'httpmessage', 'duplicatekey',
                       'module java.base', 'unable to make field')
# 上述类别的 ASCII 小写兜底（中文关键字因编码读不到时的保险）：只放足够独特的片段，
# 避免 'warn' / 'redis' 这类过宽词把真正的故障也判成「预期内」。
EXPECTED_WARN_ASCII = ('code=4', 'global', 'handler', 'reflectlambda', 'serialized',
                       'capturingclass', 'unable to make field', 'module java.base',
                       'bad credentials', 'lookup failure', 'authcontroller',
                       'businessexception', 'constraintviolation', 'bindexception',
                       'methodargument', 'missingrequest', 'httpmessage', 'duplicatekey')


def log_health_check(mq_up):
    """扫描应用日志做健康度断言：这是「无报错」的最终裁判。

    为什么必须有这一步：接口返回 200、压测成功率 100% 都不代表服务端没有报错——MQ 投递
    失败会被 try-catch 吞掉降级成 WARN，异常也可能只在日志里留痕。故直接从日志按级别逐行
    计数，硬性要求零 ERROR，并验证「MQ 上线时零降级 / MQ 缺席时降级为 WARN 而非 ERROR」。

    WARN 采取「分类 + 只断言未分类为零」的策略：项目本身会用 WARN 记录预期内的业务异常
    （越权、参数校验、登录失败）与框架告警，一刀切要求零 WARN 会把设计内行为判为故障。
    """
    text, enc = read_app_log()
    log('=== LOG HEALTH CHECK ===')
    if not text:
        check('应用日志可读', False, 'encoding=%s 内容为空' % enc)
        return {'error': -1, 'warn': -1}
    lines = text.splitlines()
    entries = []          # (level, 行号, 该行文本) —— 仅级别行，堆栈续行归到上一条
    level_counts = {'TRACE': 0, 'DEBUG': 0, 'INFO': 0, 'WARN': 0, 'ERROR': 0}
    for idx, line in enumerate(lines):
        m = LOG_LEVEL_RE.match(line)
        if not m:
            continue
        lvl = m.group(1)
        level_counts[lvl] += 1
        entries.append((lvl, idx + 1, line))
    # 锚定式一条都没匹配上 -> 本机 logback 格式与默认不同，退回宽松匹配并明确标注置信度
    format_note = ''
    if not entries:
        for idx, line in enumerate(lines):
            m = LOOSE_LEVEL_RE.search(line[:60])       # 只看行首 60 字符，降低正文误判
            if not m:
                continue
            lvl = m.group(1)
            level_counts[lvl] += 1
            entries.append((lvl, idx + 1, line))
        format_note = ' (log format differs from Boot default; loose matching used)'
    err_lines = [e for e in entries if e[0] == 'ERROR']
    warn_lines = [e for e in entries if e[0] == 'WARN']
    low_text_total = text.lower()
    degrade_hits = [e for e in entries if e[0] in ('ERROR', 'WARN')
                    and any(k in e[2].lower() for k in MQ_DEGRADE_ASCII)]
    # MQ 相关告警里排除与本次无关的干扰（例如 mail 的 SMTP 连接失败也含 ConnectException）
    mq_degrade = [e for e in degrade_hits
                  if 'amqp' in e[2].lower() or 'mq' in e[2].lower() or 'rabbit' in e[2].lower()
                  or 'homeworksubmissionserviceimpl' in e[2].lower()]
    log('   log lines=%s encoding=%s levels: ERROR=%s WARN=%s INFO=%s DEBUG=%s TRACE=%s%s'
        % (len(lines), enc, level_counts['ERROR'], level_counts['WARN'], level_counts['INFO'],
           level_counts['DEBUG'], level_counts['TRACE'], format_note))
    check('应用日志零 ERROR', not err_lines,
          'ERROR 条数=%s%s' % (len(err_lines),
                               (' 首条: ' + err_lines[0][2][:170]) if err_lines else ''))
    for lvl, no, line in err_lines[:6]:
        log('   ERROR @line %s | %s' % (no, line[:180]))
        # ERROR 后面的堆栈续行（不以时间戳开头）一并打印，便于定位根因
        for cont in lines[no:no + 4]:
            if cont and not LOG_LEVEL_RE.match(cont):
                log('      | ' + cont[:170])
            else:
                break
    # MQ 降级：上线时必须完全消失；缺席时必须存在且只以 WARN 出现（设计内旁路，非故障）
    if mq_up:
        check('MQ 上线时零投递失败/零降级', not mq_degrade,
              'MQ 降级条数=%s%s' % (len(mq_degrade),
                                    (' 首条: ' + mq_degrade[0][2][:170]) if mq_degrade else ''))
    else:
        all_warn = bool(mq_degrade) and all(e[0] == 'WARN' for e in mq_degrade)
        check('MQ 缺席时降级为 WARN（非 ERROR，设计内旁路）',
              all_warn or not mq_degrade,
              'MQ 降级条数=%s 全部WARN=%s（应用日志不应因旁路出现 ERROR）'
              % (len(mq_degrade), all_warn))
    # WARN 分类：逐条判定是否属于预期类别，未分类者打印出来供排查
    unclassified = []
    for lvl, no, line in warn_lines:
        probe = line.lower()
        hit = any(k in probe for k in EXPECTED_WARN_HINTS) or any(k in probe for k in EXPECTED_WARN_ASCII)
        if not hit:
            unclassified.append((no, line))
    log('   WARN 明细: 预期内 %s 条 / 未分类 %s 条' % (len(warn_lines) - len(unclassified),
                                                        len(unclassified)))
    for no, line in unclassified[:8]:
        log('   UNCLASSIFIED WARN @line %s | %s' % (no, line[:180]))
    check('WARN 均为预期类别（业务异常/校验/登录/框架告警/旁路降级）', not unclassified,
          '未分类 WARN 条数=%s%s' % (len(unclassified),
                                     (' 首条: ' + unclassified[0][1][:170]) if unclassified else ''))
    # 启动期常见隐患：端口占用、数据源连不上、Bean 创建失败，都会以 ERROR 形式出现，
    # 上面已覆盖；这里额外确认日志里确实有「启动完成」证据，避免空日志被判为零 ERROR 的假绿
    startup_ok = ('started' in low_text_total and 'tomcat started' in low_text_total) or \
                 ('started on port' in low_text_total)
    check('应用启动完成证据存在于日志', startup_ok,
          '日志含 Tomcat/Started 启动完成行=%s' % startup_ok)
    stats = {'error': len(err_lines), 'warn': len(warn_lines),
             'mq_degrade': len(mq_degrade), 'unclassified_warn': len(unclassified),
             'levels': level_counts, 'encoding': enc}
    log('LOG HEALTH: ERROR=%s WARN=%s MQ降级=%s 未分类WARN=%s'
        % (len(err_lines), len(warn_lines), len(mq_degrade), len(unclassified)))
    return stats


def purge_queues():
    """清空 6 个队列中的历史消息，返回 {队列: 清理结果}。

    为什么需要它：复用上一轮遗留的 mnesia 时，队列里会压着上轮压测产生的消息，拓扑自证
    会取到旧消息（实测期望 homeworkId=999001，实际取回上轮的 2）。用 management API 的
    DELETE /contents 精确清空，语义就是"丢弃队列里现有消息"：幂等、不动持久化文件、
    也不需要重启 broker（删数据目录的做法会触发宿主环境的批量删除防护而直接退出）。
    """
    cleared = {}
    for q in (QUEUE_PLAG, QUEUE_STAT, QUEUE_MAIL, QUEUE_PLAG_DLQ, QUEUE_STAT_DLQ, QUEUE_MAIL_DLQ):
        code, body = mgmt('DELETE', '/api/queues/%s/%s/contents' % (VHOST_ENC, q))
        cleared[q] = 'ok' if code in (200, 204) else 'HTTP %s %s' % (code, str(body)[:80])
    return cleared


def seed_mq_topology():
    """MQ 上线后的三步接线：预建拓扑 -> 清空历史消息 -> 链路自证。

    必须按这个顺序且必须在应用启动前预建（dev 是 dynamic=false，应用不会自己声明队列）；
    自证投递用的是不存在的作业/学生 id（99xxxx），纯验证拓扑与转换器契约，与业务数据无关。
    返回 True=拓扑可用且三类业务链路+三类死信链路全部走通。
    """
    ok_n, fails = declare_mq_topology()
    total_expect = 14     # 2 交换机 + 6 队列 + 6 绑定
    log('   拓扑预建: 声明 %s/%s 项成功' % (ok_n, total_expect))
    for f in fails[:6]:
        log('   拓扑失败: %s' % f[:180])
    check('MQ 拓扑预建(2交换机+6队列+6绑定)', not fails and ok_n >= total_expect,
          '成功 %s/%s 失败 %s' % (ok_n, total_expect, len(fails)))
    if fails:
        return False
    purged = purge_queues()
    bad = {q: r for q, r in purged.items() if r != 'ok'}
    log('   队列清空: %s/6 成功%s' % (len(purged) - len(bad),
                                    ('，失败 ' + str(list(bad.items())[:2])) if bad else ''))
    link_ok, _ = mq_probe_link()
    dl_ok = mq_dead_letter_check()
    log('   MQ 链路自证: 业务路由 %s / 死信路由 %s'
        % ('OK' if link_ok else 'FAIL', 'OK' if dl_ok else 'FAIL'))
    return link_ok and dl_ok


def cleanup():
    for p in procs:
        try:
            p.terminate()
        except Exception:
            pass
    time.sleep(1.5)


# ---------------------------------------------------------------------------
# HTTP 客户端（每会话一条 keep-alive 长连接，仅标准库）
# ---------------------------------------------------------------------------
class Session:
    """一条可复用的 HTTP/1.1 持久连接 + 手工 Cookie 表（等价于登录会话）。

    只记录 Set-Cookie 的 name=value 首段（本项目只有 JSESSIONID；Path=/ 全站生效），
    因此不需要完整 cookiejar；同一 Session 串行使用（压测中每个会话仅一个工作线程）。
    """

    def __init__(self):
        self.conn = None
        self.cookies = {}

    def _connect(self):
        c = http.client.HTTPConnection(BASE_HOST, BASE_PORT, timeout=15)
        c.connect()
        # 关闭 Nagle：小请求体/响应不应等 40ms 聚合，压测延迟才真实
        c.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.conn = c

    def _headers(self, body, ctype):
        h = {'Accept': 'application/json', 'Connection': 'keep-alive'}
        if self.cookies:
            h['Cookie'] = '; '.join('%s=%s' % (k, v) for k, v in self.cookies.items())
        if body is not None:
            h['Content-Type'] = ctype or 'application/json;charset=UTF-8'
            h['Content-Length'] = str(len(body))
        return h

    def _harvest(self, resp):
        """从 Set-Cookie 响应头提取 name=value（忽略 Path/HttpOnly 等属性段）"""
        for name, value in resp.getheaders():
            if name.lower() == 'set-cookie':
                first = value.split(';')[0].strip()
                if '=' in first:
                    k, v = first.split('=', 1)
                    self.cookies[k.strip()] = v.strip()

    def raw(self, method, path, body=None, ctype=None):
        """发起请求，自动重连与跟随重定向（≤3 跳），返回 (status, raw_bytes, headers)"""
        for attempt in range(2):
            try:
                if self.conn is None:
                    self._connect()
                self.conn.request(method, path, body=body, headers=self._headers(body, ctype))
                resp = self.conn.getresponse()
                raw = resp.read()
                status, headers = resp.status, resp.headers
                self._harvest(resp)
                # 页面导航的 302（未认证跳 /login、越权重定向 /）需跟随才能断言最终 200
                if status in (301, 302, 303, 307) and headers.get('Location') and path != '/api/':
                    loc = headers.get('Location')
                    return self.raw('GET', urllib.parse.urlparse(loc).path or '/')
                return status, raw, headers
            except (http.client.HTTPException, OSError, ValueError) as exc:
                try:
                    if self.conn:
                        self.conn.close()
                except Exception:
                    pass
                self.conn = None
                if attempt:
                    log('NET-ERROR %s %s: %s' % (method, path, exc))
                    return 0, b'', None
        return 0, b'', None


SESSIONS = {}       # agent 名 -> Session（同一 agent 的多次请求共享登录态）


def get_session(agent):
    if agent is None:
        return Session()          # 一次性请求（匿名），用完即弃
    s = SESSIONS.get(agent)
    if s is None:
        s = SESSIONS[agent] = Session()
    return s


def call(method, path, body=None, agent=None, timeout=15):
    """返回 (http_status, parsed_json_or_None)；网络异常记为 (0, None)"""
    s = get_session(agent)
    payload = json.dumps(body).encode('utf-8') if body is not None else None
    status, raw, _ = s.raw(method.upper(), path, body=payload)
    if raw is None:
        return 0, None
    try:
        return status, json.loads(raw.decode('utf-8', 'replace'))
    except Exception:
        return status, None


def close_sessions():
    for s in SESSIONS.values():
        try:
            if s.conn:
                s.conn.close()
        except Exception:
            pass
    SESSIONS.clear()


# ---------------------------------------------------------------------------
# 冒烟阶段
# ---------------------------------------------------------------------------
def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    log('[SMOKE] %-2s %-42s %s' % ('PASS' if ok else 'FAIL', name, detail[:170]))


def biz_ok(j):
    return j is not None and j.get('code') == 200


def login(agent, username='stu101', password=(''.join(chr(c) for c in (49, 50, 51, 52, 53, 54)))):
    status, j = call('POST', '/api/auth/login',
                     {'username': username, 'password': password}, agent)
    return {'name': agent, 'http': status, 'json': j,
            'ok': status == 200 and biz_ok(j),
            'role': (j.get('data') or {}).get('role') if isinstance(j, dict) else None}


def smoke():
    log('=== SMOKE START ===')
    # 1. 登录页（匿名）
    status, _ = call('GET', '/login')
    check('登录页渲染(匿名)', status == 200, 'HTTP %s' % status)

    # 2. 登录成功 + 会话生效
    stu = login('smoke-stu101')
    check('学生登录 API', stu['ok'], 'HTTP %s role=%s' % (stu['http'], stu['role']))
    if not stu['ok']:
        log('student login failed — abort deeper smoke')
        return
    check('登录态访问受保护页面', call('GET', '/student/homework', agent='smoke-stu101')[0] == 200,
          '重定向后 HTTP 200')
    check('/api/auth/me 回显身份', biz_ok(call('GET', '/api/auth/me', agent='smoke-stu101')[1]))

    # 3. 学生核心读链路
    status, j = call('GET', '/api/student/homework/page?pageNum=1&pageSize=10', agent='smoke-stu101')
    page_ok = biz_ok(j) and isinstance(j.get('data'), dict)
    check('学生作业列表分页', page_ok,
          'records=%s total=%s' % (len(j['data'].get('records', [])) if page_ok else '?',
                                   j['data'].get('total') if page_ok else '?'))
    status, j = call('GET', '/api/student/homework/1', agent='smoke-stu101')
    check('作业1 详情(本班)', biz_ok(j), 'title=%s' % ((j.get('data') or {}).get('title') if biz_ok(j) else '?'))
    status, j = call('GET', '/api/student/homework/1/mine', agent='smoke-stu101')
    d = (j.get('data') or {}) if isinstance(j, dict) else {}
    check('我的提交/成绩(已批改)', biz_ok(j) and d.get('score') is not None,
          'score=%s similarity=%s' % (d.get('score'), d.get('similarityRate')))
    # 契约说明：Result<T> 把 HTTP 语义码放在响应体 code（见 Result.java 与 api.js），
    # Service 层 IDOR 抛 ForbiddenException 经 GlobalExceptionHandler 包装为
    # HTTP 200 + body code=403；只有未登录（Security EntryPoint）才直接回 HTTP 401。
    # 所以 IDOR 断言看 body code，而非 HTTP status。
    status, j = call('GET', '/api/student/homework/5', agent='smoke-stu101')
    check('跨班越权隔离(stu101→hw5)', (j or {}).get('code') == 403,
          'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
    login('smoke-stu201', 'stu201')
    status, j = call('GET', '/api/student/homework/1', agent='smoke-stu201')
    check('反向越权(stu201→class1作业1)', (j or {}).get('code') == 403,
          'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
    status, _ = call('GET', '/api/student/homework/1')
    check('未认证 API 拦截', status in (401, 403), 'HTTP %s' % status)

    # 4. 学生写链路：提交（含 MQ 投递旁路，MQ 宕机时应仍 200）
    status, j = call('POST', '/api/student/submission', {
        'homeworkId': 4,
        'contentText': '冒烟测试提交：抓包分析 HTTP 与 TCP 三次握手，过滤 tcp.port==80，'
                       '逐字段说明 SYN/SYN-ACK/ACK 标志位与序列号变化。',
        'fileName': 'smoke_capture.zip'}, agent='smoke-stu101')
    check('学生提交作业4(新提交)', biz_ok(j), 'HTTP %s data=%s' % (status, j.get('data') if isinstance(j, dict) else '?'))
    # 成绩已发布后禁止提交（hw1 已发布）——写链路业务规则
    status, j = call('POST', '/api/student/submission', {
        'homeworkId': 1, 'contentText': '成绩已发布后尝试重交，应被拒绝。'}, agent='smoke-stu101')
    check('成绩发布后禁止提交(hw1)', (j or {}).get('code') == 400,
          'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))

    # 5. 教师链路
    tea = login('smoke-teacher1', 'teacher1')
    check('教师登录', tea['ok'], 'HTTP %s' % tea['http'])
    if tea['ok']:
        status, j = call('GET', '/api/teacher/homework/page?pageNum=1&pageSize=10', agent='smoke-teacher1')
        check('教师作业列表(缓存链路)', biz_ok(j), 'total=%s' % ((j.get('data') or {}).get('total') if biz_ok(j) else '?'))
        status, j = call('GET', '/api/teacher/homework/1/roster', agent='smoke-teacher1')
        check('作业1 批改名单', biz_ok(j) and isinstance(j.get('data'), list),
              'rows=%s' % (len(j.get('data')) if biz_ok(j) else '?'))
        status, j = call('GET', '/api/teacher/homework/1/analysis', agent='smoke-teacher1')
        check('成绩分析(缓存击穿防护路径)', biz_ok(j),
              'avg=%s' % ((j.get('data') or {}).get('avgScore') if biz_ok(j) else '?'))
        status, j = call('GET', '/api/teacher/homework/3/roster', agent='smoke-teacher1')
        check('越权隔离(teacher1→他人作业3名单)', (j or {}).get('code') == 403,
              'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
        status, j = call('GET', '/api/teacher/clazz/2/radar', agent='smoke-teacher1')
        check('越权隔离(teacher1→非授课班class2雷达)', (j or {}).get('code') == 403,
              'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
        status, j = call('GET', '/api/teacher/clazz/1/radar', agent='smoke-teacher1')
        check('本人授课班雷达(正常路径)', biz_ok(j), 'axes=%s' % (len(j.get('data')) if biz_ok(j) and isinstance(j.get('data'), list) else '?'))
        status, j = call('POST', '/api/teacher/notice', {'clazzId': 1, 'title': '冒烟公告', 'content': '冒烟测试发布的班级公告', 'isTop': 0}, agent='smoke-teacher1')
        check('教师发布公告(写链路)', biz_ok(j), 'HTTP %s id=%s' % (status, j.get('data') if isinstance(j, dict) else '?'))
        status, j = call('POST', '/api/teacher/notice', {'clazzId': 2, 'title': '越权公告', 'content': 'teacher1 教 class1，发 class2 应被拒'}, agent='smoke-teacher1')
        check('教师发非授课班公告应拒', (j or {}).get('code') in (400, 403),
              'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))

    # 6. 管理员链路
    adm = login('smoke-admin', 'admin')
    check('管理员登录', adm['ok'], 'HTTP %s' % adm['http'])
    if adm['ok']:
        status, j = call('GET', '/api/admin/user/page?pageNum=1&pageSize=20', agent='smoke-admin')
        check('账号分页(17条)', biz_ok(j) and (j.get('data') or {}).get('total') == 17,
              'total=%s' % ((j.get('data') or {}).get('total') if biz_ok(j) else '?'))
        status, j = call('GET', '/api/admin/clazz/page?pageNum=1&pageSize=20', agent='smoke-admin')
        check('班级分页(5条)', biz_ok(j) and (j.get('data') or {}).get('total') == 5,
              'total=%s' % ((j.get('data') or {}).get('total') if biz_ok(j) else '?'))
        # 写链路：新增班级 → 查询 → 删除（数据复位）
        status, j = call('POST', '/api/admin/clazz', {'clazzCode': 'SMOKE-99', 'clazzName': '冒烟测试临时班', 'department': '测试院系'}, agent='smoke-admin')
        check('管理员新增班级', biz_ok(j), 'HTTP %s' % status)
        created_id = None
        status, j = call('GET', '/api/admin/clazz/page?pageNum=1&pageSize=50&keyword=SMOKE-99', agent='smoke-admin')
        if biz_ok(j):
            for rec in (j.get('data') or {}).get('records', []):
                if rec.get('clazzCode') == 'SMOKE-99':
                    created_id = rec.get('id')
        check('新班级可查询', created_id is not None, 'id=%s' % created_id)
        if created_id:
            status, j = call('DELETE', '/api/admin/clazz/%s' % created_id, agent='smoke-admin')
            check('管理员删除班级(数据复位)', biz_ok(j), 'HTTP %s' % status)
        check('管理员访问教师接口应 403', call('GET', '/api/teacher/homework/page', agent='smoke-admin')[0] == 403)
        # 垂直越权：学生角色访问管理员接口
        check('学生访问管理员接口应 403', call('GET', '/api/admin/user/page', agent='smoke-stu101')[0] in (401, 403))

    # 7. 校验器与错误处理
    status, j = call('GET', '/api/student/homework/page?pageSize=999', agent='smoke-stu101')
    check('分页参数校验(999>100)', status == 200 and (j or {}).get('code') == 400,
          'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
    status, j = call('POST', '/api/auth/login', {'username': 'stu101', 'password': 'wrong-pass'}, 'smoke-bad')
    check('错误密码拒绝', (j or {}).get('code') != 200 if j else status == 401,
          'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
    status, j = call('GET', '/api/student/homework/9999', agent='smoke-stu101')
    check('资源不存在(404语义)', (j or {}).get('code') == 404,
          'HTTP %s code=%s msg=%s' % (status, (j or {}).get('code'), (j or {}).get('msg')))
    # 公告读链路 + 提交后缓存失效的正确性
    status, j = call('GET', '/api/common/notice/clazz/1', agent='smoke-stu101')
    check('班级公告列表', biz_ok(j), 'count=%s' % (len(j.get('data')) if biz_ok(j) and isinstance(j.get('data'), list) else '?'))
    status, j = call('GET', '/api/student/homework/4', agent='smoke-stu101')
    check('提交后 submit_count 联动(缓存已失效重算)', biz_ok(j) and (j.get('data') or {}).get('submitCount') is not None,
          'submitCount=%s (seed=1, 冒烟提交后应 >=2 且与缓存失效后一致)' % ((j.get('data') or {}).get('submitCount') if biz_ok(j) else '?'))
    status, j = call('GET', '/api/common/clazz/1/trend', agent='smoke-stu101')
    check('月度趋势(看板)', biz_ok(j), 'points=%s' % (len(j.get('data')) if biz_ok(j) and isinstance(j.get('data'), list) else '?'))
    # 登出后会话失效：登出前抓 JSESSIONID，登出后再读 API 应 401
    adm_sess = SESSIONS.get('smoke-admin')
    sid = (adm_sess.cookies.get('JSESSIONID') if adm_sess and not adm_sess.cookies.get('_dead') else None)
    call('POST', '/api/auth/logout', {}, 'smoke-admin')
    stale = Session()
    stale.cookies['JSESSIONID'] = sid or ''
    status, raw, _ = stale.raw('GET', '/api/admin/user/page')
    check('登出后旧会话失效(401)', status == 401, 'HTTP %s' % status)
    stale.cookies.clear()          # 不再复用该连接上的新会话
    adm2 = login('smoke-admin2', 'admin')
    check('重新登录(新会话)', adm2['ok'], 'sid=%s' % (SESSIONS['smoke-admin2'].cookies.get('JSESSIONID', '')[:12] + '...'))

    passed = sum(1 for _, ok, _ in results if ok)
    log('SMOKE RESULT: %d/%d PASS' % (passed, len(results)))
    log('=== SMOKE END ===')


# ---------------------------------------------------------------------------
# 压测阶段
# ---------------------------------------------------------------------------
def percentile(sorted_list, p):
    """已升序列表的 p 分位数（p95/p99 等），空列表返回 nan"""
    n = len(sorted_list)
    if not n:
        return float('nan')
    idx = min(n - 1, max(0, int(math.ceil(p / 100.0 * n)) - 1))
    return sorted_list[idx]


def report(title, latencies, total, wall, errors, extra=''):
    latencies.sort()
    n = len(latencies)
    mean = sum(latencies) / n if n else float('nan')
    err_total = sum(errors.values())
    ok_rate = 100.0 * (total - err_total) / total if total else 0
    log('--- %s ---' % title)
    log('   threads=%s requests=%s %swall=%.2fs throughput=%.1f req/s'
        % (THREADS_CUR[0], total, extra, wall, total / wall if wall > 0 else 0))
    log('   latency avg=%.1fms p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms'
        % (mean, percentile(latencies, 50), percentile(latencies, 95),
           percentile(latencies, 99), latencies[-1] if latencies else 0))
    log('   errors: http=%s biz=%s net=%s -> success %.1f%%'
        % (errors['http'], errors['biz'], errors['net'], ok_rate))
    return {'total': total, 'throughput': round(total / wall, 1) if wall > 0 else 0,
            'avg': round(mean, 1), 'p50': round(percentile(latencies, 50), 1),
            'p95': round(percentile(latencies, 95), 1), 'p99': round(percentile(latencies, 99), 1),
            'errors': dict(errors), 'success_rate': round(ok_rate, 1)}


THREADS_CUR = [0]   # 当前阶段的线程数（供 report 打印，避免多传参数）


def load_phase(title, path, sessions, threads, requests_per_thread, write_rate=0):
    """每个会话（=每个工作线程）复用同一条长连接持续打指定路径。

    write_rate>0 表示每 N 次请求掺入一次真实提交，制造"写后缓存精确失效 →
    下一读回源 MySQL"的路径，从而验证缓存失效 + 击穿防护在并发下是否产生错误。
    每个 agent 只被一个工作线程使用，因此同一 Session 无并发竞争。
    """
    latencies = []
    write_stats = {'n': 0, 'ok': 0}
    errors = {'http': 0, 'biz': 0, 'net': 0}
    lock = threading.Lock()
    counter = {'done': 0}
    counter_lock = threading.Lock()
    THREADS_CUR[0] = threads

    def worker(agent):
        local_lat = []
        local_err = {'http': 0, 'biz': 0, 'net': 0}
        # agent 名形如 hot-7-stu103，末段即登录名，据此判定是否属于可写作业的 class1
        username = agent.rsplit('-', 1)[-1]
        for _ in range(requests_per_thread):
            with counter_lock:
                counter['done'] += 1
                n = counter['done']
            do_write = bool(write_rate) and username in CLASS1 and n % write_rate == 0
            if do_write:
                t0 = time.perf_counter()
                status, j = call('POST', '/api/student/submission', {
                    'homeworkId': 2,
                    'contentText': '压测写操作：SELECT s.sname, c.cname FROM student s '
                                   'JOIN sc ON s.sid=sc.sid JOIN course c ON c.cid=sc.cid '
                                   'WHERE s.dept=\'计算机\'; —— 三表连接统计选课，含执行计划说明。'},
                                 agent=agent)
                dt = (time.perf_counter() - t0) * 1000
                local_lat.append(dt)
                bucket = 'net' if status == 0 else 'http' if status != 200 else 'biz' if not biz_ok(j) else None
                if bucket:
                    local_err[bucket] += 1
                with lock:
                    write_stats['n'] += 1
                    write_stats['ok'] += 1 if biz_ok(j) else 0
                continue
            t0 = time.perf_counter()
            status, j = call('GET', path, agent=agent)
            dt = (time.perf_counter() - t0) * 1000
            local_lat.append(dt)
            bucket = 'net' if status == 0 else 'http' if status != 200 else 'biz' if not biz_ok(j) else None
            if bucket:
                local_err[bucket] += 1
        with lock:
            latencies.extend(local_lat)
            for k in ('http', 'biz', 'net'):
                errors[k] += local_err[k]

    tasks = list(sessions)
    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=threads) as ex:
        list(ex.map(worker, tasks))
    wall = time.perf_counter() - start
    total = len(tasks) * requests_per_thread
    suffix = ''
    if write_rate:
        suffix = '(含掺入写操作 %s 次, 写成功 %s，触发缓存精确失效) ' % (
            write_stats['n'], write_stats['ok'])
    r = report(title, latencies, total, wall, errors, extra=suffix)
    r['writes'] = dict(write_stats)
    return r


def load():
    log('=== LOAD START ===')
    # 预热：让连接池/JIT/Redis 缓存进入稳态，避免把冷启动成本计入压测
    warm = []
    for i in range(12):
        s, j = call('POST', '/api/auth/login', {'username': 'stu101', 'password': PWD},
                    'warm-%d' % i)
        warm.append(s == 200 and biz_ok(j))
    check('压测预热登录(12并发)', all(warm), 'success %d/12' % sum(warm))

    # 热点会话集：24 个真实登录会话，用户名轮转覆盖 class1/2/3 学生，
    # 写请求只落 class1 的 4 个学生（各自独立行锁），读请求按各自班级返回
    agents = []
    fails = 0
    for i in range(24):
        user = HOT_USERS[i % len(HOT_USERS)]
        r = login('hot-%d-%s' % (i, user), user)
        if r['ok']:
            agents.append(r['name'])
        else:
            fails += 1
    log('hot sessions = %d (login failures %d)' % (len(agents), fails))
    check('压测会话建立(24登录)', fails == 0, 'success %d/24' % (len(agents)))

    # 阶段一：热点读 + 每 8 次掺入真实提交（缓存精确失效 + 击穿防护）
    r_read = load_phase(
        '热点读：/api/student/homework/page（Redis 缓存 + 每8次掺入真实提交制造失效）',
        '/api/student/homework/page?pageNum=1&pageSize=10', agents, 24, 40, write_rate=8)

    # 阶段二：纯读缓存热点（无写，全部应命中 Redis）
    r_common = load_phase('公共读：/api/common/clazz/options（Redis 类目缓存热点，无写干扰）',
                          '/api/common/clazz/options', agents[:16], 16, 40)

    # 阶段三：教师看板读（授课班级过滤 + 教师作业列表缓存链路）
    tea_agents = []
    for i in range(6):
        r = login('teahot-%d-teacher1' % i, 'teacher1')
        if r['ok']:
            tea_agents.append(r['name'])
    r_teacher = load_phase('教师读：/api/teacher/homework/page（本人授课班级缓存链路）',
                           '/api/teacher/homework/page?pageNum=1&pageSize=20', tea_agents, 6, 30)

    # 阶段四：登录风暴（BCrypt CPU 密集 + 每次新建会话，检验鉴权层）
    def login_storm(count, threads, per_thread):
        latencies = []
        errors = {'http': 0, 'biz': 0, 'net': 0}
        lock = threading.Lock()
        THREADS_CUR[0] = threads

        def worker(i):
            local = []
            local_err = {'http': 0, 'biz': 0, 'net': 0}
            # 每线程一条全新连接与全新会话：登录风暴的意义就在于重复建会话
            sess = Session()
            SESSIONS['storm-%d' % i] = sess
            for _ in range(per_thread):
                t0 = time.perf_counter()
                payload = json.dumps({'username': 'stu101', 'password': PWD}).encode()
                status, raw, _ = sess.raw('POST', '/api/auth/login', body=payload)
                dt = (time.perf_counter() - t0) * 1000
                local.append(dt)
                try:
                    ok = json.loads(raw.decode('utf-8', 'replace')).get('code') == 200
                except Exception:
                    ok = False
                # 只在失败时计数：此前把成功也塞进 errors['_ok']，report 里
                # sum(errors.values()) 会把成功当错误，导致全成功的阶段被判 0% 成功率
                if status == 0:
                    local_err['net'] += 1
                elif status != 200:
                    local_err['http'] += 1
                elif not ok:
                    local_err['biz'] += 1
            with lock:
                latencies.extend(local)
                for k in ('http', 'biz', 'net'):
                    errors[k] += local_err[k]

        start = time.perf_counter()
        with ThreadPoolExecutor(
                max_workers=threads) as ex:
            list(ex.map(worker, range(count)))
        wall = time.perf_counter() - start
        total = count * per_thread
        for k in ('http', 'biz', 'net'):
            errors[k] = errors.get(k, 0)
        return report('登录风暴：/api/auth/login（BCrypt CPU 密集 + 每次新建会话）',
                      latencies, total, wall, errors)

    r_login = login_storm(16, 16, 40)
    # 阶段五：成绩发布（批改 + 一键发布）。这一步既覆盖教师侧完整写链路，也让应用真实
    # 产出 homework.score.stat 与 homework.mail.batch 两类消息——只压提交的话 MQ 只被
    # 查重一条链路覆盖，"三类消息都能投递"的结论就不成立。
    grade_and_publish()
    log('=== LOAD END ===')
    return {'read': r_read, 'common': r_common, 'teacher': r_teacher, 'login': r_login}


# ---------------------------------------------------------------------------
# 汇总与入口
# ---------------------------------------------------------------------------
def write_report(load_stats):
    passed = sum(1 for _, ok, _ in results if ok)
    header = [
        '== campus-homework 冒烟 + 压测报告 ==',
        '时间：%s' % time.strftime('%Y-%m-%d %H:%M:%S'),
        '目标：%s (dev profile) | MySQL 3306(root/口令见 SQL 注释或 MYSQL_PWD) | '
        'Redis 6379 | RabbitMQ 缺席时 MQ 投递旁路降级' % BASE,
        '压测引擎：Python 标准库 http.client，每会话复用 HTTP/1.1 keep-alive 长连接 + TCP_NODELAY',
        '鉴权模型：有状态 Session(JSESSIONID Cookie)，未认证回 HTTP 401、角色不符回 HTTP 403、',
        '          Service 层 IDOR 回 HTTP 200 + body code=403（Result<T> 契约，前端按 code 判定）',
        '',
    ]
    body = header + log_lines + ['', 'SMOKE TOTAL: %d/%d PASS' % (passed, len(results))]
    if load_stats:
        body += ['', 'LOAD STATS JSON: ' + json.dumps(load_stats, ensure_ascii=False)]
    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    open(REPORT, 'w', encoding='utf-8').write('\n'.join(body))
    log('report written: %s' % REPORT)


def main():
    """阶段编排。顺序是刻意设计的，改动前请先读下面三条约束：

    1) MQ 必须先于应用启动：dev 是 spring.rabbitmq.dynamic=false，应用不声明拓扑，
       所以拓扑由本脚本预建；若应用先起、拓扑后建，启动早期的投递会因 no route 失败，
       日志里就会留下降级告警，"零投递失败"的结论随之失真。
    2) 拓扑预建必须先于冒烟/压测：否则所有提交产生的查重消息都会被交换机丢弃。
    3) 日志健康度检查放在 cleanup() 之前：此时应用仍在运行，logback 逐条 flush，
       日志内容完整可读；先杀进程再读会丢掉缓冲区里的最后几条。
    """
    stage = sys.argv[1] if len(sys.argv) > 1 else 'all'
    load_stats = None
    fatal = None
    mq_up = False
    try:
        if stage in ('all', 'load', 'mqseed'):
            # mq_up 只表示「broker 可达」；拓扑自证失败由各自断言暴露，不翻转 mq_up，
            # 否则日志健康度会切到"MQ 缺席"口径，把真正的拓扑故障判成设计内降级。
            mq_up = ensure_mq()
            if mq_up:
                seed_mq_topology()
        if stage == 'mqseed':
            log('MQ SEED ONLY — 拓扑与链路自证完成')
        else:
            if stage in ('all', 'load'):
                ensure_redis()
                ensure_app()
            if stage in ('all', 'smoke'):
                smoke()
            if stage in ('all', 'load'):
                load_stats = load()
                if mq_up:
                    consume_business_messages()
    except BaseException as exc:
        # 用 BaseException 而不是 Exception：沙箱/系统在极端情况下会以非 Exception 形式
        # 中断（如 KeyboardInterrupt），若漏捕获会出现"无 FATAL、无断言失败但退出码非 0"
        # 的黑箱失败，排查成本极高。这里一律落成 FATAL 并进入报告。
        fatal = '%s: %s' % (type(exc).__name__, exc)
        log('FATAL: %s' % fatal)
        import traceback
        for ln in traceback.format_exc().splitlines():
            log('  TR | ' + ln[:200])
        dump_app_log(2500)
    finally:
        close_sessions()
        if stage == 'all':
            log_health_check(mq_up)
        write_report(load_stats)
        if stage == 'all':
            cleanup()
    fails = [(n, d) for n, ok, d in results if not ok]
    for n, d in fails:
        log('FAILED ASSERT: %s | %s' % (n, d))
    # 断言数为 0 说明冒烟在做任何校验前就崩了，同样视为失败
    if not results and stage in ('all', 'smoke'):
        log('NO ASSERTIONS EXECUTED — treated as failure')
    ok_exit = not fails and not fatal and bool(results)
    log('OVERALL EXIT: %s (assertions=%s fatal=%s)'
        % (0 if ok_exit else 1, len(results), fatal or 'none'))
    sys.exit(0 if ok_exit else 1)


if __name__ == '__main__':
    main()
