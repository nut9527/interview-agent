ALTER TABLE interview_sessions
    MODIFY COLUMN target_role VARCHAR(160) NOT NULL,
    ADD COLUMN preparation_json JSON NULL,
    ADD INDEX idx_interview_project_review (user_id, resume_id, state, updated_at, session_id);
