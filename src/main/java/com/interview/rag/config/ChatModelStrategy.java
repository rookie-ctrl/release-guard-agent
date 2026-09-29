package com.interview.rag.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;

/**
 * 对话模型策略接口(策略模式)
 *
 * 面试要点:
 * - 各家大模型 API 都兼容 OpenAI 协议,但 baseUrl / 默认模型不同
 * - 通过策略模式把"选哪家模型"从业务代码中剥离,配置一个 provider 即可切换
 * - 新增厂商(如智谱 GLM)只需加一个实现类,符合开闭原则
 */
public interface ChatModelStrategy {

    /** 策略标识,与配置项 rag.llm.chat.provider 对应 */
    String provider();

    /** 根据配置构建流式对话模型 */
    StreamingChatModel build(ChatProperties properties);

    ChatModel buildNonStreaming(ChatProperties properties);
}
