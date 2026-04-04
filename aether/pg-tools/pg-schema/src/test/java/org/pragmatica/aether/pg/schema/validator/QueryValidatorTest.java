package org.pragmatica.aether.pg.schema.validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.schema.builder.MigrationProcessor;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.util.List;

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
            )"""
        )).unwrap();
    }

    private ValidationResult validate(String sql) {
        var cst = parser.parseCst(sql).unwrap();
        return QueryValidator.queryValidator(schema).validate(cst);
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
}
