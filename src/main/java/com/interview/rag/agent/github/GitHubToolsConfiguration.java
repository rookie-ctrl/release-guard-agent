package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.repository.AgentRunRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.tool.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;

@Configuration
public class GitHubToolsConfiguration {
    private final AgentRunRepository runs;
    private final AgentSessionRepository sessions;
    private final ObjectMapper objectMapper;

    public GitHubToolsConfiguration(AgentRunRepository runs, AgentSessionRepository sessions, ObjectMapper objectMapper) {
        this.runs = runs;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Bean
    AgentTool githubPullRequest(GitHubGateway gateway) {
        return tool("githubPullRequest", "读取当前会话 PR 的元信息、提交 SHA、变更统计。无需服务名或发布版本。",
                List.of(), List.of(), (snapshot, args) -> gateway.pullRequest(snapshot));
    }

    @Bean
    AgentTool githubFiles(GitHubGateway gateway) {
        return tool("githubFiles", "分页读取当前 PR 的文件列表，每页20个。page 默认为1，返回 nextPage 和文件 diffPage。",
                List.of(), List.of("page"), (snapshot, args) -> gateway.files(snapshot, integer(args, "page")));
    }

    @Bean
    AgentTool githubDiff(GitHubGateway gateway) {
        return tool("githubDiff", "读取当前 PR 单文件 unified diff。filename 和 diffPage 来自 githubFiles；page 默认为1。"
                        + "startLine 是 patch 内的行序号，默认1，用 nextStartLine 继续读取。缺失或截断须在结论中说明。",
                List.of("filename"), List.of("page", "startLine"),
                (snapshot, args) -> gateway.diff(snapshot, args.path("filename").asText(),
                        integer(args, "page"), integer(args, "startLine")));
    }

    @Bean
    AgentTool githubFileContext(GitHubGateway gateway) {
        return tool("githubFileContext", "读取当前 PR 仓库中必要的源文件上下文。filename 为仓库相对路径，"
                        + "side 为 HEAD 或 BASE（merge base），默认 HEAD。startLine 为源码行号，默认1。返回固定 SHA 和行号。",
                List.of("filename"), List.of("side", "startLine"),
                (snapshot, args) -> gateway.context(snapshot, args.path("filename").asText(),
                        args.path("side").asText("HEAD"), integer(args, "startLine")));
    }

    @Bean
    AgentTool githubChecks(GitHubGateway gateway) {
        return tool("githubChecks", "读取当前 PR head SHA 的 Checks 和 Commit Status，page默认1。"
                        + "UNKNOWN 表示没有结果或权限不足，不等于测试通过。不能据此编造覆盖率和生产指标。",
                List.of(), List.of("page"), (snapshot, args) -> gateway.checks(snapshot, integer(args, "page")));
    }

    private AgentTool tool(String name, String description, List<String> required, List<String> optional,
                           BiFunction<PullRequestSnapshot, JsonNode, Object> action) {
        ToolDefinition initial = ToolDefinitionFactory.stringArguments(name, description, ToolRiskLevel.READ_ONLY,
                required, optional);
        ToolDefinition definition = new ToolDefinition(name, description, ToolRiskLevel.READ_ONLY,
                Duration.ofSeconds(20), 0, initial.specification());
        return new GitHubAgentTool(definition, runs, sessions, objectMapper, action);
    }

    private static int integer(JsonNode arguments, String name) {
        if (!arguments.hasNonNull(name)) {
            return 1;
        }
        try {
            return Integer.parseInt(arguments.path(name).asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是正整数");
        }
    }
}
