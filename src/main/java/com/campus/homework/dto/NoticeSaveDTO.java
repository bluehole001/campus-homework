package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 班级公告发布/编辑入参 DTO（仅 ROLE_TEACHER 使用）。
 * <p>isTop 由前端开关传入（0/1），Service 层兜底归一化；编辑仅限本人发布的公告。</p>
 *
 * @author campus-homework
 */
@Data
public class NoticeSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 公告ID：null=发布新公告，非null=编辑（仅限本人，Service 层 IDOR 校验） */
    private Long id;

    /** 目标班级ID */
    @NotNull(message = "目标班级不能为空")
    private Long clazzId;

    /** 公告标题 */
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 128, message = "公告标题最长128个字符")
    private String title;

    /** 公告正文 */
    @NotBlank(message = "公告内容不能为空")
    private String content;

    /** 是否置顶：0否/1是（缺省视为0） */
    private Integer isTop;
}
