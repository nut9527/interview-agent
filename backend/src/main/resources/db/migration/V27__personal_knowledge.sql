CREATE TABLE knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_knowledge_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    INDEX idx_knowledge_owner (user_id, updated_at, id)
);
CREATE TABLE knowledge_chunks (
    id VARCHAR(80) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    heading VARCHAR(500) NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    content TEXT NOT NULL,
    CONSTRAINT fk_knowledge_chunk FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    UNIQUE KEY idx_knowledge_position (document_id, ordinal)
);
