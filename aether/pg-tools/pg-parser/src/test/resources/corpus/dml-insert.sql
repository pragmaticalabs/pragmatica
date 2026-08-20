-- Harvested from SQL string literals in aether/ and examples/ Java sources.
-- REAL queries the codebase issues, not hand-written fixtures: the CST differential's value
-- depends on the corpus being independently motivated, so it exercises shapes nobody thought
-- to assert. If a statement here stops parsing, fix the parser or re-harvest -- never edit
-- the statement to make it pass.
--
-- INVARIANT: exactly one statement per non-comment line. CorpusParseTest checks the facade's
-- statement count against that, which is what caught the _ROOT wrapper collapsing every
-- script to a single statement.

INSERT INTO archive SELECT * FROM users WHERE active = false;
INSERT INTO customers (id, name, email, active, created_at, deleted_at, tier, preferences) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (id) DO UPDATE SET name = $2, email = $3, active = $4, created_at = $5, deleted_at = $6, tier = $7, preferences = $8 RETURNING *;
INSERT INTO nonexistent (id) VALUES (1);
INSERT INTO order_metrics (event_date, customer_id, order_count, revenue) VALUES ($1, $2, $3, $4) RETURNING *;
INSERT INTO order_metrics (id, event_date, customer_id, order_count, revenue, created_at) VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (id) DO UPDATE SET event_date = $2, customer_id = $3, order_count = $4, revenue = $5, created_at = $6 RETURNING *;
INSERT INTO orders (id, user_id, total, status, created_at, metadata, correlation_id, total_with_tax) VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (id) DO UPDATE SET user_id = $2, total = $3, status = $4, created_at = $5, metadata = $6, correlation_id = $7, total_with_tax = $8 RETURNING *;
INSERT INTO orders (user_id, total, status) VALUES ($1, $2, $3) RETURNING *;
INSERT INTO schema_versions (version) SELECT coalesce(max(version), 0) + 1 FROM schema_versions RETURNING version;
INSERT INTO t VALUES ('c');
INSERT INTO t VALUES ('d');
INSERT INTO t VALUES (1 / 2);
INSERT INTO t VALUES (1 / 2);
INSERT INTO t VALUES (1);
INSERT INTO t VALUES (2);
INSERT INTO users (email, name) VALUES ('a@b.com', 'Alice') ON CONFLICT (email) DO UPDATE SET name = 'Alice';
INSERT INTO users (email, name) VALUES ('a@b.com', 'Alice') ON CONFLICT DO NOTHING;
INSERT INTO users (id, name, email, active, created_at, updated_at, deleted_at) VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *;
INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@test.com');
INSERT INTO users (id, nonexistent) VALUES (1, 'x');
INSERT INTO users (name, email, active) VALUES ($1, $2, $3);
INSERT INTO users (name, email) VALUES ('Alice', 'a@t.com'), ('Bob', 'b@t.com');
INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com');
INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com') RETURNING id, name, created_at;
INSERT INTO users (name, email) VALUES ($2, $3);
INSERT INTO users (name) VALUES ('Alice') RETURNING id;
INSERT INTO users (name) VALUES ($1);
