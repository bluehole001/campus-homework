# 班级学业管理与作业提交系统（campus-homework）

面向高校班级场景的学业管理与作业提交系统：学生在线交作业、教师发布与批改作业并查看教学数据看板、教务管理员维护班级与师生账号。服务端渲染单体应用，含异步查重、成绩缓存预热、批量邮件与定时任务等进阶中间件实践。

---

## 一、项目简介与技术栈

### 1.1 技术栈总览

| 层面 | 技术选型 | 用途 |
|---|---|---|
| 核心底座 | Spring Boot 2.7.18 + JDK 17 + Maven | 应用框架与构建 |
| 数据库 | MySQL 8.0（utf8mb4） | 6 张核心表（班级/用户/作业/提交/公告/月度统计） |
| ORM | MyBatis-Plus 3.5.3.1 | 全部 SQL 100% `#{}` 预编译，杜绝 `${}` 拼接 |
| 视图层 | Thymeleaf + Bootstrap 5 + jQuery 3.7.1 + ECharts 5.5.0 | 服务端渲染 + 本地化静态库（离线可用，禁 Vue/React） |
| 安全 | Spring Security（BCrypt + 三级角色） | `ROLE_STUDENT` / `ROLE_TEACHER` / `ROLE_ADMIN`，URL 规则 + `@PreAuthorize` + Service 层 IDOR 三重防御 |
| 缓存 | Redis（Lettuce 连接池） | 班级作业/公告列表缓存、成绩分析与个人成绩缓存；`__NULL__` 空值占位防穿透；SETNX 互斥锁收口并发回源防击穿（Lua 令牌解锁 + 10s TTL 防死锁）；写路径事务 afterCommit 精确失效；故障自动降级回源 |
| 消息队列 | RabbitMQ（direct 交换机 + 死信队列 DLX/DLQ） | 异步查重、成绩分析与 Redis 预热、批量邮件；手动 ACK：正常 ack、异常 `nack(requeue=false)` 直接进 DLQ（不无限重投，DLQ 供人工排查重放） |
| 定时任务 | Spring Task `@Scheduled` | 每 10 分钟逾期标记、每日 8:30 催交邮件、每月 1 日月度统计报表 |
| 邮件 | JavaMailSender | 成绩发布通知与截止前 24 小时催交（dev 环境默认关闭真实投递） |
| 查重算法 | 纯 JDK N-Gram（3-gram）+ 余弦相似度 | 零外部依赖；相似度 ≥ 0.85（`homework.plagiarism.threshold`）触发高重复预警 |

### 1.2 包结构

```text
com.campus.homework
├── config        # SecurityConfig / RedisConfig / RabbitMQConfig / MybatisPlusConfig / PasswordConfig 等
├── controller    # Auth / Student / Teacher / Admin / Common / File / Page（页面路由）
├── service       # 业务接口
│   └── impl      # 业务实现（事务、缓存同步、MQ 发送全部在此层）
├── mapper        # MyBatis-Plus Mapper（注解 SQL 全部 #{} 预编译）
├── entity        # 与 6 张表一一对应
├── dto           # 入参对象（JSR-303 校验）
├── vo            # 出参对象（密码等敏感字段一律剔除）
├── exception     # BusinessException / ForbiddenException / NotFoundException / GlobalExceptionHandler
├── utils         # Result<T> / Constants / FileUploadUtils / TextSimilarityUtils / SecurityUtils / RedisCacheUtils
├── task          # OverdueMarkTask / UrgeMailTask / MonthlyStatTask
└── mq            # PlagiarismConsumer / ScoreStatConsumer / MailConsumer
```

统一响应契约：`Result<T>`（`code`/`msg`/`data`），HTTP 状态与业务 code 同步；前端 `api.js` 的 `HW` 命名空间统一处理 401 跳登录、403 提示与错误 toast。

---

## 二、核心功能模块说明

### 2.1 学生端（ROLE_STUDENT）

| 功能 | 说明 |
|---|---|
| 我的作业列表 | 只展示本班作业；未交/按时交/逾期/已批改状态徽章，截止时间倒计时 |
| 作业详情与提交 | 查看要求与教师参考附件，提交正文 + 附件（≤50MB，扩展名白名单）；截止前可覆盖重交，重交后自动重新触发异步查重 |
| 成绩与排名 | 成绩发布前严格隐藏分数/评语/查重结论（IDOR 红线）；发布后可查看个人成绩、班级排名、知识点得分率与最相似同学提示 |
| 班级公告 | 查看本班公告（置顶优先），只读 |

页面：`/student/homework`、`/student/homework/{id}`、`/student/notice`

### 2.2 教师端（ROLE_TEACHER）

| 功能 | 说明 |
|---|---|
| 作业管理 | 发布/编辑/删除作业（知识点标签、截止时间、总分、参考附件）；仅能操作自己发布的作业 |
| 提交名单 | 全班名单含未交标记；查看每份提交的查重率与高重复预警（≥0.85 红色高亮，附最相似同学）；在线批改打分写评语 |
| 一键发布成绩 | 发布后 MQ 异步统计并全量预热 Redis 查分缓存（防查分高峰打垮 MySQL），同时异步批量发送成绩通知邮件 |
| 成绩分析 | 分数分布直方图、平均分/及格率、知识点得分率统计（ECharts） |
| 教学看板 | 月度提交率/平均分/及格率趋势折线 + 知识点得分率雷达图（仅限自己授课过的班级） |
| 公告管理 | 发布/编辑/删除班级公告，支持置顶 |

页面：`/teacher/homework`、`/teacher/homework/new`、`/teacher/homework/{id}/roster`、`/teacher/homework/{id}/analysis`、`/teacher/dashboard`、`/teacher/notice`

### 2.3 管理端（ROLE_ADMIN）

| 功能 | 说明 |
|---|---|
| 班级管理 | 班级 CRUD（班级编号唯一），在册人数随学生增删自动同步；删除有约束保护（存在作业/学生时拒绝） |
| 账号管理 | 师生账号 CRUD、重置密码、启停用；密码仅以 BCrypt 密文入库、出参一律剔除 |
| CSV 批量导入学生 | 契约：UTF-8、首行表头、列序 `user_no,username,real_name,email,clazz_id`（第 5 列为班级数字 ID），上限 1000 行；整批事务——任一行失败整批一条不入库，逐行错误报告供管理员一次修正后重传 |
| 数据看板 | 任意班级月度趋势（只读巡检） |

页面：`/admin/clazz`、`/admin/user`、`/admin/dashboard`

### 2.4 越权防御（IDOR 三重防线）

1. **URL 规则**（SecurityConfig）：`/api/student/**`、`/api/teacher/**`、`/api/admin/**` 与同名页面前缀按角色限定；未认证 API 回 401 JSON、页面重定向 `/login`。
2. **方法注解**：Controller 类级/方法级 `@PreAuthorize` 拦截垂直越权。
3. **Service 层**（最终兜底）：所有 Service 业务方法末参为 `LoginUserDTO operator`，按登录态 `userId`/`clazzId` 校验数据归属（学生只能看/交本班作业、教师只能批改自己发布的作业、缓存读取一律在越权校验之后），水平越权即使 URL 规则被误配也不会泄露数据。

---

## 三、本地启动指南

### 3.1 前置条件

- JDK 17、Maven 3.6+
- MySQL 8.0、Redis、RabbitMQ（本地默认端口即可）

### 3.2 导入 SQL

请确保本地 MySQL 服务已启动。本项目提供了完整的建库、建表与初始化数据脚本，您可以选择以下方式导入：

#### 方式一：使用命令行
**⚠️ 注意**：Windows 的 PowerShell 不支持 `<` 符号重定向，请使用 CMD（命令提示符）或 Git Bash。

```bash
# 进入项目根目录后，执行以下命令（-p 后回车输入密码）
# 加上 --default-character-set=utf8mb4 防止中文乱码
mysql -uroot -p --default-character-set=utf8mb4 < sql/homework_init.sql
```

#### 方式二：使用图形化工具（如 Navicat / DataGrip / SQLyog）
1. 打开数据库管理工具，连接到本地 MySQL 8.0 实例。
2. 新建查询窗口，将 `sql/homework_init.sql` 文件的全部内容复制进去。
3. 全选并执行，即可自动建库建表并插入数据。

#### 📋 初始化测试账号清单（共计 17 个，与 `sql/homework_init.sql` 逐行一致）
- **数据规模**：1 名管理员、3 名教师、13 名学生，分布在 5 个班级；配套 6 份作业（4 已截止、1 临近 24h、1 未截止）、15 条提交记录（含高重复预警演示）、6 条公告（2 置顶）与 6 条月度统计。各班在册人数与 `clazz_info.student_count` 逐班一致。
- **安全规范**：所有密码均已使用 `BCryptPasswordEncoder` 加密入库，**所有账号的统一明文密码均为 `123456`**。

**1. 管理员账号（1名）**

| 账号 (Username) | 姓名 (Real Name) | 角色 (Role) | 管辖范围 |
|---|---|---|---|
| admin | 教务处管理员 | ROLE_ADMIN | 全局数据维护、师生账号管理、CSV导入 |

**2. 教师账号（3名，按 SQL 作业表实际授课分布）**

| 账号 (Username) | 姓名 (Real Name) | 角色 (Role) | 实际发布作业的班级 |
|---|---|---|---|
| teacher1 | 王建国 | ROLE_TEACHER | 计算机2023级1班（作业1数据库规范化、作业2 SQL练习、作业4 协议分析） |
| teacher2 | 李晓红 | ROLE_TEACHER | 计算机2023级1班（作业3 操作系统实验）、计算机2023级2班（作业5 Java OO） |
| teacher3 | 张卫民 | ROLE_TEACHER | 软件工程2023级1班（作业6 SRS 编写）——跨班用于测试教师 IDOR 防护 |

**3. 学生账号（13名，按 SQL 真实班级映射）**

| 账号 (Username) | 姓名 (Real Name) | 角色 (Role) | 所属班级 |
|---|---|---|---|
| stu101 | 陈明 | ROLE_STUDENT | 计算机科学与技术2023级1班（CS2023-01） |
| stu102 | 刘洋 | ROLE_STUDENT | 同上（高重复预警演示账号之一） |
| stu103 | 赵磊 | ROLE_STUDENT | 同上（与 stu102 正文高度雷同，预警演示对照组） |
| stu104 | 孙倩 | ROLE_STUDENT | 同上 |
| stu201 | 周涛 | ROLE_STUDENT | 计算机科学与技术2023级2班（CS2023-02） |
| stu202 | 吴迪 | ROLE_STUDENT | 同上 |
| stu203 | 郑爽 | ROLE_STUDENT | 同上 |
| stu301 | 黄强 | ROLE_STUDENT | 软件工程2023级1班（SE2023-01） |
| stu302 | 徐丽 | ROLE_STUDENT | 同上 |
| stu303 | 马俊 | ROLE_STUDENT | 同上 |
| stu401 | 林峰 | ROLE_STUDENT | 人工智能2024级1班（AI2024-01） |
| stu402 | 何雪 | ROLE_STUDENT | 同上 |
| stu501 | 诸一帆 | ROLE_STUDENT | 数据科学与大数据技术2024级1班（DS2024-01） |

> 💡 **IDOR 演示提示**（与 SQL 数据精确对应）：
> - **学生水平越权**：用 `stu101`（计科1班）登录后改 URL 访问作业 5（Java OO 作业，属计科2班）详情接口 → 返回 403；同班内 stu101 想看 stu102 的成绩，成绩未发布前一律不可见。
> - **教师水平越权**：用 `teacher3`（只发布作业6）访问作业 1 的提交名单 `/api/teacher/homework/1/roster` → 403（只能批改自己发布的作业）。
> - **查重预警演示**：用 `teacher1` 查看作业 1 名单，stu102 与 stu103 正文相似度 0.9612 红色高亮，互相指向对方为"最相似同学"。

### 3.3 配置 Redis / RabbitMQ / 邮件

默认激活 `dev` profile（`application-dev.yml`），本机默认参数即可直连：

- Redis：`localhost:6379`，无密码，db 0
- RabbitMQ：`localhost:5672`，`guest/guest`，vhost `/`
  > dev 刻意设 `spring.rabbitmq.dynamic=false`（应用不自动声明交换机/队列）与
  > `listener.simple.auto-startup=false`（消费者不自动启动），便于本机观察消息堆积、
  > 也避免后台消费干扰调试。因此 dev 下**拓扑需要预建**：跑一次
  > `python scripts/smoke_load_test.py mqseed` 即自动创建 2 交换机 + 6 队列 + 6 绑定并自证链路；
  > 也可在管理台手工创建。`prod` profile 未关闭 `dynamic`，由应用启动时自动声明。
- 邮件：`homework.mail.enabled=false`（dev 默认关闭真实投递，仅记录日志，本机无 SMTP 也不会报错）

如需改连接参数，直接编辑 `application-dev.yml`。

### 3.4 启动项目

IDE 中直接运行 `HomeworkApplication`，或命令行：

```bash
mvn spring-boot:run
# 或先打包再运行
mvn clean package -DskipTests
java -jar target/campus-homework.jar
```

启动后访问 `http://localhost:9090/login`（dev profile 端口 9090；test=9092、prod=8080），
按角色登录后自动分流到对应首页。附件落盘目录为 `./upload`（`homework.upload.dir`）。

---

## 四、Linux 部署说明

完整运维手册见 `deploy/Linux部署说明.md`（JDK/MySQL/Redis/RabbitMQ 安装、环境变量注入、Nginx 反代、验证清单与故障排查）。此处摘要关键步骤。

### 4.1 打包 jar

```bash
mvn clean package -DskipTests
# 产物：target/campus-homework.jar（可执行 fat jar，Thymeleaf 模板与静态资源已内封）
```

上传到服务器 `/opt/campus-homework/campus-homework.jar`；附件目录 `/data/homework/upload`、日志目录 `/data/homework/logs` 需预先创建并授权给运行用户。

### 4.2 敏感配置经环境变量注入

生产 profile 的数据库/Redis/MQ/邮件凭据全部来自环境变量（`application-prod.yml` 中 `${DEPLOY_*}` 占位），建议写入 `/etc/campus-homework.env`（权限 600）：

```bash
DEPLOY_DB_HOST=127.0.0.1
DEPLOY_DB_PORT=3306
DEPLOY_DB_USER=homework
DEPLOY_DB_PASSWORD=***
DEPLOY_REDIS_HOST=127.0.0.1
DEPLOY_REDIS_PASSWORD=***
DEPLOY_MQ_HOST=127.0.0.1
DEPLOY_MQ_USER=homework
DEPLOY_MQ_PASSWORD=***
DEPLOY_MAIL_HOST=smtp.qq.com
DEPLOY_MAIL_USER=notice@campus.edu.cn
DEPLOY_MAIL_PASSWORD=***
```

### 4.3 方式一：nohup 运行（简单场景）

```bash
set -a; . /etc/campus-homework.env; set +a
nohup java -jar /opt/campus-homework/campus-homework.jar \
  --spring.profiles.active=prod \
  > /data/homework/logs/nohup.out 2>&1 &
```

### 4.4 方式二：systemd 服务（推荐，开机自启 + 崩溃自动重启）

创建 `/etc/systemd/system/campus-homework.service`：

```ini
[Unit]
Description=campus-homework
After=network.target mysql.service redis.service rabbitmq-server.service

[Service]
Type=simple
User=homework
WorkingDirectory=/opt/campus-homework
EnvironmentFile=/etc/campus-homework.env
ExecStart=/usr/bin/java -jar /opt/campus-homework/campus-homework.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启用：

```bash
systemctl daemon-reload
systemctl enable --now campus-homework
systemctl status campus-homework
journalctl -u campus-homework -f
```

### 4.5 反向代理要点

Nginx 反代 8080 端口，`client_max_body_size 50m;`（匹配上传上限）；附件不暴露物理目录，统一走 `/api/file/download` 接口（Service 层校验归属后才输出流）。生产 vhost 为 `/homework`，RabbitMQ 需预先创建该 vhost 并授权。

---

## 五、项目结构速查

```text
班级学业管理与作业提交系统/
├── pom.xml                      # Spring Boot 2.7.18 / JDK 17 / 50MB 上传上限
├── AGENTS.md / homework-plan.md # 工程契约（铁律）与 5-Batch 执行计划
├── sql/homework_init.sql        # 建库建表 + 测试数据（密码 BCrypt 密文，明文统一 123456）
├── docs/项目开发文档.md          # 需求分析 + E-R 图 + 系统设计 + 分工 + 测试（交付物）
├── deploy/Linux部署说明.md      # Linux 完整部署运维手册
├── src/main/java/com/campus/homework/     # 82 个 Java 源文件（注释率 44%+）
├── src/test/java/…/utils/       # 3 个测试类 21 项用例（查重算法/上传安全/响应契约），mvn test 全绿
├── src/main/resources/
│   ├── application.yml / -dev.yml / -test.yml / -prod.yml   # 多环境配置（任务书要求 dev/test/prod 齐备）
│   ├── templates/               # Thymeleaf：fragments/layout.html + 13 个页面（登录/学生3/教师6/管理3）
│   └── static/                  # vendor/（Bootstrap5/jQuery/ECharts5 本地库）、css/app.css、js/api.js
├── scripts/                     # run_all.py（全链路编排）+ smoke_load_test.py（55 项断言：冒烟37+压测+MQ接线+日志裁决）
└── target/campus-homework.jar   # mvn package 产物
```

---

## 六、全链路验证：建库 → 单测 → 打包 → 冒烟 → 压测

`scripts/` 下两个脚本是本次测试结论的**可复现凭据**，无需任何第三方依赖（纯 Python 标准库）：

| 脚本 | 作用 |
|---|---|
| `scripts/run_all.py` | 全链路编排器：`db`（执行 `sql/homework_init.sql` + 逐表逐班逐作业计数自洽校验）→ `test`（`mvn test`）→ `package`（`mvn clean package`）→ `e2e`（起 RabbitMQ + 预建拓扑 + 起 Redis + 起应用 + 冒烟 + 压测 + 日志健康度裁决）。参数 `all` 即按顺序串起全部阶段，任一阶段失败立即以非 0 退出码终止。 |
| `scripts/smoke_load_test.py` | 冒烟 + 压测 + MQ 接线三合一，共 **55 项断言**。`all` 串起「MQ 拓扑自证 7 项 → 冒烟 37 项 → 压测 4 阶段（含 4 项校验）→ 队列回收 3 项 → 日志健康度 4 项」；`mqseed` 只验证 MQ 侧接线，便于单独排查。自实现 HTTP/1.1 keep-alive 会话（每会话独立 Cookie 持有 JSESSIONID、支持 302 跟随与 Set-Cookie 收割），不使用任何 HTTP 客户端库。 |

### 6.1 一条命令复现

```bash
# 前置：仅需本机 MySQL(3306, root/密码见 sql 注释)。Redis 与 RabbitMQ 由脚本自动拉起
#      （便携版 redis-server 与 RabbitMQ+Erlang 会按约定目录自动定位，缺一个就跳过并降级，
#       降级会在应用日志留下 WARN 记录，日志健康度断言会明确指出）
python scripts/run_all.py all          # 建库校验 → 单测 → 打包 → MQ/Redis/应用 → 冒烟 → 压测 → 日志裁决
python scripts/smoke_load_test.py all  # 仅跑 e2e 部分（自动起 MQ/Redis/应用，无需手工准备）
python scripts/smoke_load_test.py mqseed  # 只验证 MQ：拉 broker + 预建拓扑 + 3 条业务链路 + 3 条死信链路
```

报告落在 `scripts/smoke-load-report.txt`（逐条断言 + 压测统计 + 日志级别计数），
末尾的 `OVERALL EXIT: 0` 与 `FINAL: ALL GREEN` 是判定全绿的硬标志（FATAL 异常、非 Exception
形式的异常中断与"0 条断言"都会被判为失败，不会假绿）。

### 6.2 冒烟断言覆盖（37 项，全部 PASS）

- **认证与鉴权**：登录成功/失败、登出、未认证访问受保护接口 → HTTP 401；URL 规则与
  `@PreAuthorize` 拒绝 → HTTP 403；角色互访（学生访问教师/管理员页面接口）被拦截。
- **三视角主流程**：学生（作业列表/详情/提交与重复提交/撤回/公告只读/成绩查看）、
  教师（发布作业/批改名单/批改与成绩发布/公告发布与迁移/统计）、
  管理员（班级与师生增删/CSV 导入/全局统计/公告迁移）。
- **IDOR 水平越权（body code=403）**：学生跨班看作业、教师批改非本人发布的作业、
  教师向非授课班发布公告、未发布成绩对学生不可见。
- **业务边界与异常**：截止时间后提交、重复提交、分页参数越界、404 路径、
  上传扩展名白名单拦截、查重预警（stu102/ stu103 相似度 0.9612 触发红色预警）。

### 6.3 压测结果（5 阶段，全部 0 错误 / 100% 成功）

| 阶段 | 场景 | 并发 | 吞吐 | 成功率 |
|---|---|---|---|---|
| 热点读 | 同一班级作业列表（缓存命中 + 每 8 次掺入真实提交制造失效） | 24 连接 · 960 请求 | ~230–560 req/s | 100% |
| 公共读 | 班级/学期下拉选项（纯 Redis 缓存热点） | 16 连接 · 640 请求 | ~5400 req/s | 100% |
| 教师读 | 本人授课班级作业列表（授课过滤 + 缓存链路） | 6 连接 · 180 请求 | ~1250 req/s | 100% |
| 登录风暴 | BCrypt 校验（CPU 密集）+ 每次新建会话 | 16 连接 · 640 请求 | ~300 req/s | 100% |
| 成绩发布 | 批改 4 条提交 + 一键发布成绩（写链路，触发统计/邮件消息） | 串行 | — | 100% |

> 说明：压测刻意使用长连接与 TCP_NODELAY，测的是服务端真实吞吐而非连接建立开销；
> 热点读的吞吐区间取决于掺入写操作的比例（每次写都会精确失效缓存，下一读回源 MySQL），
> 登录风暴因 BCrypt 每次约 50–100ms 而吞吐最低，均符合预期。所有阶段 http/biz/net 三类
> 错误计数均为 0，`success 100.0%`。

### 6.4 MQ 链路验证（7 项断言，全部 PASS）

测试不只"看接口返回 200"，而是把 MQ 三个环节都做成可断言的事实：

- **拓扑预建（14/14）**：2 个 direct 交换机 + 6 个队列 + 6 条绑定，参数与 `RabbitMQConfig`
  逐一对齐（durable + `x-dead-letter-exchange`/`x-dead-letter-routing-key`）。dev 配置为
  `spring.rabbitmq.dynamic=false`，应用不会自动声明拓扑，因此必须由脚本先建。
- **业务链路自证（3 项）**：向 `homework.direct.exchange` 分别投递查重 / 成绩统计 / 批量邮件
  三类消息（载荷用不存在的 999001+ id，与业务数据无关），断言「投递 → 路由 → 落队 → 取回 →
  字段原样往返」。取回的消息头 `__TypeId__` 必须是 `com.campus.homework.dto.*` 全名，
  否则消费端 `Jackson2JsonMessageConverter` 还原不出强类型 DTO。
- **死信链路自证（3 项）**：向 `homework.dlx.exchange` 投 `.dead` 路由键，断言落入对应 DLQ，
  提前暴露 `x-dead-letter-*` 参数配错（配错时应用启动会撞 `PRECONDITION_FAILED`）。
- **业务真实消息回收（3 项）**：压测期间产生 70+ 条查重消息（每次提交一条）、发布成绩产生
  统计+邮件各一条，全部堆在队列（dev 为 `listener.simple.auto-startup=false`，消费者不在线，
  `consumer_count=0` 是断言项之一）；逐队列取回并校验 JSON 可反序列化与 `__TypeId__` 正确。

> 两个实测注意点：① 队列计数 `messages_*` 是每 5s 采样一次的快照，压测刚结束时可能显示
> 1/0/0 而实际可取回 3/1/1，故断言一律以 `get` 实际取回为准；② 复用上一轮 broker 时
> 队列会有历史积压，脚本先用 `DELETE /contents` 精确清空再做自证。

### 6.5 应用日志健康度（4 项断言，全部 PASS）

接口返回 200 不等于服务端没报错（MQ 投递失败会被 `afterCommit` 捕获后降级为 WARN），
所以最后一步直接读应用日志按级别逐行计数，作为「无报错」的最终裁判：

- **零 ERROR**：本次 14090 行日志中 `ERROR=0`；WARN 仅 10 条且全部可归类
  （MyBatis-Plus 在 JDK17 下的 `SerializedLambda` 反射告警、GlobalExceptionHandler 记录的
  越权/校验/登录失败、404 资源不存在），未分类 WARN 为 0。
- **MQ 上线时零投递失败/零降级**：`MQ 降级条数=0`，说明应用侧的投递全部成功落队。
- **MQ 缺席时降级为 WARN（非 ERROR）**：若本机没有 RabbitMQ，断言切换成这个口径——
  旁路降级属设计内行为，不允许以 ERROR 形式污染日志。
- **启动完成证据**：日志必须含 `Tomcat started`/`Started ... on port`，避免"空日志"被判成零 ERROR。

> 实现细节：Java 在中文 Windows 上按 GBK 写 stdout，脚本用编码嗅探（utf-8 → cp936/gbk →
> latin-1）读取，并对中文关键字失效的情况准备 ASCII 指纹（异常类名/`connection refused` 等）
> 兜底，防止"中文匹配不到导致假绿"。

### 6.6 单元测试

`mvn test` 共 **21 项用例、0 失败 0 错误**（`src/test/java/…/utils/` 下三个测试类）：
`TextSimilarityUtilsTest`（查重：分词/SimHash/Jaccard/三档阈值 10 项）、
`FileUploadUtilsTest`（上传安全：白名单/危险扩展名/路径穿越净化/大小限制 6 项）、
`ResultTest`（统一响应契约：success/fail/error 与 code=200/400/403/500 共 5 项）。