package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.config.AgentProperties;
import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.model.ReleaseReviewContext;
import com.interview.rag.agent.repository.AgentMessageRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.repository.ReleaseReviewRepository;
import com.interview.rag.config.ChatModelManager;
import dev.langchain4j.data.message.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ConversationMemoryService {

    private static final String SUMMARY_PROMPT = "将发布评审历史压缩为 JSON。只保留服务名、版本、风险等级、已确认发现、用户决策、已完成检查、待办事项。不要推测；未知字段为 null 或空数组。输出必须符合字段 serviceName, releaseVersion, riskLevel, findings, decisions, completedChecks, pendingItems。";

    private final AgentProperties properties;
    private final AgentMessageRepository messageRepository;
    private final AgentSessionRepository sessionRepository;
    private final ReleaseReviewRepository reviewRepository;
    private final ChatModelManager chatModelManager;
    private final ObjectMapper objectMapper;

    public ConversationMemoryService(AgentProperties properties, AgentMessageRepository messageRepository,
                                    AgentSessionRepository sessionRepository,
                                    ReleaseReviewRepository reviewRepository,
                                    ChatModelManager chatModelManager, ObjectMapper objectMapper) {
        this.properties = properties;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.reviewRepository = reviewRepository;
        this.chatModelManager = chatModelManager;
        this.objectMapper = objectMapper;
    }

    public List<ChatMessage> buildMessages(String sessionId) {
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 会话不存在"));
        compactOlderMessages(session);
        session = sessionRepository.findById(sessionId).orElseThrow();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是 ReleaseGuard 发布评审 Agent。先收集发布版本、测试报告、代码变更、数据库变更、依赖和监控，再结合知识库评估风险。缺少关键信息时向用户追问。仅依据工具结果和用户提供事实，不得编造。风险等级为 LOW、MEDIUM、HIGH。创建发布审批单属于高风险操作，必须请求用户明确确认。\n当前评审状态 JSON："
                + (session.getSummary() == null ? "{}" : session.getSummary())));
        List<AgentMessage> recent = messageRepository.findBySessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, properties.recentMessageLimit()));
        recent.stream().sorted(java.util.Comparator.comparing(AgentMessage::getCreatedAt)).forEach(message -> {
            if (message.getRole() == MessageRole.USER) {
                messages.add(UserMessage.from(message.getContent()));
            } else if (message.getRole() == MessageRole.ASSISTANT) {
                messages.add(AiMessage.from(message.getContent()));
            } else if (message.getRole() == MessageRole.TOOL) {
                messages.add(UserMessage.from("工具观察：" + message.getContent()));
            }
        });
        return messages;
    }

    private void compactOlderMessages(AgentSession session) {
        long count = messageRepository.countBySessionId(session.getId());
        if (count < properties.summaryTriggerCount()) {
            return;
        }
        Long throughId = session.getSummaryThroughMessageId() == null ? 0L : session.getSummaryThroughMessageId();
        long unsummarizedCount = messageRepository.countBySessionIdAndIdGreaterThan(session.getId(), throughId);
        long messagesToCompact = unsummarizedCount - properties.recentMessageLimit();
        if (messagesToCompact <= 0) {
            return;
        }
        List<AgentMessage> oldMessages = messageRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(
                session.getId(), throughId, PageRequest.of(0, (int) Math.min(messagesToCompact, 100)));
        if (oldMessages.isEmpty()) {
            return;
        }
        String history = oldMessages.stream()
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(java.util.stream.Collectors.joining("\n"));
        String prior = session.getSummary() == null ? "{}" : session.getSummary();
        String request = "已有结构化状态:\n" + prior + "\n\n待压缩历史:\n" + history;
        try {
            String summarized = chatModelManager.nonStreamingChatModel().chat(
                    List.of(SystemMessage.from(SUMMARY_PROMPT), UserMessage.from(request))).aiMessage().text();
            ReleaseReviewContext context = objectMapper.readValue(summarized, ReleaseReviewContext.class);
            String contextJson = objectMapper.writeValueAsString(context);
            session.setSummary(contextJson);
            session.setSummaryThroughMessageId(oldMessages.get(oldMessages.size() - 1).getId());
            sessionRepository.save(session);
            ReleaseReview review = reviewRepository.findBySessionId(session.getId()).orElseGet(() -> {
                ReleaseReview newReview = new ReleaseReview();
                newReview.setId(java.util.UUID.randomUUID().toString());
                newReview.setSessionId(session.getId());
                return newReview;
            });
            review.setServiceName(context.serviceName());
            review.setReleaseVersion(context.releaseVersion());
            review.setRiskLevel(context.riskLevel());
            review.setContextJson(contextJson);
            reviewRepository.save(review);
        } catch (Exception e) {
            log.warn("会话 {} 摘要更新失败，继续使用最近消息窗口: {}", session.getId(), e.getMessage());
        }
    }
}
