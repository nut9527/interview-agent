CREATE TABLE user_target_jobs (
    id VARCHAR(40) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(160) NOT NULL,
    company VARCHAR(160) NOT NULL DEFAULT '',
    source_name VARCHAR(160) NOT NULL DEFAULT '',
    source_url VARCHAR(1000) NOT NULL DEFAULT '',
    deadline DATE NULL,
    jd_text MEDIUMTEXT NOT NULL,
    requirements_json MEDIUMTEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_target_job_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    INDEX idx_target_job_user_updated (user_id, updated_at, id)
);
