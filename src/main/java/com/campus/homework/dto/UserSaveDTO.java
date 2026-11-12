package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 用户账号新增/修改入参 DTO（仅 ROLE_ADMIN 使用，师生账号管理）。
 * <p>新增时 password 可留空（默认初始密码 123456，由 Service 层 BCrypt 加密）；
 * 修改时 password 留空表示不变更密码。role 只允许三个合法值，Service 层兜底校验。</p>
 *
 * @author campus-homework
 */
@Data
public class UserSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 用户ID：null=新增，非null=修改 */
    private Long id;

    /** 学号/工号（唯一，Service 层查重） */
    @NotBlank(message = "学号/工号不能为空")
    @Pattern(regexp = "[A-Za-z0-9]{2,32}", message = "学号/工号仅允许字母数字（2-32位）")
    private String userNo;

    /** 登录用户名（唯一，Service 层查重） */
    @NotBlank(message = "登录用户名不能为空")
    @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "用户名仅允许字母数字下划线（3-32位）")
    private String username;

    /** 明文密码（可空：新增默认123456，修改留空表示不变更）；库中只存密文 */
    @Size(min = 6, max = 32, message = "密码长度需在6-32之间")
    private String password;

    /** 真实姓名 */
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 32, message = "真实姓名最长32个字符")
    private String realName;

    /** 所属班级ID：ROLE_STUDENT 必填（Service 层校验），教师/管理员忽略 */
    private Long clazzId;

    /** 邮箱（必填，催交与成绩通知收件地址） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 角色：ROLE_STUDENT / ROLE_TEACHER / ROLE_ADMIN */
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "ROLE_(STUDENT|TEACHER|ADMIN)", message = "角色只能是 ROLE_STUDENT/ROLE_TEACHER/ROLE_ADMIN")
    private String role;

    /** 状态：1正常/0禁用，缺省视为1 */
    private Integer status;
}
