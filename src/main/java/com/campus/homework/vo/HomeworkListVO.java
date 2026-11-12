package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 作业列表项 VO（学生端作业列表、教师端作业管理列表共用）。
 * <p>scorePublished 与 deadlinePassed 为派生布尔字段，前端据此控制
 * "查看成绩"入口与倒计时样式；本 VO 不含任何学生个人分数。</p>
 *
 * @author campus-homework
 */
@Data
public class HomeworkListVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID */
    private Long id;

    /** 作业标题 */
    private String title;

    /** 知识点标签 */
    private String knowledgeTag;

    /** 作业要求（列表页截断展示由前端处理） */
    private String requirement;

    /** 教师参考附件URL（null 表示无附件） */
    private String attachmentUrl;

    /** 教师参考附件原始文件名 */
    private String attachmentName;

    /** 作业满分 */
    private Integer totalScore;

    /** 截止时间 */
    private LocalDateTime deadline;

    /** 成绩是否已发布（0/1） */
    private Integer isScorePublished;

    /** 已提交人数 */
    private Integer submitCount;

    /** 班级在册学生总数（列表页展示"已交 x/y"的分母） */
    private Integer studentCount;

    /** 发布时间 */
    private LocalDateTime createTime;
}
