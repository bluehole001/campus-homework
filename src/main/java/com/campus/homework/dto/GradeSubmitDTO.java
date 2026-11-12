package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 教师在线批改入参 DTO（仅 ROLE_TEACHER 使用）。
 * <p>按提交记录ID定位（Service 层校验该提交所属作业的发布人是当前教师，防越权批改）；
 * 得分不得超过该作业满分（Service 层二次校验，DTO 只能限定绝对上限）。</p>
 *
 * @author campus-homework
 */
@Data
public class GradeSubmitDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 提交记录ID（homework_submission.id） */
    @NotNull(message = "提交记录ID不能为空")
    private Long submissionId;

    /** 批改得分（0~1000 绝对范围；不超过作业满分由 Service 层校验） */
    @NotNull(message = "得分不能为空")
    @DecimalMin(value = "0.0", message = "得分不能为负")
    @DecimalMax(value = "1000.0", message = "得分不能超过1000")
    private BigDecimal score;

    /** 教师评语（可留空，建议填写） */
    @Size(max = 2000, message = "评语最长2000字符")
    private String teacherComment;
}
