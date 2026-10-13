package com.interview.rag.agent.service;

import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSessionAccessServiceTest {
    @Test
    void onlyCreatorBrowserCanReadRunsOrConfirmEvenWithKnownIds() {
        var sessions = mock(AgentSessionRepository.class);
        var runs = mock(AgentRunRepository.class);
        var confirmations = mock(ConfirmationRequestRepository.class);
        var access = new AgentSessionAccessService(sessions, runs, confirmations);
        var owner = new MockHttpSession();
        var other = new MockHttpSession();
        var session = new AgentSession();
        session.setOwnerBrowserSessionHash(AgentSessionAccessService.ownerHash(owner));
        when(sessions.findById("s-1")).thenReturn(Optional.of(session));
        var run = new AgentRun();
        run.setSessionId("s-1");
        when(runs.findById("r-1")).thenReturn(Optional.of(run));
        var confirmation = new ConfirmationRequest();
        confirmation.setSessionId("s-1");
        when(confirmations.findById("c-1")).thenReturn(Optional.of(confirmation));
        assertThatCode(() -> access.requireSession("s-1", owner)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireRun("r-1", owner)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireConfirmation("c-1", owner)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireSession("s-1", other)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> access.requireRun("r-1", other)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> access.requireConfirmation("c-1", other)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> access.requireSession("s-1", null)).isInstanceOf(ResponseStatusException.class);
        session.setOwnerBrowserSessionHash(null);
        assertThatThrownBy(() -> access.requireSession("s-1", owner)).hasMessageContaining("旧会话");
    }
}
