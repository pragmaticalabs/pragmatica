-- json/jsonb document columns (#1779): a jsonb and a json column, a nullable jsonb, and text columns
-- (one with a two-word name) that a @Jsonb target must not map to, and a two-word jsonb column.
CREATE TABLE documents (
    id BIGINT PRIMARY KEY,
    profile JSONB NOT NULL,
    previous JSONB,
    legacy JSON NOT NULL,
    title TEXT NOT NULL,
    nick TEXT,
    document_title TEXT,
    extra_doc JSONB
);
