-- Inventory Service Schema

CREATE TABLE IF NOT EXISTS products (
    product_id VARCHAR(64) PRIMARY KEY,
    stock INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS stock_reservations (
    reservation_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS reservation_items (
    reservation_id VARCHAR(64) NOT NULL REFERENCES stock_reservations(reservation_id),
    product_id VARCHAR(64) NOT NULL REFERENCES products(product_id),
    quantity INT NOT NULL,
    PRIMARY KEY (reservation_id, product_id)
);

CREATE INDEX IF NOT EXISTS idx_reservations_order ON stock_reservations(order_id);
CREATE INDEX IF NOT EXISTS idx_reservations_expires ON stock_reservations(expires_at);
