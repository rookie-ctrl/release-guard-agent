package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.AgentRun;
import com.interview.rag.agent.domain.AgentSession;
import com.interview.rag.agent.repository.AgentRunRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubAgentToolTest {
    @Test
    void scopesToolToSavedSessionInsteadOfModelProvidedRepository() {
        ObjectMapper mapper = new ObjectMapper();
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        PullRequestSnapshot snapshot = new PullRequestSnapshot("https://github.com/a/b/pull/1", "a/b",
                "a".repeat(40), "b".repeat(40), "c".repeat(40));
        AgentRun run = new AgentRun();
        run.setSessionId("session");
        AgentSession session = new AgentSession();
        session.setPullRequestContextJson(mapper.valueToTree(snapshot).toString());
        when(runs.findById("run")).thenReturn(Optional.of(run));
        when(sessions.findById("session")).thenReturn(Optional.of(session));
        AtomicReference<PullRequestSnapshot> seen = new AtomicReference<>();
        ToolDefinition definition = ToolDefinitionFactory.stringArguments("githubFiles", "files", ToolRiskLevel.READ_ONLY);
        GitHubAgentTool tool = new GitHubAgentTool(definition, runs, sessions, mapper, (context, arguments) -> {
            seen.set(context);
            return "ok";
        });
        assertThat(tool.execute("run", mapper.createObjectNode())).isEqualTo("ok");
        assertThat(seen.get()).isEqualTo(snapshot);
        session.setPullRequestContextJson(null);
        assertThatThrownBy(() -> tool.execute("run", mapper.createObjectNode())).hasMessageContaining("PR 评审会话");
    }

    @Test
    void separatesRealGithubToolsFromDemoTools() {
        AgentTool github = new GatewayAgentTool(ToolDefinitionFactory.stringArguments("githubFiles", "files",
                ToolRiskLevel.READ_ONLY), ignored -> "ok");
        AgentTool demo = new GatewayAgentTool(ToolDefinitionFactory.stringArguments("getReleaseInfo", "demo",
                ToolRiskLevel.READ_ONLY), ignored -> "mock");
        AgentTool knowledge = new GatewayAgentTool(ToolDefinitionFactory.stringArguments("searchKnowledge", "knowledge",
                ToolRiskLevel.READ_ONLY), ignored -> "knowledge");
        ToolRegistry registry = new ToolRegistry(List.of(github, demo, knowledge));
        assertThat(registry.specifications(true)).extracting(specification -> specification.name())
                .containsExactlyInAnyOrder("githubFiles", "searchKnowledge");
        assertThat(registry.specifications(false)).extracting(specification -> specification.name())
                .containsExactlyInAnyOrder("getReleaseInfo", "searchKnowledge");
        assertThat(registry.allowedInMode("getReleaseInfo", true)).isFalse();
    }
}
