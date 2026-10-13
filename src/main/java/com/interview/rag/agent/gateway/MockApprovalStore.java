package com.interview.rag.agent.gateway;

import com.fasterxml.jackson.databind.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Emulates the external system: its commit survives rollback of the caller's transaction. */
@Service
public class MockApprovalStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MockApprovalStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JsonNode create(String key, JsonNode request) {
        if (key == null || key.isBlank() || key.length() > 100) throw new IllegalArgumentException("幂等键无效");
        String canonical = CanonicalJson.serialize(request);
        String hash = CanonicalJson.hash(canonical);
        String approvalId = "REL-" + UUID.randomUUID();
        String result = mapper.valueToTree(Map.of("approvalId", approvalId,
                "serviceName", request.path("serviceName").asText(), "version", request.path("version").asText(),
                "status", "PENDING_REVIEW")).toString();
        try {
            // One durable row is both the business record and the idempotency record.
            jdbc.update("insert into t_mock_release_approval "
                    + "(idempotency_key, request_hash, request_json, approval_id, result_json) values (?, ?, ?, ?, ?)",
                    key, hash, canonical, approvalId, result);
        } catch (DuplicateKeyException duplicate) {
            // The unique key serializes concurrent creates, including across process restarts.
        }
        Stored row = read(key).orElseThrow();
        if (!row.hash().equals(hash)) throw new IdempotencyConflictException();
        return parse(row.result());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<JsonNode> find(String key) { return read(key).map(row -> parse(row.result())); }

    private Optional<Stored> read(String key) {
        return jdbc.query("select request_hash, result_json from t_mock_release_approval where idempotency_key = ?",
                (row, index) -> new Stored(row.getString(1), row.getString(2)), key).stream().findFirst();
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (Exception e) { throw new IllegalStateException("审批记录损坏", e); }
    }

    private record Stored(String hash, String result) {}
}
