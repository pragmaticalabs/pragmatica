-- PostgreSQL Persistence Showcase - Advanced Column Types
--
-- Demonstrates how the compile-time SQL validator handles advanced Postgres
-- column types and constraints: JSONB, UUID, ARRAY, GENERATED, CHECK,
-- partial indexes, and a named ENUM type.

-- Named ENUM type. The validator recognises it; example queries treat
-- status values as strings (TEXT) for simplicity.
CREATE TYPE order_status AS ENUM (
    'pending',
    'paid',
    'shipped',
    'delivered',
    'cancelled'
);

-- Soft-delete marker column on users (demonstrates IsNull / IsNotNull operators).
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

-- Advanced columns on orders: JSONB, UUID, ARRAY, GENERATED.
ALTER TABLE orders ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';
ALTER TABLE orders ADD COLUMN correlation_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE orders ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE orders ADD COLUMN total_with_tax NUMERIC(12,2) GENERATED ALWAYS AS (total * 1.08) STORED;

-- Domain constraint: total must be positive.
ALTER TABLE orders ADD CONSTRAINT orders_total_positive CHECK (total > 0);

-- Partial index excluding cancelled orders.
CREATE INDEX idx_orders_active_status ON orders (status) WHERE status <> 'cancelled';

-- GIN indexes for JSONB and ARRAY columns.
CREATE INDEX idx_orders_metadata ON orders USING gin (metadata);
CREATE INDEX idx_orders_tags ON orders USING gin (tags);
