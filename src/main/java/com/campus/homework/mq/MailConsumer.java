package com.campus.homework.mq;

import com.campus.homework.dto.MailMessage;
import com.campus.homework.service.MailService;
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
 * 批量邮件消费者（Batch 4，绑定 homework.mail.batch.queue）。
 * <p>消息两类：TYPE_SCORE_PUBLISH（成绩发布通知，由 publishScores 在事务
 * 提交后投递）与 TYPE_URGE（截止前催交，由每日 8:30 定时任务投递）。
 * 收件人名单由 {@link MailService} 在消费时实时查库获取，本类只做协议适配：
 * 按类型路由到对应发送方法，再执行手动 ACK。</p>
 * <p>ACK 策略与其余消费者一致：成功 basicAck；异常显式 basicNack(requeue=false)
 * 进入 homework.mail.batch.dlq 人工排查。dev 环境（homework.mail.enabled=false）
 * 时 MailService 仅日志模拟发送、正常返回，消费照常 ACK，不会因本机无 SMTP 报错。</p>
 *
 * @author campus-homework
 */
@Slf4j
// MQ 消费者组件（位于计划书规定的 mq 包）
@Component
// 构造器注入邮件业务服务（收件人查询与投递逻辑在 ServiceImpl 完成）
@RequiredArgsConstructor
public class MailConsumer {

    /** 邮件服务：成绩发布通知与催交邮件的发送入口 */
    private final MailService mailService;

    /**
     * 消费一条邮件消息：按类型发送后手动确认。
     *
     * @param msg           邮件消息（作业ID + 类型）
     * @param message       原始消息（仅用于异常日志记录消息体）
     * @param channel       RabbitMQ 通道（手动 ACK/NACK）
     * @param deliveryTag   投递标签（确认/拒绝的具体消息序号）
     * @throws IOException 通道网络异常（由外层 catch 统一记录）
     */
    @RabbitListener(queues = Constants.QUEUE_MAIL_BATCH)
    public void onMailMessage(MailMessage msg, Message message,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (msg == null || msg.getHomeworkId() == null || msg.getType() == null) {
                // 消息体缺定位键或类型：无法处理，直接拒绝进 DLQ
                log.warn("邮件消息缺少作业ID或类型，直接死信处理: {}", bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            int sent;
            if (msg.getType() == MailMessage.TYPE_SCORE_PUBLISH) {
                log.info("处理成绩发布通知邮件：作业 {}", msg.getHomeworkId());
                sent = mailService.sendScorePublishMail(msg.getHomeworkId());
            } else if (msg.getType() == MailMessage.TYPE_URGE) {
                log.info("处理催交邮件：作业 {}", msg.getHomeworkId());
                sent = mailService.sendUrgeMail(msg.getHomeworkId());
            } else {
                // 未知类型：重投也不会成功，直接死信处理（毒消息快速隔离）
                log.warn("邮件消息类型未知（type={}），直接死信处理: {}", msg.getType(), bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            log.info("邮件消息处理完成：作业 {} 共发送 {} 封", msg.getHomeworkId(), sent);
            // 处理成功：单条确认（prefetch=1，逐条 ACK 公平分发）
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("邮件消费失败（进入死信队列人工排查）: {} | body={}",
                    e.getMessage(), bodyOf(message), e);
            // requeue=false：配合队列 DLX 参数进入 homework.mail.batch.dlq
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
