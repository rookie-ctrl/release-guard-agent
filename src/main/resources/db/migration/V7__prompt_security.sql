ALTER TABLE t_agent_session ADD COLUMN summary_policy_version VARCHAR(32);
ALTER TABLE t_agent_session ADD COLUMN owner_browser_session_hash VARCHAR(64);
ALTER TABLE t_agent_message ADD COLUMN compressed_policy_version VARCHAR(32);
