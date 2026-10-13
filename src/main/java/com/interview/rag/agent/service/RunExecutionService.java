package com.interview.rag.agent.service;

import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.AgentRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;

@Service
public class RunExecutionService {
    private final AgentRunRepository runs;

    public RunExecutionService(AgentRunRepository runs) { this.runs = runs; }

    @Transactional
    public boolean claim(String runId, String token, int budgetSeconds) {
        AgentRun run = runs.findLockedById(runId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        if (run.getStatus() != AgentStatus.CREATED && run.getStatus() != AgentStatus.RUNNING) return false;
        if (run.getExecutionToken() != null && run.getExecutionLeaseUntil() != null
                && run.getExecutionLeaseUntil().isAfter(now)) return false;
        if (run.getExecutionDeadlineAt() != null && !run.getExecutionDeadlineAt().isAfter(now)) return false;
        run.setExecutionToken(token);
        run.setExecutionLeaseUntil(now.plusSeconds(120));
        if (run.getExecutionDeadlineAt() == null) run.setExecutionDeadlineAt(now.plusSeconds(budgetSeconds));
        run.setStatus(AgentStatus.RUNNING);
        runs.save(run);
        return true;
    }

    @Transactional
    public boolean renew(String runId, String token) {
        return runs.renewOwned(runId, token, LocalDateTime.now(), LocalDateTime.now().plusSeconds(120)) == 1;
    }

    public boolean isOwner(String runId, String token) {
        return runs.hasExecution(runId, token, LocalDateTime.now());
    }

    public Duration remainingBudget(String runId, String token) {
        AgentRun run = runs.findById(runId).orElseThrow();
        if (!token.equals(run.getExecutionToken()) || run.getExecutionDeadlineAt() == null) {
            throw new ExecutionLostException("执行权已失效");
        }
        return Duration.between(LocalDateTime.now(), run.getExecutionDeadlineAt());
    }

    /** The conditional UPDATE holds the run row until all related writes commit. */
    @Transactional
    public void persist(AgentRun snapshot, AgentRunLease lease, Runnable relatedWrites) {
        lease.check();
        if (runs.updateOwned(snapshot, lease.token(), LocalDateTime.now()) != 1) {
            throw new ExecutionLostException("Agent Run 执行权已失效");
        }
        relatedWrites.run();
    }

    @Transactional
    public void relinquish(String runId, String token) {
        runs.clearExecution(runId, token);
    }
}
