package com.interview.rag.agent.controller;

import com.interview.rag.agent.github.GitHubApiException;
import com.interview.rag.agent.github.GitHubController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {AgentController.class, GitHubController.class})
public class AgentExceptionHandler {
    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, Object>> githubError(GitHubApiException error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", error.getMessage(), "upstreamStatus", error.statusCode()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> stateConflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }
}
