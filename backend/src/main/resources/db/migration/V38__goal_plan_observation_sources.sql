-- Pin identifiers and revisions only; do not duplicate personal observation payloads.
ALTER TABLE learning_plans ADD COLUMN observation_refs_json TEXT NULL;
