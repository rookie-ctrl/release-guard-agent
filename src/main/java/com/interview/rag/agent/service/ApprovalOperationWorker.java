package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.ApprovalOperation;
import com.interview.rag.agent.gateway.*;
import com.interview.rag.agent.repository.ApprovalOperationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class ApprovalOperationWorker {
    private final ApprovalOperationRepository repository;
    private final ApprovalOperationService operations;
    private final DevOpsGateway gateway;
    private final ObjectMapper mapper;
    private final Executor workerExecutor;
    private final Executor requestExecutor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ApprovalOperationWorker(ApprovalOperationRepository repository, ApprovalOperationService operations,
            DevOpsGateway gateway, ObjectMapper mapper, @Qualifier("agentApprovalExecutor") Executor workerExecutor,
            @Qualifier("agentToolExecutor") Executor requestExecutor) {
        this.repository = repository; this.operations = operations; this.gateway = gateway;
        this.mapper = mapper; this.workerExecutor = workerExecutor; this.requestExecutor = requestExecutor;
    }

    @Scheduled(fixedDelayString = "${rag.agent.approval-poll-ms:1000}")
    public void poll() {
        for (ApprovalOperation operation : repository.findRecoverable(LocalDateTime.now(), PageRequest.of(0, 50))) {
            if (!inFlight.add(operation.getId())) continue;
            try {
                workerExecutor.execute(() -> {
                    try { execute(operation.getId()); }
                    catch (RuntimeException e) { log.warn("审批操作 {} 暂未完成，等待恢复", operation.getId(), e); }
                    finally { inFlight.remove(operation.getId()); }
                });
            } catch (RuntimeException e) { inFlight.remove(operation.getId()); }
        }
    }

    public void execute(String id) {
        Optional<ApprovalOperation> claimed = operations.claim(id);
        if (claimed.isEmpty()) return;
        ApprovalOperation attempt = claimed.get();
        CompletableFuture<Object> request = CompletableFuture.supplyAsync(() -> {
            Optional<Object> known = gateway.findReleaseApproval(attempt.externalKey());
            if (known.isPresent()) return known.get();
            if (attempt.getAttemptCount() > 1 && !gateway.supportsApprovalIdempotency()) {
                throw new IllegalStateException("外部不支持幂等且查询未确认结果，继续对账或人工处理");
            }
            try {
                return gateway.createReleaseApproval(attempt.externalKey(), mapper.readTree(attempt.getArgumentsJson()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        }, requestExecutor);
        try {
            Object result = request.get(15, TimeUnit.SECONDS);
            operations.finish(attempt, result);
        } catch (ExecutionLostException e) {
            log.info("审批操作 {} 的当前执行权已过期", id);
        } catch (Exception e) {
            request.cancel(true);
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // Only a rejection reported by the external request is a definitive failure.
            // Local serialization/commit failures after external success remain UNCERTAIN.
            boolean rejected = e instanceof ExecutionException && cause instanceof ApprovalRejectedException;
            try { operations.unresolved(attempt, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), rejected); }
            catch (ExecutionLostException lost) { log.info("审批操作 {} 已由其他工作者接管", id); }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
}
