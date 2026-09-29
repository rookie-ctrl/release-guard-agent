package com.interview.rag.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

/**
 * 通义千问策略:阿里 DashScope 的 OpenAI 兼容模式接口(新用户有免费额度)
 */
@Component
public class QwenChatModelStrategy implements ChatModelStrategy {

    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @Override
    public String provider() {
        return "qwen";
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
