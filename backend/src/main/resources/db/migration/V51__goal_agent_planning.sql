ALTER TABLE learning_goals ADD COLUMN auto_adjust BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN coach_state VARCHAR(32) NOT NULL DEFAULT 'WAITING_PLAN',
 ADD COLUMN planning_run_id VARCHAR(48) NULL,
 ADD COLUMN planning_trigger VARCHAR(32) NULL,
 ADD COLUMN planning_source_session VARCHAR(64) NULL,
 ADD COLUMN last_auto_evidence_hash CHAR(64) NULL;
ALTER TABLE learning_plans ADD COLUMN focus_json TEXT NULL;
CREATE TABLE learning_goal_planning (
 run_id VARCHAR(48) PRIMARY KEY, owner_id BIGINT NOT NULL, epoch BIGINT NOT NULL,
 configuration_hash CHAR(64) NOT NULL, checkpoint_json MEDIUMTEXT NOT NULL,
 attempts INT NOT NULL DEFAULT 0, tools INT NOT NULL DEFAULT 0,
 pending BOOLEAN NOT NULL DEFAULT FALSE, deadline DATETIME(6) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE
);
CREATE TABLE learning_goal_model_attempts (
 run_id VARCHAR(48) NOT NULL, attempt INT NOT NULL, epoch BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'STARTED', error_code VARCHAR(64) NULL,
 usage_json TEXT NULL, response_json MEDIUMTEXT NULL,
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), finished_at DATETIME(6) NULL,
 PRIMARY KEY(run_id,attempt), FOREIGN KEY(run_id) REFERENCES learning_goal_planning(run_id) ON DELETE CASCADE
);
CREATE TABLE learning_goal_updates (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, owner_id BIGINT NOT NULL, goal_id VARCHAR(48) NOT NULL,
 session_id VARCHAR(64) NOT NULL, source_version VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_goal_update(goal_id,session_id,source_version),
 KEY ix_goal_update_pending(status,id),
 FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE
);
