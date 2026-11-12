# 班级学业管理与作业提交系统 · Linux 部署说明

适用版本：campus-homework 1.0.0（Spring Boot 2.7.18 / JDK 17）
目标环境：CentOS 7+/Rocky/RHEL 8+/Ubuntu 20.04+（下文命令以 CentOS/RHEL 为主，Ubuntu 差异处已标注）

---

## 1. 环境与依赖要求

| 组件 | 版本要求 | 用途 | 是否必需 |
|------|---------|------|---------|
| JDK | 17（OpenJDK 17 推荐） | 运行可执行 jar | 必需 |
| MySQL | 8.0.x（InnoDB / utf8mb4） | 业务数据库 campus_homework | 必需 |
| Redis | 6.x / 7.x | 列表与成绩缓存、穿透防护 | 必需 |
| RabbitMQ | 3.9+（管理插件可选） | 异步查重 / 成绩预热 / 批量邮件 + 死信队列 | 必需 |
| Nginx | 1.18+ | 反向代理 8080（HTTPS 终结、上传体积限制） | 推荐 |
| Maven | 3.8+（仅构建机需要） | 打包 jar | 构建时必需 |

> 应用启动时会自动声明全部交换机/队列/死信队列（RabbitMQConfig，durable），
> 无需手工建队列；但 vhost `/homework` 与 MQ 账号需管理员预先创建并授权（见 §4.3）。

## 2. 服务器目录规划

```
/opt/campus-homework/campus-homework.jar     # 可执行 jar（构建产物 target/campus-homework.jar）
/data/homework/upload/                        # 附件存储根目录（prod 配置 homework.upload.dir）
/data/homework/logs/                          # 应用日志（logging.file.name）
/data/backup/mysql/                           # 数据库每日备份（mysqldump，见 §7）
```

创建目录并预置运行用户（应用以非 root 用户运行，日志与上传目录属主为该用户）：

```bash
# 运行用户与组
sudo useradd -r -s /sbin/nologin homework

# 数据目录
sudo mkdir -p /data/homework/upload /data/homework/logs /opt/campus-homework
sudo chown -R homework:homework /data/homework /opt/campus-homework

# SELinux（CentOS 默认 enforcing）：允许服务进程读写数据目录与监听 8080
sudo restorecon -Rv /data/homework /opt/campus-homework
sudo setsebool -P httpd_can_network_connect 1   # Nginx 反代应用所需
```

Ubuntu 无 SELinux，可跳过 restorecon/setsebool 两步（`setsebool` 命令不存在时忽略报错）。

## 3. 安装运行时

### 3.1 JDK 17

```bash
# CentOS/RHEL 8+/Rocky
sudo dnf install -y java-17-openjdk-devel
java -version                                 # 确认输出为 17.x

# Ubuntu
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
```

### 3.2 MySQL 8.0

```bash
sudo dnf module install -y mysql:8.0          # CentOS Stream/Rocky 模块流方式
# 或官方仓库：https://dev.mysql.com/downloads/repo/yum/
sudo systemctl enable --now mysqld
```

### 3.3 Redis

```bash
sudo dnf install -y redis
# /etc/redis.conf 关键项：
#   bind 127.0.0.1
#   requirepass <强密码>        （与 §4 的 DEPLOY_REDIS_PASSWORD 一致）
#   appendonly yes              （缓存可重建，但开启 AOF 更稳）
sudo systemctl enable --now redis
```

### 3.4 RabbitMQ

```bash
# Erlang + RabbitMQ（以 rabbitmq-erlang 仓库官方安装为准）
sudo dnf install -y erlang rabbitmq-server
sudo systemctl enable --now rabbitmq-server

# 创建业务 vhost 与专用账号（应用配置 virtual-host: /homework）
sudo rabbitmqctl add_user homework <MQ强密码>
sudo rabbitmqctl add_vhost /homework
sudo rabbitmqctl set_user_tags homework management
sudo rabbitmqctl set_permissions -p /homework homework ".*" ".*" ".*"
```

## 4. 初始化与配置注入

### 4.1 数据库初始化

```bash
# 脚本会 DROP 并重建 campus_homework 库、建 7 张表并写入初始化数据
mysql -uroot -p < /opt/campus-homework/sql/homework_init.sql
```

脚本要点（与 sql/homework_init.sql 头部注释一致）：
- 所有测试账号密码统一明文 `123456`，库内为 BCrypt 密文；
- 截止时间使用 `NOW()` 相对偏移，脚本执行后同时存在"已截止/未截止"作业，
  便于验证逾期标记与催交任务；
- 初始化后请**立即修改管理员与教师测试账号密码**（登录后台"账号管理→重置密码"或直改库）。

为应用创建最小权限账号：

```sql
CREATE USER 'hw_app'@'%' IDENTIFIED BY '<强密码>';
GRANT SELECT, INSERT, UPDATE, DELETE ON campus_homework.* TO 'hw_app'@'%';
FLUSH PRIVILEGES;
```

### 4.2 环境变量（敏感项一律环境变量注入，prod 配置不写明文）

`application-prod.yml` 读取的环境变量清单（缺省值仅为本机开发兜底，生产必须显式设置）：

| 变量 | 含义 | 示例 |
|------|------|------|
| `DEPLOY_DB_HOST` / `DEPLOY_DB_PORT` | MySQL 地址/端口 | `127.0.0.1` / `3306` |
| `DEPLOY_DB_USER` / `DEPLOY_DB_PASSWORD` | 数据库账号（建议 hw_app） | `hw_app` / `***` |
| `DEPLOY_REDIS_HOST` / `DEPLOY_REDIS_PORT` / `DEPLOY_REDIS_PASSWORD` | Redis 连接 | `127.0.0.1` / `6379` / `***` |
| `DEPLOY_MQ_HOST` / `DEPLOY_MQ_PORT` / `DEPLOY_MQ_USER` / `DEPLOY_MQ_PASSWORD` | RabbitMQ 连接（vhost 固定 /homework） | `homework` / `***` |
| `DEPLOY_MAIL_HOST` / `DEPLOY_MAIL_PORT` / `DEPLOY_MAIL_USER` / `DEPLOY_MAIL_PASSWORD` | SMTP（催交/成绩邮件，示例 smtp.qq.com:465） | `***` |

集中存放于 `/etc/campus-homework.env`（systemd EnvironmentFile），权限 600、属主 root：

```bash
sudo tee /etc/campus-homework.env > /dev/null <<'EOF'
DEPLOY_DB_HOST=127.0.0.1
DEPLOY_DB_PORT=3306
DEPLOY_DB_USER=hw_app
DEPLOY_DB_PASSWORD=***
DEPLOY_REDIS_HOST=127.0.0.1
DEPLOY_REDIS_PORT=6379
DEPLOY_REDIS_PASSWORD=***
DEPLOY_MQ_HOST=127.0.0.1
DEPLOY_MQ_PORT=5672
DEPLOY_MQ_USER=homework
DEPLOY_MQ_PASSWORD=***
DEPLOY_MAIL_HOST=smtp.qq.com
DEPLOY_MAIL_PORT=465
DEPLOY_MAIL_USER=notice@campus.edu.cn
DEPLOY_MAIL_PASSWORD=***
EOF
sudo chmod 600 /etc/campus-homework.env
```

### 4.3 时区

应用与数据库均按 `Asia/Shanghai`（JDBC `serverTimezone=Asia/Shanghai`、Jackson `GMT+8`、
定时任务 cron 按本地时区触发），服务器必须同步时区：

```bash
sudo timedatectl set-timezone Asia/Shanghai
```

## 5. 构建与部署

### 5.1 构建（构建机或服务器本机）

```bash
mvn -f 班级学业管理与作业提交系统/pom.xml -DskipTests clean package
# 产物：target/campus-homework.jar（finalName 固定，无版本号后缀）
```

上传到服务器：

```bash
sudo scp target/campus-homework.jar homework@<server>:/opt/campus-homework/campus-homework.jar.new
```

### 5.2 systemd 服务单元

`/etc/systemd/system/campus-homework.service`：

```ini
[Unit]
Description=Campus Homework (Spring Boot)
After=network.target mysqld.service redis.service rabbitmq-server.service
Wants=mysqld.service redis.service rabbitmq-server.service

[Service]
Type=simple
User=homework
Group=homework
WorkingDirectory=/opt/campus-homework
EnvironmentFile=/etc/campus-homework.env
ExecStart=/usr/bin/java -Xms512m -Xmx1024m \
  -jar /opt/campus-homework/campus-homework.jar \
  --spring.profiles.active=prod
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
LimitNOFILE=65535
# 防目录写越权
ProtectSystem=full
ReadWritePaths=/data/homework

[Install]
WantedBy=multi-user.target
```

启用与启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now campus-homework
sudo systemctl status campus-homework
```

> 升级流程：替换为 `.jar.new` → `sudo mv -f campus-homework.jar.new campus-homework.jar`
> → `sudo systemctl restart campus-homework`。回滚保留上一版 jar 副本即可。

### 5.3 Nginx 反向代理（推荐）

`/etc/nginx/conf.d/campus-homework.conf`：

```nginx
server {
    listen 80;
    server_name homework.campus.edu.cn;   # 按实际域名修改

    # 作业附件上限 50MB（与 spring.servlet.multipart 一致，Nginx 先拦截超大请求）
    client_max_body_size 50m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 页面 AJAX 与文件下载均为普通 HTTP，无需 websocket 头
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

> 附件下载一律走应用内 `/api/file/download`（会话鉴权 + Service 层归属校验），
> **不要**把 `/data/homework/upload` 直接暴露为 Nginx 静态目录——那会绕过登录校验。

## 6. 部署后验证清单

按顺序逐项确认（全部通过才算部署完成）：

1. **进程与端口**：`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/login` → `200`
2. **登录**：浏览器访问 `http://<server>/login`，用初始化学号 + `123456` 登录，
   按角色自动分流（学生→我的作业 / 教师→作业管理 / 管理员→班级管理）
3. **静态资源**：登录页在无外网情况下样式正常（Bootstrap/jQuery/ECharts 全部本地化，
   `/vendor/**` 已匿名放行，不依赖 CDN）
4. **附件上传下载**：教师发布作业上传参考附件、学生提交附件后下载，落盘于
   `/data/homework/upload`（按日期分目录）
5. **MQ 声明**：`sudo rabbitmqctl list_queues -p /homework name messages` 应出现
   `homework.plagiarism.queue / homework.score.stat.queue / homework.mail.batch.queue`
   及三个 `.dlq` 死信队列（应用启动时自动声明）
6. **缓存**：`redis-cli -a <密码> keys 'homework:*'` 在学生/教师浏览列表后出现
   `homework:list:clazz:*`、`homework:score:*` 等键
7. **定时任务**：日志中确认三类任务注册：逾期标记（每 10 分钟）、催交邮件（每日 8:30）、
   月度统计（每月 1 日 00:00）；可临时把系统时间或等待窗口观察 `OverdueMarkTask` 执行记录
8. **日志落盘**：`/data/homework/logs/campus-homework.log` 持续写入（prod 为 root=warn、业务 info）

## 7. 运维：备份、日志与故障排查

### 7.1 每日备份（cron）

```bash
# /etc/cron.d/hw-backup（每日 02:30 备份数据库并保留 14 天）
30 2 * * * root mysqldump -uhw_app -p'<密码>' --single-transaction campus_homework \
  | gzip > /data/backup/mysql/campus_homework_$(date +\%F).sql.gz && \
  find /data/backup/mysql -name '*.sql.gz' -mtime +14 -delete
```

附件目录增量备份（rsync 每日）：

```bash
0 3 * * * root rsync -a --delete /data/homework/upload/ /data/backup/upload/
```

### 7.2 日志轮转

应用日志由 Spring Boot 默认按大小轮转（prod 配置 logging.file.name）。补充 logrotate
接管系统侧文件：`/etc/logrotate.d/campus-homework` 指向 `/data/homework/logs/*.log`，
`daily / rotate 14 / compress / missingok / notifempty / copytruncate`。

### 7.3 常见故障速查

| 现象 | 排查方向 |
|------|---------|
| 启动即退出，日志 `Communications link failure` | MySQL 地址/账号/防火墙（3306）；`DEPLOY_DB_*` 是否注入 |
| 启动日志 `Connection refused` 且含 6379 | Redis 未启动或 `requirepass` 与 `DEPLOY_REDIS_PASSWORD` 不一致 |
| 启动日志 `AUTHENTICATION_FAILURE` 且含 5672 | MQ 账号/vhost 权限（§3.4 set_permissions）或 `DEPLOY_MQ_*` |
| 登录报"网络异常" | Nginx 未 reload、8080 未监听、SELinux `httpd_can_network_connect` 未开 |
| 登录成功但页面样式丢失 | `/vendor/**` 被代理改写路径；确认 Nginx `location /` 完整转发 |
| 邮件不发送 | SMTP 授权码（QQ 邮箱须用授权码而非登录密码）、465 端口出网策略 |
| 查重预警不更新 | 死信队列有积压消息 → 查 `homework.plagiarism.dlq` 与消费者异常日志 |

## 8. 安全加固建议

- 服务器仅开放 80/443（Nginx），8080/3306/6379/5672 一律绑定内网或本机；
- `firewall-cmd --permanent --add-service=http && --add-service=https && --reload`
  （Ubuntu 用 ufw allow 80,443）；
- HTTPS：证书由 Nginx 终结，`X-Forwarded-Proto` 已透传，Spring 端如需强制 HTTPS
  可在 SecurityConfig 追加 `requiresChannel` 配置（当前版本未启用，按需开启）；
- 初始密码策略：部署完成后立即重置全部演示账号密码（管理端"账号管理→重置密码"
  或 SQL 直改 BCrypt 密文）。
