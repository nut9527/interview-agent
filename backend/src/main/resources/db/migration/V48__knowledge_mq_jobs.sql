ALTER TABLE knowledge_import_jobs
    ADD COLUMN file_ref VARCHAR(80) NULL,
    ADD COLUMN execution_deadline DATETIME(6) NULL,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN notice_after DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_import_dispatch (status, notice_after);

ALTER TABLE knowledge_index_jobs
    ADD COLUMN notice_after DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
