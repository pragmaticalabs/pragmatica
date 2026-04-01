package org.pragmatica.aether.pg.schema.linter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.schema.builder.DdlAnalyzer;
import org.pragmatica.aether.pg.parser.PostgresParser;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LintEngineTest {
    static PostgresParser parser;
    static LintEngine engine;

    @BeforeAll
    static void setup() {
        parser = PostgresParser.create();
        engine = LintEngine.create();
    }

    private List<LintDiagnostic> lint(String... sqlStatements) {
        var allEvents = new java.util.ArrayList<org.pragmatica.aether.pg.schema.event.SchemaEvent>();
        for (var sql : sqlStatements) {
            var cst = parser.parseCst(sql).unwrap();
            var events = DdlAnalyzer.analyze(cst).unwrap();
            allEvents.addAll(events);
        }
        return engine.lint(allEvents);
    }

    private boolean hasRule(List<LintDiagnostic> diagnostics, String ruleId) {
        return diagnostics.stream().anyMatch(d -> d.ruleId().equals(ruleId));
    }

    @Nested
    class LockHazards {
        @Test void pg001_addNotNullWithoutDefault() {
            var diags = lint(
                "CREATE TABLE users (id bigint)",
                "ALTER TABLE users ADD COLUMN name text NOT NULL"
            );
            assertThat(hasRule(diags, "PG001")).isTrue();
        }

        @Test void pg001_addNotNullWithDefault_noWarning() {
            var diags = lint(
                "CREATE TABLE users (id bigint)",
                "ALTER TABLE users ADD COLUMN name text NOT NULL DEFAULT 'unnamed'"
            );
            // Should NOT have PG001 since DEFAULT is present
            assertThat(hasRule(diags, "PG001")).isFalse();
        }

        @Test void pg002_createIndexWithoutConcurrently() {
            var diags = lint(
                "CREATE TABLE users (id bigint, email text)",
                "CREATE INDEX idx_email ON users (email)"
            );
            assertThat(hasRule(diags, "PG002")).isTrue();
        }

        @Test void pg002_createIndexConcurrently_noWarning() {
            var diags = lint(
                "CREATE TABLE users (id bigint, email text)",
                "CREATE INDEX CONCURRENTLY idx_email ON users (email)"
            );
            assertThat(hasRule(diags, "PG002")).isFalse();
        }

        @Test void pg003_alterColumnTypeUnsafe() {
            var diags = lint(
                "CREATE TABLE users (id integer NOT NULL)",
                "ALTER TABLE users ALTER COLUMN id TYPE bigint"
            );
            assertThat(hasRule(diags, "PG003")).isTrue();
        }

        @Test void pg003_alterColumnTypeSafe_noWarning() {
            var diags = lint(
                "CREATE TABLE users (name varchar(100))",
                "ALTER TABLE users ALTER COLUMN name TYPE text"
            );
            assertThat(hasRule(diags, "PG003")).isFalse();
        }

        @Test void pg004_setNotNullDirectly() {
            var diags = lint(
                "CREATE TABLE users (id bigint, name text)",
                "ALTER TABLE users ALTER COLUMN name SET NOT NULL"
            );
            assertThat(hasRule(diags, "PG004")).isTrue();
        }

        @Test void pg005_addUniqueConstraint() {
            var diags = lint(
                "CREATE TABLE users (id bigint, email text)",
                "ALTER TABLE users ADD CONSTRAINT uq_email UNIQUE (email)"
            );
            assertThat(hasRule(diags, "PG005")).isTrue();
        }

        @Test void pg006_addForeignKey() {
            var diags = lint(
                "CREATE TABLE users (id bigint NOT NULL, PRIMARY KEY (id))",
                "CREATE TABLE orders (id bigint, user_id bigint)",
                "ALTER TABLE orders ADD CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)"
            );
            assertThat(hasRule(diags, "PG006")).isTrue();
        }
    }

    @Nested
    class TypeDesign {
        @Test void pg101_charType() {
            var diags = lint("CREATE TABLE test (code char(10))");
            assertThat(hasRule(diags, "PG101")).isTrue();
        }

        @Test void pg102_timestampWithoutTz() {
            var diags = lint("CREATE TABLE test (created_at timestamp)");
            assertThat(hasRule(diags, "PG102")).isTrue();
        }

        @Test void pg102_timestamptz_noWarning() {
            var diags = lint("CREATE TABLE test (created_at timestamptz)");
            assertThat(hasRule(diags, "PG102")).isFalse();
        }

        @Test void pg103_moneyType() {
            var diags = lint("CREATE TABLE test (amount money)");
            assertThat(hasRule(diags, "PG103")).isTrue();
        }

        @Test void pg104_serialType() {
            var diags = lint("CREATE TABLE test (id serial NOT NULL)");
            assertThat(hasRule(diags, "PG104")).isTrue();
        }

        @Test void pg105_jsonType() {
            var diags = lint("CREATE TABLE test (data json)");
            assertThat(hasRule(diags, "PG105")).isTrue();
        }

        @Test void pg105_jsonb_noWarning() {
            var diags = lint("CREATE TABLE test (data jsonb)");
            assertThat(hasRule(diags, "PG105")).isFalse();
        }

        @Test void pg106_integerPrimaryKey() {
            var diags = lint("CREATE TABLE test (id integer NOT NULL, PRIMARY KEY (id))");
            assertThat(hasRule(diags, "PG106")).isTrue();
        }

        @Test void pg106_bigintPrimaryKey_noWarning() {
            var diags = lint("CREATE TABLE test (id bigint NOT NULL, PRIMARY KEY (id))");
            assertThat(hasRule(diags, "PG106")).isFalse();
        }
    }

    @Nested
    class SchemaDesign {
        @Test void pg201_tableWithoutPk() {
            var diags = lint("CREATE TABLE test (id bigint, name text)");
            assertThat(hasRule(diags, "PG201")).isTrue();
        }

        @Test void pg201_tableWithPk_noWarning() {
            var diags = lint("CREATE TABLE test (id bigint NOT NULL, PRIMARY KEY (id))");
            assertThat(hasRule(diags, "PG201")).isFalse();
        }

        @Test void pg203_unnamedConstraint() {
            var diags = lint("CREATE TABLE test (id bigint NOT NULL, UNIQUE (id))");
            assertThat(hasRule(diags, "PG203")).isTrue();
        }

        @Test void pg203_namedConstraint_noWarning() {
            var diags = lint("CREATE TABLE test (id bigint NOT NULL, CONSTRAINT uq_id UNIQUE (id))");
            assertThat(hasRule(diags, "PG203")).isFalse();
        }
    }

    @Nested
    class Configuration {
        @Test void disabledRuleProducesNoDiagnostic() {
            var config = new LintConfig(Set.of("PG002"), Map.of());
            var customEngine = LintEngine.create(config);

            var cst = parser.parseCst("CREATE TABLE users (id bigint, email text)").unwrap();
            var events1 = DdlAnalyzer.analyze(cst).unwrap();
            var cst2 = parser.parseCst("CREATE INDEX idx ON users (email)").unwrap();
            var events2 = DdlAnalyzer.analyze(cst2).unwrap();

            var allEvents = new java.util.ArrayList<>(events1);
            allEvents.addAll(events2);

            var diags = customEngine.lint(allEvents);
            assertThat(hasRule(diags, "PG002")).isFalse();
        }

        @Test void severityOverride() {
            var config = new LintConfig(Set.of(), Map.of("PG002", LintDiagnostic.Severity.ERROR));
            var customEngine = LintEngine.create(config);

            var cst = parser.parseCst("CREATE TABLE users (id bigint, email text)").unwrap();
            var events1 = DdlAnalyzer.analyze(cst).unwrap();
            var cst2 = parser.parseCst("CREATE INDEX idx ON users (email)").unwrap();
            var events2 = DdlAnalyzer.analyze(cst2).unwrap();

            var allEvents = new java.util.ArrayList<>(events1);
            allEvents.addAll(events2);

            var diags = customEngine.lint(allEvents);
            var pg002 = diags.stream().filter(d -> d.ruleId().equals("PG002")).findFirst();
            assertThat(pg002).isPresent();
            assertThat(pg002.get().severity()).isEqualTo(LintDiagnostic.Severity.ERROR);
        }
    }

    @Nested
    class ExtendedLockHazards {
        @Test void pg007_dropIndex() {
            var diags = lint(
                "CREATE TABLE t (id bigint, name text)",
                "CREATE INDEX idx ON t (name)",
                "DROP INDEX idx"
            );
            assertThat(hasRule(diags, "PG007")).isTrue();
        }

        @Test void pg008_addCheckConstraint() {
            var diags = lint(
                "CREATE TABLE t (id bigint, age integer)",
                "ALTER TABLE t ADD CONSTRAINT chk CHECK (age > 0)"
            );
            assertThat(hasRule(diags, "PG008")).isTrue();
        }

        @Test void pg009_renameColumn() {
            var diags = lint(
                "CREATE TABLE t (id bigint, name text)",
                "ALTER TABLE t RENAME COLUMN name TO display_name"
            );
            assertThat(hasRule(diags, "PG009")).isTrue();
        }

        @Test void pg010_renameTable() {
            var diags = lint(
                "CREATE TABLE users (id bigint)",
                "ALTER TABLE users RENAME TO accounts"
            );
            assertThat(hasRule(diags, "PG010")).isTrue();
        }

        @Test void pg011_dropColumn() {
            var diags = lint(
                "CREATE TABLE t (id bigint, old_col text)",
                "ALTER TABLE t DROP COLUMN old_col"
            );
            assertThat(hasRule(diags, "PG011")).isTrue();
        }

        @Test void pg013_setDefault() {
            var diags = lint(
                "CREATE TABLE t (id bigint, name text)",
                "ALTER TABLE t ALTER COLUMN name SET DEFAULT 'unknown'"
            );
            assertThat(hasRule(diags, "PG013")).isTrue();
        }
    }

    @Nested
    class ExtendedTypeDesign {
        @Test void pg107_floatType() {
            var diags = lint("CREATE TABLE t (amount real)");
            assertThat(hasRule(diags, "PG107")).isTrue();
        }

        @Test void pg108_timetzType() {
            var diags = lint("CREATE TABLE t (t timetz)");
            assertThat(hasRule(diags, "PG108")).isTrue();
        }

        @Test void pg109_varcharWithLimit() {
            var diags = lint("CREATE TABLE t (name varchar(100))");
            assertThat(hasRule(diags, "PG109")).isTrue();
        }

        @Test void pg112_enumCreation() {
            var diags = lint("CREATE TYPE status AS ENUM ('a', 'b')");
            assertThat(hasRule(diags, "PG112")).isTrue();
        }
    }

    @Nested
    class ExtendedSchemaDesign {
        @Test void pg205_wideIndex() {
            var diags = lint(
                "CREATE TABLE t (a integer, b integer, c integer, d integer, e integer)",
                "CREATE INDEX idx ON t (a, b, c, d)"
            );
            assertThat(hasRule(diags, "PG205")).isTrue();
        }

        @Test void pg207_uppercaseName() {
            var diags = lint("CREATE TABLE \"MyTable\" (\"Id\" bigint)");
            assertThat(hasRule(diags, "PG207")).isTrue();
        }

        @Test void pg208_reservedWordName() {
            var diags = lint("CREATE TABLE test (\"order\" integer, \"user\" text)");
            assertThat(hasRule(diags, "PG208")).isTrue();
        }
    }

    @Nested
    class MigrationPractice {
        @Test void pg301_dropNonexistentTable() {
            var diags = lint("DROP TABLE nonexistent");
            assertThat(hasRule(diags, "PG301")).isTrue();
        }

        @Test void pg301_dropExistingTable_noWarning() {
            var diags = lint(
                "CREATE TABLE users (id bigint)",
                "DROP TABLE users"
            );
            assertThat(hasRule(diags, "PG301")).isFalse();
        }

        @Test void pg303_fkHintOnCreate() {
            var diags = lint(
                "CREATE TABLE users (id bigint NOT NULL, PRIMARY KEY (id))",
                "CREATE TABLE orders (id bigint, user_id bigint, FOREIGN KEY (user_id) REFERENCES users(id))"
            );
            assertThat(hasRule(diags, "PG303")).isTrue();
        }

        @Test void pg305_enumAddValue() {
            var diags = lint(
                "CREATE TYPE status AS ENUM ('a', 'b')",
                "ALTER TYPE status ADD VALUE 'c'"
            );
            assertThat(hasRule(diags, "PG305")).isTrue();
        }
    }

    @Nested
    class RealWorldMigration {
        @Test void detectsMultipleIssues() {
            var diags = lint(
                // V001: Several anti-patterns
                """
                CREATE TABLE users (
                    id serial NOT NULL,
                    name char(50),
                    email text,
                    data json,
                    created_at timestamp DEFAULT now()
                )""",
                // V002: Index without concurrently
                "CREATE INDEX idx_email ON users (email)"
            );

            // Should detect: PG104 (serial), PG101 (char), PG105 (json),
            // PG102 (timestamp), PG201 (no PK), PG002 (no concurrently)
            assertThat(hasRule(diags, "PG104")).as("PG104: serial").isTrue();
            assertThat(hasRule(diags, "PG101")).as("PG101: char").isTrue();
            assertThat(hasRule(diags, "PG105")).as("PG105: json").isTrue();
            assertThat(hasRule(diags, "PG102")).as("PG102: timestamp").isTrue();
            assertThat(hasRule(diags, "PG201")).as("PG201: no PK").isTrue();
            assertThat(hasRule(diags, "PG002")).as("PG002: no concurrently").isTrue();
        }
    }
}
