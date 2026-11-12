package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 教师端提交名单行 VO（查看提交名单与未交标记、批改工作台共用）。
 * <p>onlyUnsubmitted=true 的行是"未交占位"（无提交记录时由 Service 合成），
 * 前端以灰色标记；高重复预警行携带 similarityRate 与 similarStudentName，
 * 教师点击即可复核两份原文。</p>
 *
 * @author campus-homework
 */
@Data
public class SubmissionRosterVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 提交记录ID（未交占位时为 null，批改需先有记录） */
    private Long submissionId;

    /** 学生ID */
    private Long studentId;

    /** 学号 */
    private String userNo;

    /** 真实姓名 */
    private String realName;

    /** 提交状态：0未交/1按时交/2逾期未交/3已批改 */
    private Integer submitStatus;

    /** 提交时间（未交为 null） */
    private LocalDateTime submitTime;

    /** 作业正文（教师查看/查重复核用） */
    private String contentText;

    /** 附件URL */
    private String fileUrl;

    /** 附件原始文件名 */
    private String fileName;

    /** 最高相似度（未查重为 null） */
    private BigDecimal similarityRate;

    /** 最相似同学ID（Batch 5 补充：前端查重复核时据此定位对方原文行） */
    private Long similarStudentId;

    /** 最相似同学姓名（高重复复核入口） */
    private String similarStudentName;

    /** 高重复预警：0正常/1预警 */
    private Integer isHighRepeat;

    /** 得分（未批改或成绩未发布时教师本人仍可见，教师是数据主人） */
    private BigDecimal score;

    /** 教师评语 */
    private String teacherComment;
}
