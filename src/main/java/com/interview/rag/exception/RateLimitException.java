package com.interview.rag.exception;

/**
 * 限流异常:触发 QPS 令牌桶或每日 token 预算上限时抛出
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
