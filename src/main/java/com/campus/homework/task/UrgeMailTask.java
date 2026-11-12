package com.campus.homework.task;

import com.campus.homework.service.HomeworkSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 截止前催交邮件定时任务（Batch 4，计划书契约 cron：0 30 8 * * ?，每日早 8:30）。
 * <p>职责边界（分层铁律）：本类只做"调度触发 + 异常兜底"；候选圈定
 * （距截止不足 24 小时且成绩未发布的作业）与逐作业 MQ 投递全部在
 * {@link HomeworkSubmissionService#dispatchUrgingMails()} 的 ServiceImpl 中完成，
 * 本类不注入 Mapper、不拼 SQL、不直接发 MQ。</p>
 * <p>为什么选 8:30：早间提醒对学生最有效；与"不足 24 小时"窗口配合，
 * 保证每个临近截止的作业在截止前至少收到一次催交（截止时刻落在
 * 8:30~次日 8:30 之间的作业都会被当日或前一日的任务覆盖）。
 * 收件人（未交学生）由邮件消费者实时查库判定，任务本身不查学生。</p>
 * <p>异常兜底：任务异常仅 ERROR 记录不上抛，下一日照常触发；
 * 单作业投递失败已在 ServiceImpl 内逐条捕获跳过，不会中断整批。</p>
 *
 * @author campus-homework
 */
@Slf4j
// Spring Task 组件（位于计划书规定的 task 包）
@Component
// 构造器注入提交业务服务（仅依赖接口，不直接操作 Mapper）
@RequiredArgsConstructor
public class UrgeMailTask {

    /** 作业提交服务：催交候选圈定与 MQ 投递逻辑在 ServiceImpl 完成 */
    private final HomeworkSubmissionService homeworkSubmissionService;

    /**
     * 每日早 8:30 执行一次催交投递（cron：0 30 8 * * ?）。
     * <p>执行内容：圈定距截止不足 24 小时且成绩未发布的作业，逐作业向
     * homework.mail.batch 队列投递催交消息（TYPE_URGE），由邮件消费者
     * 实时查库判定未交学生后发送。</p>
     */
    @Scheduled(cron = "0 30 8 * * ?")
    public void dispatchUrgingMails() {
        try {
            int sent = homeworkSubmissionService.dispatchUrgingMails();
            if (sent > 0) {
                log.info("[定时任务-催交] 本轮投递催交消息 {} 条", sent);
            }
        } catch (Exception e) {
            // 单轮失败仅记录：下一日 8:30 照常触发（投递幂等，重投安全）
            log.error("[定时任务-催交] 本轮执行失败: {}", e.getMessage(), e);
        }
    }
}
