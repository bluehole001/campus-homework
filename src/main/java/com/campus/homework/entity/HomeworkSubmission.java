package com.campus.homework.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 作业提交实体（对应表 homework_submission），一名学生对一份作业仅有一条记录
 * （联合唯一索引 uk_hw_stu），截止前允许覆盖重交。
 * <p>content_text 是 N-Gram + 余弦相似度查重的比对文本；similarity_rate /
 * is_high_repeat 由 Batch 4 的 MQ 异步查重流程回写，本批次仅定义字段与基础读写。</p>
 *
 * @author campus-homework
 */
@Data
// 表名锁定为 homework_submission
@TableName("homework_submission")
public class HomeworkSubmission implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 提交主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 作业ID，与 student_id 构成联合唯一 uk_hw_stu */
    @TableField("homework_id")
    private Long homeworkId;

    /** 提交学生ID，IDOR 校验主体：学生只能读写自己的记录 */
    @TableField("student_id")
    private Long studentId;

    /** 作业正文（LONGTEXT），查重算法的输入文本 */
    @TableField("content_text")
    private String contentText;

    /** 作业附件URL（可为空，允许仅提交正文） */
    @TableField("file_url")
    private String fileUrl;

    /** 作业附件原始文件名，下载时还原 */
    @TableField("file_name")
    private String fileName;

    /** 提交状态：0 未交 / 1 按时交 / 2 逾期未交 / 3 已批改 */
    @TableField("submit_status")
    private Integer submitStatus;

    /** 与本班其他同学比较的最高余弦相似度（0~1，保留4位小数） */
    @TableField("similarity_rate")
    private BigDecimal similarityRate;

    /** 最相似的同学ID，教师复核高重复时定位对象 */
    @TableField("similar_student_id")
    private Long similarStudentId;

    /** 高重复预警：0 正常 / 1 预警（相似度 ≥ 配置阈值时置 1） */
    @TableField("is_high_repeat")
    private Integer isHighRepeat;

    /** 批改得分（保留1位小数），不得超过作业满分 */
    @TableField("score")
    private BigDecimal score;

    /** 教师评语 */
    @TableField("teacher_comment")
    private String teacherComment;

    /** 提交时间，提交成功时由 Service 层赋值 */
    @TableField("submit_time")
    private LocalDateTime submitTime;

    /** 批改时间，批改动作发生时由 Service 层赋值 */
    @TableField("grade_time")
    private LocalDateTime gradeTime;
}
