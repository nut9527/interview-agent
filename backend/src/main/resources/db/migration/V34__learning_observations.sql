-- Learning facts are derived from owned, completed sessions. No legacy agent_* tables are reused.
CREATE TABLE learning_preferences (
 owner_id BIGINT PRIMARY KEY,
 personalization_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE learning_observation_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL,
 source_session_id VARCHAR(48) NOT NULL, source_version CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), processed_at DATETIME(6) NULL,
 UNIQUE KEY uq_learning_source(owner_id,source_session_id,source_version),
 KEY idx_learning_pending(status,id),
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
 FOREIGN KEY(source_session_id) REFERENCES interview_sessions(session_id) ON DELETE CASCADE
);
CREATE TABLE learning_observations (
 id CHAR(64) PRIMARY KEY, owner_id BIGINT NOT NULL, source_session_id VARCHAR(48) NOT NULL,
 source_version CHAR(64) NOT NULL, source_round INT NOT NULL,
 competency_id VARCHAR(64) NOT NULL, dimension VARCHAR(64) NOT NULL,
 rubric_version VARCHAR(80) NOT NULL, execution_mode VARCHAR(32) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revision BIGINT NOT NULL DEFAULT 1,
 payload_json LONGTEXT NULL, correction_json TEXT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_learning_owner(owner_id,created_at,id),
 KEY idx_learning_session(owner_id,source_session_id),
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE,
 FOREIGN KEY(source_session_id) REFERENCES interview_sessions(session_id) ON DELETE CASCADE
);
CREATE TABLE learning_profile_snapshots (
 owner_id BIGINT PRIMARY KEY, revision BIGINT NOT NULL, reducer_version VARCHAR(40) NOT NULL,
 profile_json LONGTEXT NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE learning_corrections (
 observation_id CHAR(64) NOT NULL, source_revision BIGINT NOT NULL,
 owner_id BIGINT NOT NULL, text_json TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(observation_id,source_revision),
 FOREIGN KEY(observation_id) REFERENCES learning_observations(id) ON DELETE CASCADE,
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);
