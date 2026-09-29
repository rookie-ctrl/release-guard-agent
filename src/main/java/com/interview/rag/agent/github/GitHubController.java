package com.interview.rag.agent.github;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/github")
public class GitHubController {
    private final GitHubGateway gateway;

    public GitHubController(GitHubGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam String url) {
        PullRequestSnapshot snapshot = gateway.snapshot(url);
        return Map.of("snapshot", snapshot, "pullRequest", gateway.pullRequest(snapshot),
                "files", gateway.files(snapshot, 1), "checks", gateway.checks(snapshot, 1));
    }
}
