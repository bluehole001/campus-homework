package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生端"我的作业详情" VO：作业要求 + 本人提交与成绩信息聚合。
 * <p>IDOR 红线：score/teacherComment/similarityRate 等个人字段仅在
 * 成绩已发布（scorePublished=true）时由 Service 层填充，未发布时保持 null，
 * 学生即使拿到本 VO 也看不到未发布的成绩。</p>
 *
 * @author campus-homework
 */
@Data
public class MyHomeworkDetailVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID */
    private Long homeworkId;

    /** 作业标题 */
    private String title;

    /** 知识点标签 */
    private String knowledgeTag;

    /** 作业要求 */
    private String requirement;

    /** 教师参考附件URL */
    private String attachmentUrl;

    /** 教师参考附件原始文件名 */
    private String attachmentName;

    /** 作业满分 */
    private Integer totalScore;

    /** 截止时间 */
    private LocalDateTime deadline;

    /** 成绩是否已发布（0/1） */
    private Integer isScorePublished;

    /** 本人提交状态：0未交/1按时交/2逾期未交/3已批改 */
    private Integer submitStatus;

    /** 本人提交时间（未提交为 null） */
    private LocalDateTime submitTime;

    /** 本人作业正文（回显便于重交编辑） */
    private String contentText;

    /** 本人附件URL */
    private String fileUrl;

    /** 本人附件原始文件名 */
    private String fileName;

    /** 本人得分（成绩未发布时为 null） */
    private BigDecimal score;

    /** 教师评语（成绩未发布时为 null） */
    private String teacherComment;

    /** 本人最高相似度（成绩未发布时为 null，避免提前暴露查重结论） */
    private BigDecimal similarityRate;

    /** 高重复预警标记（成绩未发布时为 null） */
    private Integer isHighRepeat;
}
