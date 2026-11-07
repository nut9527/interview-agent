CREATE TABLE request_quotas (
    scope_key CHAR(64) NOT NULL,
    window_start BIGINT NOT NULL,
    used_hits INT NOT NULL DEFAULT 0,
    used_units INT NOT NULL DEFAULT 0,
    expires_at DATETIME NOT NULL,
    PRIMARY KEY (scope_key, window_start),
    INDEX idx_request_quotas_expiry (expires_at)
);

CREATE TABLE demo_capacity_lock (
    id TINYINT PRIMARY KEY
);

INSERT INTO demo_capacity_lock (id) VALUES (1);
