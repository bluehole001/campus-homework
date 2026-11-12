package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 登录表单入参 DTO（POST /api/auth/login）。
 * <p>仅做非空与长度校验；真正的凭据比对由 AuthenticationManager 完成
 * （DaoAuthenticationProvider + BCrypt），失败时由 AuthController 统一
 * 转换为 401/403 的 Result，避免泄露"用户名不存在"这类枚举信息。</p>
 *
 * @author campus-homework
 */
@Data
public class LoginDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 登录用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名最长32个字符")
    private String username;

    /** 登录密码（明文，仅用于比对，不落库不回显） */
    @NotBlank(message = "密码不能为空")
    @Size(max = 32, message = "密码最长32个字符")
    private String password;
}
