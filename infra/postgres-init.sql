-- Runs once, on first start of the PostgreSQL container.
-- Keycloak gets its own database and role; core never reads it.
\set kc_password `echo "$KEYCLOAK_DB_PASSWORD"`
CREATE ROLE keycloak LOGIN PASSWORD :'kc_password';
CREATE DATABASE keycloak OWNER keycloak;
-- pgvector is available for later retrieval features; nothing in Phase 1 uses it.
CREATE EXTENSION IF NOT EXISTS vector;
