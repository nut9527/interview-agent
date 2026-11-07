-- Keep legacy REVIEW/GOAL tasks and their progress intact. Only references are stored here.
ALTER TABLE interview_training_tasks
 ADD COLUMN source_document_id VARCHAR(64) NULL,
 ADD COLUMN source_document_version INT NULL,
 ADD COLUMN source_document_hash CHAR(64) NULL,
 ADD COLUMN training_topic VARCHAR(32) NULL,
 ADD COLUMN request_id VARCHAR(80) NULL,
 ADD COLUMN request_hash CHAR(64) NULL,
 ADD UNIQUE KEY uq_training_direct_request(user_id,request_id);
