ALTER TABLE job_postings
    ADD COLUMN salary VARCHAR(80) NOT NULL DEFAULT '薪资面议' AFTER company;

UPDATE job_postings SET salary = '8k-15k/月' WHERE id = 1;
UPDATE job_postings SET salary = '7k-12k/月' WHERE id = 2;
UPDATE job_postings SET salary = '10k-18k/月' WHERE id = 3;
UPDATE job_postings SET salary = '6k-10k/月' WHERE id = 4;
