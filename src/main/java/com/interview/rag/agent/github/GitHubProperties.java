package com.interview.rag.agent.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.github")
public record GitHubProperties(String token, String apiVersion, int timeoutSeconds, int maxResponseBytes) {
    @Override
    public String toString() {
        return "GitHubProperties[tokenConfigured=" + (token != null && !token.isBlank()) + "]";
    }
}
