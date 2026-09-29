package com.interview.rag.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.agent")
public record AgentProperties(
        int maxSteps,
        int maxToolCalls,
        int runTimeoutSeconds,
        int recentMessageLimit,
        int summaryTriggerCount
) {
}
