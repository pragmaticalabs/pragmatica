-- Harvested from SQL string literals in aether/ and examples/ Java sources.
-- REAL queries the codebase issues, not hand-written fixtures: the CST differential's value
-- depends on the corpus being independently motivated, so it exercises shapes nobody thought
-- to assert. If a statement here stops parsing, fix the parser or re-harvest -- never edit
-- the statement to make it pass.
--
-- INVARIANT: exactly one statement per non-comment line. CorpusParseTest checks the facade's
-- statement count against that, which is what caught the _ROOT wrapper collapsing every
-- script to a single statement.

SELECT * FROM (SELECT id, name FROM users) AS sub;
SELECT * FROM a CROSS JOIN b;
SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id;
SELECT * FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id;
SELECT * FROM a JOIN b ON a.id = b.user_id;
SELECT * FROM a JOIN b USING (id);
SELECT * FROM a NATURAL JOIN b;
SELECT * FROM a RIGHT JOIN b ON a.id = b.id;
SELECT * FROM events ORDER BY created_at DESC NULLS LAST;
SELECT * FROM nonexistent;
SELECT * FROM orders;
SELECT * FROM orders WHERE created_at BETWEEN '2024-01-01' AND '2024-12-31';
SELECT * FROM orders WHERE user_id = $1 AND status = $2 AND created_at > $3;
SELECT * FROM users;
SELECT * FROM users FETCH FIRST 10 ROWS ONLY;
SELECT * FROM users LIMIT 10;
SELECT * FROM users LIMIT 10 OFFSET 20;
SELECT * FROM users ORDER BY name ASC, id DESC;
SELECT * FROM users u LEFT OUTER JOIN orders o ON u.id = o.user_id;
SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id);
SELECT * FROM users u, LATERAL (SELECT * FROM orders o WHERE o.user_id = u.id LIMIT 3) AS recent;
SELECT * FROM users WHERE active = true;
SELECT * FROM users WHERE active = true AND age > 18;
SELECT * FROM users WHERE active = true AND created_at > '2024-01-01';
SELECT * FROM users WHERE email = $1;
SELECT * FROM users WHERE email = $1 AND active = $2;
SELECT * FROM users WHERE email LIKE '%@example.com';
SELECT * FROM users WHERE id = $1 OR manager_id = $1;
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders);
SELECT a, b, sum(c) FROM t GROUP BY CUBE (a, b);
SELECT a, b, sum(c) FROM t GROUP BY ROLLUP (a, b);
SELECT c.id AS customer_id, c.name AS customer_name, count(o.id) AS order_count, sum(o.total) AS total_revenue, min(o.total) AS min_order_value, max(o.total) AS max_order_value FROM customers c LEFT JOIN orders o ON o.customer_id = c.id WHERE c.active = $1 GROUP BY c.id, c.name;
SELECT column_default AS s FROM information_schema.columns;
SELECT count(*) AS c FROM aether_schema_history WHERE version = 7;
SELECT count(*) AS c FROM aether_schema_history_meta;
SELECT count(*) AS c FROM information_schema.columns;
SELECT count(*) AS c FROM information_schema.tables;
SELECT count(*) FILTER (WHERE active = true) FROM users;
SELECT count(*) FROM customers WHERE active = $1;
SELECT count(*) FROM daily_snapshot;
SELECT count(*) FROM orders WHERE status = $1;
SELECT count(*) FROM users WHERE active = $1 AND deleted_at IS NULL;
SELECT count(DISTINCT customer_id) AS count FROM orders WHERE status = $1;
SELECT customer_id, sum(revenue) AS total_revenue;
SELECT customer_id, sum(revenue) AS total_revenue FROM order_metrics WHERE customer_id = $1 GROUP BY customer_id;
SELECT data->>'name' AS name, data->'address'->>'city' AS city FROM contacts;
SELECT dept, name, salary, avg(salary) OVER (PARTITION BY dept) FROM employees;
SELECT DISTINCT ON (user_id) * FROM orders ORDER BY user_id, created_at DESC;
SELECT DISTINCT status FROM orders;
SELECT EXISTS(SELECT 1 FROM customers WHERE id = $1);
SELECT EXISTS(SELECT 1 FROM orders WHERE id = $1);
SELECT EXISTS(SELECT 1 FROM users WHERE id = $1);
SELECT EXTRACT(HOUR FROM created_at) AS hr, COUNT(*) AS order_count, SUM(total_cents) AS revenue;
SELECT f FROM foos;
SELECT id FROM a EXCEPT SELECT id FROM b;
SELECT id FROM a INTERSECT SELECT id FROM b;
SELECT id FROM a UNION ALL SELECT id FROM b;
SELECT id FROM active_users UNION SELECT id FROM pending_users;
SELECT id, CASE status WHEN 1 THEN 'active' WHEN 2 THEN 'inactive' ELSE 'unknown' END FROM users;
SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id;
SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id FROM orders WHERE (status = $1 AND total > $2) OR (status = $3 AND total > $4);
SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id FROM orders WHERE correlation_id = $1;
SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE email LIKE $1);
SELECT id, event_date, customer_id, order_count, revenue, created_at;
SELECT id, event_date, customer_id, order_count, revenue, created_at FROM order_metrics WHERE customer_id = $1;
SELECT id, event_date, customer_id, order_count, revenue, created_at FROM order_metrics WHERE id = $1;
SELECT id, name FROM users;
SELECT id, name FROM users WHERE active = true;
SELECT id, name, email;
SELECT id, name, email FROM users;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE active = $1 ORDER BY name ASC;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE active = $1 ORDER BY tier ASC, name DESC;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE deleted_at IS NOT NULL;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE deleted_at IS NULL;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE id = $1;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE name LIKE $1;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE tier != $1;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE tier = $1;
SELECT id, name, email, active, created_at, deleted_at, tier, preferences FROM customers WHERE tier = $1 AND active = $2;
SELECT id, name, email, active, created_at, updated_at, deleted_at FROM users WHERE deleted_at IS NOT NULL;
SELECT id, name, email, active, created_at, updated_at, deleted_at FROM users WHERE deleted_at IS NULL;
SELECT id, name, email, active, created_at, updated_at, deleted_at FROM users WHERE id = $1;
SELECT id, name, email, active, created_at, updated_at, deleted_at FROM users WHERE name LIKE $1;
SELECT id, sku, name, price, tags FROM products WHERE $1 = ANY(tags);
SELECT id, status AS state FROM orders;
SELECT id, status FROM orders;
SELECT id, sum(amount) OVER (ORDER BY date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM payments;
SELECT id, total, customer_id FROM orders;
SELECT id, user_id, total, status FROM orders;
SELECT id, user_id, total, status FROM orders WHERE user_id IN (SELECT id FROM users WHERE email LIKE $1);
SELECT id, user_id, total, status, created_at, metadata, correlation_id, total_with_tax FROM orders WHERE id = $1;
SELECT id, user_id, total, status, created_at, metadata, correlation_id, total_with_tax FROM orders WHERE status != $1;
SELECT id, user_id, total, status, created_at, metadata, correlation_id, total_with_tax FROM orders WHERE status = $1 ORDER BY created_at DESC;
SELECT id::text, amount::numeric(10,2) FROM orders;
SELECT is_nullable AS s FROM information_schema.columns;
SELECT key, value FROM kv_store ORDER BY key;
SELECT lower(name), date_part('year', created_at) FROM users;
SELECT max(created_at) AS latest FROM orders;
SELECT name AS user_name FROM users;
SELECT name FROM users;
SELECT name, rank() OVER (ORDER BY score DESC) FROM players;
SELECT name, rank() OVER w FROM players WINDOW w AS (ORDER BY score DESC);
SELECT o.id AS order_id, c.id AS customer_id, c.name AS customer_name, c.email AS customer_email, o.total AS order_total, o.status AS order_status, o.created_at AS order_created_at FROM customers c JOIN orders o ON o.customer_id = c.id WHERE o.status = $1;
SELECT o.id, u.name AS user_name, o.total, o.status;
SELECT o.id, u.name AS user_name, o.total, o.status FROM orders o JOIN users u ON o.user_id = u.id WHERE o.user_id = $1;
SELECT p.id AS product_id, p.sku AS product_sku, p.name AS product_name, sum(i.quantity) AS units_sold, sum(i.quantity * i.unit_price) AS total_revenue FROM products p JOIN order_items i ON i.product_id = p.id JOIN orders o ON o.id = i.order_id WHERE o.status = $1 GROUP BY p.id, p.sku, p.name;
SELECT payload FROM altq_t;
SELECT payload FROM str_t;
SELECT raw FROM ns_t;
SELECT schema_version AS c FROM aether_schema_history_meta;
SELECT sku, qty FROM orders;
SELECT statements_completed AS c FROM aether_schema_history WHERE version = 1;
SELECT statements_completed AS c FROM aether_schema_history WHERE version = 3;
SELECT statements_completed AS c FROM aether_schema_history WHERE version = 7;
SELECT status AS s FROM aether_schema_history WHERE version = 1;
SELECT status AS s FROM aether_schema_history WHERE version = 3;
SELECT status AS s FROM aether_schema_history WHERE version = 7;
SELECT status, count(*) FROM orders GROUP BY status;
SELECT status, count(*) FROM orders GROUP BY status HAVING count(*) > 5;
SELECT status, statements_completed, checksum FROM aether_schema_history;
SELECT sum(amount) AS total FROM items;
SELECT u.* FROM users u;
SELECT u.* FROM users u WHERE u.active = true;
SELECT u.id FROM users u JOIN orders o ON u.missing_fk = o.user_id;
SELECT u.id FROM users u WHERE u.active = true;
SELECT u.id FROM users u WHERE u.id IN (SELECT o.user_id FROM ghost_orders o);
SELECT u.id, u.name FROM users u;
SELECT u.id, u.name FROM users u WHERE u.active = true;
SELECT u.name, o.status FROM users u LEFT JOIN orders o ON u.id = o.user_id;
SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id;
SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id;
SELECT u.name, o.total FROM users u LEFT JOIN orders o ON u.id = o.user_id;
SELECT u.name, o.total FROM users u, orders o WHERE u.id = o.user_id;
SELECT u.nonexistent FROM users u;
SELECT version, type, description, script, checksum, applied_by, applied_at, execution_ms;
SELECT x.id FROM users u;
SELECT x.id FROM users u JOIN orders o ON u.id = o.user_id;
--
-- Nested block comments (#619, upstream siy/java-peglib#45, fixed by %nest in peglib 0.7.3).
-- Excluded from the corpus while the gap was open. The `;` INSIDE the comment is the point: it must
-- stay inside it, or the statement splits. The sibling case the issue lists,
-- `SELECT 1 /* outer ; /* inner ; */ still ; */ ; SELECT 2;`, is deliberately NOT here -- it is two
-- statements on one line, and this corpus is one statement per line (the count assertion in
-- CorpusParseTest derives its expectation from that).
SELECT 1 /* outer /* inner */ still-comment ; */ AS c;
