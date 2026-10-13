package com.interview.rag.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Set<String> PR_TOOLS = Set.of("githubPullRequest", "githubFiles", "githubDiff",
            "githubFileContext", "githubChecks", "searchKnowledge");
    private static final Set<String> DEMO_TOOLS = Set.of("getReleaseInfo", "getTestReport",
            "getCodeChangeSummary", "getDatabaseChanges", "getServiceDependencies",
            "getServiceMetrics", "createReleaseApproval", "searchKnowledge");

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> registeredTools) {
        this.tools = registeredTools.stream().collect(Collectors.toUnmodifiableMap(
                tool -> tool.definition().name(), Function.identity()));
    }

    public AgentTool get(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new ToolExecutionException("未注册或不允许调用的工具: " + name);
        }
        return tool;
    }

    public List<ToolSpecification> specifications() {
        return tools.values().stream().map(tool -> tool.definition().specification()).toList();
    }

    public boolean allowedInMode(String name, boolean pullRequestMode) {
        AgentTool tool = tools.get(name);
        return tool != null && (pullRequestMode ? PR_TOOLS : DEMO_TOOLS).contains(name)
                && tool.definition().riskLevel() != ToolRiskLevel.FORBIDDEN
                && (!pullRequestMode || tool.definition().riskLevel() == ToolRiskLevel.READ_ONLY);
    }

    public List<ToolSpecification> specifications(boolean pullRequestMode) {
        return tools.values().stream().filter(tool -> allowedInMode(tool.definition().name(), pullRequestMode))
                .map(tool -> tool.definition().specification()).toList();
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }
}
