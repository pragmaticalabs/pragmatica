// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.schema.validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class QueryValidatorTest {
    static PostgresParser parser;
    static Schema schema;

    @BeforeAll
    static void setup() {
        parser = PostgresParser.create();

        // Build a schema with users and orders tables
        schema = MigrationProcessor.create().processAll(List.of(
            """
            CREATE TABLE users (
                id bigint NOT NULL,
                name text NOT NULL,
                email text NOT NULL,
                active boolean DEFAULT true NOT NULL,
                created_at timestamptz DEFAULT now() NOT NULL,
                PRIMARY KEY (id)
            )""",
            "CREATE UNIQUE INDEX idx_users_email ON users (email)",
            """
            CREATE TABLE orders (
                id bigint NOT NULL,
                user_id bigint NOT NULL,
                total numeric(10,2) NOT NULL,
                status text DEFAULT 'pending' NOT NULL,
                created_at timestamptz DEFAULT now() NOT NULL,
                PRIMARY KEY (id),
                FOREIGN KEY (user_id) REFERENCES users(id)
            )""",
            // Shapes from the ticketing corpus that reported #649 and #646. `version` matters:
            // it is an identifier that SPELLS a keyword, which is what both failures turned on.
            """
            CREATE TABLE reservations (
                event_id bigint NOT NULL,
                seat_id bigint NOT NULL,
                claim_id text,
                customer_id text,
                state text NOT NULL,
                version bigint NOT NULL,
                expires_at timestamptz,
                PRIMARY KEY (event_id, seat_id)
            )""",
            """
            CREATE TABLE bookings (
                id bigint NOT NULL,
                reservation_claim_id text,
                status text NOT NULL,
                PRIMARY KEY (id)
            )""",
            """
            CREATE TABLE current_price (
                scope_key text NOT NULL,
                event_id bigint NOT NULL,
                tier text NOT NULL,
                amount_minor bigint NOT NULL,
                currency text NOT NULL,
                version bigint NOT NULL,
                updated_at timestamptz NOT NULL,
                PRIMARY KEY (scope_key)
            )"""
        )).unwrap();
    }

    private ValidationResult validate(String sql) {
        var cst = parser.parseCst(sql).unwrap();
        return QueryValidator.queryValidator(schema).validate(cst);
    }

    private static Option<List<String>> outputColumns(String sql) {
        var cst = parser.parseCst(sql).unwrap();

        return QueryValidator.selectOutputColumnNames(CstNavigator.wrap(cst).unwrap());
    }

    private static String messages(ValidationResult result) {
        return result.errors().stream().map(ValidationError::message).collect(Collectors.joining("; "));
    }

    @Nested
    class ValidQueries {
        @Test void simpleSelect() {
            assertThat(validate("SELECT * FROM users").isValid()).isTrue();
        }

        @Test void selectWithColumns() {
            assertThat(validate("SELECT id, name, email FROM users").isValid()).isTrue();
        }

        @Test void selectWithAlias() {
            assertThat(validate("SELECT u.id, u.name FROM users u").isValid()).isTrue();
        }

        @Test void selectWithJoin() {
            assertThat(validate(
                "SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id"
            ).isValid()).isTrue();
        }

        @Test void selectWithWhere() {
            assertThat(validate(
                "SELECT * FROM users WHERE active = true AND created_at > '2024-01-01'"
            ).isValid()).isTrue();
        }

        @Test void insertValid() {
            assertThat(validate(
                "INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@test.com')"
            ).isValid()).isTrue();
        }

        @Test void updateValid() {
            assertThat(validate(
                "UPDATE users SET name = 'Bob' WHERE id = 1"
            ).isValid()).isTrue();
        }

        @Test void deleteValid() {
            assertThat(validate("DELETE FROM users WHERE id = 1").isValid()).isTrue();
        }
    }

    @Nested
    class InvalidTableReferences {
        @Test void selectFromNonexistentTable() {
            var result = validate("SELECT * FROM nonexistent");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.tableErrors()).isNotEmpty();
        }

        @Test void insertIntoNonexistentTable() {
            var result = validate("INSERT INTO nonexistent (id) VALUES (1)");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.tableErrors()).isNotEmpty();
        }

        @Test void updateNonexistentTable() {
            var result = validate("UPDATE nonexistent SET x = 1");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.tableErrors()).isNotEmpty();
        }

        @Test void deleteFromNonexistentTable() {
            var result = validate("DELETE FROM nonexistent");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.tableErrors()).isNotEmpty();
        }
    }

    @Nested
    class InvalidColumnReferences {
        @Test void insertNonexistentColumn() {
            var result = validate("INSERT INTO users (id, nonexistent) VALUES (1, 'x')");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.columnErrors()).isNotEmpty();
        }

        @Test void updateNonexistentColumn() {
            var result = validate("UPDATE users SET nonexistent = 'x' WHERE id = 1");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.columnErrors()).isNotEmpty();
        }

        @Test void qualifiedColumnOnWrongTable() {
            var result = validate("SELECT u.nonexistent FROM users u");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.columnErrors()).isNotEmpty();
        }

        @Test void unknownAlias() {
            var result = validate("SELECT x.id FROM users u");
            assertThat(result.hasErrors()).isTrue();
        }
    }

    @Nested
    class CommonTableExpressions {
        @Test void simpleCte() {
            assertThat(validate(
                "WITH recent AS (SELECT id FROM users WHERE created_at > NOW()) SELECT * FROM recent"
            ).isValid()).isTrue();
        }

        @Test void multiCte() {
            assertThat(validate(
                "WITH a AS (SELECT id FROM users), b AS (SELECT user_id FROM orders) " +
                "SELECT * FROM a JOIN b ON a.id = b.user_id"
            ).isValid()).isTrue();
        }

        @Test void cteWithExplicitColumnList() {
            assertThat(validate(
                "WITH foo(x, y) AS (SELECT id, name FROM users) SELECT foo.x FROM foo"
            ).isValid()).isTrue();
        }

        @Test void cteWithExplicitColumnListRejectsUnknownColumn() {
            var result = validate(
                "WITH foo(x) AS (SELECT id FROM users) SELECT foo.bogus FROM foo"
            );
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.columnErrors()).isNotEmpty();
        }

        @Test void cteInnerQueryValidatesTables() {
            var result = validate("WITH a AS (SELECT * FROM bogus) SELECT * FROM a");
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.tableErrors())
                .anyMatch(e -> e.message().contains("bogus"));
        }
    }

    @Nested
    class AliasResolution {
        @Test void tableAliasInWhere() {
            assertThat(validate(
                "SELECT u.id FROM users u WHERE u.active = true"
            ).isValid()).isTrue();
        }

        @Test void multipleAliases() {
            assertThat(validate(
                "SELECT u.name, o.total FROM users u, orders o WHERE u.id = o.user_id"
            ).isValid()).isTrue();
        }

        @Test void aliasedJoin() {
            assertThat(validate(
                "SELECT u.name, o.status FROM users u LEFT JOIN orders o ON u.id = o.user_id"
            ).isValid()).isTrue();
        }
    }

    /// #649 — `ON CONFLICT ... DO UPDATE` resolves against the target relation plus the `EXCLUDED`
    /// pseudo-relation. Every statement here is a monotonic version guard from the reporting
    /// corpus: dropping the qualification changes which row the predicate reads, and splitting the
    /// upsert reintroduces the race, so neither is an available workaround.
    @Nested
    class OnConflictUpsert {
        private static final String UPSERT_CURRENT_PRICE =
            "INSERT INTO current_price (scope_key, event_id, tier, amount_minor, currency, version, updated_at) "
            + "VALUES ($1, $2, $3, $4, $5, $6, now()) "
            + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.amount_minor, "
            + "currency = EXCLUDED.currency, version = EXCLUDED.version, updated_at = now() "
            + "WHERE current_price.version < EXCLUDED.version";

        private static final String CLAIM_SEAT =
            "INSERT INTO reservations (event_id, seat_id, claim_id, customer_id, state, version, expires_at) "
            + "VALUES ($1, $2, $3, $4, 'held', 1, now()) "
            + "ON CONFLICT (event_id, seat_id) DO UPDATE SET claim_id = EXCLUDED.claim_id, "
            + "customer_id = CASE WHEN reservations.state = 'free' THEN EXCLUDED.customer_id "
            + "ELSE reservations.customer_id END, state = 'held', version = reservations.version + 1 "
            + "WHERE reservations.state IN ('free', 'expired')";

        @Test void validate_monotonicUpsert_isClean() {
            var result = validate(UPSERT_CURRENT_PRICE);

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        @Test void validate_claimSeatUpsert_isClean() {
            var result = validate(CLAIM_SEAT);

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        @Test void validate_excludedUnknownColumn_errors() {
            var result = validate(
                "INSERT INTO current_price (scope_key, amount_minor) VALUES ($1, $2) "
                + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.nonexistent_col"
            );

            assertThat(result.columnErrors())
                .as("EXCLUDED is a scoped relation, not a whitelisted name")
                .anyMatch(e -> e.message().contains("nonexistent_col"));
        }

        @Test void validate_misqualifiedDoUpdateWhere_errors() {
            var result = validate(
                "INSERT INTO current_price (scope_key, amount_minor) VALUES ($1, $2) "
                + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.amount_minor "
                + "WHERE wrong_table.version < EXCLUDED.version"
            );

            assertThat(result.errors()).anyMatch(e -> e.message().contains("wrong_table"));
        }

        @Test void validate_doNothing_isClean() {
            assertThat(validate(
                "INSERT INTO current_price (scope_key, amount_minor) VALUES ($1, $2) ON CONFLICT DO NOTHING"
            ).isValid()).isTrue();
        }

        /// The #649 trigger in isolation: `version` lexes as `Token VersionKW`, so the old
        /// name-based `findAll("ColId")` skipped the assignment target and reported the first
        /// identifier of the right-hand side — `excluded` — as a missing column of `current_price`.
        @Test void validate_setTargetSpellingKeyword_isClean() {
            var result = validate(
                "INSERT INTO current_price (scope_key, version) VALUES ($1, $2) "
                + "ON CONFLICT (scope_key) DO UPDATE SET version = EXCLUDED.version"
            );

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        /// The other half of the same defect: skipping the keyword-spelled target did not merely
        /// misreport, it also stopped CHECKING it. A bogus assignment target that spells a keyword
        /// used to pass silently.
        @Test void validate_bogusSetTargetSpellingKeyword_errors() {
            var result = validate("UPDATE reservations SET key = 1 WHERE seat_id = 1");

            assertThat(result.columnErrors()).anyMatch(e -> e.message().contains("key"));
        }

        /// Same class, insert column list: `extractColumnList` read names by rule and returned a
        /// SHORT list, so a keyword-spelled column was never validated against the table.
        @Test void validate_insertColumnSpellingKeyword_isChecked() {
            var result = validate("INSERT INTO reservations (event_id, seat_id, key) VALUES (1, 2, 3)");

            assertThat(result.columnErrors()).anyMatch(e -> e.message().contains("key"));
        }
    }

    /// #646 — `RETURNING` and the `WHERE` of an `UPDATE`/`DELETE` resolve against the statement's
    /// target relation. Before, `RETURNING` was checked against whatever single `SelectCore` the
    /// walk happened to find anywhere in the tree, and these clauses were otherwise validated by
    /// nothing at all.
    @Nested
    class StatementScopedClauses {
        private static final String EXPIRE_HOLDS =
            "UPDATE reservations SET state = 'expired' WHERE state = 'held' AND expires_at < now() "
            + "RETURNING seat_id, event_id, version";

        private static final String EXPIRE_HOLDS_WITH_SUBQUERY =
            "UPDATE reservations SET state = 'expired' WHERE state = 'held' "
            + "AND claim_id NOT IN (SELECT b.reservation_claim_id FROM bookings b WHERE b.status = 'void') "
            + "RETURNING seat_id, event_id, version";

        @Test void validate_updateReturning_isClean() {
            var result = validate(EXPIRE_HOLDS);

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        @Test void validate_updateReturningWithSubquery_isClean() {
            var result = validate(EXPIRE_HOLDS_WITH_SUBQUERY);

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        /// The canary. A bogus RETURNING column used to be reported by nothing: with no subquery
        /// the statement had zero `SelectCore` nodes, so the check bailed out entirely.
        @Test void validate_bogusReturningColumn_errors() {
            var result = validate("UPDATE reservations SET state = 'expired' RETURNING seat_id, bogus_ret");

            assertThat(result.columnErrors()).anyMatch(e -> e.message().contains("bogus_ret"));
        }

        @Test void validate_bogusWhereColumn_errors() {
            var result = validate("UPDATE reservations SET state = 'expired' WHERE bogus_col = 'held'");

            assertThat(result.columnErrors()).anyMatch(e -> e.message().contains("bogus_col"));
        }

        @Test void validate_bogusColumnInsideSubquery_errors() {
            var result = validate(
                "UPDATE reservations SET state = 'x' WHERE claim_id NOT IN (SELECT b.nope FROM bookings b)"
            );

            assertThat(result.columnErrors())
                .as("the subquery keeps validating its own scope")
                .anyMatch(e -> e.message().contains("nope"));
        }

        @Test void validate_deleteWhereAndReturning_areValidated() {
            var result = validate("DELETE FROM reservations WHERE bogus_col = 1 RETURNING bogus_ret");

            assertThat(result.columnErrors()).hasSize(2);
        }

        @Test void validate_deleteUsingJoin_isClean() {
            var result = validate(
                "DELETE FROM reservations USING bookings b WHERE reservations.claim_id = b.reservation_claim_id "
                + "RETURNING seat_id"
            );

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        @Test void validate_updateWithAliasAndFrom_isClean() {
            var result = validate(
                "UPDATE reservations r SET state = 'x' FROM bookings b "
                + "WHERE r.claim_id = b.reservation_claim_id RETURNING r.seat_id"
            );

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }

        /// The target relation is taken from the statement's structure, not from the first
        /// `QualifiedName` in the subtree. A leading `WITH` clause puts the CTE body's tables ahead
        /// of the target, which used to make the CTE's first column name "the table".
        @Test void validate_updateWithLeadingCte_isClean() {
            var result = validate(
                "WITH stale AS (SELECT id FROM bookings) UPDATE reservations SET state = 'x' "
                + "FROM stale WHERE reservations.event_id = stale.id"
            );

            assertThat(result.isValid()).as(messages(result)).isTrue();
        }
    }

    /// #646 — the output-column set a return-row record is mapped against. Sourced from the
    /// statement's own `RETURNING` list or its statement-level `SELECT` core, never from a
    /// `SelectCore` discovered anywhere in the tree.
    @Nested
    class OutputColumnResolution {
        @Test void selectOutputColumnNames_updateReturning_usesReturningList() {
            assertThat(outputColumns(
                "UPDATE reservations SET state = 'expired' RETURNING seat_id, event_id, version"
            )).isEqualTo(Option.present(List.of("seat_id", "event_id", "version")));
        }

        /// The reported regression: one subquery anywhere made that subquery's projection "the
        /// query's output", so each RETURNING component warned that it had no matching column.
        @Test void selectOutputColumnNames_updateReturningWithSubquery_ignoresSubqueryCore() {
            assertThat(outputColumns(
                "UPDATE reservations SET state = 'expired' "
                + "WHERE claim_id NOT IN (SELECT b.reservation_claim_id FROM bookings b) "
                + "RETURNING seat_id, event_id, version"
            )).isEqualTo(Option.present(List.of("seat_id", "event_id", "version")));
        }

        @Test void selectOutputColumnNames_insertSelectReturning_usesReturningList() {
            assertThat(outputColumns(
                "INSERT INTO bookings (id, reservation_claim_id, status) "
                + "SELECT event_id, claim_id, state FROM reservations RETURNING id, status"
            )).isEqualTo(Option.present(List.of("id", "status")));
        }

        @Test void selectOutputColumnNames_insertWithoutReturning_isAbsent() {
            assertThat(outputColumns("INSERT INTO users (id, name) VALUES (1, 'a')")).isEqualTo(Option.empty());
        }

        @Test void selectOutputColumnNames_plainSelect_usesTargetList() {
            assertThat(outputColumns("SELECT id, name FROM users"))
                .isEqualTo(Option.present(List.of("id", "name")));
        }

        @Test void selectOutputColumnNames_selectWithSubquery_usesOuterProjection() {
            assertThat(outputColumns("SELECT id, name FROM users WHERE id IN (SELECT user_id FROM orders)"))
                .isEqualTo(Option.present(List.of("id", "name")));
        }

        @Test void selectOutputColumnNames_star_isAbsent() {
            assertThat(outputColumns("SELECT * FROM users")).isEqualTo(Option.empty());
        }

        @Test void selectOutputColumnNames_setOperation_isAbsent() {
            assertThat(outputColumns("SELECT id FROM users UNION SELECT id FROM orders"))
                .isEqualTo(Option.empty());
        }

        @Test void selectOutputColumnNames_multipleStatements_isAbsent() {
            assertThat(outputColumns("SELECT id FROM users; SELECT id FROM orders")).isEqualTo(Option.empty());
        }
    }
}
