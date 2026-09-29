package com.interview.rag.mq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentRunProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String queueName;

    public AgentRunProducer(RabbitTemplate rabbitTemplate,
                            @Value("${rag.mq.agent-run-queue}") String queueName) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueName = queueName;
    }

    public void send(String runId) {
        rabbitTemplate.convertAndSend(queueName, new AgentRunJob(runId));
    }
}
