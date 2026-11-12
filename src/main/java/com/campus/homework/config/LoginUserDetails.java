package com.campus.homework.config;

import com.campus.homework.entity.SysUser;
import com.campus.homework.utils.Constants;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security 认证主体：包装 SysUser 实体，承载登录校验与鉴权所需信息。
 * <p>设计要点：</p>
 * <ul>
 *   <li>role 字段本身就是 ROLE_STUDENT/ROLE_TEACHER/ROLE_ADMIN 完整形式，
 *       与 GrantedAuthority 约定一致，无需二次加 ROLE_ 前缀；</li>
 *   <li>status=0 时 isEnabled() 返回 false，DaoAuthenticationProvider 自动
 *       拦截禁用账号登录（抛 DisabledException）；</li>
 *   <li>保留 clazzId 与 userId，供 Controller 从 SecurityContextHolder
 *       组装 LoginUserDTO（IDOR 越权校验的操作者身份来源）。</li>
 * </ul>
 *
 * @author campus-homework
 */
@Getter
public class LoginUserDetails implements UserDetails {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 被包装的用户实体（含 BCrypt 密文密码，仅参与认证不外传） */
    private final SysUser user;

    /**
     * 构造认证主体。
     *
     * @param user 已通过存在性校验的用户实体
     */
    public LoginUserDetails(SysUser user) {
        this.user = user;
    }

    /**
     * 权限集合：sys_user.role 已是 ROLE_* 完整形式，直接映射为单一权限。
     *
     * @return 仅含一个 ROLE_* 权限的集合
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(user.getRole()));
    }

    /**
     * BCrypt 密文密码（由 PasswordEncoder 在认证时比对）。
     *
     * @return 密文密码
     */
    @Override
    public String getPassword() {
        return user.getPassword();
    }

    /**
     * 登录用户名。
     *
     * @return username
     */
    @Override
    public String getUsername() {
        return user.getUsername();
    }

    /**
     * 账号未过期（本系统无账号有效期设计，恒为 true）。
     *
     * @return true
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * 账号未锁定（本系统无锁定策略，恒为 true）。
     *
     * @return true
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * 凭证未过期（BCrypt 密文无过期概念，恒为 true）。
     *
     * @return true
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 账号可用性：status=1 正常可用，status=0 禁用（登录被框架拦截）。
     *
     * @return 是否启用
     */
    @Override
    public boolean isEnabled() {
        return user.getStatus() != null && user.getStatus() == Constants.USER_STATUS_NORMAL;
    }
}