-- Analytics tables (in primary schema for this example).
--
-- In production these would live in a separate database/schema; see the
-- AnalyticsPgSql qualifier for the intended multi-datasource pattern.

CREATE TABLE order_metrics (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_date DATE NOT NULL,
    customer_id BIGINT NOT NULL,
    order_count INTEGER NOT NULL DEFAULT 0,
    revenue NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE daily_snapshot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_date DATE NOT NULL,
    active_customers INTEGER NOT NULL,
    total_orders INTEGER NOT NULL,
    total_revenue NUMERIC(14,2) NOT NULL
);

CREATE INDEX idx_order_metrics_customer ON order_metrics (customer_id);
CREATE INDEX idx_order_metrics_date ON order_metrics (event_date);
CREATE INDEX idx_daily_snapshot_date ON daily_snapshot (snapshot_date);
