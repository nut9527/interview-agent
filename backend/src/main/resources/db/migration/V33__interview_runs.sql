ALTER TABLE interview_training_tasks ADD COLUMN active_run_id VARCHAR(48) NULL;
ALTER TABLE interview_sessions ADD COLUMN active_run_id VARCHAR(48) NULL, ADD COLUMN material_manifest_json LONGTEXT NULL;
CREATE TABLE interview_runs (
 id VARCHAR(48) PRIMARY KEY, owner_id BIGINT NOT NULL,
 operation VARCHAR(12) NOT NULL, request_id VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL, input_hash CHAR(64) NOT NULL,
 training_task_id VARCHAR(64) NULL,
 session_id VARCHAR(48) NOT NULL, expected_turn INT NOT NULL,
 route VARCHAR(12) NOT NULL, config_json LONGTEXT NOT NULL,
 input_json LONGTEXT NOT NULL, checkpoint_json LONGTEXT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED', phase VARCHAR(16) NOT NULL DEFAULT 'CONTEXT',
 deadline DATETIME(6) NOT NULL, lease_epoch BIGINT NOT NULL DEFAULT 0, lease_until DATETIME(6) NULL,
 model_attempts INT NOT NULL DEFAULT 0, tool_batches INT NOT NULL DEFAULT 0,
 tool_executions INT NOT NULL DEFAULT 0, repairs INT NOT NULL DEFAULT 0,
 next_event_seq BIGINT NOT NULL DEFAULT 0, cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 error_code VARCHAR(64) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_run_command(owner_id,operation,request_id),
 KEY idx_run_dispatch(status,lease_until,created_at), KEY idx_run_session(owner_id,session_id),
 CONSTRAINT fk_run_task FOREIGN KEY(training_task_id) REFERENCES interview_training_tasks(id) ON DELETE CASCADE,
 CONSTRAINT fk_run_owner FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE interview_model_attempts (
 attempt_id VARCHAR(48) PRIMARY KEY, run_id VARCHAR(48) NOT NULL, ordinal INT NOT NULL,
 lease_epoch BIGINT NOT NULL, status VARCHAR(16) NOT NULL,
 request_hash CHAR(64) NOT NULL, error_code VARCHAR(64) NULL, response_json LONGTEXT NULL, usage_json TEXT NULL,
 elapsed_ms BIGINT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_run_attempt(run_id,ordinal),
 FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE
);
CREATE TABLE interview_tool_receipts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(48) NOT NULL,
 logical_call_key CHAR(64) NOT NULL, tool_call_id VARCHAR(160) NOT NULL,
 tool_name VARCHAR(64) NOT NULL, args_json TEXT NOT NULL, source_hash CHAR(64) NOT NULL,
 result_json LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_tool_association(run_id,tool_call_id), KEY idx_tool_cache(run_id,logical_call_key),
 FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE
);
CREATE TABLE interview_run_events (
 run_id VARCHAR(48) NOT NULL, seq BIGINT NOT NULL, type VARCHAR(40) NOT NULL,
 phase VARCHAR(16) NOT NULL, message VARCHAR(120) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(run_id,seq), FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE
);
