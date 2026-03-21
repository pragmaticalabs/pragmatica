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
