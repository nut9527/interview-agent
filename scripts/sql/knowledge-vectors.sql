-- Explicit provisioning on the dedicated derived-index database only.
-- psql --set=ON_ERROR_STOP=1 --set=dimension=384 --file=scripts/sql/knowledge-vectors.sql
-- The application has no DDL permission and never invokes this script.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS knowledge_vectors (
    embedding_id UUID PRIMARY KEY,
    embedding vector(:dimension),
    text TEXT NULL,
    metadata JSON NULL
);
-- Exact search is intentional for the bounded development corpus; benchmark before adding ANN.
