package com.campus.homework.controller;

import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.utils.Constants;
import com.campus.homework.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由（Batch 5 视图层入口，Thymeleaf 视图解析）。
 * <p>设计原则：本类只做"URL -> 模板"的映射与按角色的首页分流，
 * <b>不注入任何 Mapper/Service</b>——页面数据一律由前端 jQuery 调用
 * /api/** 接口获取（接口层已含完整的 IDOR 与角色校验），页面路由与
 * API 路由职责彻底分离，避免两套鉴权逻辑漂移。</p>
 * <p>鉴权纵深：SecurityConfig 已按前缀限定 /student/**、/teacher/**、
 * /admin/** 的页面角色；本类方法上的 @PreAuthorize 为第二道防线，
 * 与 API 层的三重防御策略保持一致。</p>
 *
 * @author campus-homework
 */
@Slf4j
// 视图控制器（返回模板名，非 REST JSON）——与 @RestController 的 API 层分离
@Controller
public class PageController {

    /**
     * 登录页（匿名可访问；已登录用户访问时直接分流到对应角色首页，
     * 避免"已登录还看到登录页"的困惑）。
     *
     * @return 登录页视图名，或按角色重定向
     */
    @GetMapping("/login")
    public String login() {
        LoginUserDTO user = tryCurrentUser();
        if (user != null) {
            // 已登录：直接跳到本人角色首页（会话有效时无需再登录）
            return "redirect:" + homeForRole(user.getRole());
        }
        return "login";
    }

    /**
     * 站点首页：按登录角色分流到学生/教师/管理端首页；未登录跳登录页。
     *
     * @return 重定向目标
     */
    @GetMapping("/")
    public String home() {
        LoginUserDTO user = tryCurrentUser();
        if (user == null) {
            return "redirect:/login";
        }
        return "redirect:" + homeForRole(user.getRole());
    }

    // ==================== 学生端页面（仅 ROLE_STUDENT） ====================

    /**
     * 学生作业列表页（本班作业 + 班级公告栏）。
     *
     * @return 模板名 student/homework-list
     */
    @GetMapping("/student/homework")
    @PreAuthorize("hasRole('STUDENT')")
    public String studentHomework() {
        return "student/homework-list";
    }

    /**
     * 学生作业详情/提交页（查看要求、提交或重交、成绩发布后查看个人成绩）。
     *
     * @param id 作业ID（前端按 id 调 /api/student/homework/{id}/mine 取个人详情）
     * @return 模板名 student/homework-detail
     */
    @GetMapping("/student/homework/{id}")
    @PreAuthorize("hasRole('STUDENT')")
    public String studentHomeworkDetail(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return "student/homework-detail";
    }

    /**
     * 学生公告列表页（只读，数据来自 /api/common/notice/clazz/{本人班级}）。
     *
     * @return 模板名 student/notice-list
     */
    @GetMapping("/student/notice")
    @PreAuthorize("hasRole('STUDENT')")
    public String studentNotice() {
        return "student/notice-list";
    }

    // ==================== 教师端页面（仅 ROLE_TEACHER） ====================

    /**
     * 教师作业管理列表页（本人发布的作业，含"已交 x/y"与发布入口）。
     *
     * @return 模板名 teacher/homework-list
     */
    @GetMapping("/teacher/homework")
    @PreAuthorize("hasRole('TEACHER')")
    public String teacherHomework() {
        return "teacher/homework-list";
    }

    /**
     * 教师作业发布/编辑页（新增与编辑共用同一表单模板，编辑时前端按 id 回填）。
     *
     * @return 模板名 teacher/homework-edit
     */
    @GetMapping("/teacher/homework/new")
    @PreAuthorize("hasRole('TEACHER')")
    public String teacherHomeworkNew() {
        return "teacher/homework-edit";
    }

    /**
     * 教师提交名单页（批改工作台：未交标记、查重高亮预警、逐份打分）。
     *
     * @param id 作业ID
     * @return 模板名 teacher/roster
     */
    @GetMapping("/teacher/homework/{id}/roster")
    @PreAuthorize("hasRole('TEACHER')")
    public String teacherRoster(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return "teacher/roster";
    }

    /**
     * 教师成绩分析页（ECharts 分布直方图 + 排名表 + 查重预警统计）。
     *
     * @param id 作业ID
     * @return 模板名 teacher/analysis
     */
    @GetMapping("/teacher/homework/{id}/analysis")
    @PreAuthorize("hasRole('TEACHER')")
    public String teacherAnalysis(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return "teacher/analysis";
    }

    /**
     * 教师教学看板页（月度提交趋势折线 + 知识点得分雷达图）。
     *
     * @return 模板名 teacher/dashboard
     */
    @GetMapping("/teacher/dashboard")
    @PreAuthorize("hasRole('TEACHER')")
    public String teacherDashboard() {
        return "teacher/dashboard";
    }

    /**
     * 教师公告管理页（发布/编辑/删除本人公告）。
     *
     * @return 模板名 teacher/notice-manage
     */
    @GetMapping("/teacher/notice")
    @PreAuthorize("hasRole('TEACHER')")
    public String teacherNotice() {
        return "teacher/notice-manage";
    }

    // ==================== 管理端页面（仅 ROLE_ADMIN） ====================

    /**
     * 班级管理页（班级 CRUD）。
     *
     * @return 模板名 admin/clazz-manage
     */
    @GetMapping("/admin/clazz")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminClazz() {
        return "admin/clazz-manage";
    }

    /**
     * 账号管理页（师生账号 CRUD、重置密码、启停用、CSV 批量导入学生）。
     *
     * @return 模板名 admin/user-manage
     */
    @GetMapping("/admin/user")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminUser() {
        return "admin/user-manage";
    }

    /**
     * 管理端看板页（逐班月度提交趋势查看，数据来自 /api/common/** 只读接口）。
     *
     * @return 模板名 admin/dashboard
     */
    @GetMapping("/admin/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminDashboard() {
        return "admin/dashboard";
    }

    /**
     * 尝试获取当前登录用户：未认证时返回 null（不抛异常）。
     * <p>首页/登录页的"已登录分流"属于体验优化而非数据访问，未登录是
     * 正常路径，不应触发 SecurityUtils 的 401 异常语义。</p>
     *
     * @return 登录用户或 null
     */
    private LoginUserDTO tryCurrentUser() {
        try {
            return SecurityUtils.currentLoginUser();
        } catch (Exception e) {
            // 未认证（无会话）：按匿名访客处理，跳转登录页
            return null;
        }
    }

    /**
     * 角色 -> 首页路径映射（与 Constants 角色常量一一对应，无魔法字符串）。
     *
     * @param role 完整角色（ROLE_*）
     * @return 该角色首页 URL
     */
    private String homeForRole(String role) {
        if (Constants.ROLE_STUDENT.equals(role)) {
            return "/student/homework";
        }
        if (Constants.ROLE_TEACHER.equals(role)) {
            return "/teacher/homework";
        }
        if (Constants.ROLE_ADMIN.equals(role)) {
            return "/admin/clazz";
        }
        // 理论上不会到达：未知角色回登录页重新认证
        return "/login";
    }
}
