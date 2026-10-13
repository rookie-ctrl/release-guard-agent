package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.*;
import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.gateway.*;
import com.interview.rag.agent.repository.*;
import com.interview.rag.agent.tool.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:recovery;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver"})
@Import({RunExecutionService.class, MockApprovalStore.class, ApprovalOperationService.class,
        AgentConfirmationService.class, AgentOutboxService.class, AgentRunRecovery.class,
        ExecutionRecoveryIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutionRecoveryIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource datasource) { return new JdbcTemplate(datasource); }
        @Bean AgentEventService events() { return mock(AgentEventService.class); }
        @Bean ToolRegistry registry() {
            return new ToolRegistry(List.of(new GatewayAgentTool(ToolDefinitionFactory.stringArguments(
                    "createReleaseApproval", "test", ToolRiskLevel.HIGH_RISK,
                    "serviceName", "version", "riskLevel", "summary"), ignored -> { throw new AssertionError("wrong execution path"); })));
        }
        @Bean ToolExecutionService tools(ToolRegistry registry, ToolInvocationRepository repository, ObjectMapper mapper) {
            return new ToolExecutionService(registry, repository, mapper, new SimpleMeterRegistry(), Runnable::run);
        }
    }

    @Autowired AgentRunRepository runs;
    @Autowired AgentSessionRepository sessions;
    @Autowired ConfirmationRequestRepository confirmations;
    @Autowired ApprovalOperationRepository operations;
    @Autowired AgentMessageRepository messages;
    @Autowired AgentOutboxRepository outbox;
    @Autowired ToolInvocationRepository invocations;
    @Autowired RunExecutionService executions;
    @Autowired ApprovalOperationService approvals;
    @Autowired AgentConfirmationService decisions;
    @Autowired MockApprovalStore external;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AgentRunRecovery recovery;

    @BeforeEach
    void mockExternalSchema() {
        jdbc.execute("create table if not exists t_mock_release_approval (idempotency_key varchar(100) primary key, "
                + "request_hash varchar(64) not null, request_json longtext not null, approval_id varchar(100) unique not null, result_json longtext not null)");
    }

    @Test
    void duplicateCannotClaimAfter120SecondsWhenLeaseWasRenewed() {
        AgentRun run = run(AgentStatus.CREATED);
        assertThat(executions.claim(run.getId(), "old", 180)).isTrue();
        assertThat(executions.renew(run.getId(), "old")).isTrue();
        assertThat(executions.claim(run.getId(), "duplicate", 180)).isFalse();
        assertThat(runs.findById(run.getId()).orElseThrow().getExecutionToken()).isEqualTo("old");
    }

    @Test
    void staleWorkerCannotPersistFailureOrFinalMessageAfterReplacementClaims() {
        AgentRun run = run(AgentStatus.CREATED);
        executions.claim(run.getId(), "old", 180);
        AgentRun stale = runs.findById(run.getId()).orElseThrow();
        jdbc.update("update t_agent_run set execution_lease_until = ? where id = ?", LocalDateTime.now().minusSeconds(1), run.getId());
        assertThat(executions.claim(run.getId(), "new", 180)).isTrue();
        AgentRunLease misleadingLease = mock(AgentRunLease.class);
        when(misleadingLease.token()).thenReturn("old");
        for (AgentStatus status : List.of(AgentStatus.FAILED, AgentStatus.COMPLETED)) {
            stale.setStatus(status);
            assertThatThrownBy(() -> executions.persist(stale, misleadingLease, () -> {
                AgentMessage result = new AgentMessage(); result.setSessionId(run.getSessionId()); result.setRunId(run.getId());
                result.setRole(MessageRole.ASSISTANT); result.setContent("stale"); messages.save(result);
            })).isInstanceOf(ExecutionLostException.class);
        }
        AgentRun current = runs.findById(run.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(AgentStatus.RUNNING);
        assertThat(current.getExecutionToken()).isEqualTo("new");
        assertThat(jdbc.queryForObject("select count(*) from t_agent_message where run_id = ?", Integer.class, run.getId())).isZero();
    }

    @Test
    void resultAndRelatedWritesRollbackTogether() {
        AgentRun run = run(AgentStatus.CREATED);
        executions.claim(run.getId(), "token", 180);
        AgentRun snapshot = runs.findById(run.getId()).orElseThrow();
        snapshot.setStatus(AgentStatus.COMPLETED);
        AgentRunLease lease = mock(AgentRunLease.class); when(lease.token()).thenReturn("token");
        assertThatThrownBy(() -> executions.persist(snapshot, lease, () -> { throw new IllegalStateException("save failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    void externalSuccessSurvivesLocalRollbackAndRestartRecoversOneApproval() throws Exception {
        AgentRun run = run(AgentStatus.WAITING_CONFIRMATION);
        ConfirmationRequest confirmation = confirmation(run);
        decisions.decide(confirmation.getId(), "user-token", true);
        ApprovalOperation operation = operations.findAll().stream().filter(o -> o.getRunId().equals(run.getId())).findFirst().orElseThrow();
        assertThat(operation.getStatus()).isEqualTo(ApprovalOperationStatus.PENDING);
        assertThat(confirmations.findById(confirmation.getId()).orElseThrow().getStatus()).isEqualTo(ConfirmationStatus.APPROVED);
        assertThat(external.find(operation.externalKey())).isEmpty();
        ApprovalOperation attempt = approvals.claim(operation.getId()).orElseThrow();
        JsonNode request = mapper.readTree(attempt.getArgumentsJson());
        JsonNode created = external.create(attempt.externalKey(), request);
        TransactionTemplate local = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> local.executeWithoutResult(ignored -> {
            approvals.finish(attempt, created);
            throw new IllegalStateException("crash before local commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(operations.findById(operation.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalOperationStatus.EXECUTING);
        assertThat(outbox.findAll().stream().filter(o -> o.getAggregateId().equals(run.getId()))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from t_agent_message where run_id = ?", Integer.class, run.getId())).isZero();
        // A new gateway/store instance has no in-memory state from the first process.
        MockApprovalStore restarted = new MockApprovalStore(jdbc, mapper);
        JsonNode replay = restarted.create(attempt.externalKey(), request);
        assertThat(replay).isEqualTo(created);
        jdbc.update("update t_approval_operation set lease_until = ? where id = ?", LocalDateTime.now().minusSeconds(1), operation.getId());
        ApprovalOperation recovered = approvals.claim(operation.getId()).orElseThrow();
        approvals.finish(recovered, restarted.find(recovered.externalKey()).orElseThrow());
        assertThat(operations.findById(operation.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalOperationStatus.SUCCESS);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.CREATED);
        assertThat(confirmations.findById(confirmation.getId()).orElseThrow().getStatus()).isEqualTo(ConfirmationStatus.EXECUTED);
        assertThat(outbox.findAll().stream().filter(o -> o.getAggregateId().equals(run.getId()))).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from t_agent_message where run_id = ?", Integer.class, run.getId())).isEqualTo(1);
        assertThat(invocations.findById(operation.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from t_mock_release_approval where idempotency_key = ?", Integer.class, operation.externalKey())).isEqualTo(1);
    }

    @Test
    void sameKeyRejectsChangedParametersAndAcceptsReorderedFields() throws Exception {
        String key = "releaseguard:approval:" + UUID.randomUUID();
        JsonNode first = mapper.readTree("{\"serviceName\":\"order-service\",\"version\":\"v2.3.7\",\"riskLevel\":\"HIGH\",\"summary\":\"review\"}");
        JsonNode original = external.create(key, first);
        JsonNode reordered = mapper.readTree("{\"summary\":\"review\",\"riskLevel\":\"HIGH\",\"version\":\"v2.3.7\",\"serviceName\":\"order-service\"}");
        assertThat(external.create(key, reordered)).isEqualTo(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("summary", "changed");
        assertThatThrownBy(() -> external.create(key, first)).isInstanceOf(IdempotencyConflictException.class);
        assertThat(external.find(key)).contains(original);
    }

    @Test
    void concurrentCreatesHaveOneDurableBusinessRecord() throws Exception {
        String key = "releaseguard:approval:" + UUID.randomUUID();
        JsonNode request = mapper.readTree("{\"serviceName\":\"order-service\",\"version\":\"v2.3.7\"}");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<JsonNode>> calls = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<JsonNode>) () -> external.create(key, request)).toList();
            List<Future<JsonNode>> results = pool.invokeAll(calls);
            Set<JsonNode> unique = new HashSet<>();
            for (Future<JsonNode> result : results) unique.add(result.get(5, TimeUnit.SECONDS));
            assertThat(unique).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from t_mock_release_approval where idempotency_key = ?", Integer.class, key)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void recoveryRequeuesLostWorkerWithoutResettingBudgetAndExpiresOverdueRun() {
        AgentRun run = run(AgentStatus.CREATED);
        executions.claim(run.getId(), "old", 180);
        LocalDateTime deadline = runs.findById(run.getId()).orElseThrow().getExecutionDeadlineAt();
        jdbc.update("update t_agent_run set execution_lease_until = ? where id = ?", LocalDateTime.now().minusSeconds(1), run.getId());
        recovery.recover();
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.CREATED);
        assertThat(runs.findById(run.getId()).orElseThrow().getExecutionDeadlineAt()).isEqualTo(deadline);
        assertThat(executions.claim(run.getId(), "new", 180)).isTrue();
        assertThat(runs.findById(run.getId()).orElseThrow().getExecutionDeadlineAt()).isEqualTo(deadline);
        jdbc.update("update t_agent_run set execution_deadline_at = ? where id = ?", LocalDateTime.now().minusSeconds(1), run.getId());
        recovery.recover();
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.FAILED);
        assertThat(sessions.findById(run.getSessionId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.FAILED);
        assertThat(executions.claim(run.getId(), "late", 180)).isFalse();
    }

    @Test
    void staleApprovalAttemptCannotOverwriteRecoveredResult() throws Exception {
        AgentRun run = run(AgentStatus.WAITING_CONFIRMATION);
        ConfirmationRequest confirmation = confirmation(run);
        decisions.decide(confirmation.getId(), "user-token", true);
        ApprovalOperation operation = operations.findAll().stream().filter(o -> o.getRunId().equals(run.getId())).findFirst().orElseThrow();
        ApprovalOperation old = approvals.claim(operation.getId()).orElseThrow();
        jdbc.update("update t_approval_operation set lease_until = ? where id = ?", LocalDateTime.now().minusSeconds(1), operation.getId());
        ApprovalOperation replacement = approvals.claim(operation.getId()).orElseThrow();
        Object result = external.create(replacement.externalKey(), mapper.readTree(replacement.getArgumentsJson()));
        approvals.finish(replacement, result);
        assertThatThrownBy(() -> approvals.unresolved(old, "late timeout", false)).isInstanceOf(ExecutionLostException.class);
        assertThat(operations.findById(operation.getId()).orElseThrow().getStatus()).isEqualTo(ApprovalOperationStatus.SUCCESS);
    }

    private AgentRun run(AgentStatus status) {
        AgentSession session = new AgentSession(); session.setId(UUID.randomUUID().toString()); session.setStatus(status); sessions.save(session);
        AgentRun run = new AgentRun(); run.setId(UUID.randomUUID().toString()); run.setSessionId(session.getId());
        run.setUserMessage("review"); run.setStatus(status); return runs.save(run);
    }

    private ConfirmationRequest confirmation(AgentRun run) {
        ConfirmationRequest confirmation = new ConfirmationRequest(); confirmation.setId(UUID.randomUUID().toString());
        confirmation.setRunId(run.getId()); confirmation.setSessionId(run.getSessionId()); confirmation.setToolName("createReleaseApproval");
        confirmation.setArgumentsJson("{\"serviceName\":\"order-service\",\"version\":\"v2.3.7\",\"riskLevel\":\"HIGH\",\"summary\":\"review\"}");
        confirmation.setTokenHash(CanonicalJson.hash("user-token")); confirmation.setStatus(ConfirmationStatus.PENDING);
        confirmation.setExpiresAt(LocalDateTime.now().plusMinutes(10)); return confirmations.save(confirmation);
    }
}
