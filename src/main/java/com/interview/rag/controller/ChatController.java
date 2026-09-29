package com.interview.rag.controller;

import com.interview.rag.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 智能问答:SSE 流式输出
 *
 * 事件类型:
 * - token    大模型输出的文本增量
 * - refs     答案引用的文档出处(JSON 数组)
 * - cacheHit 命中语义缓存(值为相似度)
 * - done     本次问答结束
 * - error    出错信息
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String question) {
        return chatService.streamChat(question);
    }
}
