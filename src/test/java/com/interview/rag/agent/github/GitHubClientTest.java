package com.interview.rag.agent.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class GitHubClientTest {
    private HttpServer server;
    private URI origin;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private int status;
    private String body;
    private boolean limited;
    private long delayMillis;

    @BeforeEach
    void startServer() throws Exception {
        status = 200;
        body = "{\"ok\":true}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/a/b", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (limited) {
                exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
                exchange.getResponseHeaders().set("X-RateLimit-Reset", Long.toString(
                        java.time.Instant.parse("2026-09-29T11:09:37Z").getEpochSecond()));
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void supportsAnonymousPublicReads() {
        assertThat(client("", 1024).get("/repos/a/b").body().path("ok").asBoolean()).isTrue();
        assertThat(authorization.get()).isNull();
    }

    @Test
    void sendsTokenOnlyInHeaderAndRedactsProperties() {
        client("test-token", 1024).get("/repos/a/b");
        assertThat(authorization.get()).isEqualTo("Bearer test-token");
        assertThat(new GitHubProperties("test-token", "2026-03-10", 1, 1024).toString())
                .doesNotContain("test-token");
    }

    @Test
    void mapsInvalidTokenWithoutEchoingUpstreamErrorBody() {
        status = 401;
        body = "{\"message\":\"sensitive detail\"}";
        assertThatThrownBy(() -> client("test-token", 1024).get("/repos/a/b"))
                .isInstanceOf(GitHubApiException.class).hasMessageContaining("Token 无效")
                .hasMessageNotContaining("sensitive detail");
    }

    @Test
    void distinguishesRateLimitFromPermissionFailure() {
        status = 403;
        limited = true;
        assertThatThrownBy(() -> client("", 1024).get("/repos/a/b")).hasMessageContaining("限流")
                .hasMessageContaining("2026-09-29 19:09:37").hasMessageContaining("重启不会重置");
    }

    @Test
    void reportsMissingResource() {
        status = 404;
        assertThatThrownBy(() -> client("", 1024).get("/repos/a/b")).hasMessageContaining("不存在或不可见");
    }

    @Test
    void rejectsOversizedResponse() {
        body = "x".repeat(1000);
        assertThatThrownBy(() -> client("", 64).get("/repos/a/b")).hasMessageContaining("响应过大");
    }

    @Test
    void boundsTotalRequestWait() {
        delayMillis = 1500;
        assertThatThrownBy(() -> client("", 1024).get("/repos/a/b")).hasMessageContaining("超时");
    }

    @Test
    void rejectsRedirects() {
        status = 301;
        assertThatThrownBy(() -> client("", 1024).get("/repos/a/b")).hasMessageContaining("重定向");
    }

    private GitHubClient client(String token, int maximum) {
        return new GitHubClient(new GitHubProperties(token, "2026-03-10", 1, maximum),
                new ObjectMapper(), HttpClient.newHttpClient(), origin);
    }
}
