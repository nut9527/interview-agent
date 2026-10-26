ALTER TABLE resumes ADD COLUMN details_json LONGTEXT NULL;

CREATE TABLE resume_assessments (
    id VARCHAR(40) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_assessment_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE INDEX idx_assessment_user_created ON resume_assessments(user_id, created_at);
