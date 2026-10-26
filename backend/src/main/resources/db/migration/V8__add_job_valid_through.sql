ALTER TABLE job_postings
    ADD COLUMN valid_through VARCHAR(40) NOT NULL DEFAULT '' AFTER published_at;
