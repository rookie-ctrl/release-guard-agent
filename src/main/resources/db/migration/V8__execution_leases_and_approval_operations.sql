ALTER TABLE t_agent_run
    ADD COLUMN execution_token VARCHAR(36),
    ADD COLUMN execution_lease_until DATETIME(6),
    ADD COLUMN execution_deadline_at DATETIME(6),
    ADD INDEX idx_agent_run_recovery (status, execution_lease_until);

-- Recover runs left in RUNNING by the version without leases.
UPDATE t_agent_run SET execution_lease_until = CURRENT_TIMESTAMP WHERE status = 'RUNNING';

ALTER TABLE t_tool_invocation ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE t_approval_operation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    confirmation_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_token VARCHAR(36),
    lease_until DATETIME(6),
    next_attempt_at DATETIME(6),
    attempt_count INT NOT NULL DEFAULT 0,
    external_approval_id VARCHAR(100),
    result_json LONGTEXT,
    error_message TEXT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    CONSTRAINT uk_approval_operation_confirmation UNIQUE (confirmation_id),
    INDEX idx_approval_operation_recovery (status, next_attempt_at, lease_until)
);

-- This table emulates storage owned by the external approval system.
CREATE TABLE t_mock_release_approval (
    idempotency_key VARCHAR(100) NOT NULL PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    approval_id VARCHAR(100) NOT NULL,
    result_json LONGTEXT NOT NULL,
    CONSTRAINT uk_mock_approval_id UNIQUE (approval_id)
);
