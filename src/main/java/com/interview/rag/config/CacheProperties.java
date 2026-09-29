package com.interview.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语义缓存配置(rag.cache.*)
 */
@ConfigurationProperties(prefix = "rag.cache")
public record CacheProperties(
        Boolean enabled,
        Double similarityThreshold,
        Integer maxSize
) {
}
