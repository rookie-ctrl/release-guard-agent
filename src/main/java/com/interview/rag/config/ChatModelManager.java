package com.interview.rag.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型管理器:根据配置的 provider 选择对应策略
 *
 * Spring 会把所有 ChatModelStrategy 实现注入进来,按 provider() 建立映射,
 * 新厂商接入零改动(只加实现类)。
 */
@Component
public class ChatModelManager {

    private final Map<String, ChatModelStrategy> strategies;
    private final ChatProperties properties;

    public ChatModelManager(List<ChatModelStrategy> strategyList, ChatProperties properties) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ChatModelStrategy::provider, Function.identity()));
        this.properties = properties;
    }

    public StreamingChatModel streamingChatModel() {
        return strategy().build(properties);
    }

    public ChatModel nonStreamingChatModel() {
        return strategy().buildNonStreaming(properties);
    }

    private ChatModelStrategy strategy() {
        ChatModelStrategy strategy = strategies.get(properties.provider());
        if (strategy == null) {
            throw new IllegalStateException("不支持的模型提供方: " + properties.provider()
                    + ",可选: " + strategies.keySet());
        }
        return strategy;
    }
}
