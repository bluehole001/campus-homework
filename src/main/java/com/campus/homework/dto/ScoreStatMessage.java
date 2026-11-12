package com.campus.homework.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 成绩统计与缓存预热消息体（路由键 homework.score.stat，成绩发布成功后投递）。
 * <p>消费端收到后：重算该作业成绩分析并写入
 * homework:score:analysis:{homeworkId}，再逐生预热
 * homework:score:stu:{homeworkId}:{studentId} 个人成绩缓存——
 * 把"查分高峰"的读压力提前转移到 MQ 异步消费与 Redis，保护 MySQL。</p>
 *
 * @author campus-homework
 */
@Data
public class ScoreStatMessage implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID（发布成绩成功的作业） */
    private Long homeworkId;

    /**
     * 无参构造（Jackson 反序列化需要）。
     */
    public ScoreStatMessage() {
    }

    /**
     * 全参构造。
     *
     * @param homeworkId 作业ID
     */
    public ScoreStatMessage(Long homeworkId) {
        this.homeworkId = homeworkId;
    }
}
