package com.interview.rag.agent.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRunLeaseTest {
    @Test
    void renewsBeyondOriginal120SecondsButStopsAt180SecondBudget() {
        AgentRunLock lock = mock(AgentRunLock.class);
        RunExecutionService executions = mock(RunExecutionService.class);
        AtomicLong clock = new AtomicLong();
        AgentRunLease lease = new AgentRunLease("run", "token", lock, executions, Duration.ofSeconds(180), clock::get);
        when(lock.renew("run", "token")).thenReturn(true);
        when(executions.renew("run", "token")).thenReturn(true);
        when(lock.isHeld("run", "token")).thenReturn(true);
        when(executions.isOwner("run", "token")).thenReturn(true);
        for (int second : new int[]{30, 60, 90, 120, 150}) {
            clock.set(Duration.ofSeconds(second).toNanos());
            lease.renew();
            lease.check();
        }
        verify(lock, times(5)).renew("run", "token");
        clock.set(Duration.ofSeconds(180).toNanos());
        lease.renew();
        assertThatThrownBy(lease::check).isInstanceOf(ExecutionLostException.class);
        verify(lock, times(5)).renew("run", "token");
    }

    @Test
    void failedRenewalAndNetworkErrorsPermanentlyRevokeExecution() {
        for (boolean networkError : new boolean[]{false, true}) {
            AgentRunLock lock = mock(AgentRunLock.class);
            RunExecutionService executions = mock(RunExecutionService.class);
            AgentRunLease lease = new AgentRunLease("run", "old", lock, executions, Duration.ofSeconds(180), () -> 0L);
            if (networkError) when(lock.renew("run", "old")).thenThrow(new IllegalStateException("network"));
            lease.renew();
            when(lock.isHeld("run", "old")).thenReturn(true);
            when(executions.isOwner("run", "old")).thenReturn(true);
            assertThatThrownBy(lease::check).isInstanceOf(ExecutionLostException.class);
            verify(executions, never()).renew(anyString(), anyString());
            lease.close();
            verify(lock).release("run", "old");
        }
    }
}
