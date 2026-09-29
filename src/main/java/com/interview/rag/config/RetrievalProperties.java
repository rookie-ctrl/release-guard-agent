package com.interview.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索参数配置(rag.retrieval.*)
 */
@ConfigurationProperties(prefix = "rag.retrieval")
public record RetrievalProperties(
        Integer topK,
        Double similarityThreshold
) {
}
