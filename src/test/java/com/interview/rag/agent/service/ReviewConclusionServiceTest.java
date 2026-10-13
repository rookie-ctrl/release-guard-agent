package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewConclusionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolInvocationRepository tools = mock(ToolInvocationRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    private final AgentStepRepository steps = mock(AgentStepRepository.class);
    private final ReviewConclusionService service = new ReviewConclusionService(mapper, tools, runs, sessions, steps);
    private final AgentRun run = new AgentRun();
    private final List<AgentStep> delivered = new ArrayList<>();
    private final String head = "a".repeat(40);
    private ObjectNode context;

    @BeforeEach
    void setUp() {
        run.setId("run-1");
        run.setSessionId("session-1");
        var session = new AgentSession();
        session.setId("session-1");
        session.setPullRequestContextJson("{\"url\":\"https://github.com/a/b/pull/1\",\"headSha\":\"" + head
                + "\",\"diffBaseSha\":\"" + "b".repeat(40) + "\"}");
        when(sessions.findById("session-1")).thenReturn(Optional.of(session));
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderBySequenceNumberAsc("run-1")).thenReturn(delivered);
        context = mapper.createObjectNode().put("filename", "App.java").put("available", true)
                .put("side", "HEAD").put("sha", head).put("content", "10: ignored\r\n11: actual risk\n");
    }

    private void evidence(String id, String name, com.fasterxml.jackson.databind.JsonNode data) throws Exception {
        var invocation = new ToolInvocation();
        invocation.setId(id);
        invocation.setRunId("run-1");
        invocation.setToolName(name);
        invocation.setStatus(ExecutionStatus.SUCCESS);
        invocation.setResultJson(mapper.writeValueAsString(data));
        when(tools.findById(id)).thenReturn(Optional.of(invocation));
        var step = new AgentStep();
        step.setStepType(AgentStepType.TOOL_CALL);
        step.setStatus(ExecutionStatus.SUCCESS);
        step.setOutputJson(mapper.writeValueAsString(Map.of("evidenceId", id, "tool", name, "data", data)));
        delivered.add(step);
    }

    private String answer(int line, String sha) throws Exception {
        return mapper.writeValueAsString(Map.of("riskLevel", "HIGH", "summary", "存在风险",
                "findings", List.of(Map.of("evidenceId", "e-1", "file", "App.java", "side", "HEAD",
                        "sha", sha, "startLine", line, "endLine", line, "description", "风险说明")),
                "references", List.of(), "unknowns", List.of("其他文件未检查")));
    }

    @Test
    void acceptsActualSourceRangeAndRendersCiFromBackend() throws Exception {
        evidence("e-1", "githubFileContext", context);
        evidence("ci-1", "githubChecks", mapper.createObjectNode().put("page", 1).put("observedResult", "FAILURE"));
        String report = service.validateAndRender(run, answer(10, head));
        assertThat(report).contains("App.java:10-10", "FAILURE", "后端记录", "其他文件未检查");
    }

    @Test
    void rejectsFabricatedLinesAndWrongCommit() throws Exception {
        evidence("e-1", "githubFileContext", context);
        assertThatThrownBy(() -> service.validateAndRender(run, answer(999, head)))
                .hasMessageContaining("实际读取范围");
        assertThatThrownBy(() -> service.validateAndRender(run, answer(10, "c".repeat(40))))
                .hasMessageContaining("不一致");
    }

    @Test
    void rejectsCrossSessionEvidence() throws Exception {
        evidence("e-1", "githubFileContext", context);
        var other = new AgentRun();
        other.setId("run-1");
        other.setSessionId("another-session");
        when(runs.findById("run-1")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.validateAndRender(run, answer(10, head)))
                .hasMessageContaining("当前会话");
    }

    @Test
    void rejectsStoredResultsThatWereOnlyDeliveredAsPreview() throws Exception {
        evidence("e-1", "githubFileContext", context);
        delivered.get(0).setOutputJson("{\"evidenceId\":\"e-1\",\"truncated\":true,\"preview\":\"partial\"}");
        assertThatThrownBy(() -> service.validateAndRender(run, answer(10, head)))
                .hasMessageContaining("未完整传入");
    }

    @Test
    void rejectsFakeAuthorizationFieldsCoercedLineTypesAndMissingEvidence() throws Exception {
        evidence("e-1", "githubFileContext", context);
        String valid = answer(10, head);
        assertThatThrownBy(() -> service.validateAndRender(run, valid.replace("\"startLine\":10", "\"startLine\":\"10\"")))
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> service.validateAndRender(run, valid.substring(0, valid.length() - 1) + ",\"approved\":true}"))
                .hasMessageContaining("未允许字段");
        assertThatThrownBy(() -> service.validateAndRender(run, valid.replace("e-1", "unknown")))
                .hasMessageContaining("不存在");
    }

    @Test
    void checksNormReferenceAgainstActualReturnedDocumentAndChunk() throws Exception {
        evidence("norm-1", "searchKnowledge", mapper.readTree("[{\"document\":\"release.md\",\"chunk\":2}]"));
        var good = Map.of("riskLevel", "MEDIUM", "summary", "规范说明", "findings", List.of(),
                "references", List.of(Map.of("evidenceId", "norm-1", "document", "release.md", "chunk", 2)),
                "unknowns", List.of());
        String json = mapper.writeValueAsString(good);
        assertThat(service.validateAndRender(run, json)).contains("release.md 第3段");
        assertThatThrownBy(() -> service.validateAndRender(run, json.replace("release.md", "invented.md")))
                .hasMessageContaining("不在工具结果");
    }

    @Test
    void checksDiffCoordinatesInsteadOfConfusingPatchOffsetWithSourceLine() throws Exception {
        ObjectNode diff = mapper.createObjectNode().put("filename", "App.java").put("patchAvailable", true);
        diff.putArray("lineCoordinates").addObject().put("patchLine", 2).put("headLine", 10);
        evidence("e-1", "githubDiff", diff);
        assertThat(service.validateAndRender(run, answer(10, head))).contains("App.java:10-10");
        assertThatThrownBy(() -> service.validateAndRender(run, answer(2, head)))
                .hasMessageContaining("实际读取范围");
    }
}
