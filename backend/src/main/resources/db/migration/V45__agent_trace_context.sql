-- Optional telemetry context only; never used for ownership, deduplication or recovery decisions.
ALTER TABLE interview_runs
    ADD COLUMN trace_id CHAR(32) NULL,
    ADD COLUMN trace_parent_span_id CHAR(16) NULL,
    ADD COLUMN trace_sampled BOOLEAN NULL;
