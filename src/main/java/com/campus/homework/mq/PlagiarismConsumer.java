package com.campus.homework.mq;

import com.campus.homework.dto.PlagiarismMessage;
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
 * 异步查重消费者（Batch 4，绑定 homework.plagiarism.queue）。
 * <p>消费流程：收到 (homeworkId, studentId) 定位消息后调用
 * {@link HomeworkSubmissionService#plagiarismCheck}，按最新落库正文执行
 * N-Gram + 余弦相似度比对并回写查重结论。消息在提交事务 afterCommit 后
 * 产生，故此处读到的必是已提交数据；同一 (作业,学生) 重算幂等，可安全重投。</p>
 * <p>ACK 策略（application.yml 为 manual ACK）：处理成功 basicAck；
 * 处理异常 basicNack(requeue=false)——队列已挂 DLX，消息直接进入
 * homework.plagiarism.dlq 供人工排查重投，而非无限重入业务队列。
 * 业务异常一律在方法内捕获，不让异常上抛触发容器级重试（手动 ACK 下
 * 容器重试耗尽默认 requeue，会造成死循环；显式 nack 才是确定性路径）。</p>
 *
 * @author campus-homework
 */
@Slf4j
// MQ 消费者组件（位于计划书规定的 mq 包）
@Component
// 构造器注入查重业务服务
@RequiredArgsConstructor
public class PlagiarismConsumer {

    /** 作业提交服务：提供查重执行与结论回写入口（业务逻辑在 ServiceImpl 完成） */
    private final HomeworkSubmissionService homeworkSubmissionService;

    /**
     * 消费一条查重消息：执行查重回写后手动确认。
     *
     * @param msg           查重定位消息（作业ID + 学生ID）
     * @param message       原始消息（仅用于异常日志记录消息体）
     * @param channel       RabbitMQ 通道（手动 ACK/NACK）
     * @param deliveryTag   投递标签（确认/拒绝的具体消息序号）
     * @throws IOException 通道网络异常（由外层 catch 统一记录）
     */
    @RabbitListener(queues = Constants.QUEUE_PLAGIARISM)
    public void onPlagiarismMessage(PlagiarismMessage msg, Message message,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (msg == null || msg.getHomeworkId() == null || msg.getStudentId() == null) {
                // 消息体缺定位键：无法处理，直接拒绝进 DLQ（不浪费重试）
                log.warn("查重消息缺少定位键，直接死信处理: {}", bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            log.info("开始异步查重：作业 {} 学生 {}", msg.getHomeworkId(), msg.getStudentId());
            homeworkSubmissionService.plagiarismCheck(msg.getHomeworkId(), msg.getStudentId());
            // 处理成功：单条确认（prefetch=1，逐条 ACK 公平分发）
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("查重消费失败（进入死信队列人工排查）: {} | body={}",
                    e.getMessage(), bodyOf(message), e);
            // requeue=false：配合队列 DLX 参数进入 homework.plagiarism.dlq，避免毒消息循环
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
