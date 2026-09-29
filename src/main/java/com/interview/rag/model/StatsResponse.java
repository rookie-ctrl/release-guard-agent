package com.interview.rag.model;

/**
 * 今日用量统计
 */
public record StatsResponse(
        long totalQueries,
        long totalTokens,
        long cacheHits,
        double cacheHitRate,
        long documentCount
) {
}
