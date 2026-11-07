-- Existing attempts remain unclassified; do not invent historical phase or timeout data.
ALTER TABLE interview_model_attempts
    ADD COLUMN budget_phase VARCHAR(16) NULL,
    ADD COLUMN call_timeout_ms BIGINT NULL;
