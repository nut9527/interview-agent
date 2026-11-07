-- Source results, catalog revision and task completion are published in one transaction.
ALTER TABLE job_postings
    MODIFY id BIGINT NOT NULL AUTO_INCREMENT,
    ADD COLUMN remote_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD UNIQUE KEY uk_job_remote_key (remote_key);

CREATE TABLE job_catalog_state (
    id INT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    active_run_id CHAR(36) NULL,
    last_success_at DATETIME(6) NULL
);
INSERT INTO job_catalog_state (id) VALUES (1);

CREATE TABLE job_refresh_runs (
    id CHAR(36) PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    summary VARCHAR(500) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    INDEX idx_refresh_created (created_at, id)
);

CREATE TABLE job_refresh_tasks (
    id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    source_name VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    config_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    error_code VARCHAR(40) NOT NULL DEFAULT '',
    summary VARCHAR(500) NOT NULL DEFAULT '',
    result_count INT NOT NULL DEFAULT 0,
    catalog_revision BIGINT NULL,
    CONSTRAINT fk_refresh_run FOREIGN KEY (run_id) REFERENCES job_refresh_runs(id),
    UNIQUE KEY uk_refresh_source (run_id, source_name),
    INDEX idx_refresh_due (status, next_attempt_at),
    INDEX idx_refresh_lease (status, lease_until)
);

CREATE TABLE job_refresh_attempts (
    task_id CHAR(36) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    error_code VARCHAR(40) NOT NULL DEFAULT '',
    summary VARCHAR(500) NOT NULL DEFAULT '',
    PRIMARY KEY (task_id, attempt),
    CONSTRAINT fk_refresh_attempt_task FOREIGN KEY (task_id) REFERENCES job_refresh_tasks(id)
);
