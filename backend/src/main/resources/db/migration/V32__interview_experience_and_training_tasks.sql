ALTER TABLE interview_sessions ADD COLUMN experience_json JSON NULL;

CREATE TABLE interview_training_tasks (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_session_id VARCHAR(64) NOT NULL,
    source_round INT NOT NULL,
    question_id VARCHAR(64) NOT NULL DEFAULT '',
    title VARCHAR(160) NOT NULL,
    gap_text TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'TODO',
    active_session_id VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_training_task_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_training_task_source FOREIGN KEY (source_session_id) REFERENCES interview_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT fk_training_task_active FOREIGN KEY (active_session_id) REFERENCES interview_sessions(session_id) ON DELETE SET NULL,
    UNIQUE KEY uk_training_task_source (user_id, source_session_id, source_round, question_id),
    INDEX idx_training_task_owner (user_id, created_at, id)
);
