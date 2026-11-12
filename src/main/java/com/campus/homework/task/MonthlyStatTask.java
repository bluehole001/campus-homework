package com.campus.homework.task;

import com.campus.homework.service.MonthlySubmitStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 月度提交统计定时任务（Batch 4，计划书契约 cron：0 0 1 1 * ?，每月 1 号 00:00）。
 * <p>职责边界（分层铁律）：本类只做"调度触发 + 统计月份计算 + 异常兜底"，
 * 逐班聚合（作业数/平均提交率/平均分/及格率）与幂等落库全部在
 * {@link MonthlySubmitStatService#aggregateMonth(String)} 的 ServiceImpl 中完成，
 * 本类不注入 Mapper、不拼 SQL。</p>
 * <p>为什么统计"上月"：每月 1 号 00:00 触发时，刚结束的自然月数据已完整
 * （该月所有作业已截止、批改与成绩发布均已收尾），此时生成上月报表口径
 * 最稳定；若统计当月，月初几天数据必然残缺。月份字符串由 YearMonth
 * 直接格式化为 yyyy-MM，与表字段 stat_month 格式一致。</p>
 * <p>幂等与兜底：aggregateMonth 内部逐班经 upsert 幂等写入（重跑覆盖同值、
 * 不产生重复数据）；任务异常仅 ERROR 记录不上抛，下一月照常触发，
 * 也可人工手动重跑补报。</p>
 *
 * @author campus-homework
 */
@Slf4j
// Spring Task 组件（位于计划书规定的 task 包）
@Component
// 构造器注入月度统计业务服务（仅依赖接口，不直接操作 Mapper）
@RequiredArgsConstructor
public class MonthlyStatTask {

    /** 月度提交统计服务：逐班聚合与幂等落库逻辑在 ServiceImpl 完成 */
    private final MonthlySubmitStatService monthlySubmitStatService;

    /**
     * 每月 1 号 00:00 生成上月统计报表（cron：0 0 1 1 * ?）。
     * <p>执行内容：以刚结束的自然月（上月）为统计月份，逐班聚合当月
     * 作业数、平均提交率、平均分/及格率，经 upsert 幂等写入
     * monthly_submit_stat，供教师看板趋势折线图消费。</p>
     */
    @Scheduled(cron = "0 0 1 1 * ?")
    public void aggregateLastMonth() {
        // 统计月份 = 刚结束的自然月（触发时刻为当月 1 号 00:00，minusMonths(1) 即上月）
        String statMonth = YearMonth.now().minusMonths(1).toString();
        try {
            log.info("[定时任务-月度统计] 开始聚合月份 {} 的全量班级统计", statMonth);
            monthlySubmitStatService.aggregateMonth(statMonth);
        } catch (Exception e) {
            // 单轮失败仅记录：下一月照常触发；也可人工重跑（aggregateMonth 幂等）
            log.error("[定时任务-月度统计] 月份 {} 聚合失败: {}", statMonth, e.getMessage(), e);
        }
    }
}
