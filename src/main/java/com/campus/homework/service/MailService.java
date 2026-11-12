package com.campus.homework.service;

/**
 * 批量邮件服务接口（Batch 4，成绩发布通知与截止前催交两类邮件的发送入口）。
 * <p>分层铁律：收件人查询、正文组装与 JavaMailSender 投递等业务逻辑一律在
 * {@code service.impl} 完成；MQ 消费者（mq 包）仅做协议适配与 ACK 控制，
 * 不直接操作 Mapper。</p>
 * <p>环境开关：{@code homework.mail.enabled=false}（dev 默认）时不真正投递，
 * 仅以日志记录"本应发送"的邮件内容，避免本机无 SMTP 时消费报错；
 * prod 环境 enabled=true 走真实 JavaMailSender。</p>
 *
 * @author campus-homework
 */
public interface MailService {

    /**
     * 发送成绩发布通知邮件（发给该作业所在班级全部在册且有邮箱的学生）。
     * <p>收件人在消费时实时查库获取（而非消息携带），保证名单与班级归属准确。</p>
     *
     * @param homeworkId 成绩刚发布的作业ID
     * @return 实际发送（或日志模拟发送）的邮件数；收件人为 0 时返回 0
     * @throws com.campus.homework.exception.BusinessException 开启真实发送且全部投递失败时抛出（触发死信）
     */
    int sendScorePublishMail(Long homeworkId);

    /**
     * 发送截止前催交邮件（只发给该作业尚未提交的学生，消费时重新判定）。
     * <p>"消费时重新判定"保证不会把催交邮件发给"消息发出后又补交了"的学生。</p>
     *
     * @param homeworkId 临近截止的作业ID
     * @return 实际发送（或日志模拟发送）的邮件数；无未交学生时返回 0
     * @throws com.campus.homework.exception.BusinessException 开启真实发送且全部投递失败时抛出（触发死信）
     */
    int sendUrgeMail(Long homeworkId);
}
