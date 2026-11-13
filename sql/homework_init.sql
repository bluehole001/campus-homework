-- =====================================================================
-- 班级学业管理与作业提交系统（campus_homework）数据库初始化脚本
-- 适配：MySQL 8.0 / InnoDB / utf8mb4
-- 执行方式：mysql -uroot -p < homework_init.sql
-- 约定：所有测试账号密码统一为明文 123456，库内存储 BCryptPasswordEncoder 密文；
--       每张表初始化测试数据不少于 5 条；截止时间使用 NOW() 相对偏移，
--       保证脚本执行后同时存在"已截止"与"未截止"的作业，便于验证逾期标记与催交任务。
-- =====================================================================

DROP DATABASE IF EXISTS campus_homework;
CREATE DATABASE campus_homework DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE campus_homework;

-- ---------------------------------------------------------------------
-- 1. clazz_info 班级信息表（教务管理员维护）
-- ---------------------------------------------------------------------
CREATE TABLE clazz_info (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '班级主键',
    clazz_code    VARCHAR(32)  NOT NULL COMMENT '班级编号（唯一，如 CS2023-01）',
    clazz_name    VARCHAR(64)  NOT NULL COMMENT '班级名称',
    department    VARCHAR(64)  NOT NULL COMMENT '所属院系',
    student_count INT          NOT NULL DEFAULT 0 COMMENT '班级在册学生数（导入/维护时同步）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_clazz_code (clazz_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '班级信息表';

-- ---------------------------------------------------------------------
-- 2. sys_user 系统用户表（学生/教师/管理员三级角色，BCrypt 密码）
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    user_no     VARCHAR(32) NOT NULL COMMENT '学号/工号（唯一登录凭证之一）',
    username    VARCHAR(32) NOT NULL COMMENT '登录用户名（唯一）',
    password    VARCHAR(64) NOT NULL COMMENT 'BCryptPasswordEncoder 密文（明文统一 123456）',
    real_name   VARCHAR(32) NOT NULL COMMENT '真实姓名',
    clazz_id    BIGINT      NULL COMMENT '所属班级ID（学生必填，教师/管理员为 NULL）',
    email       VARCHAR(64) NOT NULL COMMENT '邮箱（催交/成绩通知收件地址，必填）',
    role        VARCHAR(20) NOT NULL COMMENT '角色：ROLE_STUDENT/ROLE_TEACHER/ROLE_ADMIN',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1正常/0禁用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_no (user_no),
    UNIQUE KEY uk_username (username),
    KEY idx_user_clazz (clazz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户表';

-- ---------------------------------------------------------------------
-- 3. homework_task 作业任务表（教师发布，含知识点标签与附件）
-- ---------------------------------------------------------------------
CREATE TABLE homework_task (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '作业主键',
    clazz_id           BIGINT       NOT NULL COMMENT '目标班级ID',
    teacher_id         BIGINT       NOT NULL COMMENT '发布教师ID',
    title              VARCHAR(128) NOT NULL COMMENT '作业标题',
    knowledge_tag      VARCHAR(64)  NOT NULL COMMENT '知识点标签（用于得分率雷达图）',
    requirement        TEXT         NOT NULL COMMENT '作业要求正文',
    attachment_url     VARCHAR(255) NULL COMMENT '教师参考附件URL',
    attachment_name    VARCHAR(128) NULL COMMENT '教师参考附件原始文件名',
    total_score        INT          NOT NULL DEFAULT 100 COMMENT '作业满分（默认100）',
    deadline           DATETIME     NOT NULL COMMENT '截止时间',
    is_score_published TINYINT     NOT NULL DEFAULT 0 COMMENT '成绩发布：0未发布/1已发布',
    submit_count       INT          NOT NULL DEFAULT 0 COMMENT '已提交人数（提交时同步）',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (id),
    KEY idx_task_clazz (clazz_id),
    KEY idx_task_teacher (teacher_id),
    KEY idx_task_title (title),
    KEY idx_task_deadline (deadline)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '作业任务表';

-- ---------------------------------------------------------------------
-- 4. homework_submission 作业提交表（含查重结果与批改结果）
-- ---------------------------------------------------------------------
CREATE TABLE homework_submission (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '提交主键',
    homework_id         BIGINT       NOT NULL COMMENT '作业ID',
    student_id          BIGINT       NOT NULL COMMENT '提交学生ID',
    content_text        LONGTEXT     NULL COMMENT '作业正文（查重比对文本）',
    file_url            VARCHAR(255) NULL COMMENT '作业附件URL',
    file_name           VARCHAR(128) NULL COMMENT '作业附件原始文件名',
    submit_status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0未交/1按时交/2逾期未交/3已批改',
    similarity_rate     DECIMAL(5, 4) NULL COMMENT '最高余弦相似度（0~1）',
    similar_student_id  BIGINT       NULL COMMENT '最相似的同学ID',
    is_high_repeat      TINYINT      NOT NULL DEFAULT 0 COMMENT '高重复预警：0正常/1预警',
    score               DECIMAL(5, 1) NULL COMMENT '批改得分',
    teacher_comment     TEXT         NULL COMMENT '教师评语',
    submit_time         DATETIME     NULL COMMENT '提交时间',
    grade_time          DATETIME     NULL COMMENT '批改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hw_stu (homework_id, student_id),
    KEY idx_sub_hw (homework_id),
    KEY idx_sub_stu (student_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '作业提交表';

-- ---------------------------------------------------------------------
-- 5. clazz_notice 班级公告表（教师发布，支持置顶）
-- ---------------------------------------------------------------------
CREATE TABLE clazz_notice (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '公告主键',
    clazz_id    BIGINT       NOT NULL COMMENT '目标班级ID',
    teacher_id  BIGINT       NOT NULL COMMENT '发布教师ID',
    title       VARCHAR(128) NOT NULL COMMENT '公告标题',
    content     TEXT         NOT NULL COMMENT '公告正文',
    is_top      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶：0否/1是',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (id),
    KEY idx_notice_clazz (clazz_id),
    KEY idx_notice_teacher (teacher_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '班级公告表';

-- ---------------------------------------------------------------------
-- 6. monthly_submit_stat 月度提交统计表（每月1号定时任务生成）
-- ---------------------------------------------------------------------
CREATE TABLE monthly_submit_stat (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '统计主键',
    clazz_id            BIGINT        NOT NULL COMMENT '班级ID',
    stat_month          VARCHAR(7)    NOT NULL COMMENT '统计月份（yyyy-MM）',
    total_homework_num  INT           NOT NULL DEFAULT 0 COMMENT '当月作业总数',
    avg_submit_rate     DECIMAL(5, 2) NOT NULL DEFAULT 0.00 COMMENT '平均提交率（%）',
    avg_score           DECIMAL(5, 1) NULL COMMENT '平均得分',
    pass_rate           DECIMAL(5, 2) NULL COMMENT '及格率（%）',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (id),
    KEY idx_stat_clazz (clazz_id),
    UNIQUE KEY uk_stat_clazz_month (clazz_id, stat_month)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '月度提交统计表';

-- =====================================================================
-- 初始化测试数据（所有账号密码明文均为 123456，下方为 BCrypt 密文）
-- 密文由 BCryptPasswordEncoder(strength=10, $2a) 生成，可直接登录验证
-- =====================================================================

-- 1. clazz_info：5 个班级（student_count 与下方 sys_user 各班在册学生数逐一相等，数据自洽）
INSERT INTO clazz_info (id, clazz_code, clazz_name, department, student_count, create_time) VALUES
(1, 'CS2023-01', '计算机科学与技术2023级1班', '计算机学院', 4, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(2, 'CS2023-02', '计算机科学与技术2023级2班', '计算机学院', 3, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(3, 'SE2023-01', '软件工程2023级1班',         '软件学院',   3, DATE_SUB(NOW(), INTERVAL 118 DAY)),
(4, 'AI2024-01', '人工智能2024级1班',         '计算机学院', 2, DATE_SUB(NOW(), INTERVAL 60 DAY)),
(5, 'DS2024-01', '数据科学与大数据技术2024级1班', '数学学院', 1, DATE_SUB(NOW(), INTERVAL 60 DAY));

-- 2. sys_user：1 管理员 + 3 教师 + 13 学生（共 17 条，密码统一 123456）
-- 密文：$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya
INSERT INTO sys_user (id, user_no, username, password, real_name, clazz_id, email, role, status, create_time) VALUES
-- 教务管理员
(1,  'T00001', 'admin',    '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '教务处管理员', NULL, 'admin@campus.edu.cn',    'ROLE_ADMIN',   1, DATE_SUB(NOW(), INTERVAL 150 DAY)),
-- 任课教师
(2,  'T10001', 'teacher1', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '王建国',       NULL, 'wangjg@campus.edu.cn',   'ROLE_TEACHER', 1, DATE_SUB(NOW(), INTERVAL 149 DAY)),
(3,  'T10002', 'teacher2', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '李晓红',       NULL, 'lixh@campus.edu.cn',     'ROLE_TEACHER', 1, DATE_SUB(NOW(), INTERVAL 149 DAY)),
(4,  'T10003', 'teacher3', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '张卫民',       NULL, 'zhangwm@campus.edu.cn',  'ROLE_TEACHER', 1, DATE_SUB(NOW(), INTERVAL 148 DAY)),
-- 班级1学生（4人）
(5,  'S20230101', 'stu101', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '陈明',   1, 'chenming@stu.campus.edu.cn',  'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(6,  'S20230102', 'stu102', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '刘洋',   1, 'liuyang@stu.campus.edu.cn',   'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(7,  'S20230103', 'stu103', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '赵磊',   1, 'zhaolei@stu.campus.edu.cn',   'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(8,  'S20230104', 'stu104', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '孙倩',   1, 'sunqian@stu.campus.edu.cn',   'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
-- 班级2学生（3人）
(9,  'S20230201', 'stu201', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '周涛',   2, 'zhoutao@stu.campus.edu.cn',   'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(10, 'S20230202', 'stu202', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '吴迪',   2, 'wudi@stu.campus.edu.cn',      'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(11, 'S20230203', 'stu203', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '郑爽',   2, 'zhengshuang@stu.campus.edu.cn','ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
-- 班级3学生（3人）
(12, 'S20230301', 'stu301', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '黄强',   3, 'huangqiang@stu.campus.edu.cn','ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 118 DAY)),
(13, 'S20230302', 'stu302', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '徐丽',   3, 'xuli@stu.campus.edu.cn',      'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 118 DAY)),
(14, 'S20230303', 'stu303', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '马俊',   3, 'majun@stu.campus.edu.cn',     'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 118 DAY)),
-- 班级4学生（2人）
(15, 'S20240401', 'stu401', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '林峰',   4, 'linfeng@stu.campus.edu.cn',   'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 60 DAY)),
(16, 'S20240402', 'stu402', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '何雪',   4, 'hexue@stu.campus.edu.cn',     'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 60 DAY)),
-- 班级5学生（1人，补齐后 sys_user 各班实数与 clazz_info.student_count 逐班一致）
(17, 'S20240501', 'stu501', '$2a$10$vYEZYzkh/f0UqhyoJX45g.6/OUxW3MYkS6sugfv3u/3ujA0hcFtya', '诸一帆', 5, 'zhuyifan@stu.campus.edu.cn', 'ROLE_STUDENT', 1, DATE_SUB(NOW(), INTERVAL 60 DAY));

-- 3. homework_task：6 条作业（3 条已截止、2 条未截止、1 条临近截止<24h，覆盖逾期/催交场景）
INSERT INTO homework_task (id, clazz_id, teacher_id, title, knowledge_tag, requirement, attachment_url, attachment_name, total_score, deadline, is_score_published, submit_count, create_time) VALUES
(1, 1, 2, '数据库规范化理论作业',   '数据库设计',   '完成第3章课后题3.2、3.5：对给定关系模式进行范式判定并写出无损分解过程，提交Word正文与解题PDF附件。', '/files/ref/normalization_guide.pdf', '规范化理论参考资料.pdf', 100, DATE_SUB(NOW(), INTERVAL 10 DAY), 1, 3, DATE_SUB(NOW(), INTERVAL 17 DAY)),
(2, 1, 2, 'SQL查询综合练习',        'SQL编程',      '基于教学示例库完成10道多表连接与子查询练习，正文写明SQL语句与执行结果说明。', '/files/ref/sql_exercise_dataset.sql', 'SQL练习数据集.sql',       100, DATE_SUB(NOW(), INTERVAL 5 DAY),  0, 3, DATE_SUB(NOW(), INTERVAL 12 DAY)),
(3, 1, 3, '操作系统进程调度实验',   '操作系统',     '编写FCFS/SJF/时间片轮转三种调度算法的模拟程序，提交源码与实验报告，报告需含平均周转时间对比表。', '/files/ref/scheduling_template.docx', '实验报告模板.docx',   100, DATE_SUB(NOW(), INTERVAL 2 DAY),  0, 2, DATE_SUB(NOW(), INTERVAL 9 DAY)),
(4, 1, 2, '计算机网络协议分析',     '计算机网络',   '抓包分析HTTP/TCP三次握手过程，提交抓包截图与协议字段逐项说明。', NULL, NULL, 100, DATE_ADD(NOW(), INTERVAL 18 HOUR), 0, 1, DATE_SUB(NOW(), INTERVAL 3 DAY)),
(5, 2, 3, 'Java面向对象编程作业',   '面向对象',     '设计一个含继承与多态的小型库存管理系统，提交完整源码压缩包与UML类图。', '/files/ref/oop_assignment_spec.pdf', '作业规格说明书.pdf', 100, DATE_ADD(NOW(), INTERVAL 5 DAY),  0, 2, DATE_SUB(NOW(), INTERVAL 4 DAY)),
(6, 3, 4, '软件需求规格说明书编写', '需求工程',     '选取一个校园场景编写SRS文档，包含用例图、功能需求与非功能需求清单。', '/files/ref/srs_standard_excerpt.pdf', 'SRS标准节选.pdf', 100, DATE_SUB(NOW(), INTERVAL 1 DAY),  0, 2, DATE_SUB(NOW(), INTERVAL 8 DAY));

-- 4. homework_submission：15 条（含按时交、逾期未交、已批改、未交占位；1、2号作业正文高度相似用于查重预警演示）
-- 数据自洽：每条作业的"已交记录数"与 homework_task.submit_count 逐一相等（作业6 由 14/15 两条补齐）
-- 相似度为 N-Gram + 余弦相似度算法的预置演示值，系统重新查重时会被覆盖
INSERT INTO homework_submission (id, homework_id, student_id, content_text, file_url, file_name, submit_status, similarity_rate, similar_student_id, is_high_repeat, score, teacher_comment, submit_time, grade_time) VALUES
-- 作业1（已截止、成绩已发布）：3 人按时交并批改，其中 stu102 与 stu103 正文高度相似触发预警
(1, 1, 5,  '关系模式R(U,F)中F={A->B,B->C,C->D}，逐一判定各属性闭包后可知R存在非主属性对码的部分函数依赖，因此R属于1NF但不属于2NF。无损分解步骤：先按A->B拆出R1(A,B)，剩余R2(A,C,D)仍含A->C的传递依赖，继续拆出R3(A,C)，最终R4(C,D)。分解后各子模式均达到3NF，且连接后信息无损。', '/files/hw1/5.docx', '规范化理论作业_陈明.docx', 3, 0.4213, 6,    0, 92.0, '范式判定过程完整，无损分解步骤清晰，继续保持。', DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
(2, 1, 6,  '关系模式R(U,F)中F={A->B,B->C,C->D}，逐一判定各属性闭包后可知R存在非主属性对码的部分函数依赖，因此R属于1NF但不属于2NF。无损分解过程：先按A->B拆出R1(A,B)，剩余R2(A,C,D)仍含A->C的传递依赖，继续拆出R3(A,C)，最终得到R4(C,D)。分解后各子模式均达到3NF，且自然连接后信息无损。', '/files/hw1/6.docx', '规范化理论作业_刘洋.docx', 3, 0.9612, 7,    1, 85.0, '结论正确，但正文与赵磊同学高度雷同，已触发高重复预警，请注意独立写作。', DATE_SUB(NOW(), INTERVAL 11 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
(3, 1, 7,  '关系模式R(U,F)中F={A->B,B->C,C->D}，逐一判定各属性闭包后可知R存在非主属性对码的部分函数依赖，因此R属于1NF但不属于2NF。无损分解过程：先按A->B拆出R1(A,B)，剩余R2(A,C,D)仍含A->C的传递依赖，继续拆出R3(A,C)，最终得到R4(C,D)。分解后各子模式均达到3NF，且自然连接后信息无损。', '/files/hw1/7.docx', '规范化理论作业_赵磊.docx', 3, 0.9612, 6,    1, 85.0, '结论正确，但正文与刘洋同学高度雷同，已触发高重复预警，请注意独立写作。', DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY)),
-- 作业2（已截止）：2 人按时交未批改 + 1 人逾期补交（刘洋）+ 1 人未交占位
(4, 2, 5,  '练习1：SELECT sname FROM student WHERE dept=''计算机'' AND score>=85; 说明：先按院系过滤再按成绩过滤。练习2：SELECT c.cname FROM course c JOIN sc ON c.cid=sc.cid WHERE sc.sid=20230101; 说明：用学号定位选课记录再连接课程表取课名。', '/files/hw2/5.docx', 'SQL练习_陈明.docx', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 4 DAY), NULL),
(5, 2, 8,  '第1题用子查询实现：SELECT * FROM student WHERE dept IN (SELECT dept FROM student WHERE sid=20230104)。第2题用EXISTS改写相关子查询。第3题用LEFT JOIN统计各课程选修人数并按人数降序输出。全部语句已在本地MySQL 8.0验证通过。', '/files/hw2/8.docx', 'SQL练习_孙倩.docx', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 4 DAY), NULL),
(6, 2, 6,  '完成前5题，后5题因实验机房排队未完成。第1题：SELECT sname FROM student WHERE dept=''计算机''; 其余题目只写了思路未写SQL。', '/files/hw2/6.docx', 'SQL练习_刘洋_补交.docx', 2, NULL, NULL, 0, NULL, NULL, DATE_ADD(NOW(), INTERVAL 1 DAY), NULL),
(7, 2, 7,  NULL, NULL, NULL, 0, NULL, NULL, 0, NULL, NULL, NULL, NULL),
-- 作业3（已截止）：2 人按时交未批改 + 1 人未交占位
(8, 3, 5,  '用Java实现FCFS/SJF/RR三种调度算法模拟，进程到达序列固定为P1-P5，时间片q=4。运行结果：FCFS平均周转时间10.4，SJF平均周转时间6.8，RR平均周转时间11.2。SJF最优，RR周转时间最长但响应时间最均匀。', '/files/hw3/5.zip', '进程调度实验_陈明.zip', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL),
(9, 3, 8,  '实验程序采用优先队列实现SJF，RR采用循环就绪队列。对比表显示SJF平均周转时间最小，FCFS存在明显的长作业等待问题（护航效应）。附实验截图与源码。', '/files/hw3/8.zip', '进程调度实验_孙倩.zip', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL),
(10, 3, 6, NULL, NULL, NULL, 0, NULL, NULL, 0, NULL, NULL, NULL, NULL),
-- 作业4（未截止，18小时后）：1 人已交
(11, 4, 7, '用Wireshark抓取浏览器访问http://example.com的完整过程，过滤tcp.port==80。三次握手：SYN(seq=0,flags=0x02)->SYN-ACK(seq=0,ack=1)->ACK(ack=1)。HTTP请求报文含GET / HTTP/1.1与Host头，响应为301重定向。逐项字段说明见截图标注。', '/files/hw4/7.zip', '协议分析_赵磊.zip', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL),
-- 作业5（未截止，5天后）：2 人已交
(12, 5, 9,  '设计Product基类与Warehouse/Store子类，利用多态实现统一入库接口；含接口Stockable、抽象类校验库存上下限。UML类图见压缩包内class_diagram.png，全部代码通过JUnit5自测。', '/files/hw5/9.zip', 'OO作业_周涛.zip', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL),
(13, 5, 10, '库存管理系统采用组合而非继承：StockItem持有策略对象ReplenishPolicy，实现不同补货策略切换。附UML类图与测试报告，覆盖增删改查与并发扣减场景。', '/files/hw5/10.zip', 'OO作业_吴迪.zip', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL),
-- 作业6（已截止1天）：class3 的 stu301/stu302 按时交未批改（补齐后"已交记录数"与 hw6.submit_count=2 一致）
(14, 6, 12, '选取校园快递驿站场景编写SRS：用例图含寄件/取件/投诉三类参与者，功能需求12条，非功能需求含响应时间与并发指标；附用例图与需求-用例追溯矩阵。', '/files/hw6/12.docx', 'SRS_黄强.docx', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 20 HOUR), NULL),
(15, 6, 13, '选取二手教材交易场景编写SRS：含用例图、消息提醒的发布-订阅式需求、以及隐私合规的非功能需求；初稿已按SRS标准节选的章节结构组织。', '/files/hw6/13.docx', 'SRS_徐丽.docx', 1, NULL, NULL, 0, NULL, NULL, DATE_SUB(NOW(), INTERVAL 18 HOUR), NULL);

-- 5. clazz_notice：6 条公告（含 2 条置顶）
INSERT INTO clazz_notice (id, clazz_id, teacher_id, title, content, is_top, create_time) VALUES
(1, 1, 2, '本周数据库作业提交提醒', '《数据库规范化理论作业》已于本周一截止，成绩与评语已发布，请同学查看个人成绩与班级排名；对批改结果有异议请在三天内邮件联系我。', 1, DATE_SUB(NOW(), INTERVAL 9 DAY)),
(2, 1, 3, '操作系统实验课调课通知', '因机房维护，周五第7-8节操作系统实验课改至下周一同一时段，地点改为实验楼B302，请相互转告。', 1, DATE_SUB(NOW(), INTERVAL 6 DAY)),
(3, 1, 2, 'SQL练习批改安排', '《SQL查询综合练习》将在本周五前完成批改并发布成绩，逾期补交通道将在成绩发布后关闭。', 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
(4, 2, 3, 'Java作业查重规则说明', '系统将对作业正文进行N-Gram余弦相似度查重，相似度达到预警线的提交会被标记高重复，教师批改时会重点复核，请独立完成。', 0, DATE_SUB(NOW(), INTERVAL 4 DAY)),
(5, 3, 4, 'SRS写作参考资料已上传', '《软件需求规格说明书编写》作业已附SRS标准节选作为参考附件，用例图请使用课程提供的模板符号。', 0, DATE_SUB(NOW(), INTERVAL 8 DAY)),
(6, 3, 4, '需求工程期中答疑安排', '本周三19:00在软件楼A201进行SRS写作集中答疑，请带着初稿参加，现场逐份点评。', 0, DATE_SUB(NOW(), INTERVAL 2 DAY));

-- 6. monthly_submit_stat：6 条（覆盖 3 个班级 × 近两个月，供看板趋势图演示）
INSERT INTO monthly_submit_stat (id, clazz_id, stat_month, total_homework_num, avg_submit_rate, avg_score, pass_rate, create_time) VALUES
(1, 1, DATE_FORMAT(DATE_SUB(NOW(), INTERVAL 1 MONTH), '%Y-%m'), 6, 83.33, 81.5, 75.00, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(2, 1, DATE_FORMAT(NOW(), '%Y-%m'),                             4, 75.00, NULL, NULL,  NOW()),
(3, 2, DATE_FORMAT(DATE_SUB(NOW(), INTERVAL 1 MONTH), '%Y-%m'), 5, 80.00, 78.2, 60.00, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(4, 2, DATE_FORMAT(NOW(), '%Y-%m'),                             1, 100.00, NULL, NULL, NOW()),
(5, 3, DATE_FORMAT(DATE_SUB(NOW(), INTERVAL 1 MONTH), '%Y-%m'), 4, 66.67, 72.0, 50.00, DATE_SUB(NOW(), INTERVAL 1 DAY)),
(6, 3, DATE_FORMAT(NOW(), '%Y-%m'),                             1, 66.67, NULL, NULL,  NOW());
