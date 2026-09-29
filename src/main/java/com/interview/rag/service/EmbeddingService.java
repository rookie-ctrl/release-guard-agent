package com.interview.rag.service;

import com.interview.rag.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文本向量化封装
 *
 * 面试要点:
 * - 单条 embed 用于查询向量化(问答链路),批量 embedBatch 用于入库(吞吐优化)
 * - 为什么批量优先于并行:embedAll 一次 HTTP 携带多条文本,请求数降一个数量级,
 *   限流压力、延迟、网络开销同步下降(千万字 2.5 万块:2.5 万次请求 → 约 780 次)
 * - 为什么并行要有界:上游免费额度有 RPM 限制,裸并行会把 429 放大、失败更快;
 *   固定线程池把全局在途请求数压住,多个文档同时解析也不会叠加打爆上游
 * - 为什么重试:批量请求失败(429/网络抖动)重试指数退避,避免整篇文档作废
 */
@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final int maxRetries;
    /** 有界并行池:全局共享,限制同时在途的批量请求数 */
    private final ExecutorService executor;

    public EmbeddingService(EmbeddingModel embeddingModel, EmbeddingProperties properties) {
        this.embeddingModel = embeddingModel;
        this.maxRetries = properties.maxRetries() == null ? 3 : properties.maxRetries();
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.parallelism() == null ? 4 : properties.parallelism()),
                r -> {
                    Thread t = new Thread(r, "embedding-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 单条文本 → 向量(BGE-M3:1024 维,已归一化),用于查询向量化
     */
    public float[] embed(String text) {
        Response<Embedding> response = embeddingModel.embed(text);
        return response.content().vector();
    }

    /** 批量向量化(提交到有界线程池,失败含重试) */
    public CompletableFuture<List<float[]>> embedBatchAsync(List<String> texts) {
        return CompletableFuture.supplyAsync(() -> embedBatch(texts), executor);
    }

    /** 批量文本 → 向量列表(与入参顺序一致),失败按指数退避重试 */
    public List<float[]> embedBatch(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Response<List<Embedding>> response = embeddingModel.embedAll(segments);
                return response.content().stream().map(Embedding::vector).toList();
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    long backoffMs = 500L * (1L << (attempt - 1)); // 0.5s / 1s / 2s ...
                    log.warn("批量向量化失败(第 {}/{} 次): {},{}ms 后重试",
                            attempt, maxRetries, e.getMessage(), backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException("批量向量化失败(已重试 " + maxRetries + " 次): " + lastError.getMessage(), lastError);
    }
}
