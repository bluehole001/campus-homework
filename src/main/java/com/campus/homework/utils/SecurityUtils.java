package com.campus.homework.utils;

import com.campus.homework.config.LoginUserDetails;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录态读取工具：Controller 层组装 Service 层 IDOR 校验所需操作者身份的唯一入口。
 * <p>设计要点（Batch 3 核心约定）：</p>
 * <ul>
 *   <li>Service 层全部方法末参为 LoginUserDTO，Batch 2 已把越权逻辑完整落地；
 *       本批次仅把"取值来源"从显式参数切换为 SecurityContextHolder——
 *       所有 Service 方法签名保持不变；</li>
 *   <li>身份一律取自认证主体（LoginUserDetails 包装的 SysUser），前端表单
 *       无法伪造 userId/role/clazzId，这是 IDOR 防线的根基；</li>
 *   <li>未认证兜底：URL 规则已保证受保护接口必已登录，此处防御性拦截，
 *       以 401 业务码回传（与前端 Result 契约一致）。</li>
 * </ul>
 *
 * @author campus-homework
 */
public final class SecurityUtils {

    /** 工具类，禁止实例化 */
    private SecurityUtils() {
    }

    /**
     * 从当前认证上下文组装 LoginUserDTO（操作者身份载体）。
     *
     * @return 含 userId/username/role/clazzId 的操作者上下文
     * @throws BusinessException 401：当前请求未认证或认证主体类型异常
     */
    public static LoginUserDTO currentLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 防御：未认证（理论上被 URL 规则拦截）或匿名认证主体
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUserDetails)) {
            throw new BusinessException(401, "登录状态已失效，请重新登录");
        }
        LoginUserDetails principal = (LoginUserDetails) authentication.getPrincipal();
        LoginUserDTO operator = new LoginUserDTO();
        // 身份三要素全部来自认证主体，前端不可伪造
        operator.setUserId(principal.getUser().getId());
        operator.setUsername(principal.getUser().getUsername());
        operator.setRole(principal.getUser().getRole());
        operator.setClazzId(principal.getUser().getClazzId());
        return operator;
    }
}
