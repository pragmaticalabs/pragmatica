-- State-stored durable entities (#2100 E-B): one row per (entity, entity_key).
-- version starts at 1 on create and increases by exactly 1 on every committed update; it is the compare-and-set token.
CREATE TABLE IF NOT EXISTS durable_entity_state (
    entity      TEXT        NOT NULL,
    entity_key  TEXT        NOT NULL,
    version     BIGINT      NOT NULL,
    state       BYTEA       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity, entity_key)
);
