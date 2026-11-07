-- Derived index journal; retain tombstones when documents/users are deleted (including cascades).
-- No source text, user ID or credentials are stored here.
CREATE TABLE knowledge_index_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(64) NULL,
    version INT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    profile VARCHAR(512) NOT NULL,
    profile_sha256 CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempts INT NOT NULL DEFAULT 0,
    next_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    error_code VARCHAR(40) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_index_source FOREIGN KEY (source_id) REFERENCES knowledge_documents(id) ON DELETE SET NULL,
    UNIQUE KEY uq_index_generation (document_id, version, profile_sha256),
    INDEX idx_index_work (state, next_at),
    INDEX idx_index_source (source_id)
);
