CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');

CREATE TABLE customers (
    id bigserial NOT NULL,
    email text NOT NULL,
    name text NOT NULL,
    phone varchar(20),
    created_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_customers_email UNIQUE (email)
);

CREATE TABLE products (
    id bigserial NOT NULL,
    name text NOT NULL,
    description text,
    price numeric(10,2) NOT NULL,
    stock_quantity integer DEFAULT 0 NOT NULL,
    tags text[] DEFAULT '{}',
    metadata jsonb DEFAULT '{}' NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_price_positive CHECK (price > 0)
);

CREATE TABLE orders (
    id bigserial NOT NULL,
    customer_id bigint NOT NULL,
    status order_status DEFAULT 'pending' NOT NULL,
    total numeric(10,2) NOT NULL,
    notes text,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT
);

CREATE TABLE order_items (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    product_id bigint NOT NULL,
    quantity integer NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_items_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT chk_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_items_order ON order_items (order_id);
CREATE INDEX idx_items_product ON order_items (product_id);
CREATE INDEX idx_products_tags ON products USING gin (tags);
CREATE INDEX idx_products_metadata ON products USING gin (metadata);
