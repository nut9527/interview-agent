ALTER TABLE resumes
    ADD COLUMN template_key VARCHAR(32) NOT NULL DEFAULT 'classic' AFTER certificates_json;

CREATE TABLE resume_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    change_note VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_resume_version UNIQUE (resume_id, version_number)
);

CREATE INDEX idx_resume_versions_user_resume
    ON resume_versions (user_id, resume_id, version_number);
