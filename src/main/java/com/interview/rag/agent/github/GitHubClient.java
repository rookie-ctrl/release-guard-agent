package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class GitHubClient {
    private final GitHubProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI origin;

    @Autowired
    public GitHubClient(GitHubProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build(), URI.create("https://api.github.com"));
    }

    GitHubClient(GitHubProperties properties, ObjectMapper objectMapper, HttpClient httpClient, URI origin) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.origin = origin;
    }

    public Response get(String path) {
        if (!path.startsWith("/repos/") || path.startsWith("//")) {
            throw new IllegalArgumentException("GitHub 请求路径无效");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path)).GET()
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ReleaseGuard-Agent")
                .header("X-GitHub-Api-Version", properties.apiVersion());
        if (properties.token() != null && !properties.token().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.token().trim());
        }
        CompletableFuture<HttpResponse<byte[]>> pending = httpClient.sendAsync(builder.build(),
                ignored -> new BoundedBodySubscriber(properties.maxResponseBytes()));
        try {
            HttpResponse<byte[]> response = pending.get(properties.timeoutSeconds(), TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status != 200) {
                String message = switch (status) {
                    case 401 -> "GitHub Token 无效或已撤销，请在本机检查 GITHUB_TOKEN";
                    case 403, 429 -> response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0")
                            || status == 429 || response.headers().firstValue("Retry-After").isPresent()
                            ? rateLimitMessage(response)
                            : "GitHub 拒绝访问，请检查 Token 的仓库权限";
                    case 404 -> "GitHub 资源不存在或不可见，请检查 PR 链接；当前仅支持公开 PR";
                    case 301, 302, 307, 308 -> "GitHub 资源已重定向，请使用仓库的最新 PR 链接";
                    default -> "GitHub API 请求失败，HTTP " + status;
                };
                throw new GitHubApiException(status, message);
            }
            return new Response(objectMapper.readTree(response.body()),
                    response.headers().firstValue("Link").orElse("").contains("rel=\"next\""));
        } catch (GitHubApiException e) {
            throw e;
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new GitHubApiException(0, "GitHub API 请求超时，请稍后重试");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new GitHubApiException(0, "GitHub API 请求已中断");
        } catch (Exception e) {
            throw new GitHubApiException(0, "GitHub API 读取失败：网络异常、响应过大或返回内容无效");
        }
    }

    private String rateLimitMessage(HttpResponse<?> response) {
        String message = "GitHub API 已限流，请等待额度恢复，或在本机 .env 配置 GITHUB_TOKEN 后重启；重启不会重置 GitHub 额度";
        String reset = response.headers().firstValue("X-RateLimit-Reset").orElse("");
        try {
            String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(Instant.ofEpochSecond(Long.parseLong(reset)).atOffset(ZoneOffset.ofHours(8)));
            return message + "。预计重置时间：" + time + "（北京时间）";
        } catch (RuntimeException ignored) {
            return message;
        }
    }

    public record Response(JsonNode body, boolean hasNextPage) {
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalStateException("GitHub response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
