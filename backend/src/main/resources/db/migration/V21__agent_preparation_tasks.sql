CREATE TABLE agent_runs (
    id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL,
    mode VARCHAR(24) NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    model_calls INT NOT NULL DEFAULT 0,
    tool_calls INT NOT NULL DEFAULT 0,
    steps INT NOT NULL DEFAULT 0,
    active_millis BIGINT NOT NULL DEFAULT 0,
    lease_token VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    view_json MEDIUMTEXT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    checkpoint_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_agent_owner (owner_id, id),
    INDEX idx_agent_owner_created (owner_id, created_at, id),
    INDEX idx_agent_dispatch (status, lease_until, created_at, id),
    INDEX idx_agent_expiry (expires_at, status),
    CONSTRAINT fk_agent_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);

CREATE TABLE agent_steps (
    owner_id BIGINT NOT NULL,
    run_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_no INT NOT NULL,
    kind VARCHAR(12) NOT NULL,
    tool_name VARCHAR(64) NULL,
    call_id VARCHAR(200) NULL,
    status VARCHAR(24) NOT NULL,
    reserved_units INT NOT NULL,
    usage_json TEXT NULL,
    result_json MEDIUMTEXT NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (owner_id, run_id, step_no),
    CONSTRAINT fk_agent_step FOREIGN KEY (owner_id, run_id) REFERENCES agent_runs(owner_id, id) ON DELETE CASCADE
);

CREATE TABLE agent_events (
    owner_id BIGINT NOT NULL,
    run_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    content_json TEXT NOT NULL,
    PRIMARY KEY (owner_id, run_id, sequence_no),
    CONSTRAINT fk_agent_event FOREIGN KEY (owner_id, run_id) REFERENCES agent_runs(owner_id, id) ON DELETE CASCADE
);

-- Request IDs live until account deletion. Replays must survive source edits and task terminal states.
CREATE TABLE agent_commands (
    owner_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json TEXT NOT NULL,
    PRIMARY KEY (owner_id, request_id),
    CONSTRAINT fk_agent_command_owner FOREIGN KEY (owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);

-- Serialize only short claims across JVMs. Two live leases is the initial cluster-wide limit.
CREATE TABLE agent_dispatch_lock (id INT PRIMARY KEY);
INSERT INTO agent_dispatch_lock (id) VALUES (1);
