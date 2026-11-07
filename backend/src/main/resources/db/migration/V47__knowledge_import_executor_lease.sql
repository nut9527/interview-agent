ALTER TABLE knowledge_import_jobs
    ADD COLUMN executor_id VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD INDEX idx_import_lease (status, lease_until);
