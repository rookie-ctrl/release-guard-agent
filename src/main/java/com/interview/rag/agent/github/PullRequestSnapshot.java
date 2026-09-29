package com.interview.rag.agent.github;

public record PullRequestSnapshot(String url, String headRepository, String headSha,
                                  String baseSha, String diffBaseSha) {
    public PullRequestReference reference() {
        return PullRequestReference.parse(url);
    }
}
