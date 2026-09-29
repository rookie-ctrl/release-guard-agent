package com.interview.rag.agent.model;

import java.util.List;

public record ReleaseReviewContext(
        String serviceName,
        String releaseVersion,
        String riskLevel,
        List<String> findings,
        List<String> decisions,
        List<String> completedChecks,
        List<String> pendingItems
) {
}
