-- Additive: existing interview subjects, commands, events and LEGACY tables remain valid.
ALTER TABLE interview_runs MODIFY session_id VARCHAR(48) NULL, MODIFY expected_turn INT NULL,
 ADD COLUMN subject_type VARCHAR(20) NOT NULL DEFAULT 'INTERVIEW',
 ADD COLUMN subject_id VARCHAR(64) NULL, ADD COLUMN subject_revision BIGINT NULL;
UPDATE interview_runs SET subject_id=session_id WHERE subject_type='INTERVIEW';
CREATE TABLE learning_goals (
 id VARCHAR(48) PRIMARY KEY, owner_id BIGINT NOT NULL, request_id VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL, title VARCHAR(160) NOT NULL, competency_id VARCHAR(80) NOT NULL,
 completion_criterion VARCHAR(1000) NOT NULL, required_scenarios INT NOT NULL,
 question_ids_json TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', revision BIGINT NOT NULL DEFAULT 1,
 max_runs INT NOT NULL, max_training INT NOT NULL, max_model_attempts INT NOT NULL, max_tools INT NOT NULL,
 reserved_runs INT NOT NULL DEFAULT 0, reserved_training INT NOT NULL DEFAULT 0,
 reserved_model_attempts INT NOT NULL DEFAULT 0, reserved_tools INT NOT NULL DEFAULT 0,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_goal_request(owner_id,request_id),
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE learning_goal_runs (
 goal_id VARCHAR(48) NOT NULL, run_id VARCHAR(48) NOT NULL PRIMARY KEY,
 training BOOLEAN NOT NULL, model_attempts INT NOT NULL, tools INT NOT NULL,
 FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE,
 FOREIGN KEY(run_id) REFERENCES interview_runs(id) ON DELETE CASCADE
);
-- Survives removing a task, so deleting it cannot evade the Mission budget.
CREATE TABLE learning_goal_sessions (
 owner_id BIGINT NOT NULL, goal_id VARCHAR(48) NOT NULL, session_id VARCHAR(64) PRIMARY KEY,
 task_id VARCHAR(64) NOT NULL, question_id VARCHAR(64) NOT NULL,
 FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE,
 FOREIGN KEY(session_id) REFERENCES interview_sessions(session_id) ON DELETE CASCADE
);
CREATE TABLE learning_plans (
 id VARCHAR(48) PRIMARY KEY, owner_id BIGINT NOT NULL, goal_id VARCHAR(48) NOT NULL,
 revision INT NOT NULL DEFAULT 1, status VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
 content_hash CHAR(64) NOT NULL, items_json TEXT NOT NULL, limitation VARCHAR(1000) NOT NULL,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE,
 FOREIGN KEY(owner_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE learning_plan_revisions (
 plan_id VARCHAR(48) NOT NULL, revision INT NOT NULL, status VARCHAR(24) NOT NULL,
 content_hash CHAR(64) NOT NULL, items_json TEXT NOT NULL,
 PRIMARY KEY(plan_id,revision), FOREIGN KEY(plan_id) REFERENCES learning_plans(id) ON DELETE CASCADE
);
CREATE TABLE learning_plan_commands (
 owner_id BIGINT NOT NULL, request_id VARCHAR(80) NOT NULL, plan_id VARCHAR(48) NOT NULL,
 revision INT NOT NULL, PRIMARY KEY(owner_id,request_id),
 FOREIGN KEY(plan_id) REFERENCES learning_plans(id) ON DELETE CASCADE
);
CREATE TABLE learning_plan_applications (
 owner_id BIGINT NOT NULL, plan_id VARCHAR(48) NOT NULL, revision INT NOT NULL,
 item_key VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(owner_id,plan_id,revision,item_key),
 FOREIGN KEY(plan_id) REFERENCES learning_plans(id) ON DELETE CASCADE
);
ALTER TABLE interview_training_tasks MODIFY source_session_id VARCHAR(64) NULL,
 ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
 ADD COLUMN goal_id VARCHAR(48) NULL, ADD COLUMN plan_id VARCHAR(48) NULL,
 ADD COLUMN plan_revision INT NULL, ADD COLUMN plan_item_key VARCHAR(64) NULL,
 ADD COLUMN question_version INT NULL,
 ADD UNIQUE KEY uq_training_plan(user_id,plan_id,plan_revision,plan_item_key),
 ADD CONSTRAINT fk_training_goal FOREIGN KEY(goal_id) REFERENCES learning_goals(id) ON DELETE CASCADE;
