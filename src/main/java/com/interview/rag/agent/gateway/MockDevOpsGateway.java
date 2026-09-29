package com.interview.rag.agent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockDevOpsGateway implements DevOpsGateway {

    private static final String DEMO_SERVICE = "order-service";
    private static final String DEMO_VERSION = "v2.3.7";
    private final AtomicLong approvalSequence = new AtomicLong(1000);

    @Override
    public Object getReleaseInfo(String serviceName, String version) {
        requireDemoRelease(serviceName, version);
        return Map.of("serviceName", DEMO_SERVICE, "version", DEMO_VERSION,
                "pipelineId", "pipeline-5821", "commitRange", "v2.3.6..v2.3.7",
                "requestedAt", "2026-09-29T14:20:00+08:00", "environment", "production");
    }

    @Override
    public Object getTestReport(String serviceName, String version) {
        requireDemoRelease(serviceName, version);
        return Map.of("unitTestsPassed", 431, "unitTestsFailed", 4,
                "coveragePercent", 87, "integrationTestsPassed", 28,
                "pipelineStatus", "SUCCESS", "reportUrl", "mock://pipeline-5821/tests");
    }

    @Override
    public Object getCodeChangeSummary(String serviceName, String version) {
        requireDemoRelease(serviceName, version);
        return Map.of("filesChanged", 18, "additions", 426, "deletions", 91,
                "areas", new String[]{"order query API", "database mapping", "payment integration"},
                "authors", new String[]{"developer-a", "developer-b"});
    }

    @Override
    public Object getDatabaseChanges(String serviceName, String version) {
        requireDemoRelease(serviceName, version);
        return Map.of("changes", new String[]{"ALTER TABLE orders DROP COLUMN legacy_status",
                        "CREATE INDEX idx_orders_created_status ON orders(created_at, status)"},
                "rollbackScriptPresent", false, "backwardCompatible", false);
    }

    @Override
    public Object getServiceDependencies(String serviceName) {
        requireService(serviceName);
        return Map.of("serviceName", DEMO_SERVICE,
                "upstream", new String[]{"api-gateway"},
                "downstream", new String[]{"payment-service", "notification-service"},
                "database", "orders-db",
                "sharedSchemaConsumers", new String[]{"payment-service"});
    }

    @Override
    public Object getServiceMetrics(String serviceName) {
        requireService(serviceName);
        return Map.of("errorRatePercent", 0.4, "p95LatencyMs", 182,
                "cpuPercent", 41, "currentTrafficRps", 820,
                "businessWindow", "PEAK");
    }

    @Override
    public Object createReleaseApproval(JsonNode request) {
        String serviceName = request.path("serviceName").asText();
        String version = request.path("version").asText();
        requireDemoRelease(serviceName, version);
        return Map.of("approvalId", "REL-" + approvalSequence.incrementAndGet(),
                "serviceName", serviceName, "version", version, "status", "PENDING_REVIEW");
    }

    private void requireDemoRelease(String serviceName, String version) {
        if (!DEMO_SERVICE.equals(serviceName) || !DEMO_VERSION.equals(version)) {
            throw new IllegalArgumentException("演示数据仅包含 order-service v2.3.7");
        }
    }

    private void requireService(String serviceName) {
        if (!DEMO_SERVICE.equals(serviceName)) {
            throw new IllegalArgumentException("演示数据仅包含 order-service");
        }
    }
}
