-- History belongs to an account, independently of the lifetime of source documents.
CREATE TABLE knowledge_answers (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    question VARCHAR(300) NOT NULL,
    documents_json JSON NOT NULL,
    answer_json JSON NOT NULL,
    sufficient BOOLEAN NOT NULL,
    execution_mode VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_knowledge_answer_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    INDEX idx_knowledge_answer_owner (user_id, created_at, id)
);
