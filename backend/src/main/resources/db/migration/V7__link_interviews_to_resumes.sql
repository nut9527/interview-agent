ALTER TABLE interview_sessions
    ADD COLUMN resume_id BIGINT NULL AFTER target_role,
    ADD COLUMN resume_snapshot LONGTEXT NULL AFTER resume_id;

CREATE INDEX idx_interviews_user_resume ON interview_sessions (user_id, resume_id);
