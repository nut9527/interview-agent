CREATE TABLE IF NOT EXISTS user_favorite_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favorite_user_job UNIQUE (user_id, job_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE
);

CREATE INDEX idx_favorites_user_created ON user_favorite_jobs (user_id, created_at);
