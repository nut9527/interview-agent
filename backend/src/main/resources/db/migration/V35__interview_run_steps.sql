-- Child ledger of V33; the LEGACY agent_* tables are deliberately unchanged.
ALTER TABLE interview_tool_receipts ADD COLUMN execution_kind VARCHAR(12) NULL;
-- NULL for historical runs means unknown, never a fabricated zero execution count.
ALTER TABLE interview_runs ADD COLUMN actual_tool_executions INT NULL, ADD COLUMN tool_cache_hits INT NULL,
 ADD COLUMN skipped_tool_requests INT NULL;
CREATE TABLE interview_run_steps (
 owner_id BIGINT NOT NULL, run_id VARCHAR(48) NOT NULL, step_no INT NOT NULL,
 decision_type VARCHAR(16) NOT NULL, tool_name VARCHAR(64) NULL,
 observation_status VARCHAR(24) NOT NULL, new_evidence INT NOT NULL DEFAULT 0,
 before_hash CHAR(64) NULL, after_hash CHAR(64) NULL, action_key CHAR(64) NULL,
 source_keys_json TEXT NULL, attempt_id VARCHAR(48) NULL, receipt_id BIGINT NULL,
 cache_hit BOOLEAN NOT NULL DEFAULT FALSE, model_attempts INT NOT NULL,
 tool_requests INT NOT NULL, stop_reason VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(owner_id,run_id,step_no), UNIQUE KEY uq_run_step(run_id,step_no),
 FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE,
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
 FOREIGN KEY(receipt_id) REFERENCES interview_tool_receipts(id) ON DELETE SET NULL,
 FOREIGN KEY(attempt_id) REFERENCES interview_model_attempts(attempt_id) ON DELETE SET NULL
);
