ALTER TABLE question_bank
    ADD COLUMN version INT NOT NULL DEFAULT 1,
    ADD COLUMN published BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT chk_question_version CHECK (version > 0),
    ADD INDEX idx_question_published_topic_difficulty (published,topic,difficulty,id);

CREATE TABLE question_bank_versions (
    question_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    content_json JSON NOT NULL,
    change_note VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (question_id,version),
    CONSTRAINT fk_question_version FOREIGN KEY (question_id) REFERENCES question_bank(id),
    CONSTRAINT chk_question_version_content CHECK (JSON_TYPE(content_json)='OBJECT' AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO question_bank_versions (question_id,version,content_json,change_note)
SELECT id,version,JSON_OBJECT(
'id',id,'version',version,'title',title,'question',question,'topic',topic,'competencyId',competency_id,
'difficulty',difficulty,'tags',tags_json,'keyPoints',key_points_json,'referenceAnswer',reference_answer,
'commonMistakes',common_mistakes_json,'followUps',follow_ups_json,
'sourceType',source_type,'sourceTitle',source_title,'sourceUrl',source_url),'V1 原创题归档，开始版本管理' FROM question_bank;

-- The session remains the atomic source of question, answers and feedback.
-- JSON null in old checkpoints must become SQL NULL, not a literal 'null' ID.
ALTER TABLE interview_sessions
    ADD COLUMN question_bank_id VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN JSON_TYPE(JSON_EXTRACT(agent_json,'$.training.question.id')) = 'STRING'
        THEN JSON_UNQUOTE(JSON_EXTRACT(agent_json,'$.training.question.id')) ELSE NULL END
    ) STORED,
    ADD INDEX idx_interview_question_history (user_id,question_bank_id,created_at,session_id);

