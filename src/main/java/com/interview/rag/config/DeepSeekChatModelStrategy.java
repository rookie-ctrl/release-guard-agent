package com.interview.rag.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 策略:OpenAI 兼容协议,便宜且效果好
 */
@Component
public class DeepSeekChatModelStrategy implements ChatModelStrategy {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public StreamingChatModel build(ChatProperties p) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(DEFAULT_BASE_URL)
                .apiKey(p.apiKey())
                .modelName(p.model())
                .temperature(p.temperature())
                .maxTokens(p.maxTokens())
                .build();
    }

    @Override
    public ChatModel buildNonStreaming(ChatProperties p) {
        return OpenAiChatModel.builder()
                .baseUrl(DEFAULT_BASE_URL)
                .apiKey(p.apiKey())
                .modelName(p.model())
                .temperature(p.temperature())
                .maxTokens(p.maxTokens())
                .build();
    }
}
