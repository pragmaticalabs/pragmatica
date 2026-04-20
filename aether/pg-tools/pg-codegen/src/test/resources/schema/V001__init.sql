CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status TEXT NOT NULL,
    total NUMERIC NOT NULL,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    event_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE items (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL,
    amount BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE posts (
    id BIGINT PRIMARY KEY,
    tags TEXT[]
);

CREATE TABLE ranks (
    id BIGINT PRIMARY KEY,
    scores INTEGER[]
);

CREATE TABLE ledger (
    account_id UUID NOT NULL,
    amount NUMERIC NOT NULL
);

CREATE TABLE events_uuid (
    id UUID PRIMARY KEY,
    amount NUMERIC NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE blobs (
    id BIGINT PRIMARY KEY,
    payload BYTEA NOT NULL
);

CREATE TABLE foos (
    id BIGINT PRIMARY KEY,
    f TEXT NOT NULL
);
