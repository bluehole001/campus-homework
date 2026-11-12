package com.campus.homework.controller;

import com.campus.homework.dto.GradeSubmitDTO;
import com.campus.homework.dto.HomeworkSaveDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.NoticeSaveDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.service.ClazzNoticeService;
import com.campus.homework.service.HomeworkSubmissionService;
import com.campus.homework.service.HomeworkTaskService;
import com.campus.homework.service.MonthlySubmitStatService;
import com.campus.homework.utils.Result;
import com.campus.homework.utils.SecurityUtils;
import com.campus.homework.vo.HomeworkListVO;
import com.campus.homework.vo.KnowledgeRadarVO;
import com.campus.homework.vo.MonthlyStatVO;
import com.campus.homework.vo.PageVO;
import com.campus.homework.vo.ScoreAnalysisVO;
import com.campus.homework.vo.SubmissionRosterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.List;

/**
 * 教师端接口（/api/teacher/**，仅 ROLE_TEACHER）。
 * <p>与 StudentController 相同的 IDOR 接线约定：操作者身份一律来自
 * SecurityContextHolder，Service 方法签名不变；"仅限本人发布的作业/公告"
 * 的水平越权校验在 Service 层完成。看板趋势（月度统计）与成绩分析
 * 为只读接口，同样受"本人授课班级/本人作业"约束。</p>
 *
 * @author campus-homework
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 教师端前缀：SecurityConfig 已限定仅 ROLE_TEACHER 可访问
@RequestMapping("/api/teacher")
// 方法级鉴权第二道防线（与 URL 规则互为纵深防御）
@PreAuthorize("hasRole('TEACHER')")
// 构造器注入所需 Service（仅依赖接口）
@RequiredArgsConstructor
public class TeacherController {

    /** 作业任务服务：发布/编辑/删除作业与教师端作业列表 */
    private final HomeworkTaskService homeworkTaskService;

    /** 作业提交服务：提交名单、批改、成绩发布与成绩分析 */
    private final HomeworkSubmissionService homeworkSubmissionService;

    /** 班级公告服务：发布/编辑/删除公告 */
    private final ClazzNoticeService clazzNoticeService;

    /** 月度提交统计服务：教师看板趋势数据读取 */
    private final MonthlySubmitStatService monthlySubmitStatService;

    /**
     * 分页查询本人发布的作业（按发布时间倒序，含"已交 x/y"统计口径）。
     *
     * @param dto 分页参数（keyword 可空，模糊匹配标题）
     * @return 作业分页 VO
     */
    @GetMapping("/homework/page")
    public Result<PageVO<HomeworkListVO>> pageHomework(@Validated PageQueryDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkTaskService.pageForTeacher(dto, operator));
    }

    /**
     * 作业详情（仅限本人发布的作业；教师是数据主人，含提交人数）。
     *
     * @param id 作业ID
     * @return 作业详情 VO
     */
    @GetMapping("/homework/{id}")
    public Result<HomeworkListVO> homeworkDetail(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkTaskService.detail(id, operator));
    }

    /**
     * 发布或编辑作业（id 为空发布新作业；参考附件先经 /api/file/upload 落盘后回填路径）。
     *
     * @param dto 作业表单（班级/标题/知识点标签/要求/满分/截止时间）
     * @return 作业ID
     */
    @PostMapping("/homework")
    public Result<Long> saveHomework(@RequestBody @Validated HomeworkSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        Long homeworkId = homeworkTaskService.saveHomework(dto, operator);
        log.info("教师 {} 保存作业 {}", operator.getUsername(), homeworkId);
        return Result.success(homeworkId);
    }

    /**
     * 删除作业（仅限本人发布；级联清理提交记录与参考附件文件）。
     *
     * @param id 作业ID
     * @return 无数据成功响应
     */
    @DeleteMapping("/homework/{id}")
    public Result<Void> removeHomework(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        homeworkTaskService.removeHomework(id, operator);
        log.info("教师 {} 删除作业 {}", operator.getUsername(), id);
        return Result.success();
    }

    /**
     * 查看某作业的提交名单（仅限本人作业；班内无记录的学生合成未交占位行）。
     *
     * @param homeworkId 作业ID
     * @return 名单行 VO 列表（已交在前按提交时间升序）
     */
    @GetMapping("/homework/{homeworkId}/roster")
    public Result<List<SubmissionRosterVO>> roster(@PathVariable Long homeworkId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkSubmissionService.roster(homeworkId, operator));
    }

    /**
     * 批改打分写评语（仅限本人作业的提交；得分不得超过作业满分）。
     *
     * @param dto 批改表单（submissionId + score + 评语）
     * @return 无数据成功响应
     */
    @PostMapping("/grade")
    public Result<Void> grade(@RequestBody @Validated GradeSubmitDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        homeworkSubmissionService.grade(dto, operator);
        log.info("教师 {} 批改提交 {}", operator.getUsername(), dto.getSubmissionId());
        return Result.success();
    }

    /**
     * 一键发布成绩（仅限本人作业；幂等，已发布再次调用直接返回）。
     *
     * @param homeworkId 作业ID
     * @return 无数据成功响应
     */
    @PostMapping("/homework/{homeworkId}/publish")
    public Result<Void> publishScores(@PathVariable Long homeworkId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        homeworkSubmissionService.publishScores(homeworkId, operator);
        log.info("教师 {} 发布作业 {} 成绩", operator.getUsername(), homeworkId);
        return Result.success();
    }

    /**
     * 成绩分析（仅限本人作业；含分布直方图与班级排名，供 ECharts 看板渲染）。
     *
     * @param homeworkId 作业ID
     * @return 成绩分析 VO
     */
    @GetMapping("/homework/{homeworkId}/analysis")
    public Result<ScoreAnalysisVO> analysis(@PathVariable Long homeworkId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkSubmissionService.analysis(homeworkId, operator));
    }

    /**
     * 发布或编辑公告（id 为空发布新公告；编辑仅限本人）。
     *
     * @param dto 公告表单（班级/标题/正文/置顶开关）
     * @return 公告ID
     */
    @PostMapping("/notice")
    public Result<Long> saveNotice(@RequestBody @Validated NoticeSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        Long noticeId = clazzNoticeService.saveNotice(dto, operator);
        log.info("教师 {} 保存公告 {}", operator.getUsername(), noticeId);
        return Result.success(noticeId);
    }

    /**
     * 删除公告（仅限本人发布）。
     *
     * @param id 公告ID
     * @return 无数据成功响应
     */
    @DeleteMapping("/notice/{id}")
    public Result<Void> removeNotice(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        clazzNoticeService.removeNotice(id, operator);
        log.info("教师 {} 删除公告 {}", operator.getUsername(), id);
        return Result.success();
    }

    /**
     * 班级月度提交趋势（教师看板折线图；仅限本人授课班级）。
     *
     * @param clazzId 班级ID
     * @param months  最近月数（1-24，参数级校验后仍由 Service 层兜底）
     * @return 统计 VO 列表（按月份升序）
     */
    @GetMapping("/clazz/{clazzId}/trend")
    public Result<List<MonthlyStatVO>> trend(
            @PathVariable Long clazzId,
            @RequestParam(defaultValue = "6") @Min(value = 1, message = "查询月数最小为1")
            @Max(value = 24, message = "查询月数最大为24") int months) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(monthlySubmitStatService.recentMonths(clazzId, months, operator));
    }

    /**
     * 班级知识点得分雷达（教师看板 ECharts radar 数据源；仅限本人授课班级）。
     * <p>按作业知识点标签聚合本班已批改提交的平均得分率（百分制），
     * 教师据此定位薄弱知识点；无已批改样本时返回空列表，前端展示"暂无数据"。</p>
     *
     * @param clazzId 班级ID
     * @return 雷达轴 VO 列表（按平均得分率降序）
     */
    @GetMapping("/clazz/{clazzId}/radar")
    public Result<List<KnowledgeRadarVO>> knowledgeRadar(@PathVariable Long clazzId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkSubmissionService.knowledgeRadar(clazzId, operator));
    }
}
