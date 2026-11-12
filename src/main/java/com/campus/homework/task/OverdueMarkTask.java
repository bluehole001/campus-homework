package com.campus.homework.task;

import com.campus.homework.service.HomeworkSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 逾期未交标记定时任务（Batch 4，计划书契约 cron：0 0/10 * * * ?，每 10 分钟）。
 * <p>职责边界（分层铁律）：本类只做"调度触发 + 异常兜底"，两步幂等动作
 * （补插未交占位 -> 标记逾期）全部在
 * {@link HomeworkSubmissionService#markOverdueUnsubmitted()} 的 ServiceImpl
 * 中完成，本类不注入 Mapper、不拼 SQL。</p>
 * <p>为什么每 10 分钟：教师端名单页与月度统计的"未交/逾期"口径依赖
 * 截止后的状态刷新；10 分钟粒度足够让"已交 x/y"与逾期标记在作业截止后
 * 一小时内收敛，同时避免每分钟全表 UPDATE 带来的无谓负载。</p>
 * <p>异常兜底：任务异常仅 ERROR 记录不上抛——Spring Task 调度线程捕获后
 * 下一轮照常触发，单轮失败不影响后续轮次（动作幂等，重跑安全）。</p>
 *
 * @author campus-homework
 */
@Slf4j
// Spring Task 组件（位于计划书规定的 task 包）
@Component
// 构造器注入提交业务服务（仅依赖接口，不直接操作 Mapper）
@RequiredArgsConstructor
public class OverdueMarkTask {

    /** 作业提交服务：逾期标记的两步幂等动作在 ServiceImpl 完成 */
    private final HomeworkSubmissionService homeworkSubmissionService;

    /**
     * 每 10 分钟执行一次逾期标记（cron：0 0/10 * * * ?）。
     * <p>执行内容：补插"已截止且成绩未发布"作业中从未提交学生的未交占位，
     * 再把其中"从未真实提交"的记录标记为逾期未交(2)；已发布成绩的作业
     * 不改写，截止后真实补交的记录不受影响。</p>
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void markOverdue() {
        try {
            int marked = homeworkSubmissionService.markOverdueUnsubmitted();
            if (marked > 0) {
                log.info("[定时任务-逾期标记] 本轮标记逾期未交 {} 条", marked);
            }
        } catch (Exception e) {
            // 单轮失败仅记录：下一轮照常触发（动作幂等，重跑安全）
            log.error("[定时任务-逾期标记] 本轮执行失败: {}", e.getMessage(), e);
        }
    }
}
