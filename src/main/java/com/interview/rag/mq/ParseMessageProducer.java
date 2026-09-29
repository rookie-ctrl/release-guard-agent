package com.interview.rag.mq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 解析任务生产者:上传后投递文档 id,由消费者异步解析
 */
@Component
public class ParseMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;

    public ParseMessageProducer(RabbitTemplate rabbitTemplate,
                                @Value("${rag.mq.queue}") String queueName) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueName = queueName;
    }

    public void send(Long documentId) {
        rabbitTemplate.convertAndSend(queueName, documentId);
    }
}
