CREATE TABLE knowledge_import_jobs (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completed_pages INT NOT NULL DEFAULT 0,
    total_pages INT NOT NULL DEFAULT 0,
    message VARCHAR(500) NOT NULL DEFAULT '',
    draft_json MEDIUMTEXT NULL,
    document_id VARCHAR(36) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_import_job_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    INDEX idx_import_job_owner (user_id, created_at),
    INDEX idx_import_job_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
