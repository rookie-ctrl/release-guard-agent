package com.interview.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 相关 Bean 装配
 */
@Configuration
public class LlmConfig {

    /**
     * Embedding 模型:SiliconFlow 托管的 BGE-M3(1024 维,中文效果好,有免费额度)
     * 注意:换 embedding 模型时必须同步修改 rag.llm.embedding.dimension(维度与 ES mapping 强一致)
     */
    @Bean
    public EmbeddingModel embeddingModel(EmbeddingProperties p) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(p.baseUrl())
                .apiKey(p.apiKey())
                .modelName(p.model())
                .build();
    }
}
