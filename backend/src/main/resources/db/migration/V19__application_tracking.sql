CREATE TABLE tracked_applications (
    id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    stage VARCHAR(24) NOT NULL,
    title VARCHAR(160) NOT NULL,
    company VARCHAR(160) NOT NULL,
    interview_rounds INT NOT NULL,
    next_action VARCHAR(1000) NOT NULL,
    next_action_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_tracking_owner (user_id, id),
    INDEX idx_tracking_user_stage_updated (user_id, stage, updated_at, id),
    INDEX idx_tracking_user_updated (user_id, updated_at, id),
    CONSTRAINT fk_tracking_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);

CREATE TABLE application_events (
    user_id BIGINT NOT NULL,
    application_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY (user_id, application_id, sequence_no),
    CONSTRAINT fk_tracking_event FOREIGN KEY (user_id, application_id)
        REFERENCES tracked_applications(user_id, id) ON DELETE CASCADE
);

-- The row reservation and receipt commit in the same transaction as state and event.
CREATE TABLE application_commands (
    user_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json MEDIUMTEXT NULL,
    PRIMARY KEY (user_id, request_id),
    CONSTRAINT fk_tracking_command_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);
