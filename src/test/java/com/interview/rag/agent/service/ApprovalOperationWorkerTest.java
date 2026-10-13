package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.ApprovalOperation;
import com.interview.rag.agent.gateway.*;
import com.interview.rag.agent.repository.ApprovalOperationRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApprovalOperationWorkerTest {
    private final ApprovalOperationService operations = mock(ApprovalOperationService.class);
    private final DevOpsGateway gateway = mock(DevOpsGateway.class);
    private final ApprovalOperationWorker worker = new ApprovalOperationWorker(mock(ApprovalOperationRepository.class),
            operations, gateway, new ObjectMapper(), Runnable::run, Runnable::run);

    @Test
    void unknownResultWithoutIdempotencyNeverTriggersAnotherCreate() {
        ApprovalOperation operation = attempt(2);
        worker.execute(operation.getId());
        verify(gateway, never()).createReleaseApproval(anyString(), any());
        verify(operations).unresolved(eq(operation), contains("继续对账或人工处理"), eq(false));
    }

    @Test
    void localFailureAfterExternalSuccessRemainsUncertain() {
        ApprovalOperation operation = attempt(1);
        Map<String, String> result = Map.of("approvalId", "REL-existing");
        when(gateway.createReleaseApproval(eq(operation.externalKey()), any())).thenReturn(result);
        doThrow(new IllegalArgumentException("local save failed")).when(operations).finish(operation, result);
        worker.execute(operation.getId());
        verify(operations).unresolved(operation, "local save failed", false);
    }

    @Test
    void lookupRecoversExistingApprovalWithoutCreatingAgain() {
        ApprovalOperation operation = attempt(2);
        Map<String, String> result = Map.of("approvalId", "REL-existing");
        when(gateway.findReleaseApproval(operation.externalKey())).thenReturn(Optional.of(result));
        worker.execute(operation.getId());
        verify(gateway, never()).createReleaseApproval(anyString(), any());
        verify(operations).finish(operation, result);
    }

    @Test
    void supportedRetryReusesPersistedOperationKey() {
        ApprovalOperation operation = attempt(2);
        when(gateway.supportsApprovalIdempotency()).thenReturn(true);
        when(gateway.createReleaseApproval(eq(operation.externalKey()), any())).thenReturn(Map.of("approvalId", "REL-1"));
        worker.execute(operation.getId());
        verify(gateway).createReleaseApproval(eq(operation.externalKey()), any());
        verify(operations).finish(eq(operation), any());
    }

    @Test
    void explicitExternalRejectionIsDefinitiveFailure() {
        ApprovalOperation operation = attempt(1);
        when(gateway.createReleaseApproval(eq(operation.externalKey()), any())).thenThrow(new IdempotencyConflictException());
        worker.execute(operation.getId());
        verify(operations).unresolved(eq(operation), contains("相同幂等键"), eq(true));
    }

    private ApprovalOperation attempt(int count) {
        ApprovalOperation operation = new ApprovalOperation(); operation.setId("operation-1");
        operation.setArgumentsJson("{\"serviceName\":\"orders\"}"); operation.setAttemptCount(count);
        when(operations.claim(operation.getId())).thenReturn(Optional.of(operation));
        when(gateway.findReleaseApproval(operation.externalKey())).thenReturn(Optional.empty());
        return operation;
    }
}
