-- A single short-transaction mutex serializes admission and lease acquisition.
CREATE TABLE agent_run_admission (
    id TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO agent_run_admission(id) VALUES (1);

-- NULL preserves the queue semantics of historical commands.
ALTER TABLE interview_runs
    ADD COLUMN queue_deadline DATETIME(6) NULL,
    ADD COLUMN first_started_at DATETIME(6) NULL,
    ADD KEY idx_run_queue_deadline(status,queue_deadline),
    ADD KEY idx_run_owner_active(owner_id,status,first_started_at);
