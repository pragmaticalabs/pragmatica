-- Comprehensive persistence example - advanced column types.

CREATE TYPE order_status AS ENUM (
    'pending',
    'paid',
    'shipped',
    'delivered',
    'cancelled'
);

ALTER TABLE customers ADD COLUMN tier TEXT NOT NULL DEFAULT 'bronze';
ALTER TABLE customers ADD COLUMN preferences JSONB NOT NULL DEFAULT '{}';

ALTER TABLE orders ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';
ALTER TABLE orders ADD COLUMN correlation_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE orders ADD COLUMN total_with_tax NUMERIC(12,2) GENERATED ALWAYS AS (total * 1.08) STORED;

ALTER TABLE products ADD COLUMN description TEXT;
ALTER TABLE products ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}';

ALTER TABLE orders ADD CONSTRAINT orders_total_positive CHECK (total > 0);

CREATE INDEX idx_orders_metadata ON orders USING gin (metadata);
CREATE INDEX idx_customers_preferences ON customers USING gin (preferences);
CREATE INDEX idx_products_attributes ON products USING gin (attributes);

CREATE INDEX idx_orders_active_status ON orders (status) WHERE status <> 'cancelled';
