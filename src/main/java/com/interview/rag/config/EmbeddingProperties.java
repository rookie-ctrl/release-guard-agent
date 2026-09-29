package com.interview.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 模型配置(rag.llm.embedding.*)
 */
@ConfigurationProperties(prefix = "rag.llm.embedding")
public record EmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer dimension,
        /** 批量向量化:一次 HTTP 携带的文本条数(请求数 = 块数 / batchSize) */
        Integer batchSize,
        /** 批量请求的最大并行度(有界并行,防止打爆上游 RPM) */
        Integer parallelism,
        /** 批量请求失败重试次数(429/网络抖动,指数退避) */
        Integer maxRetries
) {
}
