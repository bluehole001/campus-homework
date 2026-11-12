package com.campus.homework.mq;

import com.campus.homework.dto.ScoreStatMessage;
import com.campus.homework.service.HomeworkSubmissionService;
import com.campus.homework.utils.Constants;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 成绩统计与缓存预热消费者（Batch 4，绑定 homework.score.stat.queue）。
 * <p>消费流程：收到成绩发布事件后调用
 * {@link HomeworkSubmissionService#warmScoreCache}，重算成绩分析写入
 * homework:score:analysis:{homeworkId}，并逐生预热
 * homework:score:stu:{homeworkId}:{studentId}——把"查分高峰"的读压力
 * 提前转移到 MQ 异步消费与 Redis，保护 MySQL（计划书 @backend-architect 契约）。</p>
 * <p>ACK 策略与 PlagiarismConsumer 一致：成功 basicAck；异常显式
 * basicNack(requeue=false) 进入 homework.score.stat.dlq 人工排查。
 * 预热是幂等操作（重复执行只是重建缓存），重投安全。</p>
 *
 * @author campus-homework
 */
@Slf4j
// MQ 消费者组件（位于计划书规定的 mq 包）
@Component
// 构造器注入提交业务服务（预热入口在 ServiceImpl 完成）
@RequiredArgsConstructor
public class ScoreStatConsumer {

    /** 作业提交服务：提供成绩分析与个人成绩缓存预热入口 */
    private final HomeworkSubmissionService homeworkSubmissionService;

    /**
     * 消费一条成绩统计消息：全量预热查分缓存后手动确认。
     *
     * @param msg           成绩统计消息（作业ID）
     * @param message       原始消息（仅用于异常日志记录消息体）
     * @param channel       RabbitMQ 通道（手动 ACK/NACK）
     * @param deliveryTag   投递标签（确认/拒绝的具体消息序号）
     * @throws IOException 通道网络异常（由外层 catch 统一记录）
     */
    @RabbitListener(queues = Constants.QUEUE_SCORE_STAT)
    public void onScoreStatMessage(ScoreStatMessage msg, Message message,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (msg == null || msg.getHomeworkId() == null) {
                // 消息体缺作业ID：无法处理，直接拒绝进 DLQ
                log.warn("成绩统计消息缺少作业ID，直接死信处理: {}", bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            log.info("开始成绩统计与查分缓存预热：作业 {}", msg.getHomeworkId());
            homeworkSubmissionService.warmScoreCache(msg.getHomeworkId());
            // 处理成功：单条确认（prefetch=1，逐条 ACK 公平分发）
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("成绩统计消费失败（进入死信队列人工排查）: {} | body={}",
                    e.getMessage(), bodyOf(message), e);
            // requeue=false：配合队列 DLX 参数进入 homework.score.stat.dlq
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 提取原始消息体文本（仅用于异常日志，编码失败时返回占位串）。
     *
     * @param message 原始消息
     * @return UTF-8 解码后的消息体文本
     */
    private String bodyOf(Message message) {
        if (message == null || message.getBody() == null) {
            return "<empty>";
        }
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
