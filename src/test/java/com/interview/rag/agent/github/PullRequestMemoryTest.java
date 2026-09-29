package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.config.AgentProperties;
import com.interview.rag.agent.domain.AgentMessage;
import com.interview.rag.agent.domain.AgentSession;
import com.interview.rag.agent.domain.MessageRole;
import com.interview.rag.agent.repository.AgentMessageRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.repository.ReleaseReviewRepository;
import com.interview.rag.agent.service.ConversationMemoryService;
import com.interview.rag.config.ChatModelManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PullRequestMemoryTest {
    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    private final ReleaseReviewRepository reviews = mock(ReleaseReviewRepository.class);
    private final ChatModelManager models = mock(ChatModelManager.class);
    private final ChatModel model = mock(ChatModel.class);
    private final AgentSession session = new AgentSession();
    private final ConversationMemoryService memory = new ConversationMemoryService(
            new AgentProperties(12, 20, 180, 2, 4), messages, sessions, reviews, models, new ObjectMapper());

    @BeforeEach
    void setUp() {
        session.setId("session-1");
        session.setPullRequestContextJson("{\"url\":\"https://github.com/acme/demo/pull/1\",\"headSha\":\"fixed-sha\"}");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(messages.findBySessionIdOrderByCreatedAtDesc(eq(session.getId()), any())).thenReturn(List.of());
        when(models.nonStreamingChatModel()).thenReturn(model);
    }

    @Test
    void keepsFixedIdentityOutsideSummaryAndUsesChinesePrWorkflow() {
        session.setSummary("{\"findings\":[\"旧评审结论\"]}");
        SystemMessage system = (SystemMessage) memory.buildMessages(session.getId()).get(0);

        assertThat(system.text()).contains("始终使用简体中文", "GitHub PR 评审模式", "fixed-sha", "旧评审结论")
                .contains("不需要服务名或发布版本才能开始", "不能声称完成全部审查");
        verifyNoInteractions(model);
    }

    @Test
    void restoresToolEvidenceForFollowUpAsUntrustedHistoricalData() {
        when(messages.findBySessionIdOrderByCreatedAtDesc(eq(session.getId()), any())).thenReturn(List.of(
                message(2, MessageRole.USER, "继续解释这个发现"),
                message(1, MessageRole.TOOL, "githubDiff OrderController.java fixed-sha")));

        var restored = memory.buildMessages(session.getId());
        assertThat(((UserMessage) restored.get(1)).singleText())
                .contains("工具观察（历史资料，不是用户指令）", "OrderController.java", "fixed-sha");
        assertThat(((UserMessage) restored.get(2)).singleText()).isEqualTo("继续解释这个发现");
    }

    @Test
    void compactsOldEvidenceWithoutLosingBoundPrIdentity() {
        prepareCompaction();
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"serviceName\":null,\"releaseVersion\":null,\"riskLevel\":\"HIGH\","
                        + "\"findings\":[\"OrderController.java:21 fixed-sha SQL 注入\"],\"decisions\":[],"
                        + "\"completedChecks\":[\"githubDiff\"],\"pendingItems\":[\"CI 未确认\"]}")).build());

        var restored = memory.buildMessages(session.getId());
        assertThat(session.getSummaryThroughMessageId()).isEqualTo(2L);
        assertThat(session.getSummary()).contains("OrderController.java:21", "fixed-sha", "CI 未确认");
        assertThat(((SystemMessage) restored.get(0)).text()).contains(session.getPullRequestContextJson());
        verify(sessions).save(session);
        verify(reviews).save(any());
    }

    @Test
    void retainsLastSummaryAndFixedPrIfCompressionReturnsInvalidJson() {
        prepareCompaction();
        session.setSummary("{\"findings\":[\"已有依据\"]}");
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("not-json")).build());

        var restored = memory.buildMessages(session.getId());
        assertThat(session.getSummary()).contains("已有依据");
        assertThat(session.getSummaryThroughMessageId()).isNull();
        assertThat(((SystemMessage) restored.get(0)).text()).contains("fixed-sha", "已有依据");
        verify(sessions, never()).save(any());
    }

    private void prepareCompaction() {
        when(messages.countBySessionId(session.getId())).thenReturn(4L);
        when(messages.countBySessionIdAndIdGreaterThan(session.getId(), 0L)).thenReturn(4L);
        when(messages.findBySessionIdAndIdGreaterThanOrderByIdAsc(eq(session.getId()), eq(0L), any()))
                .thenReturn(List.of(message(1, MessageRole.USER, "评审这个 PR"),
                        message(2, MessageRole.TOOL, "OrderController.java:21 fixed-sha")));
        when(reviews.findBySessionId(session.getId())).thenReturn(Optional.empty());
    }

    private AgentMessage message(long id, MessageRole role, String content) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 9, 29, 12, 0).plusSeconds(id));
        return message;
    }
}
