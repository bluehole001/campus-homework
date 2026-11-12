package com.campus.homework.config.mq;

import com.campus.homework.utils.Constants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（Batch 4，与 Constants 中的 MQ 契约常量一一对应）。
 * <p>拓扑结构（计划书 @backend-architect 契约）：</p>
 * <pre>
 * homework.direct.exchange ─┬─ homework.plagiarism.queue      ─┐
 *                           ├─ homework.score.stat.queue       ├─(TTL 60s 后)─> homework.dlx.exchange
 *                           └─ homework.mail.batch.queue       ─┘        └─> 3 个死信队列（人工排查）
 * </pre>
 * <p>三个业务队列均挂 x-dead-letter-exchange 指向 DLX、x-dead-letter-routing-key
 * 指向对应 .dead 路由键；配合 application.yml 的消费端重试（3 次、指数退避）与
 * 手动 ACK，消费重试耗尽后消息进入死信队列而非丢弃，保证"查重/预热/邮件"
 * 三类异步任务可追溯、可人工重投。</p>
 * <p>声明的 Queue/Exchange/Binding 由 Boot 自动声明器在启动时幂等声明
 * （已存在则校验参数一致），重复启动不会报错。</p>
 *
 * @author campus-homework
 */
// MQ 拓扑配置类（独立于 Web/Redis/Security 配置，职责单一）
@Configuration
public class RabbitMQConfig {

    // ==================== 交换机 ====================

    /**
     * 业务直连交换机：承载查重/成绩统计/批量邮件三类路由键。
     *
     * @return 直连交换机声明（非持久化标志此处同样持久化，保证重启后拓扑不丢）
     */
    @Bean
    public DirectExchange homeworkExchange() {
        // durable=true：Broker 重启后交换机仍存在，避免消息路由失败
        return new DirectExchange(Constants.EXCHANGE_HOMEWORK, true, false);
    }

    /**
     * 死信交换机（DLX）：业务队列消费重试耗尽的消息经死信路由进入 DLQ。
     *
     * @return DLX 直连交换机声明
     */
    @Bean
    public DirectExchange homeworkDlxExchange() {
        return new DirectExchange(Constants.EXCHANGE_HOMEWORK_DLX, true, false);
    }

    // ==================== 业务队列 ====================

    /**
     * 异步查重队列：学生提交作业后，正文与同班其他已提交文本做余弦相似度比对。
     *
     * @return 队列声明（死信参数指向 DLX 与查重死信路由键）
     */
    @Bean
    public Queue plagiarismQueue() {
        return QueueBuilder.durable(Constants.QUEUE_PLAGIARISM)
                // 消费重试耗尽后进入 DLX，按 .dead 路由键投递到对应 DLQ
                .deadLetterExchange(Constants.EXCHANGE_HOMEWORK_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_PLAGIARISM_DEAD)
                .build();
    }

    /**
     * 成绩分析队列：成绩发布后重算成绩分析并全量预热 Redis 查分缓存。
     *
     * @return 队列声明（死信参数指向 DLX 与成绩统计死信路由键）
     */
    @Bean
    public Queue scoreStatQueue() {
        return QueueBuilder.durable(Constants.QUEUE_SCORE_STAT)
                .deadLetterExchange(Constants.EXCHANGE_HOMEWORK_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_SCORE_STAT_DEAD)
                .build();
    }

    /**
     * 批量邮件队列：成绩发布通知与截止前催交邮件异步投递，不阻塞业务主流程。
     *
     * @return 队列声明（死信参数指向 DLX 与邮件死信路由键）
     */
    @Bean
    public Queue mailBatchQueue() {
        return QueueBuilder.durable(Constants.QUEUE_MAIL_BATCH)
                .deadLetterExchange(Constants.EXCHANGE_HOMEWORK_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_MAIL_BATCH_DEAD)
                .build();
    }

    // ==================== 死信队列 ====================

    /**
     * 查重死信队列：消费失败的查重消息落在此处供人工排查重投。
     *
     * @return 纯死信队列（不再挂死信参数，避免死信循环）
     */
    @Bean
    public Queue plagiarismDlq() {
        return QueueBuilder.durable(Constants.QUEUE_PLAGIARISM_DLQ).build();
    }

    /**
     * 成绩统计死信队列。
     *
     * @return 纯死信队列
     */
    @Bean
    public Queue scoreStatDlq() {
        return QueueBuilder.durable(Constants.QUEUE_SCORE_STAT_DLQ).build();
    }

    /**
     * 邮件死信队列。
     *
     * @return 纯死信队列
     */
    @Bean
    public Queue mailBatchDlq() {
        return QueueBuilder.durable(Constants.QUEUE_MAIL_BATCH_DLQ).build();
    }

    // ==================== 绑定（业务路由 -> 业务队列；死信路由 -> DLQ） ====================

    /**
     * 绑定查重路由键到查重队列。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding plagiarismBinding() {
        return BindingBuilder.bind(plagiarismQueue()).to(homeworkExchange())
                .with(Constants.ROUTING_PLAGIARISM);
    }

    /**
     * 绑定成绩统计路由键到成绩统计队列。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding scoreStatBinding() {
        return BindingBuilder.bind(scoreStatQueue()).to(homeworkExchange())
                .with(Constants.ROUTING_SCORE_STAT);
    }

    /**
     * 绑定批量邮件路由键到邮件队列。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding mailBatchBinding() {
        return BindingBuilder.bind(mailBatchQueue()).to(homeworkExchange())
                .with(Constants.ROUTING_MAIL_BATCH);
    }

    /**
     * 绑定查重死信路由键到查重 DLQ。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding plagiarismDlqBinding() {
        return BindingBuilder.bind(plagiarismDlq()).to(homeworkDlxExchange())
                .with(Constants.ROUTING_PLAGIARISM_DEAD);
    }

    /**
     * 绑定成绩统计死信路由键到成绩统计 DLQ。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding scoreStatDlqBinding() {
        return BindingBuilder.bind(scoreStatDlq()).to(homeworkDlxExchange())
                .with(Constants.ROUTING_SCORE_STAT_DEAD);
    }

    /**
     * 绑定邮件死信路由键到邮件 DLQ。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding mailBatchDlqBinding() {
        return BindingBuilder.bind(mailBatchDlq()).to(homeworkDlxExchange())
                .with(Constants.ROUTING_MAIL_BATCH_DEAD);
    }

    // ==================== 消息序列化 ====================

    /**
     * JSON 消息转换器：消息体以 Jackson JSON 收发（与全项目 JSON 契约一致），
     * 类型信息随消息头 __TypeId__ 传递，消费端自动还原为强类型 DTO。
     *
     * @return JSON 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 定制 RabbitTemplate：挂载 JSON 转换器，生产端统一以 JSON 投递。
     *
     * @param connectionFactory Boot 自动装配的连接工厂
     * @return 完成转换器装配的 RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        // 手动 ACK 模式下由消费端确认，生产端不开 confirm/returns 回调（YAGNI）
        return template;
    }
}
