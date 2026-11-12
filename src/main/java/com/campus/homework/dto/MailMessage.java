package com.campus.homework.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 批量邮件消息体（路由键 homework.mail.batch，成绩发布通知与截止前催交共用）。
 * <p>消息只携带"作业ID + 邮件类型"，收件人名单由消费端实时查库获取——
 * 避免把整班邮箱列表塞进消息体（消息体积膨胀且可能过期），也保证
 * 催交邮件不会发给"消息发出后又补交了"的学生（消费时重新判定）。</p>
 *
 * @author campus-homework
 */
@Data
public class MailMessage implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 邮件类型：1 成绩发布通知（发给全班） */
    public static final int TYPE_SCORE_PUBLISH = 1;

    /** 邮件类型：2 截止前催交提醒（只发给尚未提交的学生） */
    public static final int TYPE_URGE = 2;

    /** 作业ID（消费端据此查作业、班级与收件人） */
    private Long homeworkId;

    /** 邮件类型（TYPE_SCORE_PUBLISH / TYPE_URGE） */
    private Integer type;

    /**
     * 无参构造（Jackson 反序列化需要）。
     */
    public MailMessage() {
    }

    /**
     * 全参构造。
     *
     * @param homeworkId 作业ID
     * @param type       邮件类型
     */
    public MailMessage(Long homeworkId, Integer type) {
        this.homeworkId = homeworkId;
        this.type = type;
    }
}
