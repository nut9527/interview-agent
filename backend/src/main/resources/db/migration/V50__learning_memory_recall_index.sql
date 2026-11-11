CREATE INDEX idx_learning_recall
    ON learning_observations(owner_id,execution_mode,status,competency_id,created_at);
