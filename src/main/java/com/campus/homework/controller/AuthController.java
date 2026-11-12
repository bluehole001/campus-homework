package com.campus.homework.controller;

import com.campus.homework.config.LoginUserDetails;
import com.campus.homework.dto.LoginDTO;
import com.campus.homework.entity.SysUser;
import com.campus.homework.utils.Result;
import com.campus.homework.utils.SecurityUtils;
import com.campus.homework.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * 认证接口（登录/当前用户/登出兜底）。
 * <p>登录流程：本 Controller 调用 AuthenticationManager 手工执行认证
 * （DaoAuthenticationProvider 内部走 UserDetailsServiceImpl + BCrypt 比对），
 * 成功后把认证结果写入 SecurityContextHolder 并绑定到 HttpSession——页面与
 * 接口共用同一会话（SessionCreationPolicy.IF_REQUIRED 按需创建）。
 * 登出的主链路由框架 LogoutFilter 处理（SecurityConfig 中声明 POST
 * /api/auth/logout 并回 Result JSON），本类的 logout 方法仅作为
 * LogoutFilter 之前被 MVC 命中的兜底入口，行为一致。</p>
 * <p>安全细节：认证失败统一回 401"用户名或密码错误"，不区分"用户不存在"
 * 与"密码错误"（hideUserNotFoundExceptions），防止账号枚举攻击；禁用账号
 * （DisabledException）同样按 401 处理，但提示文案单独给出。</p>
 *
 * @author campus-homework
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 认证接口统一前缀 /api/auth（SecurityConfig 已对 login/logout 匿名放行）
@RequestMapping("/api/auth")
// 构造器注入认证管理器（SecurityConfig 暴露的 Bean）
@RequiredArgsConstructor
public class AuthController {

    /** 认证管理器：内部已装配 DaoAuthenticationProvider + BCrypt */
    private final AuthenticationManager authenticationManager;

    /** 登出处理器：销毁会话并清空 SecurityContext（与 LogoutFilter 内部同一实现） */
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    /**
     * 账号密码登录：认证通过后写入会话，回当前用户脱敏信息。
     *
     * @param dto     登录表单（username/password，JSR-303 校验非空）
     * @param request 当前请求（用于按需创建/绑定会话）
     * @return 成功时 data 为当前用户脱敏 VO（不含密码）
     */
    @PostMapping("/login")
    public Result<UserVO> login(@RequestBody @Validated LoginDTO dto, HttpServletRequest request) {
        Authentication authentication;
        try {
            // 未认证 token（principal=用户名, credentials=明文密码），交由框架比对
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));
        } catch (DisabledException e) {
            // 账号被管理员禁用（LoginUserDetails.isEnabled() 返回 false）
            log.warn("登录被拒绝（账号禁用）: username={}", dto.getUsername());
            return Result.error(401, "账号已被禁用，请联系教务管理员");
        } catch (BadCredentialsException e) {
            // 用户名不存在或密码错误：统一文案，防账号枚举
            log.warn("登录被拒绝（凭证错误）: username={}", dto.getUsername());
            return Result.error(401, "用户名或密码错误");
        } catch (AuthenticationException e) {
            // 其余认证失败（锁定/凭证过期等本系统未启用的分支）兜底
            log.warn("登录被拒绝（{}）: username={}", e.getClass().getSimpleName(), dto.getUsername());
            return Result.error(401, "用户名或密码错误");
        }
        // 认证通过：写入安全上下文，再绑定到会话（IF_REQUIRED 策略下首次登录创建会话）
        SecurityContextHolder.getContext().setAuthentication(authentication);
        HttpSession session = request.getSession(true);
        // 会话内保存安全上下文，后续请求由 SecurityContextPersistenceFilter 自动恢复
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());
        log.info("用户 {} 登录成功（会话 {}）", dto.getUsername(), session.getId());
        // 出参直接复用认证主体（无需二次查库），逐字段映射为脱敏 VO
        LoginUserDetails principal = (LoginUserDetails) authentication.getPrincipal();
        return Result.success(toUserVO(principal.getUser()));
    }

    /**
     * 当前登录用户信息（前端导航栏渲染与角色路由判断的数据源）。
     * <p>需已登录（SecurityConfig anyRequest().authenticated() 覆盖本路径）；
     * 出参为脱敏 VO，绝不含密码密文。</p>
     *
     * @return 当前用户脱敏 VO
     */
    @GetMapping("/me")
    public Result<UserVO> me() {
        // 身份一律取自认证主体，前端无法伪造
        return Result.success(toUserVO(SecurityUtils.currentLoginUser()));
    }

    /**
     * 登出兜底入口：正常情况下 POST /api/auth/logout 由框架 LogoutFilter
     * 先行处理（销毁会话 + 清空上下文 + 回 Result JSON），本方法仅在
     * 过滤器链配置异常时被 MVC 命中，行为与 LogoutFilter 保持一致。
     *
     * @param request 当前请求
     * @return 恒为成功（登出是幂等操作，无会话也回 200）
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        // 复用框架登出语义：invalidate 会话并清空 SecurityContext
        logoutHandler.logout(request, null, SecurityContextHolder.getContext().getAuthentication());
        log.info("用户主动登出（兜底入口）");
        return Result.success();
    }

    /**
     * 用户实体 -> 脱敏 UserVO（逐字段显式映射，杜绝 password 字段外泄）。
     *
     * @param user 认证主体包装的用户实体
     * @return 脱敏用户 VO（不含密码）
     */
    private UserVO toUserVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUserNo(user.getUserNo());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setClazzId(user.getClazzId());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        // 刻意不复制 password：BCrypt 密文严禁回传前端
        return vo;
    }

    /**
     * 登录态上下文 -> 脱敏 UserVO（/me 复用，字段与实体版一致）。
     *
     * @param operator 当前登录用户上下文
     * @return 脱敏用户 VO（仅含上下文可提供的字段）
     */
    private UserVO toUserVO(com.campus.homework.dto.LoginUserDTO operator) {
        UserVO vo = new UserVO();
        vo.setId(operator.getUserId());
        vo.setUsername(operator.getUsername());
        vo.setRole(operator.getRole());
        vo.setClazzId(operator.getClazzId());
        return vo;
    }
}
