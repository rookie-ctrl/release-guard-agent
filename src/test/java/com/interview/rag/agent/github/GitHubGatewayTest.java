package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubClient client = mock(GitHubClient.class);
    private final GitHubGateway gateway = new GitHubGateway(client, mapper);
    private final String head = "a".repeat(40);
    private final String base = "b".repeat(40);
    private final String mergeBase = "c".repeat(40);
    private final String pullPath = "/repos/owner/repo/pulls/12";
    private final PullRequestSnapshot snapshot = new PullRequestSnapshot("https://github.com/owner/repo/pull/12",
            "fork/repo", head, base, mergeBase);
    private ObjectNode pull;

    @BeforeEach
    void stubPull() throws Exception {
        pull = (ObjectNode) mapper.readTree("{\"head\":{\"repo\":{\"full_name\":\"fork/repo\",\"private\":false}},"
                + "\"base\":{\"repo\":{\"private\":false}},\"changed_files\":21}");
        ((ObjectNode) pull.path("head")).put("sha", head);
        ((ObjectNode) pull.path("base")).put("sha", base);
        when(client.get(pullPath)).thenReturn(response(pull, false));
    }

    @Test
    void pinsForkHeadAndMergeBaseAtSessionCreation() throws Exception {
        JsonNode compare = mapper.readTree("{\"merge_base_commit\":{\"sha\":\"" + mergeBase + "\"}}");
        when(client.get("/repos/owner/repo/compare/" + base + "..." + head + "?per_page=1"))
                .thenReturn(response(compare, false));
        assertThat(gateway.snapshot(snapshot.url())).isEqualTo(snapshot);
    }

    @Test
    void rejectsPrivatePullEvenWhenTokenCouldReadIt() {
        ((ObjectNode) pull.path("base").path("repo")).put("private", true);
        assertThatThrownBy(() -> gateway.snapshot(snapshot.url())).hasMessageContaining("仅支持公开");
        verify(client, times(1)).get(pullPath);
        verifyNoMoreInteractions(client);
    }

    @Test
    void returnsPaginationAndRenameMetadataWithoutAllPatches() throws Exception {
        JsonNode files = mapper.readTree("[{\"filename\":\"New.java\",\"previous_filename\":\"Old.java\","
                + "\"status\":\"renamed\",\"additions\":1,\"deletions\":0,\"patch\":\"@@ -1 +1 @@\\n+new\"}]");
        when(client.get(pullPath + "/files?per_page=20&page=1")).thenReturn(response(files, true));
        JsonNode result = gateway.files(snapshot, 1);
        assertThat(result.path("nextPage").asInt()).isEqualTo(2);
        assertThat(result.path("files").get(0).path("previousFilename").asText()).isEqualTo("Old.java");
        assertThat(result.path("files").get(0).has("patch")).isFalse();
    }

    @Test
    void refusesDiffIfPullMovesWhileReadingFiles() throws Exception {
        ObjectNode updated = pull.deepCopy();
        ((ObjectNode) updated.path("head")).put("sha", "d".repeat(40));
        when(client.get(pullPath)).thenReturn(response(pull, false), response(updated, false));
        when(client.get(pullPath + "/files?per_page=20&page=1")).thenReturn(response(mapper.readTree("[]"), false));
        assertThatThrownBy(() -> gateway.files(snapshot, 1)).hasMessageContaining("已更新");
    }

    @Test
    void missingPatchDoesNotBecomeAnEmptySafeDiff() throws Exception {
        when(client.get(pullPath + "/files?per_page=20&page=1"))
                .thenReturn(response(mapper.readTree("[{\"filename\":\"image.png\"}]"), false));
        JsonNode result = gateway.diff(snapshot, "image.png", 1, 1);
        assertThat(result.path("patchAvailable").asBoolean()).isFalse();
        assertThat(result.path("reason").asText()).contains("不能据此判定没有风险");
    }

    @Test
    void exposesPatchTruncationAndContinuation() {
        ObjectNode file = mapper.createObjectNode().put("filename", "Order.java").put("additions", 100)
                .put("patch", "@@ -0,0 +1,100 @@\n" + "+line\n".repeat(100));
        when(client.get(pullPath + "/files?per_page=20&page=1"))
                .thenReturn(response(mapper.createArrayNode().add(file), false));
        JsonNode result = gateway.diff(snapshot, "Order.java", 1, 1);
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("nextStartLine").asInt()).isEqualTo(81);
        assertThat(result.path("apiPatchIncomplete").asBoolean()).isFalse();
    }

    @Test
    void readsForkAtHeadAndTargetAtMergeBaseWithSourceLineNumbers() {
        when(client.get("/repos/fork/repo/contents/src/Order.java?ref=" + head))
                .thenReturn(response(textFile("first\nsecond\nthird"), false));
        when(client.get("/repos/owner/repo/contents/src/Order.java?ref=" + mergeBase))
                .thenReturn(response(textFile("old"), false));
        JsonNode current = gateway.context(snapshot, "src/Order.java", "HEAD", 2);
        assertThat(current.path("content").asText()).startsWith("2: second");
        assertThat(current.path("sha").asText()).isEqualTo(head);
        assertThat(gateway.context(snapshot, "src/Order.java", "BASE", 1).path("sha").asText()).isEqualTo(mergeBase);
    }

    @Test
    void rejectsPathTraversalAndBinaryContent() {
        assertThatThrownBy(() -> gateway.context(snapshot, "../secret", "HEAD", 1))
                .hasMessageContaining("相对文件路径");
        when(client.get("/repos/fork/repo/contents/image.png?ref=" + head))
                .thenReturn(response(textFile("text\0binary"), false));
        assertThat(gateway.context(snapshot, "image.png", "HEAD", 1).path("available").asBoolean()).isFalse();
    }

    @Test
    void detectsRepositoryVisibilityChangesBeforeReadingContext() {
        ((ObjectNode) pull.path("head").path("repo")).put("private", true);
        assertThatThrownBy(() -> gateway.context(snapshot, "Order.java", "HEAD", 1)).hasMessageContaining("仅支持公开");
    }

    @Test
    void missingChecksAndStatusesAreUnknown() throws Exception {
        stubChecks("{\"total_count\":0,\"check_runs\":[]}", "{\"state\":\"pending\",\"total_count\":0,\"statuses\":[]}");
        assertThat(gateway.checks(snapshot, 1).path("observedResult").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void failedCheckIsFailureAndPassingCheckIsNotProofOfRequiredChecks() throws Exception {
        stubChecks("{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"failure\"}]}", "{\"statuses\":[]}");
        assertThat(gateway.checks(snapshot, 1).path("observedResult").asText()).isEqualTo("FAILURE");
        stubChecks("{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"}]}", "{\"statuses\":[]}");
        JsonNode result = gateway.checks(snapshot, 1);
        assertThat(result.path("observedResult").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("requiredChecksVerified").asBoolean()).isFalse();
    }

    @Test
    void inaccessibleChecksRemainUnknownRatherThanPassed() throws Exception {
        stubChecks("{\"check_runs\":[]}", "{\"statuses\":[{\"state\":\"success\"}]}");
        when(client.get("/repos/owner/repo/commits/" + head + "/check-runs?per_page=20&page=1"))
                .thenThrow(new GitHubApiException(403, "权限不足"));
        assertThat(gateway.checks(snapshot, 1).path("observedResult").asText()).isEqualTo("UNKNOWN");
    }

    private void stubChecks(String checks, String statuses) throws Exception {
        when(client.get("/repos/owner/repo/commits/" + head + "/check-runs?per_page=20&page=1"))
                .thenReturn(response(mapper.readTree(checks), false));
        when(client.get("/repos/owner/repo/commits/" + head + "/status?per_page=20&page=1"))
                .thenReturn(response(mapper.readTree(statuses), false));
    }

    private ObjectNode textFile(String content) {
        return mapper.createObjectNode().put("type", "file").put("encoding", "base64").put("size", content.length())
                .put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    }

    private GitHubClient.Response response(JsonNode body, boolean next) {
        return new GitHubClient.Response(body, next);
    }
}
