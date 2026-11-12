package com.campus.homework.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.homework.dto.HomeworkSaveDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.exception.BusinessException;
import com.campus.homework.exception.ForbiddenException;
import com.campus.homework.exception.NotFoundException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.HomeworkSubmissionMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.HomeworkTaskService;
import com.campus.homework.utils.Constants;
import com.campus.homework.utils.FileUploadUtils;
import com.campus.homework.utils.RedisCacheUtils;
import com.campus.homework.vo.HomeworkListVO;
import com.campus.homework.vo.PageVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 作业任务业务实现：教师发布/编辑/删除作业，学生查看本班作业列表。
 * <p>越权红线：学生仅可查看自己班级（clazz_id 匹配）的作业；教师仅可维护
 * 自己发布（teacher_id 匹配）的作业；删除作业事务内级联删除全部提交记录并
 * 清理参考附件磁盘文件。</p>
 * <p>缓存策略（Batch 3）：学生端班级作业列表按契约键
 * homework:list:clazz:{clazzId} 整体缓存（TTL 5 分钟，空列表写 __NULL__
 * 防穿透），关键词过滤与分页在缓存全量列表上内存完成，与库端语义一致；
 * studentCount 不进缓存段——每次读路径以 sys_user 实时统计回填，因此
 * 管理员增删学生/转班无需联动失效本缓存（分母始终准确）。
 * 发布/编辑/删除作业在事务提交后精确失效涉及班级键。教师端列表按
 * teacher_id 跨班过滤、契约未定义教师维度键，保持直查数据库；
 * 删除作业同时级联失效该作业的成绩分析与个人成绩缓存。</p>
 *
 * @author campus-homework
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkTaskServiceImpl extends ServiceImpl<HomeworkTaskMapper, HomeworkTask> implements HomeworkTaskService {

    /** 班级表 Mapper：发布前校验班级存在、列表页取班级在册人数 */
    private final ClazzInfoMapper clazzInfoMapper;

    /** 用户表 Mapper：实时统计班级人数（列表分母） */
    private final SysUserMapper sysUserMapper;

    /** 提交表 Mapper：删除作业级联清理提交、下调满分前校验已有提交 */
    private final HomeworkSubmissionMapper homeworkSubmissionMapper;

    /** 安全文件工具：删除作业时清理教师参考附件 */
    private final FileUploadUtils fileUploadUtils;

    /** Redis 缓存工具：班级作业列表缓存读写与写路径失效（故障时自动降级回源） */
    private final RedisCacheUtils redisCacheUtils;

    /**
     * 学生端作业分页（仅限登录学生所在班级，按截止时间倒序）。
     * <p>缓存策略：越权校验先于缓存读取；缓存段为班级全量列表（不含
     * studentCount），关键词过滤、排序与分页在内存完成，语义与库端一致；
     * studentCount 每次以 sys_user 实时统计回填，故管理员增删学生/转班
     * 无需联动失效本缓存。Redis 故障时 listOrLoad 自动降级直查数据库。</p>
     *
     * @param dto      分页参数
     * @param operator 当前登录学生
     * @return 作业列表 VO
     */
    @Override
    public PageVO<HomeworkListVO> pageForStudent(PageQueryDTO dto, LoginUserDTO operator) {
        // 角色校验：本方法仅服务学生端
        requireRole(operator, Constants.ROLE_STUDENT, "该功能仅面向学生");
        // 水平越权锚点：学生必须有归属班级（数据异常时直接拒绝而非返回空）
        if (operator.getClazzId() == null) {
            throw new ForbiddenException("当前账号未归属任何班级，请联系教务管理员");
        }
        long[] paging = normalizePaging(dto);
        // 越权校验已通过才读缓存：缓存键以登录态 clazzId 拼装，前端不可伪造
        List<HomeworkListVO> full = redisCacheUtils.listOrLoad(
                Constants.CACHE_HOMEWORK_LIST + operator.getClazzId(),
                Constants.CACHE_TTL_LIST,
                new TypeReference<List<HomeworkListVO>>() {
                },
                () -> loadClassHomeworkList(operator.getClazzId()));
        // 关键词过滤：与库端 like 语义一致（标题包含即命中，大小写敏感同 MySQL 默认）
        String kw = dto.getKeyword() == null ? null : dto.getKeyword().trim();
        List<HomeworkListVO> filtered = full.stream()
                .filter(t -> kw == null || kw.isEmpty() || (t.getTitle() != null && t.getTitle().contains(kw)))
                // 排序与库端 ORDER BY deadline DESC, id DESC 完全一致
                .sorted(Comparator.comparing(HomeworkListVO::getDeadline, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(HomeworkListVO::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        // 内存分页：越界页码返回空页（与 MyBatis-Plus 行为一致）
        long total = filtered.size();
        long from = (paging[0] - 1) * paging[1];
        List<HomeworkListVO> records = from >= total ? new ArrayList<>()
                : new ArrayList<>(filtered.subList((int) from, (int) Math.min(from + paging[1], total)));
        // 分母实时化：全班同一 studentCount，仅一次实时统计（优于逐条 N+1 查询）
        int studentCount = sysUserMapper.countActiveStudents(operator.getClazzId());
        records.forEach(vo -> vo.setStudentCount(studentCount));
        return buildPageVO(paging[0], paging[1], total, records);
    }

    /**
     * 教师端作业分页（仅限登录教师本人发布，按发布时间倒序）。
     *
     * @param dto      分页参数
     * @param operator 当前登录教师
     * @return 作业列表 VO（教师是数据主人，含提交人数与班级人数）
     */
    @Override
    public PageVO<HomeworkListVO> pageForTeacher(PageQueryDTO dto, LoginUserDTO operator) {
        requireRole(operator, Constants.ROLE_TEACHER, "该功能仅面向任课教师");
        long[] paging = normalizePaging(dto);
        LambdaQueryWrapper<HomeworkTask> wrapper = new LambdaQueryWrapper<HomeworkTask>()
                // 归属隔离：教师只能看到自己发布的作业
                .eq(HomeworkTask::getTeacherId, operator.getUserId())
                .orderByDesc(HomeworkTask::getCreateTime).orderByDesc(HomeworkTask::getId);
        if (dto.getKeyword() != null && !dto.getKeyword().trim().isEmpty()) {
            wrapper.like(HomeworkTask::getTitle, dto.getKeyword().trim());
        }
        Page<HomeworkTask> page = page(new Page<>(paging[0], paging[1]), wrapper);
        // 教师端需要"已交 x/y"口径，逐条取班级在册人数作为分母
        List<HomeworkListVO> records = page.getRecords().stream()
                .map(t -> toVO(t, sysUserMapper.countActiveStudents(t.getClazzId())))
                .collect(Collectors.toList());
        return buildPage(page, records);
    }

    /**
     * 作业详情：教师仅限本人发布，学生仅限本班作业。
     *
     * @param id       作业ID
     * @param operator 当前登录用户
     * @return 作业详情 VO
     */
    @Override
    public HomeworkListVO detail(Long id, LoginUserDTO operator) {
        HomeworkTask task = requireTask(id);
        if (Constants.ROLE_TEACHER.equals(operator.getRole())) {
            // 教师端：仅限本人发布的作业（防越权查看他班教学数据）
            if (!operator.getUserId().equals(task.getTeacherId())) {
                throw new ForbiddenException("只能查看自己发布的作业");
            }
            return toVO(task, sysUserMapper.countActiveStudents(task.getClazzId()));
        }
        if (Constants.ROLE_STUDENT.equals(operator.getRole())) {
            // 学生端：仅限本班作业；本 VO 不含任何个人分数
            if (operator.getClazzId() == null || !operator.getClazzId().equals(task.getClazzId())) {
                throw new ForbiddenException("只能查看自己班级的作业");
            }
            return toVO(task, sysUserMapper.countActiveStudents(task.getClazzId()));
        }
        // 管理员可只读查看任意作业详情（教务巡检）
        if (!Constants.ROLE_ADMIN.equals(operator.getRole())) {
            throw new ForbiddenException("当前角色无权查看作业详情");
        }
        return toVO(task, sysUserMapper.countActiveStudents(task.getClazzId()));
    }

    /**
     * 发布或编辑作业（仅教师；新增校验班级存在与截止时间合法；
     * 编辑仅限本人；已有提交后禁止下调满分；成绩发布后禁止改截止时间与满分）。
     *
     * @param dto      作业表单
     * @param operator 当前登录教师
     * @return 作业ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveHomework(HomeworkSaveDTO dto, LoginUserDTO operator) {
        requireRole(operator, Constants.ROLE_TEACHER, "仅任课教师可发布作业");
        // 目标班级必须存在（防 clazz_id 悬挂引用）
        ClazzInfo clazz = clazzInfoMapper.selectById(dto.getClazzId());
        if (clazz == null) {
            throw new NotFoundException("目标班级不存在: " + dto.getClazzId());
        }
        // 满分缺省补 100（与表默认值一致）
        int totalScore = dto.getTotalScore() == null ? 100 : dto.getTotalScore();
        if (dto.getId() == null) {
            // 新增：截止时间必须晚于当前时间，否则发布即逾期，属明显误操作
            if (dto.getDeadline().isBefore(LocalDateTime.now())) {
                throw new BusinessException(400, "截止时间必须晚于当前时间");
            }
            HomeworkTask task = new HomeworkTask();
            BeanUtils.copyProperties(dto, task);
            task.setTotalScore(totalScore);
            // 教师归属以登录态为准，表单不可伪造
            task.setTeacherId(operator.getUserId());
            task.setIsScorePublished(Constants.SCORE_UNPUBLISHED);
            task.setSubmitCount(0);
            task.setCreateTime(LocalDateTime.now());
            save(task);
            // 事务提交后失效该班级作业列表缓存（回滚则不删，旧缓存仍正确）
            redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + task.getClazzId());
            log.info("教师 {} 发布作业 {}（班级 {}）", operator.getUsername(), task.getId(), task.getClazzId());
            return task.getId();
        }
        // 编辑：先取原记录做归属与口径校验
        HomeworkTask old = requireTask(dto.getId());
        if (!operator.getUserId().equals(old.getTeacherId())) {
            throw new ForbiddenException("只能编辑自己发布的作业");
        }
        // 成绩已发布后锁定统计口径：禁止修改满分与截止时间
        if (old.getIsScorePublished() != null && old.getIsScorePublished() == Constants.SCORE_PUBLISHED) {
            if (!old.getTotalScore().equals(totalScore)) {
                throw new BusinessException(400, "成绩已发布，不允许修改满分");
            }
            if (!dto.getDeadline().isEqual(old.getDeadline())) {
                throw new BusinessException(400, "成绩已发布，不允许修改截止时间");
            }
        } else if (old.getSubmitCount() != null && old.getSubmitCount() > 0) {
            // 已有提交但未发布成绩：允许延长截止时间，但不允许下调满分（已批改分数口径漂移）
            if (totalScore < old.getTotalScore()) {
                throw new BusinessException(400, "已有学生提交，不允许下调满分（只允许上调）");
            }
        }
        HomeworkTask task = new HomeworkTask();
        BeanUtils.copyProperties(dto, task);
        task.setTotalScore(totalScore);
        // 归属与统计字段以库内旧值为准，防止表单伪造
        task.setTeacherId(old.getTeacherId());
        task.setIsScorePublished(old.getIsScorePublished());
        task.setSubmitCount(old.getSubmitCount());
        task.setCreateTime(old.getCreateTime());
        updateById(task);
        // 事务提交后失效新班级作业列表缓存；编辑可能变更归属班级，旧班级键同样失效
        redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + dto.getClazzId());
        if (old.getClazzId() != null && !old.getClazzId().equals(dto.getClazzId())) {
            redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + old.getClazzId());
        }
        log.info("教师 {} 编辑作业 {}", operator.getUsername(), dto.getId());
        return dto.getId();
    }

    /**
     * 删除作业（仅教师本人；事务内级联删除提交记录并清理参考附件文件）。
     *
     * @param id       作业ID
     * @param operator 当前登录教师
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeHomework(Long id, LoginUserDTO operator) {
        requireRole(operator, Constants.ROLE_TEACHER, "仅任课教师可删除作业");
        HomeworkTask task = requireTask(id);
        if (!operator.getUserId().equals(task.getTeacherId())) {
            throw new ForbiddenException("只能删除自己发布的作业");
        }
        // 级联清理：先删全部提交记录（成绩与查重数据随作业一并作废）
        homeworkSubmissionMapper.delete(new LambdaQueryWrapper<HomeworkSubmission>()
                .eq(HomeworkSubmission::getHomeworkId, id));
        // 清理教师参考附件磁盘文件（失败仅告警，不阻断数据库删除）
        if (task.getAttachmentUrl() != null && !task.getAttachmentUrl().isEmpty()) {
            fileUploadUtils.delete(task.getAttachmentUrl());
        }
        removeById(id);
        // 事务提交后失效：班级作业列表缓存 + 该作业的成绩分析缓存 + 全部个人成绩缓存
        // （删除级联清理提交记录，成绩与查重数据随作业一并作废，三类缓存必须同步清理）
        redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + task.getClazzId());
        redisCacheUtils.evict(Constants.CACHE_SCORE_ANALYSIS + id);
        redisCacheUtils.evictByPattern(Constants.CACHE_SCORE_STUDENT + id + ":*");
        log.info("教师 {} 删除作业 {}（级联清理提交记录）", operator.getUsername(), id);
    }

    /**
     * 回源加载班级全量作业列表（缓存段：不含 studentCount，由读路径实时回填）。
     *
     * @param clazzId 班级ID
     * @return 作业列表 VO（studentCount 为 null，缓存段不携带实时分母）
     */
    private List<HomeworkListVO> loadClassHomeworkList(Long clazzId) {
        List<HomeworkTask> tasks = list(new LambdaQueryWrapper<HomeworkTask>()
                .eq(HomeworkTask::getClazzId, clazzId));
        return tasks.stream().map(t -> toVO(t, null)).collect(Collectors.toList());
    }

    /**
     * Entity -> 列表 VO：附带班级在册人数（前端展示"已交 x/y"）。
     *
     * @param task         作业实体
     * @param studentCount 班级在册学生数（调用方已按场景取好；缓存段传 null 由读路径回填）
     * @return 作业列表 VO
     */
    private HomeworkListVO toVO(HomeworkTask task, Integer studentCount) {
        HomeworkListVO vo = new HomeworkListVO();
        BeanUtils.copyProperties(task, vo);
        vo.setStudentCount(studentCount);
        return vo;
    }

    /**
     * 作业存在性校验。
     *
     * @param id 作业ID
     * @return 作业实体
     */
    private HomeworkTask requireTask(Long id) {
        HomeworkTask task = getById(id);
        if (task == null) {
            throw new NotFoundException("作业不存在或已被删除");
        }
        return task;
    }

    /**
     * 角色校验（垂直越权防线）。
     *
     * @param operator 当前登录用户
     * @param role     要求的角色
     * @param msg      拒绝提示
     */
    private void requireRole(LoginUserDTO operator, String role, String msg) {
        if (!role.equals(operator.getRole())) {
            throw new ForbiddenException(msg);
        }
    }

    /**
     * 分页参数归一化：非法页码回退默认，页容量封顶 100。
     *
     * @param dto 分页参数
     * @return [pageNum, pageSize]
     */
    private long[] normalizePaging(PageQueryDTO dto) {
        long pageNum = dto.getPageNum() == null || dto.getPageNum() < 1 ? Constants.DEFAULT_PAGE_NUM : dto.getPageNum();
        long pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? Constants.DEFAULT_PAGE_SIZE
                : Math.min(dto.getPageSize(), Constants.MAX_PAGE_SIZE);
        return new long[]{pageNum, pageSize};
    }

    /**
     * MyBatis-Plus 分页对象 -> 通用 PageVO。
     *
     * @param page    持久层分页结果
     * @param records 转换后的 VO 列表
     * @param <T>     VO 类型
     * @return 对外分页 VO
     */
    private <T> PageVO<T> buildPage(Page<?> page, List<T> records) {
        PageVO<T> vo = new PageVO<>();
        vo.setPageNum(page.getCurrent());
        vo.setPageSize(page.getSize());
        vo.setTotal(page.getTotal());
        vo.setPages(page.getPages());
        vo.setRecords(records);
        return vo;
    }

    /**
     * 内存分页结果 -> 通用 PageVO（与 buildPage 出参口径一致：pages 向上取整）。
     *
     * @param pageNum  当前页码（已归一化）
     * @param pageSize 页容量（已封顶）
     * @param total    过滤后的总条数
     * @param records  当前页记录
     * @param <T>      VO 类型
     * @return 对外分页 VO
     */
    private <T> PageVO<T> buildPageVO(long pageNum, long pageSize, long total, List<T> records) {
        PageVO<T> vo = new PageVO<>();
        vo.setPageNum(pageNum);
        vo.setPageSize(pageSize);
        vo.setTotal(total);
        // 总页数 = ceil(total / pageSize)，与 MyBatis-Plus Page#getPages 算法一致
        vo.setPages(pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize);
        vo.setRecords(records);
        return vo;
    }
}
