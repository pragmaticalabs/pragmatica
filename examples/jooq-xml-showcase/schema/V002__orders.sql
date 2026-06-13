-- Orders domain: customer orders with line items, exercising composite PK and cascading FK.
CREATE TABLE orders (
    id BIGINT NOT NULL,
    customer_email TEXT NOT NULL,
    status order_status NOT NULL DEFAULT 'pending',
    total NUMERIC(12,2) NOT NULL DEFAULT 0,
    placed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT chk_orders_total CHECK (total >= 0)
);

CREATE TABLE order_lines (
    order_id BIGINT NOT NULL,
    line_no INTEGER NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(10,2) NOT NULL,
    CONSTRAINT pk_order_lines PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_lines_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_order_lines_qty CHECK (quantity > 0)
);

CREATE INDEX idx_order_lines_product ON order_lines (product_id);
