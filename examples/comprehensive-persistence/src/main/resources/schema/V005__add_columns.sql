-- Adds a native PostgreSQL array column to products so the example can
-- exercise TEXT[] → String[] mapping end-to-end.

ALTER TABLE products ADD COLUMN tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

CREATE INDEX idx_products_tags ON products USING gin (tags);
