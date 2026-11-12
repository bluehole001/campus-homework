package com.campus.homework.config;

import com.campus.homework.entity.SysUser;
import com.campus.homework.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security 认证数据源：按用户名加载 sys_user 记录并包装为 LoginUserDetails。
 * <p>实现说明：</p>
 * <ul>
 *   <li>复用 SysUserService.getByUsername（Batch 2 已声明的认证数据源契约），
 *       不直接注入 Mapper，遵守分层铁律；</li>
 *   <li>密码比对由框架调用 PasswordConfig 提供的 BCryptPasswordEncoder 完成，
 *       本类只负责"取人"，不做密码校验；</li>
 *   <li>账号不存在时抛 UsernameNotFoundException（框架统一转为 401 认证失败，
 *       提示文案与"密码错误"一致，避免枚举攻击泄露账号是否存在）。</li>
 * </ul>
 *
 * @author campus-homework
 */
@Slf4j
// 作为 Spring 容器中的认证服务单例
@Service
// 构造器注入用户服务（复用既有 Service 契约，不跨层访问 Mapper）
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    /** 用户服务：getByUsername 即本类唯一的数据访问入口 */
    private final SysUserService sysUserService;

    /**
     * 按登录用户名加载认证主体。
     *
     * @param username 登录用户名（uk_username 唯一索引命中）
     * @return 认证主体（含权限、密文密码、启用状态）
     * @throws UsernameNotFoundException 账号不存在（对外统一表现为"用户名或密码错误"）
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserService.getByUsername(username);
        if (user == null) {
            // 日志保留真实原因便于排障；对外提示由 AuthenticationEntryPoint 统一脱敏
            log.warn("登录失败：用户名不存在 {}", username);
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        // status=0 的禁用账号照常加载，由 isEnabled()=false 触发框架 DisabledException
        return new LoginUserDetails(user);
    }
}