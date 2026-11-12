package com.campus.homework.controller;

import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.dto.SubmissionSubmitDTO;
import com.campus.homework.service.HomeworkSubmissionService;
import com.campus.homework.service.HomeworkTaskService;
import com.campus.homework.utils.Result;
import com.campus.homework.utils.SecurityUtils;
import com.campus.homework.vo.HomeworkListVO;
import com.campus.homework.vo.MyHomeworkDetailVO;
import com.campus.homework.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端接口（/api/student/**，仅 ROLE_STUDENT）。
 * <p>IDOR 接线约定（Batch 3）：本 Controller 不接收任何"操作者身份"参数，
 * LoginUserDTO 一律由 SecurityUtils 从 SecurityContextHolder 组装后传入
 * Service——前端无法伪造 userId/clazzId；水平越权（只能看本班作业、只能
 * 读写自己的提交、未发布成绩不可见）全部在 Service 层完成，本层只做
 * 垂直越权（@PreAuthorize + URL 规则双保险）与参数校验。</p>
 *
 * @author campus-homework
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 学生端前缀：SecurityConfig 已限定仅 ROLE_STUDENT 可访问
@RequestMapping("/api/student")
// 方法级鉴权第二道防线：hasRole 自动补 ROLE_ 前缀
@PreAuthorize("hasRole('STUDENT')")
// 构造器注入所需 Service（仅依赖接口，不跨层访问 Mapper）
@RequiredArgsConstructor
public class StudentController {

    /** 作业任务服务：学生查看本班作业列表与详情 */
    private final HomeworkTaskService homeworkTaskService;

    /** 作业提交服务：学生提交/重交与个人成绩查看 */
    private final HomeworkSubmissionService homeworkSubmissionService;

    /**
     * 分页查询本班作业列表（按截止时间倒序；VO 不含个人分数）。
     *
     * @param dto 分页参数（keyword 可空，模糊匹配作业标题）
     * @return 作业分页 VO
     */
    @GetMapping("/homework/page")
    public Result<PageVO<HomeworkListVO>> pageHomework(@Validated PageQueryDTO dto) {
        // 操作者身份取自登录态，Service 层据此限定 clazz_id（IDOR）
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkTaskService.pageForStudent(dto, operator));
    }

    /**
     * 作业详情（仅限本班作业；教师端统计字段对学生不展开）。
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
     * 我的作业详情：提交内容回显 + 成绩段（未发布时分数/评语/查重结论为 null）。
     *
     * @param homeworkId 作业ID
     * @return 个人详情 VO
     */
    @GetMapping("/homework/{homeworkId}/mine")
    public Result<MyHomeworkDetailVO> myDetail(@PathVariable Long homeworkId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(homeworkSubmissionService.myDetail(homeworkId, operator));
    }

    /**
     * 提交或覆盖重交作业（正文与附件至少其一；附件先经 /api/file/upload 落盘后回填路径）。
     *
     * @param dto 提交表单（homeworkId + 正文/附件路径）
     * @return 提交记录ID
     */
    @PostMapping("/submission")
    public Result<Long> submit(@RequestBody @Validated SubmissionSubmitDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        Long submissionId = homeworkSubmissionService.submit(dto, operator);
        log.info("学生 {} 提交作业 {}（提交记录 {}）", operator.getUsername(), dto.getHomeworkId(), submissionId);
        return Result.success(submissionId);
    }
}
