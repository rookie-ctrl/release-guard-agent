package com.interview.rag.agent.service;

import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

@Slf4j
public final class AgentRunLease implements AutoCloseable {
    private final String runId;
    private final String token;
    private final AgentRunLock lock;
    private final RunExecutionService executions;
    private final LongSupplier ticker;
    private final long deadline;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScheduledFuture<?> renewal;

    AgentRunLease(String runId, String token, AgentRunLock lock, RunExecutionService executions,
                  Duration budget, LongSupplier ticker) {
        this.runId = runId;
        this.token = token;
        this.lock = lock;
        this.executions = executions;
        this.ticker = ticker;
        this.deadline = ticker.getAsLong() + budget.toNanos();
    }

    void start(ScheduledExecutorService scheduler) {
        renewal = scheduler.scheduleWithFixedDelay(this::renew, 30, 30, TimeUnit.SECONDS);
    }

    public String token() { return token; }

    public long remainingMillis() {
        check();
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - ticker.getAsLong()));
    }

    public void check() {
        if (closed.get() || lost.get() || ticker.getAsLong() >= deadline) {
            lost.set(true);
            throw new ExecutionLostException("Agent 执行权失效或已达到执行截止时间");
        }
        try {
            if (!lock.isHeld(runId, token) || !executions.isOwner(runId, token)) {
                throw new ExecutionLostException("Agent Run 已失去锁或执行权");
            }
        } catch (RuntimeException e) {
            lost.set(true);
            throw new ExecutionLostException("无法确认 Agent Run 执行权: " + e.getMessage());
        }
    }

    void renew() {
        if (closed.get() || lost.get()) return;
        try {
            if (ticker.getAsLong() >= deadline || !lock.renew(runId, token)
                    || !executions.renew(runId, token)) {
                lost.set(true);
            }
        } catch (RuntimeException e) {
            lost.set(true);
            log.warn("Run {} 续期失败，当前工作者停止提交", runId, e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (renewal != null) renewal.cancel(false);
        try { executions.relinquish(runId, token); }
        finally {
            try { lock.release(runId, token); }
            catch (RuntimeException e) { log.warn("Run {} 释放锁失败，等待 TTL 回收", runId, e); }
        }
    }
}
