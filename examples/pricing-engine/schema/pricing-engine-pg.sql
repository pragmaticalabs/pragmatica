-- Pricing Engine Schema (PostgreSQL)

-- Reference data (seeded at startup)
CREATE TABLE IF NOT EXISTS products (
    product_id VARCHAR(20) PRIMARY KEY,
    price_cents INT NOT NULL
);

CREATE TABLE IF NOT EXISTS discount_codes (
    code VARCHAR(20) PRIMARY KEY,
    percent_off INT NOT NULL
);

CREATE TABLE IF NOT EXISTS tax_rates (
    region_code VARCHAR(10) PRIMARY KEY,
    rate_bps INT NOT NULL  -- basis points (725 = 7.25%)
);

-- Analytics (written at runtime)
CREATE TABLE IF NOT EXISTS high_value_orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id VARCHAR(20) NOT NULL,
    quantity INT NOT NULL,
    total_cents INT NOT NULL,
    region_code VARCHAR(10) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_hvo_created_at ON high_value_orders(created_at);
CREATE INDEX IF NOT EXISTS idx_hvo_region ON high_value_orders(region_code, created_at);

-- Seed data (upsert, safe on restart)
INSERT INTO products (product_id, price_cents) VALUES
    ('WIDGET-A',  999), ('WIDGET-B', 2499), ('WIDGET-C', 4999),
    ('WIDGET-D', 7999), ('WIDGET-E', 14999), ('WIDGET-F', 1299),
    ('WIDGET-G', 3499), ('WIDGET-H', 5999), ('WIDGET-I', 9999),
    ('WIDGET-J', 19999)
ON CONFLICT (product_id) DO UPDATE SET price_cents = EXCLUDED.price_cents;

INSERT INTO discount_codes (code, percent_off) VALUES
    ('SAVE10', 10), ('SAVE20', 20), ('VIP50', 50), ('WELCOME', 5)
ON CONFLICT (code) DO UPDATE SET percent_off = EXCLUDED.percent_off;

INSERT INTO tax_rates (region_code, rate_bps) VALUES
    ('US-CA', 725), ('US-NY', 800), ('US-TX', 625), ('US-FL', 600),
    ('US-WA', 650), ('GB', 2000), ('JP', 1000), ('DE', 1900)
ON CONFLICT (region_code) DO UPDATE SET rate_bps = EXCLUDED.rate_bps;
-- Tax-exempt regions (US-OR, US-MT, US-NH, DE-FREE) have no entry -> 0 tax
