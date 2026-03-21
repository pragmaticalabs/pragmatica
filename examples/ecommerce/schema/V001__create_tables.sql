-- Ecommerce Schema (PostgreSQL)

-- Inventory
CREATE TABLE IF NOT EXISTS products (
    product_id VARCHAR(20) PRIMARY KEY,
    stock INT NOT NULL DEFAULT 0,
    price_cents INT NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_reservations (
    reservation_id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS reservation_items (
    reservation_id VARCHAR(36) NOT NULL REFERENCES stock_reservations(reservation_id),
    product_id VARCHAR(20) NOT NULL REFERENCES products(product_id),
    quantity INT NOT NULL,
    PRIMARY KEY (reservation_id, product_id)
);

-- Payment
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    amount_cents INT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    card_type VARCHAR(20),
    masked_card VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Fulfillment
CREATE TABLE IF NOT EXISTS shipments (
    shipment_id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    tracking_number VARCHAR(36) UNIQUE NOT NULL,
    shipping_option VARCHAR(20) NOT NULL,
    destination_street VARCHAR(255),
    destination_city VARCHAR(100),
    destination_state VARCHAR(10),
    destination_postal VARCHAR(20),
    destination_country VARCHAR(10),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    estimated_delivery TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
