-- Banking Account Service Schema
--
-- Two tables, following the layout used by pg-showcase and comprehensive-persistence: account
-- metadata is written once and read often, while the balance row is updated on every credit and
-- every debit. Splitting them keeps the hot row narrow.
--
-- Amount columns are NUMERIC(19, 2) because the domain type (shared/Money) rounds to two decimal
-- places -- the column scale and the value object agree, so no rounding happens on the way in or
-- out.

CREATE TABLE accounts (
    account_id  TEXT PRIMARY KEY,
    holder_name TEXT NOT NULL,
    email       TEXT NOT NULL,
    currency    TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE balances (
    account_id TEXT PRIMARY KEY REFERENCES accounts(account_id),
    amount     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    pending    NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency   TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
