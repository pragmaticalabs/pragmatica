-- Harvested from SQL string literals in aether/ and examples/ Java sources.
-- REAL queries the codebase issues, not hand-written fixtures: the CST differential's value
-- depends on the corpus being independently motivated, so it exercises shapes nobody thought
-- to assert. If a statement here stops parsing, fix the parser or re-harvest -- never edit
-- the statement to make it pass.
--
-- INVARIANT: exactly one statement per non-comment line. CorpusParseTest checks the facade's
-- statement count against that, which is what caught the _ROOT wrapper collapsing every
-- script to a single statement.

DELETE FROM aether_stream_segments;
DELETE FROM customers WHERE id = $1;
DELETE FROM nonexistent;
DELETE FROM order_metrics WHERE id = $1;
DELETE FROM orders USING users WHERE orders.user_id = users.id AND users.active = false;
DELETE FROM orders WHERE id = $1;
DELETE FROM temp_data;
DELETE FROM users WHERE active = false RETURNING id, name;
DELETE FROM users WHERE id = 1;
UPDATE nonexistent SET x = 1;
UPDATE orders SET status = 'shipped' FROM shipments WHERE orders.id = shipments.order_id;
UPDATE orders SET status = $1 WHERE id = $2;
UPDATE t SET c = 1;
UPDATE users SET active = false WHERE last_login < '2023-01-01' RETURNING id, name;
UPDATE users SET name = 'Bob' WHERE id = 1;
UPDATE users SET name = 'Bob', email = 'bob@test.com', updated_at = now() WHERE id = 1;
UPDATE users SET nonexistent = 'x' WHERE id = 1;
UPDATE users SET rank = (SELECT count(*) FROM orders WHERE orders.user_id = users.id);
UPDATE users SET score = DEFAULT WHERE id = 1;

-- The #646 A/B pair. Byte-identical apart from the NOT IN subquery, which is what made the
-- RETURNING list validate against the subquery's projection in one case and against nothing
-- at all in the other. Both must parse, and both must keep the pair adjacent so a future
-- differential shows them together.
UPDATE reservations SET state = 'expired' WHERE state = 'held' AND expires_at < now() RETURNING seat_id, event_id, version;
UPDATE reservations SET state = 'expired' WHERE state = 'held' AND expires_at < now() AND claim_id NOT IN (SELECT reservation_claim_id FROM bookings WHERE status = 'void') RETURNING seat_id, event_id, version;
