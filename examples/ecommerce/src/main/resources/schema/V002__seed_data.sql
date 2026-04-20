-- Ecommerce Seed Data (PostgreSQL)

INSERT INTO products (product_id, stock, price_cents) VALUES
    ('LAPTOP-PRO', 50, 99999),
    ('MOUSE-WIRELESS', 200, 4999),
    ('KEYBOARD-MECH', 100, 14999),
    ('MONITOR-4K', 30, 59999),
    ('HEADSET-BT', 75, 7999),
    ('WEBCAM-HD', 60, 8999),
    ('USB-HUB', 150, 2999),
    ('CHARGER-65W', 120, 3999)
ON CONFLICT (product_id) DO NOTHING;
