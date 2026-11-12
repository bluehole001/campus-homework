package com.campus.homework.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.homework.dto.GradeSubmitDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.MailMessage;
import com.campus.homework.dto.PlagiarismMessage;
import com.campus.homework.dto.ScoreStatMessage;
import com.campus.homework.dto.SubmissionSubmitDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.entity.SysUser;
import com.campus.homework.exception.BusinessException;
import com.campus.homework.exception.ForbiddenException;
import com.campus.homework.exception.NotFoundException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.HomeworkSubmissionMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.HomeworkSubmissionService;
import com.campus.homework.utils.Constants;
import com.campus.homework.utils.RedisCacheUtils;
import com.campus.homework.utils.TextSimilarityUtils;
import com.campus.homework.vo.KnowledgeRadarVO;
import com.campus.homework.vo.MyHomeworkDetailVO;
import com.campus.homework.vo.ScoreAnalysisVO;
import com.campus.homework.vo.SubmissionRosterVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 作业提交业务实现：学生提交/重交、教师名单与批改、成绩发布、成绩分析。
 * <p>IDOR 越权红线集中落地：</p>
 * <ul>
 *   <li>学生仅可读写自己的提交（student_id 与登录态比对）；</li>
 *   <li>成绩未发布时学生看不到分数/评语/查重结论；</li>
 *   <li>教师仅可批改、发布、分析自己发布的作业（homework_task.teacher_id 比对）。</li>
 * </ul>
 * <p>缓存策略（Batch 3）：成绩分析按契约键 homework:score:analysis:{homeworkId}
 * 整体缓存（TTL 10 分钟，越权校验先于缓存读取）；学生"我的作业详情"仅在
 * 成绩已发布后按 homework:score:stu:{hwId}:{stuId} 缓存（未发布阶段正文
 * 可反复重交、状态随截止时间漂移，不做缓存）；批改/提交/发布在事务提交后
 * 精确失效分析键与个人键——发布成绩时按 homework:score:stu:{hwId}:* 批量清理。</p>
 * <p>MQ 生产者接线（Batch 4）：提交/重交事务提交后投递查重消息
 * （homework.plagiarism），成绩发布事务提交后投递成绩统计预热消息
 * （homework.score.stat）与发布通知邮件消息（homework.mail.batch）。
 * 投递一律挂在 afterCommit 回调：数据未落库不发消息、回滚则不发，
 * 消费端读到的必是已提交数据；投递失败仅记 ERROR 不阻断已成功的业务
 * （查重/预热均为幂等旁路，后续动作会再次触发）。本类同时提供两个
 * MQ 消费端专用入口：plagiarismCheck（查重回写）与 warmScoreCache
 * （查分缓存预热），均为系统内部调用、无登录态。</p>
 *
 * @author campus-homework
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkSubmissionServiceImpl extends ServiceImpl<HomeworkSubmissionMapper, HomeworkSubmission>
        implements HomeworkSubmissionService {

    /** 及格线：得分率达到 60% 视为及格 */
    private static final BigDecimal PASS_LINE = new BigDecimal("60");

    /** 百分制换算基数 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 作业表 Mapper：提交/批改前校验作业存在与归属 */
    private final HomeworkTaskMapper homeworkTaskMapper;

    /** 班级表 Mapper：知识点雷达接口校验班级存在（Batch 5） */
    private final ClazzInfoMapper clazzInfoMapper;

    /** 用户表 Mapper：班级名单（未交占位合成）、排名姓名回填 */
    private final SysUserMapper sysUserMapper;

    /** Redis 缓存工具：成绩分析/个人成绩缓存读写与写路径失效（故障时自动降级回源） */
    private final RedisCacheUtils redisCacheUtils;

    /** RabbitMQ 生产模板（RabbitMQConfig 中装配 JSON 转换器，事务提交后投递异步任务消息） */
    private final RabbitTemplate rabbitTemplate;

    /** 高重复预警阈值：来自配置 homework.plagiarism.threshold（默认 0.85） */
    @Value("${homework.plagiarism.threshold:0.85}")
    private double plagiarismThreshold;

    /**
     * 学生提交或覆盖重交作业。
     * <p>规则：作业存在且属于该学生班级；正文与附件至少其一非空；
     * 成绩已发布后禁止重交；截止前重交覆盖旧记录并清除已批改成绩；
     * 截止后提交标记为逾期未交(2)；首次形成有效提交时原子自增作业 submit_count。</p>
     *
     * @param dto      提交表单
     * @param operator 当前登录学生
     * @return 提交记录ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(SubmissionSubmitDTO dto, LoginUserDTO operator) {
        // 角色校验：仅学生可提交作业
        if (!Constants.ROLE_STUDENT.equals(operator.getRole())) {
            throw new ForbiddenException("仅学生可提交作业");
        }
        // 水平越权：作业必须存在且属于该学生所在班级
        HomeworkTask task = requireTask(dto.getHomeworkId());
        if (operator.getClazzId() == null || !operator.getClazzId().equals(task.getClazzId())) {
            throw new ForbiddenException("只能提交自己班级的作业");
        }
        // 内容校验：正文与附件至少其一非空（纯空提交无意义）
        boolean hasText = dto.getContentText() != null && !dto.getContentText().trim().isEmpty();
        boolean hasFile = dto.getFileUrl() != null && !dto.getFileUrl().isEmpty();
        if (!hasText && !hasFile) {
            throw new BusinessException(400, "作业正文与附件至少填写一项");
        }
        // 成绩已发布后锁定提交：防止"先交垃圾再刷成绩"的口径漂移
        if (task.getIsScorePublished() != null && task.getIsScorePublished() == Constants.SCORE_PUBLISHED) {
            throw new BusinessException(400, "成绩已发布，不允许再提交或重交");
        }
        // 提交状态判定：当前时间不晚于截止时间视为按时交(1)，否则逾期未交(2)
        LocalDateTime now = LocalDateTime.now();
        int status = !now.isAfter(task.getDeadline()) ? Constants.SUBMIT_ON_TIME : Constants.SUBMIT_OVERDUE;
        // 定位既有记录（uk_hw_stu 唯一索引命中）：占位(0)/已交(1)/逾期(2)/已批改(3)
        HomeworkSubmission existing = getBaseMapper().selectByHwAndStu(dto.getHomeworkId(), operator.getUserId());
        if (existing == null) {
            // 首次提交：插入新记录并原子自增作业提交人数
            HomeworkSubmission entity = new HomeworkSubmission();
            entity.setHomeworkId(dto.getHomeworkId());
            entity.setStudentId(operator.getUserId());
            entity.setContentText(trimOrNull(dto.getContentText()));
            entity.setFileUrl(trimOrNull(dto.getFileUrl()));
            entity.setFileName(trimOrNull(dto.getFileName()));
            entity.setSubmitStatus(status);
            entity.setIsHighRepeat(Constants.REPEAT_NORMAL);
            entity.setSubmitTime(now);
            save(entity);
            homeworkTaskMapper.addSubmitCount(dto.getHomeworkId(), 1);
            // 事务提交后失效：班级作业列表缓存（submit_count 已自增，"已交 x/y"必须实时）
            // 与成绩分析缓存（提交率分子变化）——提交只发生在成绩发布前，个人成绩键尚不存在
            redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + task.getClazzId());
            redisCacheUtils.evict(Constants.CACHE_SCORE_ANALYSIS + dto.getHomeworkId());
            // 事务提交后投递查重消息：消费端读到的必是已落库正文（Batch 4 异步查重）
            sendAfterCommit(() -> rabbitTemplate.convertAndSend(
                    Constants.EXCHANGE_HOMEWORK, Constants.ROUTING_PLAGIARISM,
                    new PlagiarismMessage(dto.getHomeworkId(), operator.getUserId())));
            log.info("学生 {} 首次提交作业 {}（状态 {}）", operator.getUsername(), dto.getHomeworkId(), status);
            return entity.getId();
        }
        // 覆盖重交：正文/附件整体覆盖，查重结论重置（由 Batch 4 MQ 重新计算）
        HomeworkSubmission update = new HomeworkSubmission();
        update.setId(existing.getId());
        update.setContentText(trimOrNull(dto.getContentText()));
        update.setFileUrl(trimOrNull(dto.getFileUrl()));
        update.setFileName(trimOrNull(dto.getFileName()));
        update.setSubmitStatus(status);
        update.setSubmitTime(now);
        // 重置查重与成绩字段：重交后旧成绩与旧查重结论均作废
        update.setSimilarityRate(null);
        update.setSimilarStudentId(null);
        update.setIsHighRepeat(Constants.REPEAT_NORMAL);
        update.setScore(null);
        update.setTeacherComment(null);
        update.setGradeTime(null);
        // 占位记录(0)此前未被计入 submit_count，转为有效提交时补 +1
        if (existing.getSubmitStatus() != null && existing.getSubmitStatus() == Constants.SUBMIT_NOT) {
            homeworkTaskMapper.addSubmitCount(dto.getHomeworkId(), 1);
        }
        updateById(update);
        // 覆盖重交同样改变"已交 x/y"与提交率：事务提交后失效班级列表缓存与成绩分析缓存
        redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + task.getClazzId());
        redisCacheUtils.evict(Constants.CACHE_SCORE_ANALYSIS + dto.getHomeworkId());
        // 重交后正文已变化：事务提交后重新投递查重消息（消费端按最新正文重算，幂等）
        sendAfterCommit(() -> rabbitTemplate.convertAndSend(
                Constants.EXCHANGE_HOMEWORK, Constants.ROUTING_PLAGIARISM,
                new PlagiarismMessage(dto.getHomeworkId(), operator.getUserId())));
        log.info("学生 {} 覆盖重交作业 {}（状态 {}）", operator.getUsername(), dto.getHomeworkId(), status);
        return existing.getId();
    }

    /**
     * 学生查看"我的作业详情"（仅限本班作业；成绩未发布时个人分数段保持 null）。
     * <p>缓存策略：仅成绩已发布后走个人成绩缓存（发布后禁止重交，数据冻结；
     * 教师发布后补批改会在 grade() 中同步失效该键）；未发布阶段正文可反复
     * 重交、提交状态随截止时间漂移，一律直查数据库。</p>
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录学生
     * @return 个人详情 VO
     */
    @Override
    public MyHomeworkDetailVO myDetail(Long homeworkId, LoginUserDTO operator) {
        if (!Constants.ROLE_STUDENT.equals(operator.getRole())) {
            throw new ForbiddenException("该功能仅面向学生");
        }
        HomeworkTask task = requireTask(homeworkId);
        // 水平越权：学生只能查看自己班级的作业
        if (operator.getClazzId() == null || !operator.getClazzId().equals(task.getClazzId())) {
            throw new ForbiddenException("只能查看自己班级的作业");
        }
        boolean published = task.getIsScorePublished() != null && task.getIsScorePublished() == Constants.SCORE_PUBLISHED;
        if (!published) {
            // 未发布：不缓存（正文/状态随时可能被本人重交覆盖，且状态依赖当前时间）
            return buildMyDetail(task, getBaseMapper().selectByHwAndStu(homeworkId, operator.getUserId()));
        }
        // 已发布：越权校验已通过才读缓存；键含本人 userId，天然按人隔离（IDOR 安全）
        return redisCacheUtils.objectOrLoad(
                Constants.CACHE_SCORE_STUDENT + homeworkId + ":" + operator.getUserId(),
                Constants.CACHE_TTL_SCORE,
                new TypeReference<MyHomeworkDetailVO>() {
                },
                () -> buildMyDetail(task, getBaseMapper().selectByHwAndStu(homeworkId, operator.getUserId())),
                false);
    }

    /**
     * 组装"我的作业详情" VO：作业公共信息段 + 本人提交段；成绩未发布时
     * 分数/评语/查重结论严格隐藏（IDOR 红线，即使缓存命中也不提前暴露）。
     *
     * @param task 作业实体（调用方已加载并校验归属）
     * @param sub  本人提交记录（可为 null，表示从未提交）
     * @return 个人详情 VO
     */
    private MyHomeworkDetailVO buildMyDetail(HomeworkTask task, HomeworkSubmission sub) {
        boolean published = task.getIsScorePublished() != null && task.getIsScorePublished() == Constants.SCORE_PUBLISHED;
        MyHomeworkDetailVO vo = new MyHomeworkDetailVO();
        // 作业公共信息段（要求/附件/满分/截止时间）学生始终可见
        vo.setHomeworkId(task.getId());
        vo.setTitle(task.getTitle());
        vo.setKnowledgeTag(task.getKnowledgeTag());
        vo.setRequirement(task.getRequirement());
        vo.setAttachmentUrl(task.getAttachmentUrl());
        vo.setAttachmentName(task.getAttachmentName());
        vo.setTotalScore(task.getTotalScore());
        vo.setDeadline(task.getDeadline());
        vo.setIsScorePublished(task.getIsScorePublished());
        if (sub == null) {
            // 从未提交：状态按未交(0)呈现，个人字段全部留空
            vo.setSubmitStatus(Constants.SUBMIT_NOT);
            return vo;
        }
        // 个人提交段：正文与附件是"自己的数据"，始终回显（便于截止前重交编辑）
        vo.setSubmitStatus(sub.getSubmitStatus());
        vo.setSubmitTime(sub.getSubmitTime());
        vo.setContentText(sub.getContentText());
        vo.setFileUrl(sub.getFileUrl());
        vo.setFileName(sub.getFileName());
        // 成绩段：未发布时严格隐藏分数/评语/查重结论（防提前暴露）
        if (published) {
            vo.setScore(sub.getScore());
            vo.setTeacherComment(sub.getTeacherComment());
            vo.setSimilarityRate(sub.getSimilarityRate());
            vo.setIsHighRepeat(sub.getIsHighRepeat());
        }
        return vo;
    }

    /**
     * 教师查看提交名单（仅限本人作业；班内无提交记录的学生合成未交占位行）。
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录教师
     * @return 名单行 VO 列表
     */
    @Override
    public List<SubmissionRosterVO> roster(Long homeworkId, LoginUserDTO operator) {
        requireTeacherOwnTask(homeworkId, operator);
        HomeworkTask task = requireTask(homeworkId);
        // 班级在册学生名单（学号升序）：作为占位合成的基准与姓名/学号来源
        List<SysUser> students = sysUserMapper.selectStudentsByClazzId(task.getClazzId());
        Map<Long, SysUser> studentMap = new HashMap<>();
        for (SysUser s : students) {
            studentMap.put(s.getId(), s);
        }
        // 该作业全部提交记录（含占位）
        List<HomeworkSubmission> submissions = getBaseMapper().selectByHomeworkId(homeworkId);
        Map<Long, HomeworkSubmission> subMap = new HashMap<>();
        for (HomeworkSubmission s : submissions) {
            subMap.put(s.getStudentId(), s);
        }
        List<SubmissionRosterVO> rows = new ArrayList<>();
        for (SysUser student : students) {
            SubmissionRosterVO row = new SubmissionRosterVO();
            row.setStudentId(student.getId());
            row.setUserNo(student.getUserNo());
            row.setRealName(student.getRealName());
            HomeworkSubmission sub = subMap.get(student.getId());
            if (sub == null) {
                // 合成未交占位行：教师端以灰色标记"未交"
                row.setSubmitStatus(Constants.SUBMIT_NOT);
                rows.add(row);
                continue;
            }
            // 有记录：回填提交与批改数据（教师是数据主人，成绩未发布教师本人仍可见）
            row.setSubmissionId(sub.getId());
            row.setSubmitStatus(sub.getSubmitStatus());
            row.setSubmitTime(sub.getSubmitTime());
            row.setContentText(sub.getContentText());
            row.setFileUrl(sub.getFileUrl());
            row.setFileName(sub.getFileName());
            row.setSimilarityRate(sub.getSimilarityRate());
            row.setIsHighRepeat(sub.getIsHighRepeat());
            row.setScore(sub.getScore());
            row.setTeacherComment(sub.getTeacherComment());
            // 高重复复核入口：回填最相似同学ID（前端据此定位对方原文行）并翻译为姓名
            if (sub.getSimilarStudentId() != null) {
                row.setSimilarStudentId(sub.getSimilarStudentId());
                SysUser similar = studentMap.get(sub.getSimilarStudentId());
                row.setSimilarStudentName(similar == null ? null : similar.getRealName());
            }
            rows.add(row);
        }
        // 兜底：提交者已不在册名单（如被删除账号）仍展示，避免教师漏看
        for (HomeworkSubmission sub : submissions) {
            if (!studentMap.containsKey(sub.getStudentId())) {
                SysUser ghost = sysUserMapper.selectById(sub.getStudentId());
                if (ghost == null) {
                    continue;
                }
                SubmissionRosterVO row = new SubmissionRosterVO();
                row.setSubmissionId(sub.getId());
                row.setStudentId(ghost.getId());
                row.setUserNo(ghost.getUserNo());
                row.setRealName(ghost.getRealName());
                row.setSubmitStatus(sub.getSubmitStatus());
                row.setSubmitTime(sub.getSubmitTime());
                row.setContentText(sub.getContentText());
                row.setFileUrl(sub.getFileUrl());
                row.setFileName(sub.getFileName());
                row.setSimilarityRate(sub.getSimilarityRate());
                row.setIsHighRepeat(sub.getIsHighRepeat());
                row.setScore(sub.getScore());
                row.setTeacherComment(sub.getTeacherComment());
                // 兜底行同样回填查重定位信息（最相似同学可能仍是在册学生）
                if (sub.getSimilarStudentId() != null) {
                    row.setSimilarStudentId(sub.getSimilarStudentId());
                    SysUser similar = studentMap.get(sub.getSimilarStudentId());
                    row.setSimilarStudentName(similar == null ? null : similar.getRealName());
                }
                rows.add(row);
            }
        }
        // 排序：已交在前按提交时间升序，未交占位排最后，方便教师逐份批改
        rows.sort(Comparator.<SubmissionRosterVO, Boolean>comparing(r -> r.getSubmitStatus() == Constants.SUBMIT_NOT)
                .thenComparing(r -> r.getSubmitTime() == null ? LocalDateTime.MAX : r.getSubmitTime()));
        return rows;
    }

    /**
     * 教师批改打分写评语（仅限本人作业的提交；得分不得超过作业满分）。
     *
     * @param dto      批改表单
     * @param operator 当前登录教师
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grade(GradeSubmitDTO dto, LoginUserDTO operator) {
        // 定位提交记录并校验其所属作业的归属（防越权批改他人作业）
        HomeworkSubmission sub = getById(dto.getSubmissionId());
        if (sub == null) {
            throw new NotFoundException("提交记录不存在");
        }
        HomeworkTask task = requireTask(sub.getHomeworkId());
        if (!operator.getUserId().equals(task.getTeacherId())) {
            throw new ForbiddenException("只能批改自己发布的作业");
        }
        // 得分口径校验：不得超过该作业满分（DTO 只能限定绝对上限）
        if (dto.getScore().compareTo(new BigDecimal(task.getTotalScore())) > 0) {
            throw new BusinessException(400, "得分不能超过作业满分 " + task.getTotalScore());
        }
        HomeworkSubmission update = new HomeworkSubmission();
        update.setId(sub.getId());
        // 得分保留1位小数入库（与 DECIMAL(5,1) 精度一致）
        update.setScore(dto.getScore().setScale(1, RoundingMode.HALF_UP));
        update.setTeacherComment(trimOrNull(dto.getTeacherComment()));
        // 状态置为已批改(3)，记录批改时间
        update.setSubmitStatus(Constants.SUBMIT_GRADED);
        update.setGradeTime(LocalDateTime.now());
        updateById(update);
        // 事务提交后失效：成绩分析缓存（均值/极值/分布/排名全部随本次批改变化）
        // 与该生个人成绩缓存（发布后补批改场景：学生已缓存的旧分数必须作废）
        redisCacheUtils.evict(Constants.CACHE_SCORE_ANALYSIS + sub.getHomeworkId());
        redisCacheUtils.evict(Constants.CACHE_SCORE_STUDENT + sub.getHomeworkId() + ":" + sub.getStudentId());
        log.info("教师 {} 批改提交 {}：{}分", operator.getUsername(), sub.getId(), update.getScore());
    }

    /**
     * 一键发布成绩（仅限本人作业；幂等：已发布再次调用直接返回）。
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录教师
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishScores(Long homeworkId, LoginUserDTO operator) {
        HomeworkTask task = requireTeacherOwnTask(homeworkId, operator);
        // 幂等保护：重复点击发布直接返回，不重复触发后续 MQ 预热（Batch 4）
        if (task.getIsScorePublished() != null && task.getIsScorePublished() == Constants.SCORE_PUBLISHED) {
            log.info("作业 {} 成绩已发布，忽略重复发布请求", homeworkId);
            return;
        }
        // 前置校验：至少有一条已批改记录才允许发布（空发布无意义）
        Long gradedCount = getBaseMapper().selectCount(new LambdaQueryWrapper<HomeworkSubmission>()
                .eq(HomeworkSubmission::getHomeworkId, homeworkId)
                .eq(HomeworkSubmission::getSubmitStatus, Constants.SUBMIT_GRADED));
        if (gradedCount == null || gradedCount == 0) {
            throw new BusinessException(400, "尚无已批改的提交，请先完成批改再发布成绩");
        }
        HomeworkTask update = new HomeworkTask();
        update.setId(homeworkId);
        update.setIsScorePublished(Constants.SCORE_PUBLISHED);
        homeworkTaskMapper.updateById(update);
        // 事务提交后失效三类缓存：
        // 1) 班级作业列表缓存（isScorePublished 标志变化，学生端"查看成绩"入口据此显隐）
        // 2) 成绩分析缓存（发布前后学生可见口径不同，且教师看板需即时反映）
        // 3) 该作业全部个人成绩缓存（发布前 myDetail 不缓存，此处为防御性清理，
        //    覆盖"发布->补批改->再查看"等边界场景，SCAN 批量删除不阻塞 Redis）
        redisCacheUtils.evict(Constants.CACHE_HOMEWORK_LIST + task.getClazzId());
        redisCacheUtils.evict(Constants.CACHE_SCORE_ANALYSIS + homeworkId);
        redisCacheUtils.evictByPattern(Constants.CACHE_SCORE_STUDENT + homeworkId + ":*");
        // 事务提交后投递两类消息（Batch 4）：
        // 1) 成绩统计消息：消费端重算分析并全量预热两类查分缓存，扛住查分高峰
        // 2) 成绩发布通知邮件：异步批量投递，不阻塞发布接口响应
        sendAfterCommit(() -> {
            rabbitTemplate.convertAndSend(Constants.EXCHANGE_HOMEWORK,
                    Constants.ROUTING_SCORE_STAT, new ScoreStatMessage(homeworkId));
            rabbitTemplate.convertAndSend(Constants.EXCHANGE_HOMEWORK,
                    Constants.ROUTING_MAIL_BATCH, new MailMessage(homeworkId, MailMessage.TYPE_SCORE_PUBLISH));
        });
        log.info("教师 {} 发布作业 {} 成绩", operator.getUsername(), homeworkId);
    }

    /**
     * 教师查看作业成绩分析（仅限本人作业；含分布直方图与班级排名）。
     * <p>缓存策略：越权校验先于缓存读取（requireTeacherOwnTask 在缓存之前执行，
     * 命中缓存也不会泄露他人作业）。高频变更（提交/批改/发布/改作业/删作业）
     * 均已接入事务提交后失效，故缓存窗口内分数与排名始终准确；
     * 管理员增删/转班学生属低频管理操作、未联动失效本键，其影响的
     * 提交率分母与排名姓名最长一个 TTL（10 分钟）后自然刷新——教师端
     * 分析页可接受该最终一致性，下一次批改或发布动作会立即重建。</p>
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录教师
     * @return 成绩分析 VO
     */
    @Override
    public ScoreAnalysisVO analysis(Long homeworkId, LoginUserDTO operator) {
        HomeworkTask task = requireTeacherOwnTask(homeworkId, operator);
        // 越权校验已通过才读缓存：缓存键按 homeworkId，教师是数据主人，无泄露风险
        return redisCacheUtils.objectOrLoad(
                Constants.CACHE_SCORE_ANALYSIS + homeworkId,
                Constants.CACHE_TTL_SCORE,
                new TypeReference<ScoreAnalysisVO>() {
                },
                () -> loadAnalysis(task),
                false);
    }

    /**
     * 教师查看班级知识点得分雷达（仅限本人授课班级；Batch 5 看板数据源）。
     * <p>IDOR 校验与月度趋势接口完全同口径：教师必须在该班级发布过作业
     * （homework_task.teacher_id = 本人）才可查看，否则 403。聚合一律
     * 直查数据库（雷达为低频看板视图，且批改随时改变轴值，不做缓存）。
     * 聚合 SQL 在 Mapper 内以 #{} 预编译完成，本方法只负责越权把关。</p>
     *
     * @param clazzId  班级ID
     * @param operator 当前登录教师
     * @return 雷达轴 VO 列表（按平均得分率降序；无已批改样本时为空列表）
     */
    @Override
    public List<KnowledgeRadarVO> knowledgeRadar(Long clazzId, LoginUserDTO operator) {
        if (!Constants.ROLE_TEACHER.equals(operator.getRole())) {
            throw new ForbiddenException("该功能仅面向任课教师");
        }
        ClazzInfo clazz = clazzInfoMapper.selectById(clazzId);
        if (clazz == null) {
            throw new NotFoundException("班级不存在或已被删除");
        }
        // 水平越权：教师仅可看自己授课（已发布过作业）班级的雷达
        Long ownTasks = homeworkTaskMapper.selectCount(new LambdaQueryWrapper<HomeworkTask>()
                .eq(HomeworkTask::getTeacherId, operator.getUserId())
                .eq(HomeworkTask::getClazzId, clazzId));
        if (ownTasks == null || ownTasks == 0) {
            throw new ForbiddenException("只能查看自己授课班级的知识点雷达");
        }
        // 直查聚合：Mapper 内 100% #{} 预编译，按知识点汇总已批改样本的平均得分率
        return getBaseMapper().selectKnowledgeRadar(clazzId);
    }

    /**
     * 回源计算成绩分析（无缓存时执行；含分布直方图、均值/极值、及格率、
     * 提交率与班级排名，全部基于实时数据）。
     *
     * @param task 作业实体（调用方已加载并校验归属）
     * @return 成绩分析 VO
     */
    private ScoreAnalysisVO loadAnalysis(HomeworkTask task) {
        Long homeworkId = task.getId();
        // 全部提交与班级名单（分母与姓名来源）
        List<HomeworkSubmission> submissions = getBaseMapper().selectByHomeworkId(homeworkId);
        List<SysUser> students = sysUserMapper.selectStudentsByClazzId(task.getClazzId());
        Map<Long, SysUser> studentMap = new HashMap<>();
        for (SysUser s : students) {
            studentMap.put(s.getId(), s);
        }
        int totalStudents = students.size();
        // 已批改样本：状态为已批改(3)且分数非空
        List<HomeworkSubmission> graded = submissions.stream()
                .filter(s -> s.getSubmitStatus() != null && s.getSubmitStatus() == Constants.SUBMIT_GRADED && s.getScore() != null)
                .collect(Collectors.toList());
        BigDecimal totalScore = new BigDecimal(task.getTotalScore());
        ScoreAnalysisVO vo = new ScoreAnalysisVO();
        vo.setHomeworkId(task.getId());
        vo.setTitle(task.getTitle());
        vo.setKnowledgeTag(task.getKnowledgeTag());
        vo.setTotalScore(task.getTotalScore());
        // 提交率：已提交（按时交/逾期补交/已批改均计入）/ 班级在册人数
        int submitted = getBaseMapper().countSubmitted(homeworkId);
        vo.setSubmitRate(totalStudents == 0 ? BigDecimal.ZERO
                : new BigDecimal(submitted).multiply(HUNDRED).divide(new BigDecimal(totalStudents), 2, RoundingMode.HALF_UP));
        // 高重复预警人数
        vo.setHighRepeatCount((int) submissions.stream()
                .filter(s -> s.getIsHighRepeat() != null && s.getIsHighRepeat() == Constants.REPEAT_HIGH).count());
        // 无已批改样本：统计段全部留空，前端展示"暂无数据"
        if (graded.isEmpty()) {
            vo.setAvgScore(null);
            vo.setMaxScore(null);
            vo.setMinScore(null);
            vo.setPassRate(null);
            vo.setScoreBuckets(new ArrayList<>());
            vo.setRanking(new ArrayList<>());
            return vo;
        }
        // 平均/最高/最低分：得分保留1位小数
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = graded.get(0).getScore();
        BigDecimal min = graded.get(0).getScore();
        for (HomeworkSubmission s : graded) {
            sum = sum.add(s.getScore());
            if (s.getScore().compareTo(max) > 0) {
                max = s.getScore();
            }
            if (s.getScore().compareTo(min) < 0) {
                min = s.getScore();
            }
        }
        vo.setAvgScore(sum.divide(new BigDecimal(graded.size()), 1, RoundingMode.HALF_UP));
        vo.setMaxScore(max);
        vo.setMinScore(min);
        // 分数分布：按得分率 10% 一档共 10 档（下标0=0-10% … 下标9=90-100%）
        List<Integer> buckets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buckets.add(0);
        }
        int passCount = 0;
        for (HomeworkSubmission s : graded) {
            // 得分率换算到百分制后定位档位，边界 100% 归入最后一档
            BigDecimal rate = s.getScore().multiply(HUNDRED).divide(totalScore, 10, RoundingMode.HALF_UP);
            int idx = Math.min(9, rate.setScale(0, RoundingMode.DOWN).intValue() / 10);
            buckets.set(idx, buckets.get(idx) + 1);
            // 及格判定：得分率 ≥ 60%
            if (rate.compareTo(PASS_LINE) >= 0) {
                passCount++;
            }
        }
        vo.setScoreBuckets(buckets);
        vo.setPassRate(new BigDecimal(passCount).multiply(HUNDRED)
                .divide(new BigDecimal(graded.size()), 2, RoundingMode.HALF_UP));
        // 班级排名：得分降序，同分按提交时间先后定序（先交者靠前）
        graded.sort(Comparator.<HomeworkSubmission, BigDecimal>comparing(HomeworkSubmission::getScore).reversed()
                .thenComparing(s -> s.getSubmitTime() == null ? LocalDateTime.MAX : s.getSubmitTime()));
        List<ScoreAnalysisVO.RankItemVO> ranking = new ArrayList<>();
        int rank = 1;
        for (HomeworkSubmission s : graded) {
            ScoreAnalysisVO.RankItemVO item = new ScoreAnalysisVO.RankItemVO();
            item.setRank(rank++);
            item.setStudentId(s.getStudentId());
            SysUser student = studentMap.get(s.getStudentId());
            if (student != null) {
                item.setUserNo(student.getUserNo());
                item.setRealName(student.getRealName());
            }
            item.setScore(s.getScore());
            // 得分率 = score / totalScore * 100，保留1位小数
            item.setScoreRate(s.getScore().multiply(HUNDRED).divide(totalScore, 1, RoundingMode.HALF_UP));
            ranking.add(item);
        }
        vo.setRanking(ranking);
        return vo;
    }

    /**
     * 执行异步查重并回写结论（MQ 消费端专用入口，Batch 4）。
     * <p>系统内部调用无登录态：消息由 submit() 在事务提交后产生，归属校验
     * 已在提交路径完成；本方法只保证数据归属正确（按 uk_hw_stu 定位，
     * 记录已删除时静默放弃）。回写一律走显式 UPDATE，"无命中"时把
     * similarity_rate / similar_student_id 置回 NULL、is_high_repeat 归 0，
     * 避免重交后旧结论残留误导教师。</p>
     *
     * @param homeworkId 作业ID
     * @param studentId  提交学生ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void plagiarismCheck(Long homeworkId, Long studentId) {
        // 定位本人提交：记录可能已被教师删除作业级联清理，静默放弃（消息幂等可重投）
        HomeworkSubmission self = getBaseMapper().selectByHwAndStu(homeworkId, studentId);
        if (self == null) {
            log.warn("查重消息对应的提交不存在（可能已删除）：hw={}, stu={}", homeworkId, studentId);
            return;
        }
        // 纯附件提交（正文为空）无文本可比：确保查重字段保持"无结论"状态后返回
        if (self.getContentText() == null || self.getContentText().trim().isEmpty()) {
            getBaseMapper().updatePlagiarismResult(self.getId(), null, null, Constants.REPEAT_NORMAL);
            log.info("提交 {} 无正文文本，跳过查重比对", self.getId());
            return;
        }
        // 候选集合：同作业除本人外、正文非空的已提交文本（防自比恒为 1）
        List<HomeworkSubmission> candidates = getBaseMapper().selectPlagiarismCandidates(homeworkId, studentId);
        Map<Long, String> candidateTexts = new HashMap<>();
        for (HomeworkSubmission c : candidates) {
            candidateTexts.put(c.getStudentId(), c.getContentText());
        }
        // N-Gram + 余弦相似度比对：仅返回 ≥ 阈值的命中，按相似度降序，首位即最相似同学
        List<TextSimilarityUtils.MatchResult> matches =
                TextSimilarityUtils.findMatches(self.getContentText(), candidateTexts, plagiarismThreshold);
        if (matches.isEmpty()) {
            // 无命中：显式置空结论并归零预警（覆盖重交后旧结论作废）
            getBaseMapper().updatePlagiarismResult(self.getId(), null, null, Constants.REPEAT_NORMAL);
            log.info("查重完成：提交 {} 无高重复命中（候选 {} 篇，阈值 {}）", self.getId(), candidateTexts.size(), plagiarismThreshold);
            return;
        }
        // 命中：取相似度最高的一位作为最相似同学，预警置 1（相似度保留4位小数，与 DECIMAL(5,4) 一致）
        TextSimilarityUtils.MatchResult top = matches.get(0);
        BigDecimal rate = BigDecimal.valueOf(top.getSimilarity()).setScale(4, RoundingMode.HALF_UP);
        getBaseMapper().updatePlagiarismResult(self.getId(), rate, top.getMatchedId(), Constants.REPEAT_HIGH);
        log.info("查重完成：提交 {} 与学生 {} 相似度 {}，触发高重复预警", self.getId(), top.getMatchedId(), rate);
    }

    /**
     * 成绩发布后的缓存预热（MQ 消费端专用入口，Batch 4）。
     * <p>预热范围：1) 成绩分析整体缓存（教师看板与查分页共用）；
     * 2) 仅对"有提交记录"的学生逐个预热个人成绩缓存——没交过的学生
     * 不会在查分高峰发起查分请求，预热他们纯属浪费 Redis 容量。
     * 全部读写经 RedisCacheUtils 降级保护，Redis 故障时预热退化为空操作，
     * 后续首查自动回源，不影响业务正确性。</p>
     *
     * @param homeworkId 作业ID（成绩刚发布的作业）
     */
    @Override
    public void warmScoreCache(Long homeworkId) {
        HomeworkTask task = requireTask(homeworkId);
        // 1) 预热成绩分析缓存：objectOrLoad 未命中时执行 loadAnalysis 回源并写入
        redisCacheUtils.objectOrLoad(
                Constants.CACHE_SCORE_ANALYSIS + homeworkId,
                Constants.CACHE_TTL_SCORE,
                new TypeReference<ScoreAnalysisVO>() {
                },
                () -> loadAnalysis(task),
                false);
        // 2) 逐生预热个人成绩缓存：只覆盖有提交记录的学生（查分高峰的真实来源）
        List<HomeworkSubmission> submissions = getBaseMapper().selectByHomeworkId(homeworkId);
        int warmed = 0;
        for (HomeworkSubmission sub : submissions) {
            MyHomeworkDetailVO detail = redisCacheUtils.objectOrLoad(
                    Constants.CACHE_SCORE_STUDENT + homeworkId + ":" + sub.getStudentId(),
                    Constants.CACHE_TTL_SCORE,
                    new TypeReference<MyHomeworkDetailVO>() {
                    },
                    () -> buildMyDetail(task, sub),
                    false);
            if (detail != null) {
                warmed++;
            }
        }
        log.info("成绩发布预热完成：作业 {} 分析缓存已重建，个人成绩缓存预热 {} 份", homeworkId, warmed);
    }

    /**
     * 标记逾期未交（Spring Task 每 10 分钟入口，Batch 4）。
     * <p>两步幂等：先补插"已截止且未发布"作业中从未提交学生的未交占位
     * （NOT EXISTS + uk_hw_stu 双重防重），再把其中"从未真实提交"
     * （status=0 且 submit_time IS NULL）的记录置 2。已发布成绩的作业
     * 不改写（成绩口径已冻结）；截止后真实补交（status=2 且 submit_time
     * 非空）不受影响，也不会被修正后的 countSubmitted 误计为已交。</p>
     * <p>无需失效缓存：已交人数/提交率的分子一律走 countSubmitted 口径，
     * 本动作不改变任何已缓存的统计值；逾期标记只影响教师名单页的红色
     * "逾期"角标，名单页本就实时查库。</p>
     *
     * @return 被标记为逾期未交的记录数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markOverdueUnsubmitted() {
        // 第一步：补插未交占位（幂等，重跑不产生重复行）
        int inserted = getBaseMapper().insertNotSubmittedPlaceholders();
        // 第二步：把"从未真实提交"的占位标记为逾期未交(2)
        int marked = getBaseMapper().markOverdueUnsubmitted();
        if (inserted > 0 || marked > 0) {
            log.info("逾期标记任务完成：补插未交占位 {} 条，标记逾期 {} 条", inserted, marked);
        } else {
            log.debug("逾期标记任务本轮无事可做（无新增逾期）");
        }
        return marked;
    }

    /**
     * 投递截止前催交邮件消息（Spring Task 每日 8:30 入口，Batch 4）。
     * <p>候选由 Mapper 圈定（deadline ∈ (NOW, NOW+24h] 且未发布成绩）；
     * 逐作业向 homework.mail.batch 投递 TYPE_URGE 消息，收件人（未交学生）
     * 由邮件消费者实时查库判定——本方法不查学生，避免把整班邮箱塞进消息。
     * 单作业投递失败仅 WARN 跳过，不影响其余作业（定时任务下一轮或人工重投兜底）。</p>
     *
     * @return 成功投递的催交消息数
     */
    @Override
    public int dispatchUrgingMails() {
        List<HomeworkTask> urging = homeworkTaskMapper.selectUrgingTasks();
        if (urging.isEmpty()) {
            log.debug("催交任务：当前没有距截止不足24小时的作业");
            return 0;
        }
        int sent = 0;
        for (HomeworkTask task : urging) {
            try {
                // 定时任务无事务上下文且消息为"即时提醒"语义：直接投递（不挂 afterCommit）
                rabbitTemplate.convertAndSend(
                        Constants.EXCHANGE_HOMEWORK, Constants.ROUTING_MAIL_BATCH,
                        new MailMessage(task.getId(), MailMessage.TYPE_URGE));
                sent++;
                log.info("催交消息已投递：作业 {}（截止 {}）", task.getId(), task.getDeadline());
            } catch (Exception e) {
                // 单作业投递失败仅跳过：其余作业照常催交
                log.warn("催交消息投递失败（作业 {} 跳过）: {}", task.getId(), e.getMessage());
            }
        }
        log.info("催交任务完成：候选 {} 个作业，成功投递 {} 条消息", urging.size(), sent);
        return sent;
    }

    /**
     * 事务提交后投递 MQ 消息（与 Redis 失效同一时机：数据真正落库后才通知异步任务）。
     * <p>无事务上下文时立即发送（理论上不会发生：本类两处调用点均在
     * {@code @Transactional} 内）。投递异常仅 ERROR 记录、不向上抛出——
     * 事务已提交，数据是权威状态；MQ 不可用时查重/预热退化为"下一次
     * 触发动作时重算"，绝不让异步旁路拖垮已成功的业务主流程。</p>
     *
     * @param action 投递动作（在 afterCommit 回调中执行）
     */
    private void sendAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        // 消息丢失可接受：查重/预热均为幂等旁路任务，后续动作会再次触发
                        log.error("事务提交后 MQ 投递失败（本次异步任务降级跳过）: {}", e.getMessage());
                    }
                }
            });
        } else {
            try {
                action.run();
            } catch (Exception e) {
                log.error("MQ 投递失败（本次异步任务降级跳过）: {}", e.getMessage());
            }
        }
    }

    /**
     * 教师归属校验：作业存在且为当前教师发布，返回作业实体。
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录用户
     * @return 作业实体
     */
    private HomeworkTask requireTeacherOwnTask(Long homeworkId, LoginUserDTO operator) {
        if (!Constants.ROLE_TEACHER.equals(operator.getRole())) {
            throw new ForbiddenException("该功能仅面向任课教师");
        }
        HomeworkTask task = requireTask(homeworkId);
        if (!operator.getUserId().equals(task.getTeacherId())) {
            throw new ForbiddenException("只能操作自己发布的作业");
        }
        return task;
    }

    /**
     * 作业存在性校验。
     *
     * @param id 作业ID
     * @return 作业实体
     */
    private HomeworkTask requireTask(Long id) {
        HomeworkTask task = homeworkTaskMapper.selectById(id);
        if (task == null) {
            throw new NotFoundException("作业不存在或已被删除");
        }
        return task;
    }

    /**
     * 空白字符串归一化为 null（入库时保持 NULL 而非空串，便于 IS NOT NULL 过滤）。
     *
     * @param value 原始字符串（可为 null）
     * @return trim 后的字符串；空白时返回 null
     */
    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
