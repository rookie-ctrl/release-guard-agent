package com.interview.rag.mq;

import com.interview.rag.agent.service.AgentRunLock;
import com.interview.rag.agent.service.AgentRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentRunConsumer {

    private final AgentRunner agentRunner;
    private final AgentRunLock runLock;

    public AgentRunConsumer(AgentRunner agentRunner, AgentRunLock runLock) {
        this.agentRunner = agentRunner;
        this.runLock = runLock;
    }

    @RabbitListener(queues = "${rag.mq.agent-run-queue}", concurrency = "2-4")
    public void consume(AgentRunJob job) {
        String lockToken = runLock.acquire(job.runId());
        if (lockToken == null) {
            log.info("Agent Run {} 已由其他消费者处理，确认重复投递", job.runId());
            return;
        }
        try {
            agentRunner.process(job.runId());
        } finally {
            runLock.release(job.runId(), lockToken);
        }
    }
}
