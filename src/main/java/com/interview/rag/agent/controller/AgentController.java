package com.interview.rag.agent.controller;

import com.interview.rag.agent.domain.AgentRun;
import com.interview.rag.agent.domain.AgentSession;
import com.interview.rag.agent.domain.AgentStatus;
import com.interview.rag.agent.service.AgentConfirmationService;
import com.interview.rag.agent.service.AgentEventService;
import com.interview.rag.agent.service.AgentSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentSessionService sessionService;
    private final AgentEventService eventService;
    private final AgentConfirmationService confirmationService;

    public AgentController(AgentSessionService sessionService,
                           AgentEventService eventService, AgentConfirmationService confirmationService) {
        this.sessionService = sessionService;
        this.eventService = eventService;
        this.confirmationService = confirmationService;
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@Valid @RequestBody CreateSessionRequest request) {
        AgentSession session = sessionService.createSession(request.userId(), request.pullRequestUrl());
        return sessionResponse(session);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public RunResponse sendMessage(@PathVariable String sessionId,
                                   @Valid @RequestBody SendMessageRequest request) {
        AgentRun run = sessionService.submitMessage(sessionId, request.message());
        return new RunResponse(run.getId(), sessionId, run.getStatus());
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        AgentSession session = sessionService.getSession(sessionId);
        return sessionResponse(session);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public java.util.List<MessageResponse> getMessages(@PathVariable String sessionId) {
        return sessionService.getMessages(sessionId).stream()
                .map(message -> new MessageResponse(message.getRole(), message.getContent()))
                .toList();
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String runId,
                                                 @RequestHeader(value = "Last-Event-ID", required = false)
                                                 String lastEventId) {
        AgentRun run = sessionService.getRun(runId);
        long after = parseSequence(lastEventId);
        boolean terminal = run.getStatus() == AgentStatus.COMPLETED
                || run.getStatus() == AgentStatus.FAILED
                || run.getStatus() == AgentStatus.CANCELLED;
        return eventService.stream(runId, after, terminal);
    }

    @PostMapping("/confirmations/{confirmationId}/decision")
    public DecisionResponse decide(@PathVariable String confirmationId,
                                   @Valid @RequestBody ConfirmationDecision request) {
        String runId = confirmationService.decide(confirmationId, request.token(), request.approved());
        return new DecisionResponse(confirmationId, request.approved());
    }

    private long parseSequence(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private SessionResponse sessionResponse(AgentSession session) {
        return new SessionResponse(session.getId(), session.getStatus(),
                session.getPullRequestContextJson() == null ? "DEMO" : "GITHUB_PR",
                session.getPullRequestContextJson());
    }

    public record CreateSessionRequest(String userId, String pullRequestUrl) {
    }

    public record SendMessageRequest(@NotBlank String message) {
    }

    public record ConfirmationDecision(@NotBlank String token, boolean approved) {
    }

    public record SessionResponse(String sessionId, AgentStatus status, String mode, String pullRequestContextJson) {
    }

    public record MessageResponse(com.interview.rag.agent.domain.MessageRole role, String content) {
    }

    public record RunResponse(String runId, String sessionId, AgentStatus status) {
    }

    public record DecisionResponse(String confirmationId, boolean approved) {
    }
}
