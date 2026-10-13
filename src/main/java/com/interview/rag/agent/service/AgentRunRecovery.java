package com.interview.rag.agent.service;

import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.AgentRunRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AgentRunRecovery {
    private final AgentRunRepository runs;
    private final AgentOutboxService outbox;
    private final TransactionTemplate transactions;
    private final AgentSessionRepository sessions;
    private final AgentEventService events;

    public AgentRunRecovery(AgentRunRepository runs, AgentOutboxService outbox,
                            PlatformTransactionManager transactionManager, AgentSessionRepository sessions, AgentEventService events) {
        this.runs = runs;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.sessions = sessions;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${rag.agent.run-recovery-poll-ms:30000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        for (AgentRun candidate : runs.findRecoverable(now, now.minusSeconds(120), PageRequest.of(0, 50))) {
            transactions.executeWithoutResult(ignored -> {
                AgentRun run = runs.findLockedById(candidate.getId()).orElseThrow();
                LocalDateTime checkedAt = LocalDateTime.now();
                if (run.getStatus() == AgentStatus.CREATED) {
                    if (run.getUpdatedAt().isAfter(checkedAt.minusSeconds(120))) return;
                    run.setUpdatedAt(checkedAt);
                    runs.save(run);
                    outbox.enqueueRun(run.getId());
                    return;
                }
                if (run.getStatus() != AgentStatus.RUNNING) return;
                if (run.getExecutionDeadlineAt() != null && !run.getExecutionDeadlineAt().isAfter(checkedAt)) {
                    run.setStatus(AgentStatus.FAILED);
                    run.setErrorMessage("Agent 执行超过独立执行截止时间");
                    run.setExecutionToken(null);
                    run.setExecutionLeaseUntil(null);
                    runs.save(run);
                    sessions.findById(run.getSessionId()).ifPresent(session -> {
                        session.setStatus(AgentStatus.FAILED); sessions.save(session);
                    });
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() {
                            try {
                                events.publish(run.getId(), "runFailed", Map.of("message", run.getErrorMessage()));
                                events.complete(run.getId());
                            } catch (RuntimeException e) {
                                log.warn("Run {} 截止状态已保存，失败事件暂未发送", run.getId(), e);
                            }
                        }
                    });
                    return;
                }
                if (run.getExecutionLeaseUntil() == null || run.getExecutionLeaseUntil().isAfter(checkedAt)) return;
                run.setExecutionToken(null);
                run.setExecutionLeaseUntil(null);
                run.setStatus(AgentStatus.CREATED);
                runs.save(run);
                outbox.enqueueRun(run.getId());
            });
        }
    }
}
