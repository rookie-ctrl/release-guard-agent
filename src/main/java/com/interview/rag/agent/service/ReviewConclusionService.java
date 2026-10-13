package com.interview.rag.agent.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** Checks citation scope against the exact tool data delivered to the model, not its own assertions. */
@Service
public class ReviewConclusionService {
    public static final String OUTPUT_RULES = """
            最终回复只能是JSON对象，不要Markdown围栏。格式：
            {"riskLevel":"LOW或MEDIUM或HIGH","summary":"分析摘要",
            "findings":[{"evidenceId":"工具结果的evidenceId","file":"文件路径","side":"HEAD或BASE",
            "sha":"对应提交SHA","startLine":1,"endLine":1,"description":"发现说明"}],
            "references":[{"evidenceId":"searchKnowledge结果的evidenceId","document":"原文档名","chunk":0}],
            "unknowns":["未检查的文件或范围、未知事实"]}
            没有证据的发现不能放进findings，只能放进unknowns并说明待确认。
            文件行号必须在对应工具实际返回的范围内。diff用lineCoordinates中的headLine或baseLine；
            HEAD使用headSha，BASE使用diffBaseSha。源码上下文使用工具返回的sha和side。
            references的chunk使用工具返回的零起始编号。截断为preview的证据不能用于精确引用。
            未读取数据不能声称已检查；CI以服务端展示的工具状态为准，不在summary中另行断言通过。
            """;

    private final ObjectMapper mapper;
    private final ToolInvocationRepository tools;
    private final AgentRunRepository runs;
    private final AgentSessionRepository sessions;
    private final AgentStepRepository steps;

    public ReviewConclusionService(ObjectMapper mapper, ToolInvocationRepository tools,
                                   AgentRunRepository runs, AgentSessionRepository sessions,
                                   AgentStepRepository steps) {
        this.mapper = mapper;
        this.tools = tools;
        this.runs = runs;
        this.sessions = sessions;
        this.steps = steps;
    }

    public String validateAndRender(AgentRun run, String answer) throws Exception {
        if (answer == null || answer.length() > 24000) throw invalid("结论为空或过长");
        JsonNode root = mapper.reader()
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(answer);
        validateShape(root);
        Conclusion conclusion = mapper.readerFor(Conclusion.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(root);
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(text(conclusion.riskLevel(), 10))) {
            throw invalid("风险等级无效");
        }
        text(conclusion.summary(), 4000);
        requireList(conclusion.findings(), 30);
        requireList(conclusion.references(), 30);
        requireList(conclusion.unknowns(), 30);
        var evidence = new HashMap<String, Evidence>();
        var runSteps = new HashMap<String, List<AgentStep>>();
        JsonNode snapshot = mapper.readTree(sessions.findById(run.getSessionId()).orElseThrow()
                .getPullRequestContextJson());

        StringBuilder report = new StringBuilder("PR：").append(snapshot.path("url").asText())
                .append("\n提交：").append(snapshot.path("headSha").asText())
                .append("\n风险等级（模型评估）：").append(conclusion.riskLevel())
                .append("\n\n分析摘要（模型生成）：\n").append(conclusion.summary()).append("\n\n发现：\n");
        for (Finding finding : conclusion.findings()) {
            Evidence source = evidence(run, text(finding.evidenceId(), 36), evidence, runSteps);
            validateFinding(finding, source, snapshot);
            report.append("- ").append(text(finding.file(), 1024)).append(":")
                    .append(finding.startLine()).append("-").append(finding.endLine())
                    .append(" [").append(finding.side()).append(" ").append(finding.sha()).append("] ")
                    .append(text(finding.description(), 2000))
                    .append("（证据 ").append(finding.evidenceId()).append("）\n");
        }
        if (conclusion.findings().isEmpty()) report.append("- 无已引用的发现；不代表完整审查或没有风险。\n");
        report.append("\n规范引用：\n");
        for (NormReference reference : conclusion.references()) {
            Evidence source = evidence(run, text(reference.evidenceId(), 36), evidence, runSteps);
            if (!"searchKnowledge".equals(source.tool()) || !source.data().isArray()
                    || reference.chunk() == null || reference.chunk() < 0) throw invalid("规范证据无效");
            String document = text(reference.document(), 500);
            boolean found = false;
            for (JsonNode fragment : source.data()) {
                found |= document.equals(fragment.path("document").asText())
                        && fragment.path("chunk").isInt() && fragment.path("chunk").asInt() == reference.chunk();
            }
            if (!found) throw invalid("规范引用不在工具结果中");
            report.append("- ").append(document).append(" 第").append(reference.chunk() + 1)
                    .append("段（证据 ").append(reference.evidenceId()).append("）\n");
        }
        if (conclusion.references().isEmpty()) report.append("- 未引用规范。\n");
        // Display every CI observation in this execution directly from persisted tool data.
        report.append("\n本轮CI工具状态（后端记录）：\n");
        boolean ciRead = false;
        for (AgentStep step : runSteps.computeIfAbsent(run.getId(), steps::findByRunIdOrderBySequenceNumberAsc)) {
            if (step.getStepType() != AgentStepType.TOOL_CALL || step.getStatus() != ExecutionStatus.SUCCESS) continue;
            JsonNode output = mapper.readTree(step.getOutputJson());
            if (!"githubChecks".equals(output.path("tool").asText()) || !output.has("data")) continue;
            ciRead = true;
            JsonNode data = output.path("data");
            report.append("- 第").append(data.path("page").asInt()).append("页：")
                    .append(data.path("observedResult").asText("UNKNOWN")).append("；证据 ")
                    .append(output.path("evidenceId").asText()).append("\n");
        }
        if (!ciRead) report.append("- UNKNOWN：本轮没有可展示的CI读取结果，不能据此判定通过。\n");
        report.append("\n未知项与未检查范围：\n");
        for (String unknown : conclusion.unknowns()) report.append("- ").append(text(unknown, 1000)).append("\n");
        if (conclusion.unknowns().isEmpty()) report.append("- 模型未列出；不能据此认定所有文件已检查。\n");
        report.append("\n引用已通过工具来源与范围校验；模型的风险判断仍需人工评估。");
        return report.toString();
    }

    private Evidence evidence(AgentRun current, String id, Map<String, Evidence> cache,
                              Map<String, List<AgentStep>> runSteps) throws Exception {
        if (cache.containsKey(id)) return cache.get(id);
        ToolInvocation invocation = tools.findById(id).orElseThrow(() -> invalid("引用的工具调用不存在"));
        AgentRun sourceRun = runs.findById(invocation.getRunId()).orElseThrow(() -> invalid("证据执行不存在"));
        if (!current.getSessionId().equals(sourceRun.getSessionId())
                || invocation.getStatus() != ExecutionStatus.SUCCESS) throw invalid("证据不属于当前会话或未成功");
        JsonNode actual = mapper.readTree(invocation.getResultJson());
        for (AgentStep step : runSteps.computeIfAbsent(sourceRun.getId(), steps::findByRunIdOrderBySequenceNumberAsc)) {
            if (step.getStepType() != AgentStepType.TOOL_CALL || step.getStatus() != ExecutionStatus.SUCCESS) continue;
            JsonNode output = mapper.readTree(step.getOutputJson());
            if (id.equals(output.path("evidenceId").asText())
                    && invocation.getToolName().equals(output.path("tool").asText())
                    && output.has("data") && actual.equals(output.path("data"))) {
                Evidence value = new Evidence(invocation.getToolName(), output.path("data"));
                cache.put(id, value);
                return value;
            }
        }
        throw invalid("证据未完整传入模型上下文，不能精确引用");
    }

    private void validateFinding(Finding finding, Evidence source, JsonNode snapshot) {
        String file = text(finding.file(), 1024);
        String side = text(finding.side(), 10);
        String sha = text(finding.sha(), 40);
        if (!Set.of("HEAD", "BASE").contains(side) || finding.startLine() == null || finding.endLine() == null
                || finding.startLine() < 1 || finding.endLine() < finding.startLine()
                || (long) finding.endLine() - finding.startLine() > 80) throw invalid("发现位置无效");
        if (!file.equals(source.data().path("filename").asText())
                || !sha.equals(snapshot.path("HEAD".equals(side) ? "headSha" : "diffBaseSha").asText())) {
            throw invalid("文件或提交与会话证据不一致");
        }
        Set<Integer> visibleLines = new HashSet<>();
        if ("githubFileContext".equals(source.tool())) {
            if (!source.data().path("available").asBoolean() || source.data().path("lineClipped").asBoolean()
                    || !side.equals(source.data().path("side").asText())
                    || !sha.equals(source.data().path("sha").asText())) throw invalid("源码上下文证据无效");
            for (String line : source.data().path("content").asText().split("\n")) {
                var match = java.util.regex.Pattern.compile("^(\\d+): ").matcher(line);
                if (match.find()) visibleLines.add(Integer.parseInt(match.group(1)));
            }
        } else if ("githubDiff".equals(source.tool())) {
            if (!source.data().path("patchAvailable").asBoolean()) throw invalid("diff不可读取");
            String field = "HEAD".equals(side) ? "headLine" : "baseLine";
            for (JsonNode line : source.data().path("lineCoordinates")) {
                if (line.has(field)) visibleLines.add(line.path(field).asInt());
            }
        } else throw invalid("发现必须引用diff或源码上下文工具");
        for (int line = finding.startLine(); line <= finding.endLine(); line++) {
            if (!visibleLines.contains(line)) throw invalid("发现行号超出实际读取范围");
        }
    }

    private static void validateShape(JsonNode root) {
        objectFields(root, Set.of("riskLevel", "summary", "findings", "references", "unknowns"));
        for (String field : List.of("riskLevel", "summary")) requireText(root.path(field));
        for (String field : List.of("findings", "references", "unknowns")) {
            if (!root.path(field).isArray() || root.path(field).size() > 30) throw invalid("结论列表无效");
        }
        for (JsonNode finding : root.path("findings")) {
            objectFields(finding, Set.of("evidenceId", "file", "side", "sha", "startLine", "endLine", "description"));
            for (String field : List.of("evidenceId", "file", "side", "sha", "description")) requireText(finding.path(field));
            if (!finding.path("startLine").isInt() || !finding.path("endLine").isInt()) throw invalid("行号必须为整数");
        }
        for (JsonNode reference : root.path("references")) {
            objectFields(reference, Set.of("evidenceId", "document", "chunk"));
            requireText(reference.path("evidenceId"));
            requireText(reference.path("document"));
            if (!reference.path("chunk").isInt()) throw invalid("片段编号必须为整数");
        }
        for (JsonNode unknown : root.path("unknowns")) requireText(unknown);
    }

    private static void objectFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) throw invalid("结论对象无效");
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw invalid("结论包含未允许字段");
        });
    }

    private static void requireText(JsonNode node) {
        if (!node.isTextual()) throw invalid("结论字段必须为字符串");
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid("结论字段为空或过长");
        return value;
    }

    private static void requireList(List<?> values, int maximum) {
        if (values == null || values.size() > maximum) throw invalid("结论列表缺失或过长");
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("PR结论校验失败：" + reason);
    }

    record Evidence(String tool, JsonNode data) {}
    record Conclusion(String riskLevel, String summary, List<Finding> findings,
                      List<NormReference> references, List<String> unknowns) {}
    record Finding(String evidenceId, String file, String side, String sha, Integer startLine,
                   Integer endLine, String description) {}
    record NormReference(String evidenceId, String document, Integer chunk) {}
}
