-- #2795: one nullable column per column type, for the CRUD nullable-column check (NullableColumnCrudValidationTest).
-- ratio is FLOAT8 (the same type as DOUBLE PRECISION): the pg-parser grammar does not parse DOUBLE PRECISION as a column type.
CREATE TABLE nullables (
    id BIGINT PRIMARY KEY,
    note TEXT,
    qty INTEGER,
    big_n BIGINT,
    small_n SMALLINT,
    ratio FLOAT8,
    score REAL,
    flag BOOLEAN,
    amount NUMERIC(12,2),
    born_on DATE,
    seen_at TIMESTAMP,
    seen_tz TIMESTAMPTZ,
    at_time TIME,
    ident UUID,
    payload BYTEA,
    tags TEXT[],
    nums INTEGER[],
    doc JSONB,
    kept TEXT NOT NULL DEFAULT 'x'
);
