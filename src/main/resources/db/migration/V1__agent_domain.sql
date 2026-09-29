CREATE TABLE IF NOT EXISTS t_agent_session (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    status VARCHAR(30) NOT NULL,
    user_id VARCHAR(100),
    summary TEXT,
    version BIGINT,
    created_at DATETIME(6),
    updated_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS t_agent_run (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    user_message TEXT NOT NULL,
    final_answer LONGTEXT,
    error_message TEXT,
    current_step INT,
    tool_call_count INT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    elapsed_ms BIGINT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    INDEX idx_agent_run_session (session_id)
);

CREATE TABLE IF NOT EXISTS t_agent_step (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    input_json LONGTEXT,
    output_json LONGTEXT,
    duration_ms BIGINT,
    created_at DATETIME(6),
    CONSTRAINT uk_agent_step_run_sequence UNIQUE (run_id, sequence_number)
);

CREATE TABLE IF NOT EXISTS t_agent_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36),
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    token_count INT,
    created_at DATETIME(6),
    INDEX idx_agent_message_session (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS t_tool_invocation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    result_json LONGTEXT,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    retry_count INT,
    duration_ms BIGINT,
    error_message TEXT,
    created_at DATETIME(6),
    CONSTRAINT uk_tool_invocation_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS t_confirmation_request (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6),
    INDEX idx_confirmation_run (run_id)
);

CREATE TABLE IF NOT EXISTS t_release_review (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    service_name VARCHAR(100),
    release_version VARCHAR(50),
    risk_level VARCHAR(20),
    context_json LONGTEXT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    CONSTRAINT uk_release_review_session UNIQUE (session_id)
);

CREATE TABLE IF NOT EXISTS t_agent_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    data_json LONGTEXT NOT NULL,
    created_at DATETIME(6),
    CONSTRAINT uk_agent_event_run_sequence UNIQUE (run_id, sequence_number)
);

CREATE TABLE IF NOT EXISTS t_document (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    doc_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    file_size BIGINT,
    status VARCHAR(20) NOT NULL,
    chunk_count INT,
    parsed_chunks INT,
    error_msg TEXT,
    created_at DATETIME(6),
    updated_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS t_qa_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    question TEXT NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    cache_hit BIT,
    elapsed_ms BIGINT,
    created_at DATETIME(6)
);
