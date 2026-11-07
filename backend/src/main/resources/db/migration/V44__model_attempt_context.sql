-- Outbound projection diagnostics only. Do not fabricate measurements for historical attempts.
ALTER TABLE interview_model_attempts
    ADD COLUMN context_version VARCHAR(40) NULL,
    ADD COLUMN input_chars INT NULL,
    ADD COLUMN tool_chars INT NULL,
    ADD COLUMN omitted_tool_results INT NULL;
