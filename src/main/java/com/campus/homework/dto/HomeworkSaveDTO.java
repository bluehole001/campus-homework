package com.campus.homework.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 作业发布/编辑入参 DTO（仅 ROLE_TEACHER 使用）。
 * <p>教师参考附件通过独立的 multipart 上传接口落盘后，把返回的相对路径回填到
 * attachmentUrl/attachmentName 字段再提交本 DTO；附件格式与大小校验由
 * FileUploadUtils 完成，本 DTO 只做长度与取值范围校验。</p>
 *
 * @author campus-homework
 */
@Data
public class HomeworkSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID：null=发布新作业，非null=编辑（编辑仅限本人，Service 层 IDOR 校验） */
    private Long id;

    /** 目标班级ID（Service 层校验班级存在） */
    @NotNull(message = "目标班级不能为空")
    private Long clazzId;

    /** 作业标题 */
    @NotBlank(message = "作业标题不能为空")
    @Size(max = 128, message = "作业标题最长128个字符")
    private String title;

    /** 知识点标签（雷达图统计维度） */
    @NotBlank(message = "知识点标签不能为空")
    @Size(max = 64, message = "知识点标签最长64个字符")
    private String knowledgeTag;

    /** 作业要求正文 */
    @NotBlank(message = "作业要求不能为空")
    private String requirement;

    /** 教师参考附件相对路径（可为空，由上传接口返回后回填） */
    @Size(max = 255, message = "附件路径过长")
    private String attachmentUrl;

    /** 教师参考附件原始文件名（可为空） */
    @Size(max = 128, message = "附件名称最长128个字符")
    private String attachmentName;

    /** 作业满分（1-1000，默认100） */
    @Min(value = 1, message = "满分至少为1")
    @Max(value = 1000, message = "满分不能超过1000")
    private Integer totalScore;

    /** 截止时间（新增时必须晚于当前时间，Service 层校验） */
    @NotNull(message = "截止时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deadline;
}
