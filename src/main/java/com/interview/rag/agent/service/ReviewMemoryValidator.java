package com.interview.rag.agent.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.model.ReleaseReviewContext;

import java.util.List;
import java.util.Set;

/** Validates shape and size only; text within a valid summary remains untrusted data. */
final class ReviewMemoryValidator {
    private static final Set<String> FIELDS = Set.of("serviceName", "releaseVersion", "riskLevel",
            "findings", "decisions", "completedChecks", "pendingItems");

    private ReviewMemoryValidator() {
    }

    static ReleaseReviewContext parse(String json, ObjectMapper mapper) throws Exception {
        if (json == null || json.length() > 16000) {
            throw new IllegalArgumentException("历史摘要为空或超过长度限制");
        }
        JsonNode root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("历史摘要必须为JSON对象");
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("历史摘要包含未允许字段");
            }
        });
        for (String field : List.of("serviceName", "releaseVersion", "riskLevel")) {
            JsonNode value = root.path(field);
            if (!value.isMissingNode() && !value.isNull()
                    && (!value.isTextual() || value.textValue().length() > 200)) {
                throw new IllegalArgumentException("历史摘要字段类型或长度无效");
            }
        }
        JsonNode risk = root.path("riskLevel");
        if (!risk.isMissingNode() && !risk.isNull()
                && !Set.of("LOW", "MEDIUM", "HIGH").contains(risk.textValue())) {
            throw new IllegalArgumentException("历史摘要风险等级无效");
        }
        for (String field : List.of("findings", "decisions", "completedChecks", "pendingItems")) {
            JsonNode values = root.path(field);
            if (values.isMissingNode() || values.isNull()) {
                continue;
            }
            if (!values.isArray() || values.size() > 30) {
                throw new IllegalArgumentException("历史摘要列表类型或数量无效");
            }
            for (JsonNode value : values) {
                if (!value.isTextual() || value.textValue().length() > 1000) {
                    throw new IllegalArgumentException("历史摘要条目类型或长度无效");
                }
            }
        }
        return mapper.treeToValue(root, ReleaseReviewContext.class);
    }
}
