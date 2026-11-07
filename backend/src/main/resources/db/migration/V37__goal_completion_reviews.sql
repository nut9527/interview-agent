-- Additive only. Old criteria remain the two original dimensions until explicitly revised.
ALTER TABLE learning_goals
 ADD COLUMN criteria_revision BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN dimensions_json VARCHAR(500) NOT NULL DEFAULT '["CAUSAL_EXPLANATION","RESPONSIBILITY"]';
CREATE TABLE learning_goal_criteria (
 goal_id VARCHAR(48) NOT NULL, revision BIGINT NOT NULL,
 required_scenarios INT NOT NULL, dimensions_json VARCHAR(500) NOT NULL,
 confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(goal_id,revision),
 FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE
);
INSERT INTO learning_goal_criteria(goal_id,revision,required_scenarios,dimensions_json)
 SELECT id,criteria_revision,required_scenarios,dimensions_json FROM learning_goals;
CREATE TABLE learning_goal_completions (
 goal_id VARCHAR(48) NOT NULL, owner_id BIGINT NOT NULL,
 request_id VARCHAR(80) NOT NULL, request_hash CHAR(64) NOT NULL,
 goal_revision BIGINT NOT NULL, criteria_revision BIGINT NOT NULL, evidence_hash CHAR(64) NOT NULL,
 review_note VARCHAR(1000) NOT NULL, review_kind VARCHAR(32) NOT NULL DEFAULT 'USER_ATTESTED',
 confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(owner_id,request_id), UNIQUE KEY uq_goal_completion_revision(goal_id,goal_revision),
 FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE
);
