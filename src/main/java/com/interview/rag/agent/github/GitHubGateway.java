package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class GitHubGateway {
    private static final int PAGE_SIZE = 20;
    private final GitHubClient client;
    private final ObjectMapper objectMapper;

    public GitHubGateway(GitHubClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public PullRequestSnapshot snapshot(String url) {
        PullRequestReference reference = PullRequestReference.parse(url);
        JsonNode pull = client.get(reference.apiPath()).body();
        requirePublic(pull);
        String head = sha(pull.path("head").path("sha").asText());
        String base = sha(pull.path("base").path("sha").asText());
        String repository = pull.path("head").path("repo").path("full_name")
                .asText(reference.owner() + "/" + reference.repository());
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("GitHub 返回的 head 仓库无效");
        }
        JsonNode comparison = client.get("/repos/" + reference.owner() + "/" + reference.repository()
                + "/compare/" + base + "..." + head + "?per_page=1").body();
        PullRequestSnapshot snapshot = new PullRequestSnapshot(reference.url(), repository, head, base,
                sha(comparison.path("merge_base_commit").path("sha").asText()));
        stablePull(snapshot);
        return snapshot;
    }

    public ObjectNode pullRequest(PullRequestSnapshot snapshot) {
        JsonNode pull = stablePull(snapshot);
        ObjectNode result = identity(snapshot);
        result.put("title", clip(pull.path("title").asText(), 300));
        result.put("description", clip(pull.path("body").asText(""), 2000));
        result.put("descriptionTruncated", pull.path("body").asText("").length() > 2000);
        result.put("state", pull.path("state").asText());
        result.put("draft", pull.path("draft").asBoolean());
        result.put("merged", pull.path("merged").asBoolean());
        result.put("baseBranch", pull.path("base").path("ref").asText());
        result.put("headBranch", pull.path("head").path("ref").asText());
        result.put("changedFiles", pull.path("changed_files").asInt());
        result.put("additions", pull.path("additions").asInt());
        result.put("deletions", pull.path("deletions").asInt());
        result.set("mergeable", pull.path("mergeable"));
        return result;
    }

    public ObjectNode files(PullRequestSnapshot snapshot, int page) {
        FilePage fetched = filePage(snapshot, page);
        List<ObjectNode> files = new ArrayList<>();
        for (JsonNode file : fetched.body()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("filename", file.path("filename").asText());
            item.put("previousFilename", file.path("previous_filename").asText(""));
            item.put("status", file.path("status").asText());
            item.put("additions", file.path("additions").asInt());
            item.put("deletions", file.path("deletions").asInt());
            item.put("patchAvailable", file.hasNonNull("patch"));
            item.put("diffPage", page);
            files.add(item);
        }
        ObjectNode result = identity(snapshot);
        result.put("page", page);
        result.put("totalFiles", fetched.totalFiles());
        result.put("hasNextPage", fetched.hasNext());
        result.put("nextPage", fetched.hasNext() ? page + 1 : 0);
        result.put("apiFileLimit", 3000);
        result.put("listingIncomplete", fetched.totalFiles() > 3000);
        result.set("files", objectMapper.valueToTree(files));
        return result;
    }

    public ObjectNode diff(PullRequestSnapshot snapshot, String filename, int page, int startLine) {
        validatePath(filename);
        FilePage fetched = filePage(snapshot, page);
        for (JsonNode file : fetched.body()) {
            if (!filename.equals(file.path("filename").asText())) {
                continue;
            }
            ObjectNode result = identity(snapshot);
            result.put("filename", filename);
            result.put("fileStatus", file.path("status").asText());
            result.put("previousFilename", file.path("previous_filename").asText(""));
            result.put("patchAvailable", file.hasNonNull("patch"));
            if (!file.hasNonNull("patch")) {
                result.put("reason", "GitHub 未提供 patch，可能是二进制或过大变更；不能据此判定没有风险");
                return result;
            }
            String patch = file.path("patch").asText();
            long additions = patch.lines().filter(line -> line.startsWith("+")).count();
            long deletions = patch.lines().filter(line -> line.startsWith("-")).count();
            result.put("apiPatchIncomplete", additions != file.path("additions").asInt()
                    || deletions != file.path("deletions").asInt());
            addLines(result, patch, startLine, false, "patch");
            return result;
        }
        throw new IllegalArgumentException("本页找不到该变更文件，请使用文件列表返回的 diffPage");
    }

    public ObjectNode context(PullRequestSnapshot snapshot, String filename, String side, int startLine) {
        stablePull(snapshot);
        validatePath(filename);
        if (!side.equals("HEAD") && !side.equals("BASE")) {
            throw new IllegalArgumentException("side 必须为 HEAD 或 BASE");
        }
        boolean head = side.equals("HEAD");
        PullRequestReference reference = snapshot.reference();
        String repository = head ? snapshot.headRepository() : reference.owner() + "/" + reference.repository();
        String revision = head ? snapshot.headSha() : snapshot.diffBaseSha();
        String encodedPath = String.join("/", java.util.Arrays.stream(filename.split("/"))
                .map(GitHubGateway::encode).toList());
        ObjectNode result = identity(snapshot);
        result.put("repository", repository);
        result.put("filename", filename);
        result.put("sha", revision);
        result.put("side", side);
        result.put("url", "https://github.com/" + repository + "/blob/" + revision + "/" + encodedPath);
        JsonNode file;
        try {
            file = client.get("/repos/" + repository + "/contents/" + encodedPath + "?ref=" + revision).body();
        } catch (GitHubApiException e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            result.put("available", false);
            result.put("reason", "该文件在指定提交中不存在或当前不可读取");
            return result;
        }
        stablePull(snapshot);
        if (!"file".equals(file.path("type").asText()) || !"base64".equals(file.path("encoding").asText())
                || file.path("size").asLong() > 131072) {
            result.put("available", false);
            result.put("reason", "文件非普通文本文件、编码不支持或超过 128 KiB；该上下文未读取");
            return result;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(file.path("content").asText().replaceAll("\\s", ""));
            if (decoded.length > 131072) {
                throw new IllegalArgumentException();
            }
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded)).toString();
            if (content.indexOf('\0') >= 0) {
                throw new IllegalArgumentException();
            }
            result.put("available", true);
            addLines(result, content, startLine, true, "content");
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
            result.put("available", false);
            result.put("reason", "文件不是可读取的 UTF-8 文本");
        }
        return result;
    }

    public ObjectNode checks(PullRequestSnapshot snapshot, int page) {
        validatePage(page);
        stablePull(snapshot);
        PullRequestReference reference = snapshot.reference();
        String prefix = "/repos/" + reference.owner() + "/" + reference.repository()
                + "/commits/" + snapshot.headSha();
        ObjectNode result = identity(snapshot);
        result.put("page", page);
        List<ObjectNode> checks = new ArrayList<>();
        List<ObjectNode> statuses = new ArrayList<>();
        boolean hasNext = false;
        boolean available = true;
        try {
            GitHubClient.Response response = client.get(prefix + "/check-runs?per_page=" + PAGE_SIZE + "&page=" + page);
            hasNext = response.hasNextPage();
            result.put("totalCheckRuns", response.body().path("total_count").asInt());
            for (JsonNode check : response.body().path("check_runs")) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("name", clip(check.path("name").asText(), 200));
                item.put("status", check.path("status").asText());
                item.put("conclusion", check.path("conclusion").asText("UNKNOWN"));
                checks.add(item);
            }
        } catch (GitHubApiException e) {
            available = false;
            result.put("checkRunsError", e.getMessage());
        }
        try {
            GitHubClient.Response response = client.get(prefix + "/status?per_page=" + PAGE_SIZE + "&page=" + page);
            hasNext |= response.hasNextPage();
            result.put("totalStatuses", response.body().path("total_count").asInt());
            result.put("commitStatusState", response.body().path("state").asText());
            for (JsonNode status : response.body().path("statuses")) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("name", clip(status.path("context").asText(), 200));
                item.put("state", status.path("state").asText());
                statuses.add(item);
            }
        } catch (GitHubApiException e) {
            available = false;
            result.put("statusesError", e.getMessage());
        }
        boolean failed = checks.stream().anyMatch(check -> List.of("failure", "timed_out", "cancelled",
                "action_required", "startup_failure", "stale").contains(check.path("conclusion").asText()))
                || statuses.stream().anyMatch(status -> List.of("failure", "error").contains(status.path("state").asText()));
        boolean pending = checks.stream().anyMatch(check -> !"completed".equals(check.path("status").asText()))
                || statuses.stream().anyMatch(status -> "pending".equals(status.path("state").asText()));
        boolean passed = !checks.isEmpty() || !statuses.isEmpty();
        passed &= checks.stream().allMatch(check -> "success".equals(check.path("conclusion").asText()));
        passed &= statuses.stream().allMatch(status -> "success".equals(status.path("state").asText()));
        result.put("observedResult", failed ? "FAILURE" : pending ? "PENDING"
                : !available ? "UNKNOWN" : hasNext || page > 1 ? "PARTIAL" : passed ? "SUCCESS" : "UNKNOWN");
        result.put("hasNextPage", hasNext);
        result.put("nextPage", hasNext ? page + 1 : 0);
        result.put("requiredChecksVerified", false);
        result.put("note", "仅为已读取 CI 状态；缺失不等于通过，不能证明覆盖率、完整测试或生产发布条件");
        result.set("checkRuns", objectMapper.valueToTree(checks));
        result.set("statuses", objectMapper.valueToTree(statuses));
        stablePull(snapshot);
        return result;
    }

    private FilePage filePage(PullRequestSnapshot snapshot, int page) {
        validatePage(page);
        JsonNode pull = stablePull(snapshot);
        GitHubClient.Response response = client.get(snapshot.reference().apiPath()
                + "/files?per_page=" + PAGE_SIZE + "&page=" + page);
        if (!response.body().isArray()) {
            throw new IllegalArgumentException("GitHub 文件列表格式无效");
        }
        stablePull(snapshot);
        return new FilePage(response.body(), pull.path("changed_files").asInt(), response.hasNextPage());
    }

    private JsonNode stablePull(PullRequestSnapshot snapshot) {
        JsonNode pull = client.get(snapshot.reference().apiPath()).body();
        requirePublic(pull);
        if (!snapshot.headSha().equals(pull.path("head").path("sha").asText())
                || !snapshot.baseSha().equals(pull.path("base").path("sha").asText())) {
            throw new IllegalStateException("PR 提交或目标分支已更新，请创建新 PR 评审会话");
        }
        return pull;
    }

    private static void requirePublic(JsonNode pull) {
        JsonNode headRepository = pull.path("head").path("repo");
        if (pull.path("base").path("repo").path("private").asBoolean(true)
                || !headRepository.isMissingNode() && !headRepository.isNull()
                && headRepository.path("private").asBoolean(true)) {
            throw new IllegalArgumentException("当前仅支持公开 GitHub PR；私有仓库需在应用登录和访问控制接入后开放");
        }
    }

    private ObjectNode identity(PullRequestSnapshot snapshot) {
        return objectMapper.valueToTree(snapshot);
    }

    private void addLines(ObjectNode result, String text, int startLine, boolean numbered, String field) {
        String[] lines = text.split("\\n", -1);
        if (startLine < 1 || startLine > lines.length) {
            throw new IllegalArgumentException("startLine 超出可读范围");
        }
        StringBuilder content = new StringBuilder();
        int nextLine = startLine;
        boolean lineClipped = false;
        while (nextLine <= lines.length && nextLine < startLine + 80) {
            String line = (numbered ? nextLine + ": " : "") + lines[nextLine - 1] + "\n";
            if (content.length() + line.length() > 5000) {
                if (content.isEmpty()) {
                    content.append(clip(line, 5000));
                    lineClipped = true;
                    nextLine++;
                }
                break;
            }
            content.append(line);
            nextLine++;
        }
        result.put(field, content.toString());
        result.put("startLine", startLine);
        result.put("totalLines", lines.length);
        result.put("nextStartLine", nextLine <= lines.length ? nextLine : 0);
        result.put("truncated", nextLine <= lines.length || lineClipped);
        result.put("lineClipped", lineClipped);
    }

    private static void validatePath(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 1024 || filename.startsWith("/")
                || filename.contains("\\") || filename.indexOf('\0') >= 0
                || java.util.Arrays.stream(filename.split("/", -1)).anyMatch(part -> part.isEmpty()
                || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("请输入仓库内有效的相对文件路径");
        }
    }

    private static void validatePage(int page) {
        if (page < 1 || page > 150) {
            throw new IllegalArgumentException("page 必须在 1 到 150 之间");
        }
    }

    private static String sha(String value) {
        if (!value.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("GitHub 返回的提交 SHA 无效");
        }
        return value;
    }

    private static String clip(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record FilePage(JsonNode body, int totalFiles, boolean hasNext) {
    }
}
