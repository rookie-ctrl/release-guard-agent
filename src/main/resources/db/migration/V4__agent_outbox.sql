CREATE TABLE t_agent_outbox (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    last_error TEXT,
    created_at DATETIME(6),
    INDEX idx_agent_outbox_pending (status, next_attempt_at)
);
