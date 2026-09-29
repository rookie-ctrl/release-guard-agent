package com.interview.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ReleaseGuard 微服务发布变更评审 Agent
 *
 * 两条核心链路:
 * 1. 入库链路:上传文档 → MQ 异步 → Tika 解析 → 分块 → Embedding → ES 索引
 * 2. 问答链路:提问 → 语义缓存 → 混合检索(BM25 + kNN,RRF 融合)→ Prompt 拼装 → 大模型 SSE 流式回答 + 溯源
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
