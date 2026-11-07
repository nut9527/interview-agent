-- Retired product data is intentionally removed. Keep V1-V48 (including V17's
-- Java migration) intact so existing installations can validate and upgrade.
-- Drop dependent tables before their parents; keep foreign-key checks enabled.
DROP TABLE IF EXISTS job_refresh_attempts;
DROP TABLE IF EXISTS job_refresh_tasks;
DROP TABLE IF EXISTS job_refresh_runs;
DROP TABLE IF EXISTS job_catalog_state;

DROP TABLE IF EXISTS user_favorite_jobs;
DROP TABLE IF EXISTS job_postings;

DROP TABLE IF EXISTS application_events;
DROP TABLE IF EXISTS application_commands;
DROP TABLE IF EXISTS tracked_applications;

DROP TABLE IF EXISTS resume_assessments;
DROP TABLE IF EXISTS career_reports;

DROP TABLE IF EXISTS agent_steps;
DROP TABLE IF EXISTS agent_events;
DROP TABLE IF EXISTS agent_commands;
DROP TABLE IF EXISTS agent_runs;
DROP TABLE IF EXISTS agent_dispatch_lock;
