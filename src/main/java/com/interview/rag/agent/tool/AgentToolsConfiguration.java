package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.interview.rag.agent.gateway.DevOpsGateway;
import com.interview.rag.service.EmbeddingService;
import com.interview.rag.service.HybridSearchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;
import java.util.Map;

@Configuration
public class AgentToolsConfiguration {

    @Bean
    AgentTool releaseInfoTool(DevOpsGateway gateway) {
        return tool("getReleaseInfo", "查询发布版本、流水线和提交范围", ToolRiskLevel.READ_ONLY,
                new String[]{"serviceName", "version"}, args -> gateway.getReleaseInfo(
                        args.path("serviceName").asText(), args.path("version").asText()));
    }

    @Bean
    AgentTool testReportTool(DevOpsGateway gateway) {
        return tool("getTestReport", "查询指定版本的单元测试和集成测试报告", ToolRiskLevel.READ_ONLY,
                new String[]{"serviceName", "version"}, args -> gateway.getTestReport(
                        args.path("serviceName").asText(), args.path("version").asText()));
    }

    @Bean
    AgentTool codeChangeTool(DevOpsGateway gateway) {
        return tool("getCodeChangeSummary", "查询指定版本的代码变更摘要", ToolRiskLevel.READ_ONLY,
                new String[]{"serviceName", "version"}, args -> gateway.getCodeChangeSummary(
                        args.path("serviceName").asText(), args.path("version").asText()));
    }

    @Bean
    AgentTool databaseChangesTool(DevOpsGateway gateway) {
        return tool("getDatabaseChanges", "查询数据库迁移内容及回滚脚本情况", ToolRiskLevel.READ_ONLY,
                new String[]{"serviceName", "version"}, args -> gateway.getDatabaseChanges(
                        args.path("serviceName").asText(), args.path("version").asText()));
    }

    @Bean
    AgentTool dependenciesTool(DevOpsGateway gateway) {
        return tool("getServiceDependencies", "查询服务上下游依赖及共享数据库消费者", ToolRiskLevel.READ_ONLY,
                new String[]{"serviceName"}, args -> gateway.getServiceDependencies(
                        args.path("serviceName").asText()));
    }

    @Bean
    AgentTool metricsTool(DevOpsGateway gateway) {
        return tool("getServiceMetrics", "查询服务当前错误率、延迟、CPU和流量", ToolRiskLevel.READ_ONLY,
                new String[]{"serviceName"}, args -> gateway.getServiceMetrics(
                        args.path("serviceName").asText()));
    }

    @Bean
    AgentTool createApprovalTool(DevOpsGateway gateway) {
        return tool("createReleaseApproval", "创建发布审批单。仅在用户明确同意创建后调用。",
                ToolRiskLevel.HIGH_RISK, new String[]{"serviceName", "version", "riskLevel", "summary"},
                gateway::createReleaseApproval);
    }

    @Bean
    AgentTool knowledgeSearchTool(EmbeddingService embeddingService, HybridSearchService searchService) {
        ToolDefinition definition = ToolDefinitionFactory.stringArguments("searchKnowledge",
                "检索指定类型和服务的发布规范、故障复盘或运维手册，返回相关原文和出处",
                ToolRiskLevel.READ_ONLY, java.util.List.of("query", "documentType"),
                java.util.List.of("serviceName"));
        return new GatewayAgentTool(definition, args -> {
                    try {
                        var fragments = searchService.hybridSearch(args.path("query").asText(),
                                embeddingService.embed(args.path("query").asText()),
                                args.path("documentType").asText(),
                                args.hasNonNull("serviceName") ? args.path("serviceName").asText() : null);
                        return fragments.stream().map(fragment -> Map.of(
                                "document", fragment.docName(),
                                "chunk", fragment.chunkIndex(),
                                "content", fragment.content(),
                                "similarity", fragment.similarity())).toList();
                    } catch (Exception e) {
                        throw new ToolExecutionException("知识库检索失败", e);
                    }
                });
    }

    private AgentTool tool(String name, String description, ToolRiskLevel risk, String[] required,
                           Function<JsonNode, Object> executor) {
        ToolDefinition definition = ToolDefinitionFactory.stringArguments(name, description, risk, required);
        return new GatewayAgentTool(definition, executor);
    }
}
