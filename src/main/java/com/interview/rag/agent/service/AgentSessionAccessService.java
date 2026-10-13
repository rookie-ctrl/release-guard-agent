package com.interview.rag.agent.service;

import com.interview.rag.agent.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Anonymous browser-session ownership; not an account authentication system. */
@Service
public class AgentSessionAccessService {
    private final AgentSessionRepository sessions;
    private final AgentRunRepository runs;
    private final ConfirmationRequestRepository confirmations;

    public AgentSessionAccessService(AgentSessionRepository sessions, AgentRunRepository runs,
                                     ConfirmationRequestRepository confirmations) {
        this.sessions = sessions;
        this.runs = runs;
        this.confirmations = confirmations;
    }

    public void requireSession(String sessionId, HttpSession browser) {
        var session = sessions.findById(sessionId).orElseThrow(AgentSessionAccessService::denied);
        if (browser == null || session.getOwnerBrowserSessionHash() == null
                || !MessageDigest.isEqual(session.getOwnerBrowserSessionHash().getBytes(StandardCharsets.UTF_8),
                ownerHash(browser).getBytes(StandardCharsets.UTF_8))) throw denied();
    }

    public void requireRun(String runId, HttpSession browser) {
        var run = runs.findById(runId).orElseThrow(AgentSessionAccessService::denied);
        requireSession(run.getSessionId(), browser);
    }

    public void requireConfirmation(String confirmationId, HttpSession browser) {
        var confirmation = confirmations.findById(confirmationId).orElseThrow(AgentSessionAccessService::denied);
        requireSession(confirmation.getSessionId(), browser);
    }

    public static String ownerHash(HttpSession browser) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(browser.getId().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "当前浏览器无权访问该评审会话；旧会话或浏览器会话已失效时，请创建新会话");
    }
}
