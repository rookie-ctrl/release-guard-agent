package com.interview.rag.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 装配:文档解析任务队列
 *
 * Spring Boot 自动配置会把这里的 MessageConverter Bean 应用到 RabbitTemplate 和监听容器,
 * 消息体用 JSON 序列化。
 */
@Configuration
public class RabbitConfig {

    @Value("${rag.mq.queue}")
    private String queueName;

    @Value("${rag.mq.agent-run-queue}")
    private String agentRunQueueName;

    @Value("${rag.mq.agent-run-dlq}")
    private String agentRunDlqName;

    /** 文档解析任务队列(持久化,服务重启不丢消息) */
    @Bean
    public Queue documentParseQueue() {
        return new Queue(queueName, true);
    }

    @Bean
    public Queue agentRunDeadLetterQueue() {
        return QueueBuilder.durable(agentRunDlqName).build();
    }

    @Bean
    public Queue agentRunQueue() {
        return QueueBuilder.durable(agentRunQueueName)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", agentRunDlqName)
                .build();
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
