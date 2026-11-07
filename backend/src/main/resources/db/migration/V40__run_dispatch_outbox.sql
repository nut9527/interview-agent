ALTER TABLE interview_runs
 ADD COLUMN dispatch_seq BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN next_dispatch_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD KEY idx_run_recovery(status,next_dispatch_at,lease_until);

CREATE TABLE interview_run_outbox (
 event_id VARCHAR(48) PRIMARY KEY,
 run_id VARCHAR(48) NOT NULL,
 dispatch_seq BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 publisher_token VARCHAR(48) NULL,
 lease_until DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL,
 last_error_code VARCHAR(40) NULL,
 UNIQUE KEY uq_outbox_dispatch(run_id,dispatch_seq),
 KEY idx_outbox_pending(status,next_attempt_at),
 KEY idx_outbox_lease(status,lease_until),
 CONSTRAINT fk_outbox_run FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE
);
