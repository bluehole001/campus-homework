package com.campus.homework.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 作业任务实体（对应表 homework_task），由任课教师（ROLE_TEACHER）发布。
 * <p>knowledge_tag 用于知识点得分率雷达图；is_score_published 控制成绩可见性，
 * 未发布时学生不得查看分数（IDOR 越权红线在 Service 层校验）。</p>
 *
 * @author campus-homework
 */
@Data
// 表名锁定为 homework_task
@TableName("homework_task")
public class HomeworkTask implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 目标班级ID，索引 idx_task_clazz，学生仅可查看本班作业 */
    @TableField("clazz_id")
    private Long clazzId;

    /** 发布教师ID，索引 idx_task_teacher，编辑/删除时校验归属防越权 */
    @TableField("teacher_id")
    private Long teacherId;

    /** 作业标题，索引 idx_task_title，支持关键字检索 */
    @TableField("title")
    private String title;

    /** 知识点标签（如 数据库设计/SQL编程），驱动得分率雷达图统计维度 */
    @TableField("knowledge_tag")
    private String knowledgeTag;

    /** 作业要求正文（TEXT），学生端完整展示 */
    @TableField("requirement")
    private String requirement;

    /** 教师参考附件URL（可为空），学生可下载 */
    @TableField("attachment_url")
    private String attachmentUrl;

    /** 教师参考附件原始文件名，下载时还原 Content-Disposition */
    @TableField("attachment_name")
    private String attachmentName;

    /** 作业满分，默认 100，批改得分不得超过该值 */
    @TableField("total_score")
    private Integer totalScore;

    /** 截止时间，索引 idx_task_deadline，驱动逾期标记与催交邮件任务 */
    @TableField("deadline")
    private LocalDateTime deadline;

    /** 成绩发布状态：0 未发布 / 1 已发布（发布动作触发 MQ 统计与 Redis 预热） */
    @TableField("is_score_published")
    private Integer isScorePublished;

    /** 已提交人数，提交成功时在 ServiceImpl 中同步自增 */
    @TableField("submit_count")
    private Integer submitCount;

    /** 发布时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
