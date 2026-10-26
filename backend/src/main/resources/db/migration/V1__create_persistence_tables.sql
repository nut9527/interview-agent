CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    target_role VARCHAR(100) NOT NULL DEFAULT '',
    skill_tags LONGTEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS resumes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    owner_name VARCHAR(80) NOT NULL,
    target_role VARCHAR(120) NOT NULL,
    summary TEXT,
    skills_json LONGTEXT NOT NULL,
    education_json LONGTEXT NOT NULL,
    projects_json LONGTEXT NOT NULL,
    internships_json LONGTEXT NOT NULL,
    certificates_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS job_postings (
    id BIGINT PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    company VARCHAR(180) NOT NULL,
    required_skills_json LONGTEXT NOT NULL,
    description TEXT,
    location VARCHAR(200),
    source_type VARCHAR(20) NOT NULL,
    source_name VARCHAR(120),
    source_url VARCHAR(2048),
    published_at VARCHAR(40),
    fetched_at VARCHAR(40),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS career_reports (
    report_id VARCHAR(48) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    resume_id BIGINT,
    owner_name VARCHAR(80) NOT NULL,
    target_role VARCHAR(120) NOT NULL,
    total_score INT NOT NULL,
    readiness_level VARCHAR(40) NOT NULL,
    report_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_sessions (
    session_id VARCHAR(48) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    target_role VARCHAR(120) NOT NULL,
    state VARCHAR(32) NOT NULL,
    turn_count INT NOT NULL,
    turns_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sessions_user ON app_sessions (user_id);
CREATE INDEX idx_sessions_expiry ON app_sessions (expires_at);
CREATE INDEX idx_resumes_user ON resumes (user_id);
CREATE INDEX idx_jobs_source ON job_postings (source_type);
CREATE INDEX idx_reports_user_created ON career_reports (user_id, created_at);
CREATE INDEX idx_interviews_user ON interview_sessions (user_id);
