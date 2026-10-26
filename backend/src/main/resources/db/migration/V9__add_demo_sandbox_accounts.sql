ALTER TABLE app_users
    ADD COLUMN account_type VARCHAR(24) NOT NULL DEFAULT 'STANDARD' AFTER skill_tags,
    ADD COLUMN demo_expires_at TIMESTAMP NULL AFTER account_type;

CREATE INDEX idx_app_users_demo_expiry
    ON app_users (account_type, demo_expires_at);
