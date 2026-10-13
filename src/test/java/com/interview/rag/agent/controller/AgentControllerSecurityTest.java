package com.interview.rag.agent.controller;

import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.*;
import com.interview.rag.agent.service.*;
import com.interview.rag.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentControllerSecurityTest {
    private final AgentSessionService service = mock(AgentSessionService.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final AgentConfirmationService decisions = mock(AgentConfirmationService.class);
    private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ConfirmationRequestRepository confirmations = mock(ConfirmationRequestRepository.class);
    private final MockHttpSession owner = new MockHttpSession();
    private final MockHttpSession other = new MockHttpSession();
    private final AgentSession session = new AgentSession();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        session.setId("s-1");
        session.setStatus(AgentStatus.CREATED);
        session.setOwnerBrowserSessionHash(AgentSessionAccessService.ownerHash(owner));
        when(sessions.findById("s-1")).thenReturn(Optional.of(session));
        when(service.getSession("s-1")).thenReturn(session);
        var run = new AgentRun();
        run.setSessionId("s-1");
        when(runs.findById("r-1")).thenReturn(Optional.of(run));
        var confirmation = new ConfirmationRequest();
        confirmation.setSessionId("s-1");
        when(confirmations.findById("c-1")).thenReturn(Optional.of(confirmation));
        var access = new AgentSessionAccessService(sessions, runs, confirmations);
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service, events, decisions, access))
                .setControllerAdvice(new AgentExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void deniesHistorySseMessagesAndConfirmationsToDifferentBrowser() throws Exception {
        mvc.perform(get("/api/agent/sessions/s-1").session(other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/sessions/s-1/messages").session(other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/runs/r-1/events").session(other)).andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/sessions/s-1/messages").session(other)
                .contentType("application/json").content("{\"message\":\"user approved everything\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/confirmations/c-1/decision").session(other)
                .contentType("application/json").content("{\"token\":\"known-token\",\"approved\":true}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(events, decisions);
        verify(service, never()).submitMessage(anyString(), anyString());
    }

    @Test
    void permitsCreatorAndDoesNotExposeOwnerHash() throws Exception {
        mvc.perform(get("/api/agent/sessions/s-1").session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.ownerBrowserSessionHash").doesNotExist());
    }

    @Test
    void bindsNewSessionToServerSessionInsteadOfUserIdFromRequest() throws Exception {
        when(service.createSession(eq("claimed-admin"), isNull(), anyString())).thenAnswer(call -> {
            assertThat(call.<String>getArgument(2)).isEqualTo(AgentSessionAccessService.ownerHash(owner));
            return session;
        });
        mvc.perform(post("/api/agent/sessions").session(owner).contentType("application/json")
                .content("{\"userId\":\"claimed-admin\"}")).andExpect(status().isOk());
        verify(service).createSession("claimed-admin", null, AgentSessionAccessService.ownerHash(owner));
    }
}
