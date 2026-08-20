-- Harvested from SQL string literals in aether/ and examples/ Java sources.
-- REAL queries the codebase issues, not hand-written fixtures: the CST differential's value
-- depends on the corpus being independently motivated, so it exercises shapes nobody thought
-- to assert. If a statement here stops parsing, fix the parser or re-harvest -- never edit
-- the statement to make it pass.
--
-- INVARIANT: exactly one statement per non-comment line. CorpusParseTest checks the facade's
-- statement count against that, which is what caught the _ROOT wrapper collapsing every
-- script to a single statement.

WITH a AS (SELECT * FROM bogus) SELECT * FROM a;
WITH active AS (SELECT * FROM users WHERE active = true) SELECT * FROM active;
WITH active_users AS (SELECT id, name FROM users WHERE active = true) SELECT id, name FROM active_users;
WITH foo(x, y) AS (SELECT id, name FROM users) SELECT foo.x FROM foo;
WITH foo(x) AS (SELECT id FROM users) SELECT foo.bogus FROM foo;
WITH recent AS (SELECT customer_id, revenue FROM order_metrics WHERE created_at > $1) SELECT sum(revenue) AS total_revenue FROM recent WHERE customer_id = $2;
WITH recent AS (SELECT id FROM users WHERE created_at > NOW()) SELECT * FROM recent;
WITH recent_orders AS ( SELECT id, total, customer_id FROM orders WHERE created_at > $1 AND total > $2) SELECT sum(total) AS total_amount FROM recent_orders WHERE customer_id = $3;
WITH RECURSIVE tree AS (SELECT id, parent_id FROM nodes WHERE parent_id IS NULL UNION ALL SELECT n.id, n.parent_id FROM nodes n JOIN tree t ON n.parent_id = t.id) SELECT * FROM tree;
