CREATE TABLE knowledge_versions (
    document_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    title VARCHAR(160) NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (document_id, version),
    CONSTRAINT fk_knowledge_version FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE
);

-- Archive existing originals without changing their IDs, offsets or chunk IDs.
INSERT INTO knowledge_versions(document_id,version,title,source_name,content,created_at)
SELECT id,version,title,source_name,content,updated_at FROM knowledge_documents;

ALTER TABLE knowledge_chunks
    ADD COLUMN version INT NOT NULL DEFAULT 1,
    DROP INDEX idx_knowledge_position,
    ADD UNIQUE KEY idx_knowledge_position (document_id,version,ordinal);
