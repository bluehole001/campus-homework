package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 学生在线提交作业入参 DTO（仅 ROLE_STUDENT 使用）。
 * <p>正文与附件至少其一非空（Service 层校验）；附件先经 multipart 上传接口
 * 落盘后回填 fileUrl/fileName。截止前重复提交视为覆盖重交（uk_hw_stu 定位后 update）。</p>
 *
 * @author campus-homework
 */
@Data
public class SubmissionSubmitDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID（Service 层校验作业存在且属于该学生所在班级） */
    @NotNull(message = "作业ID不能为空")
    private Long homeworkId;

    /** 作业正文（查重比对文本；与附件至少其一非空） */
    @Size(max = 20000, message = "正文最长20000字符")
    private String contentText;

    /** 作业附件相对路径（可为空；由上传接口返回后回填） */
    @Size(max = 255, message = "附件路径过长")
    private String fileUrl;

    /** 作业附件原始文件名（可为空） */
    @Size(max = 128, message = "附件名称最长128个字符")
    private String fileName;
}
