package com.interview.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.config.ChatModelManager;
import com.interview.rag.domain.QaRecord;
import com.interview.rag.exception.RateLimitException;
import com.interview.rag.model.DocFragment;
import com.interview.rag.model.Reference;
import com.interview.rag.repository.QaRecordRepository;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 问答编排:缓存 → 检索 → 拼装 Prompt → 大模型 SSE 流式生成 → 统计/缓存回写
 *
 * 面试要点:
 * - SSE 用于单向服务端推送(打字机效果),对比 WebSocket 实现简单、HTTP 原生、代理友好
 * - 检索无结果时不调用大模型(直接返回提示)——成本控制
 * - token 用量从 ChatResponse.metadata().tokenUsage() 获取,落库统计
 * - 事件流设计:token(文本增量)/ refs(引用出处)/ cacheHit(缓存命中)/ done / error
 */
@Slf4j
@Service
public class ChatService {

    private final ChatModelManager chatModelManager;
    private final EmbeddingService embeddingService;
    private final HybridSearchService hybridSearchService;
    private final SemanticCacheService semanticCacheService;
    private final RateLimitService rateLimitService;
    private final QaRecordRepository qaRecordRepository;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public ChatService(ChatModelManager chatModelManager,
                       EmbeddingService embeddingService,
                       HybridSearchService hybridSearchService,
                       SemanticCacheService semanticCacheService,
                       RateLimitService rateLimitService,
                       QaRecordRepository qaRecordRepository,
                       ObjectMapper objectMapper,
                       @Value("${rag.llm.system-prompt}") String systemPrompt) {
        this.chatModelManager = chatModelManager;
        this.embeddingService = embeddingService;
        this.hybridSearchService = hybridSearchService;
        this.semanticCacheService = semanticCacheService;
        this.rateLimitService = rateLimitService;
        this.qaRecordRepository = qaRecordRepository;
        this.objectMapper = objectMapper;
        this.systemPrompt = systemPrompt;
    }

    public Flux<ServerSentEvent<String>> streamChat(String question) {
        return Flux.create(sink -> {
            long start = System.currentTimeMillis();
            try {
                // 0. 限流 + 预算检查(先于一切昂贵操作)
                rateLimitService.acquire();
                rateLimitService.checkDailyBudget();

                float[] questionEmbedding = embeddingService.embed(question);

                // 1. 语义缓存:相似问题直接返回
                SemanticCacheService.CachedAnswer cached = semanticCacheService.lookup(questionEmbedding);
                if (cached != null) {
                    log.info("命中语义缓存, 相似度 {}", cached.similarity());
                    sink.next(event("cacheHit", String.format("%.3f", cached.similarity())));
                    sink.next(event("refs", toRefsJson(cached.refs())));
                    sink.next(event("token", cached.answer()));
                    sink.next(event("done", ""));
                    sink.complete();
                    saveRecord(question, 0, 0, 0, true, System.currentTimeMillis() - start);
                    return;
                }

                // 2. 混合检索 + 阈值过滤
                List<DocFragment> fragments = hybridSearchService.hybridSearch(question, questionEmbedding);
                List<Reference> refs = fragments.stream()
                        .map(f -> new Reference(f.docName(), f.chunkIndex(), snippet(f.content())))
                        .toList();
                sink.next(event("refs", toRefsJson(refs)));

                // 3. 无相关内容:不调用大模型,直接降级回答(省 token)
                if (fragments.isEmpty()) {
                    sink.next(event("token", "知识库中没有找到相关内容,请换个问法或先上传相关文档。"));
                    sink.next(event("done", ""));
                    sink.complete();
                    saveRecord(question, 0, 0, 0, false, System.currentTimeMillis() - start);
                    return;
                }

                // 4. 拼装 Prompt:系统约束 + 编号参考资料 + 问题
                String prompt = buildPrompt(question, fragments);
                StringBuilder answer = new StringBuilder();
                StreamingChatModel model = chatModelManager.streamingChatModel();

                model.chat(prompt, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        answer.append(token);
                        sink.next(event("token", token));
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        try {
                            TokenUsage usage = response.metadata() == null
                                    ? null : response.metadata().tokenUsage();
                            long in = usage == null ? 0 : usage.inputTokenCount();
                            long out = usage == null ? 0 : usage.outputTokenCount();
                            long total = usage == null ? 0 : usage.totalTokenCount();
                            rateLimitService.addTokens(total);
                            saveRecord(question, in, out, total, false, System.currentTimeMillis() - start);
                            // 回写语义缓存(带引用出处)
                            semanticCacheService.put(question, answer.toString(), questionEmbedding, refs);
                            sink.next(event("done", ""));
                            sink.complete();
                        } catch (Exception e) {
                            log.error("统计/缓存回写失败", e);
                            sink.next(event("error", "回答已完成,但统计信息记录失败: " + e.getMessage()));
                            sink.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("大模型调用失败", error);
                        sink.next(event("error", "大模型调用失败: " + error.getMessage()));
                        sink.complete();
                    }
                });
            } catch (RateLimitException e) {
                sink.next(event("error", e.getMessage()));
                sink.complete();
            } catch (Exception e) {
                log.error("问答流程异常", e);
                sink.next(event("error", "系统异常: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    private String buildPrompt(String question, List<DocFragment> fragments) {
        StringBuilder sb = new StringBuilder(systemPrompt);
        sb.append("\n\n【参考资料】\n");
        for (int i = 0; i < fragments.size(); i++) {
            DocFragment f = fragments.get(i);
            sb.append("[").append(i + 1).append("]《").append(f.docName())
                    .append("》第").append(f.chunkIndex() + 1).append("段:\n")
                    .append(f.content()).append("\n\n");
        }
        sb.append("【用户问题】\n").append(question);
        return sb.toString();
    }

    private String snippet(String content) {
        return content.length() > 120 ? content.substring(0, 120) + "..." : content;
    }

    private String toRefsJson(List<Reference> refs) {
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void saveRecord(String question, long in, long out, long total,
                            boolean cacheHit, long elapsedMs) {
        try {
            QaRecord record = new QaRecord();
            record.setQuestion(question);
            record.setInputTokens(in);
            record.setOutputTokens(out);
            record.setTotalTokens(total);
            record.setCacheHit(cacheHit);
            record.setElapsedMs(elapsedMs);
            qaRecordRepository.save(record);
        } catch (Exception e) {
            // 统计失败不影响主流程
            log.warn("问答记录落库失败: {}", e.getMessage());
        }
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
