package com.interview.rag.agent.gateway;

import com.fasterxml.jackson.databind.JsonNode;

public interface DevOpsGateway {

    Object getReleaseInfo(String serviceName, String version);

    Object getTestReport(String serviceName, String version);

    Object getCodeChangeSummary(String serviceName, String version);

    Object getDatabaseChanges(String serviceName, String version);

    Object getServiceDependencies(String serviceName);

    Object getServiceMetrics(String serviceName);

    Object createReleaseApproval(JsonNode request);
}
