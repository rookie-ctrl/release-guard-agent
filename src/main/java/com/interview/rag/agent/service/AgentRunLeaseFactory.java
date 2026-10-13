package com.interview.rag.agent.service;

import com.interview.rag.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;

@Service
public class AgentRunLeaseFactory {
    private final AgentRunLock lock;
    private final RunExecutionService executions;
    private final AgentProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, task -> {
        Thread thread = new Thread(task, "agent-run-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public AgentRunLeaseFactory(AgentRunLock lock, RunExecutionService executions, AgentProperties properties) {
        this.lock = lock;
        this.executions = executions;
        this.properties = properties;
    }

    public AgentRunLease acquire(String runId) {
        String token = lock.acquire(runId);
        if (token == null) return null;
        boolean claimed = false;
        try {
            if (!lock.isHeld(runId, token) || !executions.claim(runId, token, properties.runTimeoutSeconds())) {
                return null;
            }
            AgentRunLease lease = new AgentRunLease(runId, token, lock, executions,
                    executions.remainingBudget(runId, token), System::nanoTime);
            lease.start(scheduler);
            claimed = true;
            return lease;
        } finally {
            if (!claimed) {
                try { executions.relinquish(runId, token); }
                finally { lock.release(runId, token); }
            }
        }
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); }
}
