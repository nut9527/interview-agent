-- Stored projections are derived from the historical snapshot itself, never the current resume.
-- MySQL backfills existing rows and maintains these columns atomically on future writes.
ALTER TABLE resume_assessments
    ADD COLUMN owner_name VARCHAR(80) GENERATED ALWAYS AS (LEFT(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.input.resume.ownerName')), 80)) STORED,
    ADD COLUMN target_role VARCHAR(120) GENERATED ALWAYS AS (LEFT(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.result.roleFit.targetRole')), 120)) STORED,
    ADD COLUMN source VARCHAR(32) GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.source'))) STORED,
    ADD COLUMN resume_revision BIGINT GENERATED ALWAYS AS (CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.result.resumeRevision')), 'null') AS SIGNED)) STORED,
    ADD COLUMN rule_version VARCHAR(80) GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.result.ruleVersion'))) STORED,
    ADD COLUMN total_score INT GENERATED ALWAYS AS (JSON_EXTRACT(snapshot_json, '$.result.totalScore')) STORED,
    ADD INDEX idx_assessment_page (user_id, created_at DESC, id DESC);

ALTER TABLE career_reports
    ADD COLUMN rule_version VARCHAR(80) GENERATED ALWAYS AS (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(report_json, '$.diagnosis.ruleVersion')), 'null')) STORED;

-- Existing report and version indexes include the primary key implicitly in InnoDB.
-- Reverse scans already satisfy their stable order; avoid duplicate indexes.

ALTER TABLE interview_sessions
    ADD COLUMN answered_count INT NOT NULL DEFAULT 0,
    ADD COLUMN preview VARCHAR(161) NOT NULL DEFAULT '',
    ADD INDEX idx_interview_page (user_id, updated_at DESC, session_id DESC);

UPDATE interview_sessions s SET
    answered_count = (SELECT COUNT(*) FROM JSON_TABLE(s.turns_json, '$[*]' COLUMNS(speaker VARCHAR(32) PATH '$.speaker')) t WHERE t.speaker = 'user'),
    preview = COALESCE((SELECT CASE WHEN CHAR_LENGTH(TRIM(t.content)) > 160 THEN CONCAT(LEFT(TRIM(t.content),160), '…') ELSE TRIM(t.content) END
        FROM JSON_TABLE(s.turns_json, '$[*]' COLUMNS(seq FOR ORDINALITY, speaker VARCHAR(32) PATH '$.speaker', content TEXT PATH '$.content')) t
        WHERE t.speaker = 'interviewer' ORDER BY seq DESC LIMIT 1), '暂无面试内容');

ALTER TABLE job_postings
    ADD COLUMN closing_date DATE NULL,
    ADD COLUMN closed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN salary_value DOUBLE NULL,
    ADD COLUMN description_preview VARCHAR(160) GENERATED ALWAYS AS (LEFT(description,160)) STORED,
    ADD INDEX idx_job_page (active, published_at DESC, id DESC);
