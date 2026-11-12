package com.campus.homework.controller;

import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.service.ClazzInfoService;
import com.campus.homework.service.ClazzNoticeService;
import com.campus.homework.service.MonthlySubmitStatService;
import com.campus.homework.utils.Result;
import com.campus.homework.utils.SecurityUtils;
import com.campus.homework.vo.ClazzVO;
import com.campus.homework.vo.MonthlyStatVO;
import com.campus.homework.vo.NoticeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.List;

/**
 * 通用接口（/api/common/**，任意已登录角色可访问）。
 * <p>这些读接口对三种角色都有合理用途（学生查看自己的班级/公告/趋势、
 * 教师查看授课班级数据、管理员巡检），因此不按角色划分 URL，而是
 * "登录即可 + Service 层 IDOR 兜底"：具体某条数据能不能被当前用户
 * 看到，一律由 Service 层按登录态 userId/clazzId 判定（水平越权防线），
 * 与 URL 规则无关——即使 URL 规则被误配也不会泄露数据。</p>
 *
 * @author campus-homework
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 通用前缀：SecurityConfig 中归入 anyRequest().authenticated()
@RequestMapping("/api/common")
// 构造器注入所需 Service（仅依赖接口）
@RequiredArgsConstructor
public class CommonController {

    /** 班级信息服务：班级下拉数据源 */
    private final ClazzInfoService clazzInfoService;

    /** 班级公告服务：公告列表（归属校验在 Service 层） */
    private final ClazzNoticeService clazzNoticeService;

    /** 月度提交统计服务：趋势数据（归属校验在 Service 层） */
    private final MonthlySubmitStatService monthlySubmitStatService;

    /**
     * 班级下拉数据：管理员返回全部；教师返回自己授课班级；学生返回自己班级。
     *
     * @return 班级 VO 列表（简要字段，供表单下拉）
     */
    @GetMapping("/clazz/options")
    public Result<List<ClazzVO>> clazzOptions() {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(clazzInfoService.listSelectable(operator));
    }

    /**
     * 班级公告列表（学生限本班、教师限授课班级、管理员可巡检；置顶在前）。
     *
     * @param clazzId 班级ID
     * @return 公告 VO 列表
     */
    @GetMapping("/notice/clazz/{clazzId}")
    public Result<List<NoticeVO>> noticeList(@PathVariable Long clazzId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(clazzNoticeService.listByClazz(clazzId, operator));
    }

    /**
     * 班级月度提交趋势（学生限本班、教师限授课班级、管理员不限；月份升序）。
     *
     * @param clazzId 班级ID
     * @param months  最近月数（1-24，参数级校验后仍由 Service 层兜底）
     * @return 统计 VO 列表
     */
    @GetMapping("/clazz/{clazzId}/trend")
    public Result<List<MonthlyStatVO>> trend(
            @PathVariable Long clazzId,
            @RequestParam(defaultValue = "6") @Min(value = 1, message = "查询月数最小为1")
            @Max(value = 24, message = "查询月数最大为24") int months) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(monthlySubmitStatService.recentMonths(clazzId, months, operator));
    }
}
