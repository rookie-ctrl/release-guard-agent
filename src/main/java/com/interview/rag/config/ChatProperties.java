package com.interview.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话模型配置(rag.llm.chat.*)
 */
@ConfigurationProperties(prefix = "rag.llm.chat")
public record ChatProperties(
        String provider,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens
) {
}
