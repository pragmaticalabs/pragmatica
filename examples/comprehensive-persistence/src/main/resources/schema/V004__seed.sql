-- Primary schema seed data for local smoke runs.
--
-- Analytics-specific tables live under `schema/analytics/` and are loaded via
-- the @AnalyticsPgSql qualifier; this file only touches primary tables.

INSERT INTO customers (name, email, tier) VALUES
    ('Alice', 'alice@example.com', 'gold'),
    ('Bob', 'bob@example.com', 'silver'),
    ('Carol', 'carol@example.com', 'bronze');

INSERT INTO products (sku, name, price) VALUES
    ('SKU-001', 'Widget', 19.99),
    ('SKU-002', 'Gadget', 49.50),
    ('SKU-003', 'Gizmo', 99.00);
