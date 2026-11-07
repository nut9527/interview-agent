ALTER TABLE interview_sessions
    ADD COLUMN answer_lease_token VARCHAR(36) NULL,
    ADD COLUMN answer_lease_until DATETIME(6) NULL;
