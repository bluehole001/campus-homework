package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 班级新增/修改入参 DTO（仅 ROLE_ADMIN 使用）。
 * <p>id 为 null 表示新增，非 null 表示修改；班级编号格式校验在 DTO 层完成，
 * 唯一性冲突校验在 ServiceImpl 中完成（分层铁律）。</p>
 *
 * @author campus-homework
 */
@Data
public class ClazzSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 班级ID：null=新增，非null=修改 */
    private Long id;

    /** 班级编号，如 CS2023-01（唯一，Service 层查重） */
    @NotBlank(message = "班级编号不能为空")
    @Pattern(regexp = "[A-Za-z0-9-]{2,32}", message = "班级编号仅允许字母数字与连字符（2-32位）")
    private String clazzCode;

    /** 班级名称 */
    @NotBlank(message = "班级名称不能为空")
    @Size(max = 64, message = "班级名称最长64个字符")
    private String clazzName;

    /** 所属院系 */
    @NotBlank(message = "所属院系不能为空")
    @Size(max = 64, message = "院系名称最长64个字符")
    private String department;
}
