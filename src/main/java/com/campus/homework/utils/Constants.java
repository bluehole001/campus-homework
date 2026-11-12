package com.campus.homework.utils;

/**
 * 全局常量集中定义：角色、状态、缓存键前缀、MQ 拓扑名称、分页边界。
 * <p>集中管理避免魔法字符串散落各层；MQ 名称在 Batch 4 的 config/mq 包中
 * 声明队列交换机时直接复用这里的常量，保证拓扑一致。</p>
 *
 * @author campus-homework
 */
public final class Constants {

    /** 工具类常量集合，禁止实例化 */
    private Constants() {
    }

    // ==================== 角色（与 Spring Security GrantedAuthority 一致） ====================
    /** 学生角色 */
    public static final String ROLE_STUDENT = "ROLE_STUDENT";
    /** 任课教师角色 */
    public static final String ROLE_TEACHER = "ROLE_TEACHER";
    /** 教务系统管理员角色 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    // ==================== 账号状态 ====================
    /** 账号正常 */
    public static final int USER_STATUS_NORMAL = 1;
    /** 账号禁用（登录被拦截） */
    public static final int USER_STATUS_DISABLED = 0;

    // ==================== 提交状态（homework_submission.submit_status） ====================
    /** 0 未交（占位记录，由定时任务或教师名单页生成） */
    public static final int SUBMIT_NOT = 0;
    /** 1 按时交 */
    public static final int SUBMIT_ON_TIME = 1;
    /** 2 逾期未交（截止后补交或定时任务标记） */
    public static final int SUBMIT_OVERDUE = 2;
    /** 3 已批改 */
    public static final int SUBMIT_GRADED = 3;

    // ==================== 成绩发布 / 置顶 / 预警开关 ====================
    /** 成绩未发布（学生不可见分数） */
    public static final int SCORE_UNPUBLISHED = 0;
    /** 成绩已发布 */
    public static final int SCORE_PUBLISHED = 1;
    /** 公告不置顶 */
    public static final int NOTICE_NOT_TOP = 0;
    /** 公告置顶 */
    public static final int NOTICE_TOP = 1;
    /** 查重正常（未达预警阈值） */
    public static final int REPEAT_NORMAL = 0;
    /** 高重复预警（相似度 ≥ 阈值） */
    public static final int REPEAT_HIGH = 1;

    // ==================== 分页边界 ====================
    /** 默认页码（从 1 开始） */
    public static final long DEFAULT_PAGE_NUM = 1L;
    /** 默认每页条数 */
    public static final long DEFAULT_PAGE_SIZE = 10L;
    /** 每页最大条数（防止前端传入超大 pageSize 拖垮数据库） */
    public static final long MAX_PAGE_SIZE = 100L;

    // ==================== Redis 缓存键前缀（与计划书契约一致） ====================
    /** 班级作业列表缓存前缀：homework:list:clazz:{clazzId} */
    public static final String CACHE_HOMEWORK_LIST = "homework:list:clazz:";
    /** 班级公告列表缓存前缀：homework:notice:clazz:{clazzId} */
    public static final String CACHE_NOTICE_LIST = "homework:notice:clazz:";
    /** 作业成绩分析缓存前缀：homework:score:analysis:{homeworkId} */
    public static final String CACHE_SCORE_ANALYSIS = "homework:score:analysis:";
    /** 学生个人成绩缓存前缀：homework:score:stu:{homeworkId}:{studentId} */
    public static final String CACHE_SCORE_STUDENT = "homework:score:stu:";
    /** 缓存空值占位符（防缓存穿透：空结果也短暂缓存该标记） */
    public static final String CACHE_NULL_VALUE = "__NULL__";

    // ==================== 缓存过期时间（秒，Batch 3 接入 Redis 时使用） ====================
    /** 班级作业/公告列表缓存 TTL：5 分钟（列表读多写少，写路径主动失效兜底） */
    public static final long CACHE_TTL_LIST = 300L;
    /** 成绩分析/个人成绩缓存 TTL：10 分钟（批改与发布动作会主动失效） */
    public static final long CACHE_TTL_SCORE = 600L;
    /** 空值占位缓存 TTL：60 秒（防穿透又不长期占用内存，新数据写入后立即重建） */
    public static final long CACHE_TTL_NULL = 60L;
    /** 个人成绩缓存键通配后缀（发布成绩时按 homework:score:stu:{hwId}:* 批量清理） */
    public static final String CACHE_SCORE_STUDENT_WILDCARD = "*";

    // ==================== RabbitMQ 拓扑（Batch 4 声明时复用） ====================
    /** 业务直连交换机：承载查重/统计/邮件三类路由 */
    public static final String EXCHANGE_HOMEWORK = "homework.direct.exchange";
    /** 异步查重队列：提交后全文与同班已提交文本做余弦相似度比对 */
    public static final String QUEUE_PLAGIARISM = "homework.plagiarism.queue";
    /** 成绩分析队列：成绩发布后统计并预热 Redis 查分缓存 */
    public static final String QUEUE_SCORE_STAT = "homework.score.stat.queue";
    /** 批量邮件队列：成绩发布通知与催交邮件异步投递 */
    public static final String QUEUE_MAIL_BATCH = "homework.mail.batch.queue";
    /** 查重路由键 */
    public static final String ROUTING_PLAGIARISM = "homework.plagiarism";
    /** 成绩统计路由键 */
    public static final String ROUTING_SCORE_STAT = "homework.score.stat";
    /** 批量邮件路由键 */
    public static final String ROUTING_MAIL_BATCH = "homework.mail.batch";
    /** 死信交换机：消费重试耗尽的消息进入 DLX 人工排查 */
    public static final String EXCHANGE_HOMEWORK_DLX = "homework.dlx.exchange";
    /** 查重死信队列 */
    public static final String QUEUE_PLAGIARISM_DLQ = "homework.plagiarism.dlq";
    /** 成绩统计死信队列 */
    public static final String QUEUE_SCORE_STAT_DLQ = "homework.score.stat.dlq";
    /** 邮件死信队列 */
    public static final String QUEUE_MAIL_BATCH_DLQ = "homework.mail.batch.dlq";
    /** 死信路由键（与业务路由键一一对应加 .dead 后缀） */
    public static final String ROUTING_PLAGIARISM_DEAD = "homework.plagiarism.dead";
    /** 成绩统计死信路由键 */
    public static final String ROUTING_SCORE_STAT_DEAD = "homework.score.stat.dead";
    /** 邮件死信路由键 */
    public static final String ROUTING_MAIL_BATCH_DEAD = "homework.mail.batch.dead";
}
