package com.interview.rag.agent.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class AgentRunLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public AgentRunLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String acquire(String runId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                "agent:run:lock:" + runId, token, Duration.ofMinutes(2));
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void release(String runId, String token) {
        redisTemplate.execute(RELEASE_SCRIPT, List.of("agent:run:lock:" + runId), token);
    }
}
