package com.campus.homework.config;

import com.campus.homework.utils.Constants;
import com.campus.homework.utils.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 三级鉴权配置（Batch 3 核心）：
 * <p>鉴权体系分两层，互为纵深防御：</p>
 * <ol>
 *   <li><b>URL 规则（本类）</b>：按角色划分 API 前缀——/api/student/** 仅
 *       ROLE_STUDENT、/api/teacher/** 仅 ROLE_TEACHER、/api/admin/** 仅
 *       ROLE_ADMIN、/api/common/** 与 /api/file/** 登录即可（具体归属仍由
 *       Service 层 IDOR 校验兜底）；未认证请求统一回 401 JSON，已认证但
 *       角色不符回 403 JSON，与前端 Result 契约一致。</li>
 *   <li><b>方法注解 + Service 层（纵深防御）</b>：Controller 上的 @PreAuthorize
 *       做垂直越权拦截；水平越权（IDOR）一律在 Service 层按登录态 userId/clazzId
 *       校验，URL 规则被误配也不会泄露数据。</li>
 * </ol>
 * <p>登录方式：POST /api/auth/login（JSON 表单，由 AuthController 调用
 * AuthenticationManager 认证后写入 HttpSession），页面与接口共用同一会话；
 * 登出走框架 LogoutFilter（POST /api/auth/logout）销毁会话并回 200 JSON。</p>
 *
 * @author campus-homework
 */
// Spring Security 配置入口
@EnableWebSecurity
// 开启方法级鉴权注解（Controller 使用 @PreAuthorize 做垂直越权防线）
@EnableGlobalMethodSecurity(prePostEnabled = true)
// 声明为配置类，提供 SecurityFilterChain 等 Bean
@Configuration
// 构造器注入 Jackson 用于手写 JSON 错误响应
@RequiredArgsConstructor
public class SecurityConfig {

    /** JSON 序列化器（Spring Boot 容器提供，含全局日期格式配置） */
    private final ObjectMapper objectMapper;

    /**
     * 安全过滤链：定义放行规则、角色映射、认证入口与异常处理。
     *
     * @param http HttpSecurity 构建器
     * @return 配置完成的过滤链
     * @throws Exception 构建失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 关闭：API 全部由 jQuery AJAX 以 JSON 调用，登录接口自带凭据；
                // Batch 5 的 Thymeleaf 表单同样通过 AJAX 提交，不依赖 CSRF token 流程
                .csrf().disable()
                // 会话策略：按需创建（登录成功后才建立会话），保持与页面渲染兼容
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .and()
                .authorizeRequests()
                // 登录/登出接口与登录页资源必须匿名可访问
                .antMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                // 静态资源与错误页匿名放行（Bootstrap/jQuery/ECharts 本地文件位于
                // classpath:/static/，由 Spring Boot 映射到根路径：/vendor/**、/css/**、/js/**；
                // 登录页匿名访问也必须能加载这些资源，故全部 permitAll）
                .antMatchers("/", "/favicon.ico", "/error",
                        "/css/**", "/js/**", "/img/**", "/vendor/**", "/webjars/**", "/static/**").permitAll()
                // 登录页匿名可访问（Batch 5：未登录用户直接访问登录页表单）
                .antMatchers("/login").permitAll()
                // 学生端接口：仅 ROLE_STUDENT（垂直越权第一道防线）
                .antMatchers("/api/student/**").hasRole(extractRoleSuffix(Constants.ROLE_STUDENT))
                // 教师端接口：仅 ROLE_TEACHER
                .antMatchers("/api/teacher/**").hasRole(extractRoleSuffix(Constants.ROLE_TEACHER))
                // 管理端接口：仅 ROLE_ADMIN
                .antMatchers("/api/admin/**").hasRole(extractRoleSuffix(Constants.ROLE_ADMIN))
                // 页面路由与 API 同口径按角色限定（Batch 5：页面与接口双重防线一致，
                // 学生直接输 URL 访问 /teacher/** 同样被拒，不会渲染错角色的页面）
                .antMatchers("/student/**").hasRole(extractRoleSuffix(Constants.ROLE_STUDENT))
                .antMatchers("/teacher/**").hasRole(extractRoleSuffix(Constants.ROLE_TEACHER))
                .antMatchers("/admin/**").hasRole(extractRoleSuffix(Constants.ROLE_ADMIN))
                // 其余接口（/api/common/**、/api/file/** 等）：任意已登录角色可访问，
                // 数据归属由 Service 层 IDOR 校验兜底（纵深防御）
                .anyRequest().authenticated()
                .and()
                // 登出：仅接受 POST，防止 GET 链接预取误登出；登出后回 Result(200) JSON
                .logout()
                .logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout", "POST"))
                .logoutSuccessHandler((request, response, authentication) ->
                        writeResult(response, HttpStatus.OK, Result.success()))
                .and()
                // 未认证（无会话/会话过期）：回 401 JSON，前端统一跳转登录页
                .exceptionHandling()
                .authenticationEntryPoint(this::writeUnauthorized)
                // 已认证但角色不符（URL 规则拒绝）：回 403 JSON；
                // 方法级 @PreAuthorize 的拒绝由 GlobalExceptionHandler 处理，口径一致
                .accessDeniedHandler(this::writeForbidden);
        return http.build();
    }

    /**
     * 暴露 AuthenticationManager：供 AuthController 手工执行登录认证。
     *
     * @param configuration Spring Security 自动装配的配置句柄
     * @return 认证管理器（内部已装配 DaoAuthenticationProvider + BCrypt）
     * @throws Exception 获取失败
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        // 复用 Boot 自动装配：UserDetailsServiceImpl + PasswordConfig 的 BCrypt
        // 自动组成 DaoAuthenticationProvider，这里仅取出成品，避免重复组装
        return configuration.getAuthenticationManager();
    }

    /**
     * 从完整角色常量中取出 hasRole() 所需的后缀（ROLE_STUDENT -> STUDENT）。
     *
     * @param fullRole 完整角色字符串（ROLE_*）
     * @return 去掉 ROLE_ 前缀的角色名
     */
    private String extractRoleSuffix(String fullRole) {
        // hasRole 会自动补 ROLE_ 前缀，因此这里必须传入去前缀后的裸角色名
        return fullRole.substring("ROLE_".length());
    }

    /**
     * 未认证处理器：API 请求回 Result(401) JSON（前端 AJAX 据此跳转登录页）；
     * 页面请求（浏览器直接访问 /student/** 等且会话失效）重定向到 /login，
     * 符合浏览器导航的常规体验，两种路径的判定依据是请求路径前缀与 AJAX 头。
     *
     * @param request  请求
     * @param response 响应
     * @param e        认证异常
     * @throws IOException 写响应失败
     */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   AuthenticationException e) throws IOException {
        if (isApiRequest(request)) {
            writeResult(response, HttpStatus.UNAUTHORIZED,
                    Result.error(401, "登录状态已失效，请重新登录"));
            return;
        }
        // 页面导航未认证：重定向登录页（登录页本身 permitAll，不会形成循环）
        response.sendRedirect(request.getContextPath() + "/login");
    }

    /**
     * 越权处理器：API 请求回 Result(403) JSON；页面请求角色不符时重定向到
     * 本人角色首页（登录态有效但访问了他人角色的页面，回首页是合理兜底）。
     *
     * @param request  请求
     * @param response 响应
     * @param e        访问拒绝异常
     * @throws IOException 写响应失败
     */
    private void writeForbidden(HttpServletRequest request, HttpServletResponse response,
                                org.springframework.security.access.AccessDeniedException e) throws IOException {
        if (isApiRequest(request)) {
            writeResult(response, HttpStatus.FORBIDDEN,
                    Result.error(403, "当前角色无权访问该功能"));
            return;
        }
        // 页面越权：登录态有效但角色不符，重定向首页由 PageController 按角色分流
        response.sendRedirect(request.getContextPath() + "/");
    }

    /**
     * 判定是否为 API 请求：路径以 /api/ 开头，或携带 X-Requested-With: XMLHttpRequest
     * 头（jQuery AJAX 默认附带）。其余视为浏览器页面导航。
     *
     * @param request 请求
     * @return true 表示 API/AJAX 请求（应回 JSON），false 表示页面导航（应重定向）
     */
    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.contains("/api/")) {
            return true;
        }
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }

    /**
     * 把 Result 以 JSON 写入响应（HTTP 状态与业务 code 同步，便于前端 fetch 拦截）。
     *
     * @param response   响应
     * @param status     HTTP 状态
     * @param result     统一响应体
     * @throws IOException 序列化或写流失败
     */
    private void writeResult(HttpServletResponse response, HttpStatus status, Result<Void> result)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}