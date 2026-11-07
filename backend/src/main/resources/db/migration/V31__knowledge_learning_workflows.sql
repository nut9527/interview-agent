CREATE TABLE knowledge_organization (
    document_id VARCHAR(64) PRIMARY KEY,
    category VARCHAR(40) NOT NULL DEFAULT '',
    tags_json JSON NOT NULL,
    CONSTRAINT fk_knowledge_organization_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE
);

CREATE TABLE knowledge_self_tests (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    question_json JSON NOT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_knowledge_self_test_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    INDEX idx_knowledge_self_test_owner (user_id, confirmed, created_at, id)
);

CREATE TABLE knowledge_answer_parents (
    answer_id VARCHAR(64) PRIMARY KEY,
    parent_id VARCHAR(64) NULL,
    CONSTRAINT fk_knowledge_child_answer FOREIGN KEY (answer_id) REFERENCES knowledge_answers(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_parent_answer FOREIGN KEY (parent_id) REFERENCES knowledge_answers(id) ON DELETE SET NULL
);

CREATE TABLE knowledge_answer_feedback (
    answer_id VARCHAR(64) PRIMARY KEY,
    reasons_json JSON NOT NULL,
    comment VARCHAR(1000) NOT NULL,
    missing_sources_json JSON NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_knowledge_feedback_answer FOREIGN KEY (answer_id) REFERENCES knowledge_answers(id) ON DELETE CASCADE
);
