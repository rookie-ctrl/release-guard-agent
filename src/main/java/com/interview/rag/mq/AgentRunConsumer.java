package com.interview.rag.mq;

import com.interview.rag.agent.service.AgentRunLease;
import com.interview.rag.agent.service.AgentRunLeaseFactory;
import com.interview.rag.agent.service.AgentRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentRunConsumer {

    private final AgentRunner agentRunner;
    private final AgentRunLeaseFactory leases;

    public AgentRunConsumer(AgentRunner agentRunner, AgentRunLeaseFactory leases) {
        this.agentRunner = agentRunner;
        this.leases = leases;
    }

    @RabbitListener(queues = "${rag.mq.agent-run-queue}", concurrency = "2-4")
    public void consume(AgentRunJob job) {
        AgentRunLease lease = leases.acquire(job.runId());
        if (lease == null) {
            log.info("Agent Run {} 已由其他消费者处理，确认重复投递", job.runId());
            return;
        }
        try (lease) {
            agentRunner.process(job.runId(), lease);
        }
    }
}
