package com.interview.rag.service;

import com.google.common.util.concurrent.RateLimiter;
import com.interview.rag.exception.RateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 限流与成本控制
 *
 * 面试要点:
 * - QPS 限流:Guava 令牌桶(平滑突发流量),放在调用大模型之前
 * - 免费模型 API 有 RPM/QPM 限制,自己不先限流会把额度打爆
 * - 每日 token 预算:Redis INCR 计数(跨实例共享),超预算直接拒绝——成本意识
 * - 预算 key 设 TTL 自动过期,否则每天永久遗留一个 key,Redis 内存缓慢泄漏
 */
@Service
public class RateLimitService {

    private static final String TOKEN_KEY_PREFIX = "rag:stats:tokens:";

    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;
    private final long dailyTokenBudget;
    private final long tokenKeyTtlHours;

    public RateLimitService(StringRedisTemplate redisTemplate,
                            @Value("${rag.rate-limit.qps:5}") double qps,
                            @Value("${rag.rate-limit.daily-token-budget:500000}") long dailyTokenBudget,
                            @Value("${rag.rate-limit.token-key-ttl-hours:48}") long tokenKeyTtlHours) {
        this.redisTemplate = redisTemplate;
        this.rateLimiter = RateLimiter.create(qps);
        this.dailyTokenBudget = dailyTokenBudget;
        this.tokenKeyTtlHours = tokenKeyTtlHours;
    }

    /** QPS 限流:5 秒内拿不到令牌则拒绝 */
    public void acquire() {
        if (!rateLimiter.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new RateLimitException("请求过于频繁,请稍后再试");
        }
    }

    /** 每日 token 预算检查 */
    public void checkDailyBudget() {
        if (todayUsedTokens() >= dailyTokenBudget) {
            throw new RateLimitException("今日 token 预算已用完,请明天再试");
        }
    }

    /** 记录本次消耗的 token(调用大模型之后) */
    public void addTokens(long tokens) {
        if (tokens > 0) {
            // setIfAbsent 只在 key 首次创建时设 TTL(不是滑动过期),
            // 保证「当天」的计数在整个预算周期内不丢,隔天旧 key 自动回收
            redisTemplate.opsForValue().setIfAbsent(tokenKey(), "0", tokenKeyTtlHours, TimeUnit.HOURS);
            redisTemplate.opsForValue().increment(tokenKey(), tokens);
        }
    }

    private long todayUsedTokens() {
        String value = redisTemplate.opsForValue().get(tokenKey());
        return value == null ? 0 : Long.parseLong(value);
    }

    private String tokenKey() {
        return TOKEN_KEY_PREFIX + LocalDate.now();
    }
}
