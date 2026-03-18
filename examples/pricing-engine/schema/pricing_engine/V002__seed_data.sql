-- Pricing Engine Seed Data (H2)
-- MERGE = upsert, safe on restart with persistent DB

MERGE INTO products (product_id, price_cents) VALUES
    ('WIDGET-A',  999), ('WIDGET-B', 2499), ('WIDGET-C', 4999),
    ('WIDGET-D', 7999), ('WIDGET-E', 14999), ('WIDGET-F', 1299),
    ('WIDGET-G', 3499), ('WIDGET-H', 5999), ('WIDGET-I', 9999),
    ('WIDGET-J', 19999);

MERGE INTO discount_codes (code, percent_off) VALUES
    ('SAVE10', 10), ('SAVE20', 20), ('VIP50', 50), ('WELCOME', 5);

MERGE INTO tax_rates (region_code, rate_bps) VALUES
    ('US-CA', 725), ('US-NY', 800), ('US-TX', 625), ('US-FL', 600),
    ('US-WA', 650), ('GB', 2000), ('JP', 1000), ('DE', 1900);
-- Tax-exempt regions (US-OR, US-MT, US-NH, DE-FREE) have no entry -> 0 tax
