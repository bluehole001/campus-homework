package com.campus.homework.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 异步查重消息体（路由键 homework.plagiarism，提交/重交成功后投递）。
 * <p>消息只携带定位信息（作业ID + 学生ID），正文由消费端从数据库读取——
 * 保证消费时读到的一定是"已提交事务"的最新正文（消息在事务 afterCommit
 * 后发出），避免"消息携带旧正文、正文随后被重交覆盖"的乱序问题；
 * 同一 (homeworkId, studentId) 的重复消息重算结果幂等，可安全重投。</p>
 *
 * @author campus-homework
 */
@Data
public class PlagiarismMessage implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID（定位提交记录 uk_hw_stu 的前半键） */
    private Long homeworkId;

    /** 提交学生ID（定位提交记录 uk_hw_stu 的后半键，同时作为自比排除项） */
    private Long studentId;

    /**
     * 无参构造（Jackson 反序列化需要）。
     */
    public PlagiarismMessage() {
    }

    /**
     * 全参构造（生产端一行构造，避免逐字段 set 的样板代码）。
     *
     * @param homeworkId 作业ID
     * @param studentId  提交学生ID
     */
    public PlagiarismMessage(Long homeworkId, Long studentId) {
        this.homeworkId = homeworkId;
        this.studentId = studentId;
    }
}
