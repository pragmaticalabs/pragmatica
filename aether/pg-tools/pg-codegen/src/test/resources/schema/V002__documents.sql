-- json/jsonb document columns (#1779): a jsonb and a json column, a nullable jsonb, and a text column
-- that a @Jsonb target must not map to.
CREATE TABLE documents (
    id BIGINT PRIMARY KEY,
    profile JSONB NOT NULL,
    previous JSONB,
    legacy JSON NOT NULL,
    title TEXT NOT NULL,
    nick TEXT
);
