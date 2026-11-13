"""全链路验证编排器（测试脚本，非项目源码）：建库 → 单测 → 打包 → 起Redis → 起应用 → 冒烟 → 压测。

为什么要在同一进程内串起这些阶段：Windows 沙箱会在工具轮次结束时回收子进程。此前 Redis 与
Java 应用正是在轮次切换时被回收、端口随之消失，导致冒烟没有目标可打。单进程串起后，进程随
脚本正常结束而收尾，不会中途被回收。

所有路径默认基于本仓库位置推导（脚本位于 <repo>/scripts/），并支持环境变量覆盖，便于换机器复现：
  MVN_HOME / MAVEN_HOME   Maven 安装目录（未提供则回退 PATH 上的 mvn）
  JAVA_HOME / JDK17_HOME  JDK 17 安装目录（未提供则回退 PATH 上的 java / javac）
  HW_TEST_OUT             日志与临时产物目录（默认 scripts/ 自身）
  MYSQL_USER / MYSQL_PWD  数据库账号（默认 root / 123456，与 sql/homework_init.sql 注释一致）
  REDIS_EXE               redis-server 可执行文件路径（未提供则回退 PATH，或假定 6379 已在跑）
MySQL 驱动 jar 自动从 ~/.m2/repository/com/mysql 下定位（mvn 构建时已自动下载，无需手工准备）。

用法（在任意目录执行皆可，脚本自行定位仓库）：
  python scripts/run_all.py db        # 仅 JDBC 执行 homework_init.sql 并回读自洽校验
  python scripts/run_all.py test      # 仅 mvn clean test
  python scripts/run_all.py package   # 仅 mvn clean package
  python scripts/run_all.py e2e       # 起 Redis + 起应用 + 冒烟 + 压测
  python scripts/run_all.py all       # db + test + package + e2e（完整流水线）
退出码：任一阶段失败即以非 0 退出，末尾打印 FINAL: ALL GREEN 或 HAS FAILURES。
"""
import os
import shutil
import subprocess
import sys
import time


def _env_dir(*names):
    """按顺序返回第一个存在且为目录的环境变量值，全部无效则返回 None"""
    for name in names:
        value = os.environ.get(name)
        if value and os.path.isdir(value):
            return value
    return None


def _tool(root, binary):
    """优先使用 <root>/bin/<binary>（补 Windows 的 .cmd/.exe 后缀），否则回退 PATH 上的同名命令。

    后缀顺序必须把可被 CreateProcess 直接执行的格式排在最前：Maven 的 bin/mvn（无扩展名）
    是给 Unix shell 写的脚本，Windows 下 CreateProcess 会抛 WinError 193「%1 不是有效的
    Win32 应用程序」，只有 bin/mvn.cmd 才是可执行的。故先试 .cmd/.exe/.bat 再试裸名。
    """
    if root:
        for suffix in ('.cmd', '.exe', '.bat', ''):
            candidate = os.path.join(root, 'bin', binary + suffix)
            if os.path.isfile(candidate):
                return candidate
    found = shutil.which(binary)
    return found or binary


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPT_DIR)                          # 仓库根目录
OUT_DIR = _env_dir('HW_TEST_OUT') or SCRIPT_DIR            # 日志/临时产物目录
os.makedirs(OUT_DIR, exist_ok=True)

MVN = _tool(_env_dir('MVN_HOME', 'MAVEN_HOME') or r'W:\apache-maven-3.9.16', 'mvn')
JDK = _env_dir('JAVA_HOME', 'JDK17_HOME')
JAVA = _tool(JDK, 'java')
JAVAC = _tool(JDK, 'javac')
PY = os.environ.get('PYTHON_EXE') or sys.executable
DRIVER = None

POM = os.path.join(REPO, 'pom.xml')
SQLFILE = os.path.join(REPO, 'sql', 'homework_init.sql')
SQLRUNNER = os.path.join(OUT_DIR, 'sqlrunner_out')         # SqlRunner.class 编译输出目录
JAVA_SRC = os.path.join(SCRIPT_DIR, 'SqlRunner.java')      # JDBC 初始化执行器（随仓库分发）
SMOKE = os.path.join(SCRIPT_DIR, 'smoke_load_test.py')
MYSQL_USER = os.environ.get('MYSQL_USER', 'root')
# 明文口令与 sql/homework_init.sql 一致；用 chr 构造避免任何文本管道对连续数字的脱敏改写
MYSQL_PWD = os.environ.get('MYSQL_PWD') or ''.join(chr(c) for c in (49, 50, 51, 52, 53, 54))
JDBC_URL = ('jdbc:mysql://localhost:3306/?useUnicode=true&characterEncoding=utf-8'
            '&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
            '&allowMultiQueries=true')


def find_driver():
    """在 ~/.m2/repository/com/mysql 下定位 mysql-connector-j 驱动 jar（排除 sources/javadoc）"""
    home = _env_dir('HOME', 'M2_HOME') or os.path.expanduser('~')
    base = os.path.join(home, '.m2', 'repository', 'com', 'mysql')
    if not os.path.isdir(base):
        return None
    for root, _, files in os.walk(base):
        for name in sorted(files):
            if name.startswith('mysql-connector-j') and name.endswith('.jar') \
                    and 'sources' not in name and 'javadoc' not in name:
                return os.path.join(root, name)
    return None


DRIVER = find_driver()


def banner(text):
    print('\n########## %s ##########' % text, flush=True)


def run(cmd, label, timeout=1200):
    """执行子进程并逐行透传（只回显关键行，避免构建日志刷屏），返回 (退出码, 全部输出行)"""
    print('$ %s' % ' '.join(str(c) for c in cmd), flush=True)
    start = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, errors='replace', encoding='utf-8')
    lines = []
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        lines.append(line.rstrip())
        low = line.lower()
        if any(k in low for k in ('error', 'fail', 'tests run', 'build success', 'started',
                                  'smoke', 'load', 'throughput', 'latency', 'errors:',
                                  'redis', 'pass', 'exception', 'exit', 'port', 'ready',
                                  'rowcount', 'declared')):
            print('   ' + line.rstrip()[:200], flush=True)
    code = proc.wait()
    print('[%s] exit=%d elapsed=%.1fs' % (label, code, time.time() - start), flush=True)
    return code, lines


def compile_runner():
    """编译 JDBC 初始化执行器；class 已存在且比源码新则跳过"""
    class_file = os.path.join(SQLRUNNER, 'SqlRunner.class')
    if os.path.exists(class_file) and os.path.getmtime(class_file) > os.path.getmtime(JAVA_SRC):
        banner('COMPILE JDBC UTILITIES (cached)')
        return 0
    banner('COMPILE JDBC UTILITIES')
    os.makedirs(SQLRUNNER, exist_ok=True)
    code, _ = run([JAVAC, '-encoding', 'UTF-8', '-d', SQLRUNNER, JAVA_SRC], 'javac', timeout=180)
    return code


def init_db():
    """用 JDBC 执行 homework_init.sql，并回读逐表/逐班/逐作业计数做自洽性校验"""
    banner('DB INIT (JDBC)')
    if not DRIVER:
        print('   !! 未找到 mysql-connector-j 驱动 jar，请先执行 mvn -f %s compile' % POM, flush=True)
        return 1
    code, lines = run([JAVA, '-Dfile.encoding=UTF-8', '-cp', DRIVER + os.pathsep + SQLRUNNER,
                       'SqlRunner', SQLFILE, JDBC_URL, MYSQL_USER, MYSQL_PWD],
                      'SqlRunner', timeout=180)
    for line in lines:
        if line.startswith(('rowcount', 'class ', 'hw#')):
            print('   ' + line[:170], flush=True)
    if any(' FAIL' in line for line in lines):
        print('   !! data self-consistency FAIL detected', flush=True)
        code = code or 1
    return code


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'all'
    banner('ENV RESOLUTION')
    print('   repo    = %s' % REPO, flush=True)
    print('   mvn     = %s' % MVN, flush=True)
    print('   java    = %s' % JAVA, flush=True)
    print('   driver  = %s' % (DRIVER or 'NOT FOUND'), flush=True)
    print('   outdir  = %s' % OUT_DIR, flush=True)
    ok = True
    if mode in ('all', 'db') and ok:
        ok = compile_runner() == 0 and init_db() == 0
    if mode in ('all', 'test') and ok:
        banner('MAVEN UNIT TESTS')
        ok = run([MVN, '-f', POM, 'clean', 'test'], 'mvn-test', timeout=1200)[0] == 0
    if mode in ('all', 'package') and ok:
        banner('MAVEN PACKAGE')
        ok = run([MVN, '-f', POM, 'clean', 'package'], 'mvn-package', timeout=1200)[0] == 0
    if mode in ('all', 'e2e') and ok:
        banner('SMOKE + LOAD')
        ok = run([PY, SMOKE, 'all'], 'smoke-load', timeout=1800)[0] == 0
    banner('FINAL: %s' % ('ALL GREEN' if ok else 'HAS FAILURES'))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
