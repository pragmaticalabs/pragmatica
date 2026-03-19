package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType.*;
import static org.pragmatica.aether.slice.blueprint.MigrationEntry.migrationEntry;

class ParsedMigrationTest {

    private static final String SAMPLE_SQL = "SELECT 1";
    private static final long SAMPLE_CHECKSUM = 12345L;

    @Nested
    class VersionedMigrations {

        @Test
        void parsedMigration_versionedPrefix_parsesTypeVersionAndDescription() {
            var entry = migrationEntry("V001__create_tables.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(parsed -> {
                    assertThat(parsed.type()).isEqualTo(VERSIONED);
                    assertThat(parsed.version()).isEqualTo(1);
                    assertThat(parsed.description()).isEqualTo("create_tables");
                    assertThat(parsed.entry()).isSameAs(entry);
                });
        }

        @Test
        void parsedMigration_leadingZeros_parsesVersionCorrectly() {
            var entry = migrationEntry("V0042__add_indexes.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(parsed -> {
                    assertThat(parsed.type()).isEqualTo(VERSIONED);
                    assertThat(parsed.version()).isEqualTo(42);
                });
        }
    }

    @Nested
    class RepeatableMigrations {

        @Test
        void parsedMigration_repeatablePrefix_parsesDescription() {
            var entry = migrationEntry("R__refresh_views.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(parsed -> {
                    assertThat(parsed.type()).isEqualTo(REPEATABLE);
                    assertThat(parsed.description()).isEqualTo("refresh_views");
                });
        }

        @Test
        void parsedMigration_repeatablePrefix_hasVersionZero() {
            var entry = migrationEntry("R__seed_data.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(parsed -> {
                    assertThat(parsed.version()).isEqualTo(0);
                });
        }
    }

    @Nested
    class UndoMigrations {

        @Test
        void parsedMigration_undoPrefix_parsesTypeVersionAndDescription() {
            var entry = migrationEntry("U001__undo_create.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(parsed -> {
                    assertThat(parsed.type()).isEqualTo(UNDO);
                    assertThat(parsed.version()).isEqualTo(1);
                    assertThat(parsed.description()).isEqualTo("undo_create");
                });
        }
    }

    @Nested
    class BaselineMigrations {

        @Test
        void parsedMigration_baselinePrefix_parsesTypeVersionAndDescription() {
            var entry = migrationEntry("B005__baseline.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(parsed -> {
                    assertThat(parsed.type()).isEqualTo(BASELINE);
                    assertThat(parsed.version()).isEqualTo(5);
                    assertThat(parsed.description()).isEqualTo("baseline");
                });
        }
    }

    @Nested
    class InvalidFormats {

        @Test
        void parsedMigration_noSqlExtension_returnsFailure() {
            var entry = migrationEntry("V001__create_tables.txt", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains(".sql"));
        }

        @Test
        void parsedMigration_unknownPrefix_returnsFailure() {
            var entry = migrationEntry("X001__create_tables.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("unknown prefix"));
        }

        @Test
        void parsedMigration_missingSeparator_returnsFailure() {
            var entry = migrationEntry("V001create_tables.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("separator"));
        }

        @Test
        void parsedMigration_emptyDescription_returnsFailure() {
            var entry = migrationEntry("V001__.sql", SAMPLE_SQL, SAMPLE_CHECKSUM);

            ParsedMigration.parsedMigration(entry)
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).contains("description"));
        }
    }
}
