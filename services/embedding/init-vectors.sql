\set ON_ERROR_STOP on
\getenv app_password AGENT_VECTOR_PG_PASSWORD
CREATE EXTENSION vector;
CREATE ROLE agent_vectors LOGIN PASSWORD :'app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE TABLE knowledge_vectors (
    embedding_id UUID PRIMARY KEY,
    embedding vector(512),
    text TEXT,
    metadata JSON
);
GRANT CONNECT ON DATABASE agent_vectors TO agent_vectors;
GRANT USAGE ON SCHEMA public TO agent_vectors;
GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_vectors TO agent_vectors;
