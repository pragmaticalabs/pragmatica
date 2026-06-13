-- Catalog domain: product categories and the product enum type.
CREATE TYPE order_status AS ENUM ('pending', 'shipped', 'delivered', 'cancelled');

CREATE SEQUENCE product_code_seq START WITH 1000 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE categories (
    id BIGINT NOT NULL,
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uq_categories_slug UNIQUE (slug),
    CONSTRAINT chk_name_not_blank CHECK (length(name) > 0)
);

CREATE TABLE products (
    id BIGINT NOT NULL,
    tenant_id INTEGER NOT NULL,
    category_id BIGINT,
    external_id UUID NOT NULL DEFAULT gen_random_uuid(),
    code INTEGER NOT NULL DEFAULT nextval('product_code_seq'),
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    tags TEXT[],
    metadata JSONB NOT NULL DEFAULT '{}',
    launch_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT uq_products_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT chk_products_price CHECK (price >= 0)
);

CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_metadata ON products USING gin (metadata);
