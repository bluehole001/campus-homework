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
| 缓存 | Redis（Lettuce 连接池） | 班级作业/公告列表缓存、成绩分析与个人成绩缓存；`__NULL__` 空值占位防穿透；写路径事务 afterCommit 精确失效 |
| 消息队列 | RabbitMQ（direct 交换机 + 死信队列 DLX/DLQ） | 异步查重、成绩分析与 Redis 预热、批量邮件；手动 ACK + 消费重试 3 次后入 DLQ |
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

#### 📋 初始化测试账号清单（共计 18 个）
- **数据规模**：1 名管理员、3 名教师、14 名学生，另含 3 个班级及配套的作业、提交记录、公告与统计数据。
- **安全规范**：所有密码均已使用 `BCryptPasswordEncoder` 加密入库，**所有账号的统一明文密码均为 `123456`**。

**1. 管理员账号（1名）**

| 账号 (Username) | 姓名 (Real Name) | 角色 (Role) | 管辖范围 |
|---|---|---|---|
| admin | 超级管理员 | ROLE_ADMIN | 全局数据维护、师生账号管理、CSV导入 |

**2. 教师账号（3名）**

| 账号 (Username) | 姓名 (Real Name) | 角色 (Role) | 所属院系 / 授课班级 |
|---|---|---|---|
| teacher1 | 张老师 | ROLE_TEACHER | 软件工程系 / 软件1班、软件2班 |
| teacher2 | 李老师 | ROLE_TEACHER | 计算机科学系 / 计算机1班 |
| teacher3 | 王老师 | ROLE_TEACHER | 软件工程系 / 跨班授课（用于测试教师IDOR防护） |

**3. 学生账号（14名）**

| 账号 (Username) | 姓名 (Real Name) | 角色 (Role) | 所属班级 |
|---|---|---|---|
| stu101 | 赵一 | ROLE_STUDENT | 软件1班 |
| stu102 | 钱二 | ROLE_STUDENT | 软件1班 |
| stu103 | 孙三 | ROLE_STUDENT | 软件1班 |
| stu104 | 李四 | ROLE_STUDENT | 软件1班 |
| stu105 | 周五 | ROLE_STUDENT | 软件1班 |
| stu201 | 吴六 | ROLE_STUDENT | 软件2班 |
| stu202 | 郑七 | ROLE_STUDENT | 软件2班 |
| stu203 | 王八 | ROLE_STUDENT | 软件2班 |
| stu204 | 冯九 | ROLE_STUDENT | 软件2班 |
| stu205 | 陈十 | ROLE_STUDENT | 软件2班 |
| stu301 | 褚十一 | ROLE_STUDENT | 计算机1班 |
| stu302 | 卫十二 | ROLE_STUDENT | 计算机1班 |
| stu303 | 蒋十三 | ROLE_STUDENT | 计算机1班 |
| stu304 | 沈十四 | ROLE_STUDENT | 计算机1班 |

> 💡 **提示**：在演示防越权（IDOR）功能时，可分别用 `teacher1` 和 `teacher2` 登录，或者用 `stu101`（软件1班）去尝试访问 `stu201`（软件2班）的作业 ID，展示系统在 Service 层对跨班级/跨用户的拦截效果。

### 3.3 配置 Redis / RabbitMQ / 邮件

默认激活 `dev` profile（`application-dev.yml`），本机默认参数即可直连：

- Redis：`localhost:6379`，无密码，db 0
- RabbitMQ：`localhost:5672`，`guest/guest`，vhost `/`（交换机/队列/死信队列由应用启动时自动声明，无需手工创建）
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

启动后访问 `http://localhost:8080/login`，按角色登录后自动分流到对应首页。附件落盘目录为 `./upload`（`homework.upload.dir`）。

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
├── sql/homework_init.sql        # 建库建表 + 测试数据（密码 BCrypt 密文，明文统一 123456）
├── deploy/Linux部署说明.md      # Linux 完整部署运维手册
├── src/main/java/com/campus/homework/     # 81 个 Java 源文件（注释率 35.2%）
├── src/main/resources/
│   ├── application.yml / -dev.yml / -prod.yml   # 多环境配置
│   ├── templates/               # Thymeleaf：fragments/layout.html + 13 个页面（登录/学生3/教师6/管理3）
│   └── static/                  # vendor/（Bootstrap5/jQuery/ECharts5 本地库）、css/app.css、js/api.js
└── target/campus-homework.jar   # mvn package 产物
```