package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.repository.AgentRunRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.tool.AgentTool;
import com.interview.rag.agent.tool.ToolDefinition;
import com.interview.rag.agent.tool.ToolExecutionException;

import java.util.function.BiFunction;

public record GitHubAgentTool(ToolDefinition definition, AgentRunRepository runs, AgentSessionRepository sessions,
                              ObjectMapper objectMapper, BiFunction<PullRequestSnapshot, JsonNode, Object> action)
        implements AgentTool {
    @Override
    public Object execute(JsonNode arguments) {
        throw new ToolExecutionException("GitHub 工具必须绑定 PR 评审会话");
    }

    @Override
    public Object execute(String runId, JsonNode arguments) {
        var run = runs.findById(runId).orElseThrow(() -> new ToolExecutionException("执行不存在"));
        var session = sessions.findById(run.getSessionId())
                .orElseThrow(() -> new ToolExecutionException("会话不存在"));
        if (session.getPullRequestContextJson() == null) {
            throw new ToolExecutionException("请先创建 GitHub PR 评审会话");
        }
        try {
            PullRequestSnapshot snapshot = objectMapper.readValue(session.getPullRequestContextJson(), PullRequestSnapshot.class);
            return action.apply(snapshot, arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ToolExecutionException("PR 会话上下文无效", e);
        }
    }
}
