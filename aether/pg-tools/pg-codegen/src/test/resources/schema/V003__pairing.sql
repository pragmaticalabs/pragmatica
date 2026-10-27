-- #2401: identifiers that are keywords or quoted with upper case, a quoted json column beside an unquoted text column of
-- the same letters, and a table with a text column named like a json column of `documents`.
CREATE TABLE kw (
    id BIGINT PRIMARY KEY,
    "order" JSONB,
    value JSONB,
    "Doc" JSONB,
    doc TEXT
);

CREATE TABLE other (
    id BIGINT PRIMARY KEY,
    profile TEXT,
    name TEXT
);
