package com.campus.homework.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.entity.MonthlySubmitStat;
import com.campus.homework.exception.BusinessException;
import com.campus.homework.exception.ForbiddenException;
import com.campus.homework.exception.NotFoundException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.HomeworkSubmissionMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.MonthlySubmitStatMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.MonthlySubmitStatService;
import com.campus.homework.utils.Constants;
import com.campus.homework.vo.MonthlyStatVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 月度提交统计业务实现：教师看板趋势数据读取 + 定时任务幂等写入与月度聚合。
 * <p>Batch 4 起本类同时承担"每月 1 号生成上月报表"的聚合计算（aggregateMonth）：
 * 逐班统计当月作业数、平均提交率、平均分/及格率后一律经 upsert 幂等落库
 * （存在则更新、不存在则插入，重跑不产生重复数据）。统计口径与教师端
 * "已交 x/y"、成绩分析页完全一致（同一分母、同一分子、同一及格判定），
 * 保证看板与单作业分析不会出现两套数字。</p>
 *
 * @author campus-homework
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlySubmitStatServiceImpl extends ServiceImpl<MonthlySubmitStatMapper, MonthlySubmitStat>
        implements MonthlySubmitStatService {

    /** 及格线：得分率达到 60% 视为及格（与成绩分析页同一口径） */
    private static final BigDecimal PASS_LINE = new BigDecimal("60");

    /** 百分制换算基数 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 班级表 Mapper：班级存在性校验、名称回填与月度聚合遍历全部班级 */
    private final ClazzInfoMapper clazzInfoMapper;

    /** 作业表 Mapper：教师查看非自己授课班级时拒绝；月度聚合取当月作业 */
    private final HomeworkTaskMapper homeworkTaskMapper;

    /** 提交表 Mapper：月度聚合的已交人数与已批改成绩样本来源 */
    private final HomeworkSubmissionMapper homeworkSubmissionMapper;

    /** 用户表 Mapper：月度聚合的提交率分母（班级在册正常学生数） */
    private final SysUserMapper sysUserMapper;

    /**
     * 查询某班级最近 N 个月统计（教师限自己授课班级；管理员不限）。
     *
     * @param clazzId  班级ID
     * @param months   最近月数（1-24）
     * @param operator 当前登录用户
     * @return 统计 VO 列表（按月份升序，前端直接绘制折线）
     */
    @Override
    public List<MonthlyStatVO> recentMonths(Long clazzId, int months, LoginUserDTO operator) {
        // 参数边界校验：月数 1-24，防止非法值拖库或负数 LIMIT 报错
        if (months < 1 || months > 24) {
            throw new BusinessException(400, "查询月数必须在1-24之间");
        }
        ClazzInfo clazz = clazzInfoMapper.selectById(clazzId);
        if (clazz == null) {
            throw new NotFoundException("班级不存在或已被删除");
        }
        String role = operator.getRole();
        if (Constants.ROLE_STUDENT.equals(role)) {
            // 学生仅可查看自己班级的学业统计
            if (operator.getClazzId() == null || !operator.getClazzId().equals(clazzId)) {
                throw new ForbiddenException("只能查看自己班级的学业统计");
            }
        } else if (Constants.ROLE_TEACHER.equals(role)) {
            // 教师仅可查看自己授课（已发布过作业）班级的统计
            Long ownTasks = homeworkTaskMapper.selectCount(new LambdaQueryWrapper<HomeworkTask>()
                    .eq(HomeworkTask::getTeacherId, operator.getUserId())
                    .eq(HomeworkTask::getClazzId, clazzId));
            if (ownTasks == null || ownTasks == 0) {
                throw new ForbiddenException("只能查看自己授课班级的学业统计");
            }
        } else if (!Constants.ROLE_ADMIN.equals(role)) {
            throw new ForbiddenException("当前角色无权查看学业统计");
        }
        // Mapper 内 LIMIT #{months} 取最近 N 个月，再在内存翻转为月份升序供前端直接绘制
        List<MonthlySubmitStat> rows = getBaseMapper().selectRecentMonths(clazzId, months);
        List<MonthlyStatVO> vos = rows.stream().map(s -> toVO(s, clazz)).collect(Collectors.toList());
        vos.sort(Comparator.comparing(MonthlyStatVO::getStatMonth));
        return vos;
    }

    /**
     * 幂等写入某班级某月统计（定时任务调用：存在则更新、不存在则插入）。
     *
     * @param stat 统计实体（clazz_id + stat_month 定位）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsert(MonthlySubmitStat stat) {
        // 先按联合唯一键定位既有记录（uk_stat_clazz_month 命中）
        MonthlySubmitStat existing = getBaseMapper().selectByClazzAndMonth(stat.getClazzId(), stat.getStatMonth());
        if (existing == null) {
            // 不存在：插入新统计行
            if (stat.getCreateTime() == null) {
                stat.setCreateTime(LocalDateTime.now());
            }
            save(stat);
            log.info("生成月度统计: clazz={}, month={}", stat.getClazzId(), stat.getStatMonth());
        } else {
            // 存在：覆盖更新统计值（重跑幂等，不产生重复数据）
            stat.setId(existing.getId());
            if (stat.getCreateTime() == null) {
                stat.setCreateTime(existing.getCreateTime());
            }
            updateById(stat);
            log.info("更新月度统计: clazz={}, month={}", stat.getClazzId(), stat.getStatMonth());
        }
    }

    /**
     * 聚合生成指定月份的全量班级统计（Spring Task 每月 1 号 00:00 入口，Batch 4）。
     * <p>系统内部调用，无登录态、不适用 IDOR 校验。逐班处理：当月无作业
     * 则跳过（不生成全零行，避免看板出现无意义的 0 值月份）；有作业则
     * 按契约口径计算三项指标后一律经 upsert 幂等落库（重跑覆盖同值）。</p>
     * <p>口径说明：提交率分母为班级在册正常学生数（与教师端"已交 x/y"
     * 同一分母）；分子走修正后的 countSubmitted（定时逾期标记不算已交）；
     * 平均分/及格率仅统计"成绩已发布"作业的已批改样本，当月无已发布
     * 成绩时两字段为 null（与表结构 NULL 语义一致，看板折线自然断点）。</p>
     *
     * @param statMonth 统计月份（yyyy-MM，由定时任务传入上月）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void aggregateMonth(String statMonth) {
        // 全部班级（定时任务无登录态，逐班聚合是系统级批处理，不适用 IDOR）
        List<ClazzInfo> classes = clazzInfoMapper.selectList(null);
        int generated = 0;
        for (ClazzInfo clazz : classes) {
            // 该班当月发布的作业（按 create_time 归属月份）
            List<HomeworkTask> tasks = homeworkTaskMapper.selectTasksInMonth(clazz.getId(), statMonth);
            if (tasks.isEmpty()) {
                // 当月无作业：不生成全零行（看板只呈现有教学活动的月份）
                continue;
            }
            // 提交率分母：班级在册正常学生数（与教师端列表同一口径）
            int denominator = sysUserMapper.countActiveStudents(clazz.getId());
            // 提交率累加器：逐作业计算后对作业取平均（各作业难度/要求不同，按作业平均）
            BigDecimal rateSum = BigDecimal.ZERO;
            int rateCount = 0;
            // 成绩样本池：仅"成绩已发布"作业的已批改记录（未发布作业的分数不参与）
            BigDecimal scoreSum = BigDecimal.ZERO;
            int gradedCount = 0;
            int passCount = 0;
            for (HomeworkTask task : tasks) {
                // 分子：真实提交数（按时交/已批改/截止后补交计入；定时逾期标记不算）
                int submitted = homeworkSubmissionMapper.countSubmitted(task.getId());
                if (denominator > 0) {
                    rateSum = rateSum.add(new BigDecimal(submitted).multiply(HUNDRED)
                            .divide(new BigDecimal(denominator), 2, RoundingMode.HALF_UP));
                    rateCount++;
                }
                // 成绩样本：仅已发布成绩的作业，已批改(3)且分数非空的记录
                if (task.getIsScorePublished() != null && task.getIsScorePublished() == Constants.SCORE_PUBLISHED) {
                    BigDecimal totalScore = new BigDecimal(task.getTotalScore());
                    for (HomeworkSubmission sub : homeworkSubmissionMapper.selectByHomeworkId(task.getId())) {
                        if (sub.getSubmitStatus() != null && sub.getSubmitStatus() == Constants.SUBMIT_GRADED
                                && sub.getScore() != null) {
                            scoreSum = scoreSum.add(sub.getScore());
                            gradedCount++;
                            // 及格判定：得分率 ≥ 60%（与成绩分析页同一口径）
                            if (sub.getScore().multiply(HUNDRED).divide(totalScore, 10, RoundingMode.HALF_UP)
                                    .compareTo(PASS_LINE) >= 0) {
                                passCount++;
                            }
                        }
                    }
                }
            }
            MonthlySubmitStat stat = new MonthlySubmitStat();
            stat.setClazzId(clazz.getId());
            stat.setStatMonth(statMonth);
            stat.setTotalHomeworkNum(tasks.size());
            // 平均提交率：作业平均，保留2位小数；在册人数为0时记 0（防御除零）
            stat.setAvgSubmitRate(rateCount == 0 ? BigDecimal.ZERO
                    : rateSum.divide(new BigDecimal(rateCount), 2, RoundingMode.HALF_UP));
            if (gradedCount > 0) {
                // 有已发布成绩样本：均值保留1位小数、及格率保留2位小数
                stat.setAvgScore(scoreSum.divide(new BigDecimal(gradedCount), 1, RoundingMode.HALF_UP));
                stat.setPassRate(new BigDecimal(passCount).multiply(HUNDRED)
                        .divide(new BigDecimal(gradedCount), 2, RoundingMode.HALF_UP));
            } else {
                // 无已发布成绩：保持 null（表结构允许，看板折线断点，不误导为 0 分）。
                // 注：upsert 走 MyBatis-Plus updateById 的 NOT_NULL 策略会跳过 null 字段，
                // 但"成绩发布"是单向动作、已批改样本只增不减，value->null 的回退在
                // 同一 (班级,月份) 上实际不会发生，null->value 的正常覆盖不受影响。
                stat.setAvgScore(null);
                stat.setPassRate(null);
            }
            // createTime 不显式赋值：由 upsert 统一处理（插入=当前时间，更新=保留原生成时间）
            upsert(stat);
            generated++;
        }
        log.info("月度统计聚合完成：月份 {} 共处理 {} 个班级（当月有作业的班级 {} 个）",
                statMonth, classes.size(), generated);
    }

    /**
     * Entity -> VO：回填班级名称，供看板多班切换时展示。
     *
     * @param stat  统计实体
     * @param clazz 已加载的班级实体
     * @return 统计 VO
     */
    private MonthlyStatVO toVO(MonthlySubmitStat stat, ClazzInfo clazz) {
        MonthlyStatVO vo = new MonthlyStatVO();
        vo.setId(stat.getId());
        vo.setClazzId(stat.getClazzId());
        vo.setClazzName(clazz.getClazzName());
        vo.setStatMonth(stat.getStatMonth());
        vo.setTotalHomeworkNum(stat.getTotalHomeworkNum());
        vo.setAvgSubmitRate(stat.getAvgSubmitRate());
        vo.setAvgScore(stat.getAvgScore());
        vo.setPassRate(stat.getPassRate());
        vo.setCreateTime(stat.getCreateTime());
        return vo;
    }
}
