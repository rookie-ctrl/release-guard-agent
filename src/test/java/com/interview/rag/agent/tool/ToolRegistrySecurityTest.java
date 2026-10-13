package com.interview.rag.agent.tool;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistrySecurityTest {
    private AgentTool tool(String name, ToolRiskLevel risk) {
        return new GatewayAgentTool(ToolDefinitionFactory.stringArguments(name, "test", risk),
                ignored -> Map.of());
    }

    @Test
    void prefixDoesNotAuthorizeNewOrUnknownTools() {
        var registry = new ToolRegistry(List.of(tool("githubDeleteRepository", ToolRiskLevel.READ_ONLY),
                tool("githubDiff", ToolRiskLevel.READ_ONLY), tool("createReleaseApproval", ToolRiskLevel.HIGH_RISK)));
        assertThat(registry.allowedInMode("githubDeleteRepository", true)).isFalse();
        assertThat(registry.allowedInMode("githubUnknown", true)).isFalse();
        assertThat(registry.allowedInMode("githubDiff", true)).isTrue();
        assertThat(registry.allowedInMode("createReleaseApproval", true)).isFalse();
        assertThat(registry.allowedInMode("createReleaseApproval", false)).isTrue();
        assertThat(registry.specifications(true)).extracting(spec -> spec.name()).containsExactly("githubDiff");
    }

    @Test
    void prModeRequiresReadOnlyEvenForWhitelistedName() {
        var registry = new ToolRegistry(List.of(tool("githubDiff", ToolRiskLevel.HIGH_RISK)));
        assertThat(registry.allowedInMode("githubDiff", true)).isFalse();
    }
}
