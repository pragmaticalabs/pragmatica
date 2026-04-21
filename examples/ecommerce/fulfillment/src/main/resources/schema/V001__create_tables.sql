-- Fulfillment Service Schema

CREATE TABLE IF NOT EXISTS shipments (
    shipment_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    tracking_number VARCHAR(128),
    shipping_option VARCHAR(32) NOT NULL,
    destination_street VARCHAR(256),
    destination_city VARCHAR(128),
    destination_state VARCHAR(64),
    destination_postal VARCHAR(20),
    destination_country VARCHAR(3),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    estimated_delivery TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_shipments_order ON shipments(order_id);
CREATE INDEX IF NOT EXISTS idx_shipments_status ON shipments(status);
