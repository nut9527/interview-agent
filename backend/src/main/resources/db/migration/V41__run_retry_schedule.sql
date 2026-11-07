-- Execution eligibility is separate from the outbox reminder schedule.
ALTER TABLE interview_runs
 ADD COLUMN not_before DATETIME(6) NULL,
 ADD KEY idx_run_not_before(status,not_before,created_at);
