package com.interview.rag.agent.github;

public class GitHubApiException extends RuntimeException {
    private final int statusCode;

    public GitHubApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
