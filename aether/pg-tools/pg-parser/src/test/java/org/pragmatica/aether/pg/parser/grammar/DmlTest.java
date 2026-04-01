package org.pragmatica.aether.pg.parser.grammar;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DmlTest extends GrammarTestBase {

    @Nested
    class Select {
        @Test void simpleLiteral() { assertParses("SELECT 1"); }
        @Test void simpleColumn() { assertParses("SELECT name FROM users"); }
        @Test void multipleColumns() { assertParses("SELECT id, name, email FROM users"); }
        @Test void star() { assertParses("SELECT * FROM users"); }
        @Test void qualifiedStar() { assertParses("SELECT u.* FROM users u"); }
        @Test void alias() { assertParses("SELECT name AS user_name FROM users"); }
        @Test void distinct() { assertParses("SELECT DISTINCT status FROM orders"); }
        @Test void distinctOn() { assertParses("SELECT DISTINCT ON (user_id) * FROM orders ORDER BY user_id, created_at DESC"); }
        @Test void where() { assertParses("SELECT * FROM users WHERE active = true AND age > 18"); }
        @Test void orderBy() { assertParses("SELECT * FROM users ORDER BY name ASC, id DESC"); }
        @Test void orderByNulls() { assertParses("SELECT * FROM events ORDER BY created_at DESC NULLS LAST"); }
        @Test void limit() { assertParses("SELECT * FROM users LIMIT 10"); }
        @Test void offset() { assertParses("SELECT * FROM users LIMIT 10 OFFSET 20"); }
        @Test void fetchFirst() { assertParses("SELECT * FROM users FETCH FIRST 10 ROWS ONLY"); }
        @Test void groupBy() { assertParses("SELECT status, count(*) FROM orders GROUP BY status"); }
        @Test void having() { assertParses("SELECT status, count(*) FROM orders GROUP BY status HAVING count(*) > 5"); }
        @Test void groupByRollup() { assertParses("SELECT a, b, sum(c) FROM t GROUP BY ROLLUP (a, b)"); }
        @Test void groupByCube() { assertParses("SELECT a, b, sum(c) FROM t GROUP BY CUBE (a, b)"); }

        @Test void innerJoin() {
            assertParses("SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id");
        }

        @Test void leftJoin() {
            assertParses("SELECT u.name, o.total FROM users u LEFT JOIN orders o ON u.id = o.user_id");
        }

        @Test void leftOuterJoin() {
            assertParses("SELECT * FROM users u LEFT OUTER JOIN orders o ON u.id = o.user_id");
        }

        @Test void rightJoin() {
            assertParses("SELECT * FROM a RIGHT JOIN b ON a.id = b.id");
        }

        @Test void fullJoin() {
            assertParses("SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id");
        }

        @Test void crossJoin() {
            assertParses("SELECT * FROM a CROSS JOIN b");
        }

        @Test void naturalJoin() {
            assertParses("SELECT * FROM a NATURAL JOIN b");
        }

        @Test void multipleJoins() {
            assertParses("SELECT * FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id");
        }

        @Test void joinUsing() {
            assertParses("SELECT * FROM a JOIN b USING (id)");
        }

        @Test void subqueryInFrom() {
            assertParses("SELECT * FROM (SELECT id, name FROM users) AS sub");
        }

        @Test void subqueryInWhere() {
            assertParses("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)");
        }

        @Test void existsSubquery() {
            assertParses("SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)");
        }

        @Test void union() {
            assertParses("SELECT id FROM active_users UNION SELECT id FROM pending_users");
        }

        @Test void unionAll() {
            assertParses("SELECT id FROM a UNION ALL SELECT id FROM b");
        }

        @Test void intersect() {
            assertParses("SELECT id FROM a INTERSECT SELECT id FROM b");
        }

        @Test void except() {
            assertParses("SELECT id FROM a EXCEPT SELECT id FROM b");
        }

        @Test void cte() {
            assertParses("WITH active AS (SELECT * FROM users WHERE active = true) SELECT * FROM active");
        }

        @Test void cteRecursive() {
            assertParses("WITH RECURSIVE tree AS (SELECT id, parent_id FROM nodes WHERE parent_id IS NULL UNION ALL SELECT n.id, n.parent_id FROM nodes n JOIN tree t ON n.parent_id = t.id) SELECT * FROM tree");
        }

        @Test void windowFunction() {
            assertParses("SELECT name, rank() OVER (ORDER BY score DESC) FROM players");
        }

        @Test void windowPartition() {
            assertParses("SELECT dept, name, salary, avg(salary) OVER (PARTITION BY dept) FROM employees");
        }

        @Test void windowFrame() {
            assertParses("SELECT id, sum(amount) OVER (ORDER BY date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM payments");
        }

        @Test void namedWindow() {
            assertParses("SELECT name, rank() OVER w FROM players WINDOW w AS (ORDER BY score DESC)");
        }

        @Test void aggregateFilter() {
            assertParses("SELECT count(*) FILTER (WHERE active = true) FROM users");
        }

        @Test void lateralJoin() {
            assertParses("SELECT * FROM users u, LATERAL (SELECT * FROM orders o WHERE o.user_id = u.id LIMIT 3) AS recent");
        }

        @Test void typeCastInSelect() {
            assertParses("SELECT id::text, amount::numeric(10,2) FROM orders");
        }

        @Test void caseInSelect() {
            assertParses("SELECT id, CASE status WHEN 1 THEN 'active' WHEN 2 THEN 'inactive' ELSE 'unknown' END FROM users");
        }

        @Test void functionInSelect() {
            assertParses("SELECT lower(name), date_part('year', created_at) FROM users");
        }

        @Test void jsonInSelect() {
            assertParses("SELECT data->>'name' AS name, data->'address'->>'city' AS city FROM contacts");
        }

        @Test void arrayInWhere() {
            assertParses("SELECT * FROM products WHERE tags && ARRAY['sale', 'new']");
        }

        @Test void betweenInWhere() {
            assertParses("SELECT * FROM orders WHERE created_at BETWEEN '2024-01-01' AND '2024-12-31'");
        }

        @Test void likeInWhere() {
            assertParses("SELECT * FROM users WHERE email LIKE '%@example.com'");
        }

        @Test void complexRealWorld() {
            assertParses("""
                SELECT u.id, u.name, u.email,
                       count(o.id) AS order_count,
                       sum(o.total) AS total_spent,
                       max(o.created_at) AS last_order
                FROM users u
                LEFT JOIN orders o ON u.id = o.user_id
                WHERE u.active = true
                  AND u.created_at > '2024-01-01'
                GROUP BY u.id, u.name, u.email
                HAVING count(o.id) > 0
                ORDER BY total_spent DESC NULLS LAST
                LIMIT 100""");
        }
    }

    @Nested
    class Insert {
        @Test void values() {
            assertParses("INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com')");
        }

        @Test void multipleRows() {
            assertParses("INSERT INTO users (name, email) VALUES ('Alice', 'a@t.com'), ('Bob', 'b@t.com')");
        }

        @Test void defaultValues() {
            assertParses("INSERT INTO users DEFAULT VALUES");
        }

        @Test void fromSelect() {
            assertParses("INSERT INTO archive SELECT * FROM users WHERE active = false");
        }

        @Test void returning() {
            assertParses("INSERT INTO users (name) VALUES ('Alice') RETURNING id");
        }

        @Test void onConflictDoNothing() {
            assertParses("INSERT INTO users (email, name) VALUES ('a@b.com', 'Alice') ON CONFLICT DO NOTHING");
        }

        @Test void onConflictDoUpdate() {
            assertParses("INSERT INTO users (email, name) VALUES ('a@b.com', 'Alice') ON CONFLICT (email) DO UPDATE SET name = 'Alice'");
        }

        @Test void withReturning() {
            assertParses("INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com') RETURNING id, name, created_at");
        }
    }

    @Nested
    class Update {
        @Test void simple() {
            assertParses("UPDATE users SET name = 'Bob' WHERE id = 1");
        }

        @Test void multipleColumns() {
            assertParses("UPDATE users SET name = 'Bob', email = 'bob@test.com', updated_at = now() WHERE id = 1");
        }

        @Test void withFrom() {
            assertParses("UPDATE orders SET status = 'shipped' FROM shipments WHERE orders.id = shipments.order_id");
        }

        @Test void returning() {
            assertParses("UPDATE users SET active = false WHERE last_login < '2023-01-01' RETURNING id, name");
        }

        @Test void withDefault() {
            assertParses("UPDATE users SET score = DEFAULT WHERE id = 1");
        }

        @Test void withSubquery() {
            assertParses("UPDATE users SET rank = (SELECT count(*) FROM orders WHERE orders.user_id = users.id)");
        }
    }

    @Nested
    class Delete {
        @Test void simple() {
            assertParses("DELETE FROM users WHERE id = 1");
        }

        @Test void noWhere() {
            assertParses("DELETE FROM temp_data");
        }

        @Test void returning() {
            assertParses("DELETE FROM users WHERE active = false RETURNING id, name");
        }

        @Test void withUsing() {
            assertParses("DELETE FROM orders USING users WHERE orders.user_id = users.id AND users.active = false");
        }
    }

    @Nested
    class Passthrough {
        @Test void begin() { assertParses("BEGIN"); }
        @Test void commit() { assertParses("COMMIT"); }
        @Test void rollback() { assertParses("ROLLBACK"); }
        @Test void set() { assertParses("SET search_path TO public, extensions"); }
        @Test void show() { assertParses("SHOW search_path"); }
        @Test void truncate() { assertParses("TRUNCATE TABLE users CASCADE"); }
        @Test void vacuum() { assertParses("VACUUM ANALYZE users"); }
    }

    @Nested
    class MultiStatement {
        @Test void twoStatements() {
            assertParses("CREATE TABLE t (id integer); INSERT INTO t VALUES (1)");
        }

        @Test void migrationSequence() {
            assertParses("""
                CREATE TABLE users (id bigserial PRIMARY KEY, name text NOT NULL, email text NOT NULL);
                CREATE UNIQUE INDEX idx_users_email ON users (email);
                ALTER TABLE users ADD COLUMN created_at timestamptz DEFAULT now() NOT NULL""");
        }
    }
}
