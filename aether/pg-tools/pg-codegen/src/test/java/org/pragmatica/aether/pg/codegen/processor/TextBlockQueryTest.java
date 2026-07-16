// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end tests for text-block (`"""…"""`) multi-line `@Query` support (issue #390).
///
/// A text block arrives at the processor (via the typed annotation accessor) as the
/// compiler-cooked value containing REAL newline characters. Before the fix, those newlines
/// were emitted unescaped into a `"…"` Java string literal → unterminated literal → the
/// GENERATED factory failed to compile. These tests compile the generated factory all the way
/// to bytecode (no `-proc:only`), which is the only way to catch a mis-emitted literal — the
/// other E2E suites stop after annotation processing and never compile the output.
class TextBlockQueryTest {

    @TempDir
    Path tempDir;

    @Nested
    class GeneratedCodeCompiles {
        @Test
        void textBlockQuery_generatesValidCompilableFactory() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TextBlockRepo {

                    record UserRow(long id, String name, String email) {}

                    @Query(""\"
                        SELECT id, name, email
                        FROM users
                        WHERE email = :email
                          AND name = :name
                        ""\")
                    Promise<Option<UserRow>> findByEmailAndName(String email, String name);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/TextBlockRepo.java", tempDir);

            assertThat(result.success())
                .as("Generated factory must compile (text-block newlines escaped): " + result.diagnostics())
                .isTrue();

            var generated = result.generatedSource("test.TextBlockRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("findByEmailAndName");
            // Named params rewritten exactly as in the single-line form.
            assertThat(generated).contains("$1");
            assertThat(generated).contains("$2");
            assertThat(generated).doesNotContain(":email");
            assertThat(generated).doesNotContain(":name");
        }
    }

    @Nested
    class LiteralWellFormedness {
        @Test
        void textBlockQuery_emitsLiteralWithoutRawNewlines() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface LiteralRepo {

                    record UserRow(long id, String name, String email) {}

                    @Query(""\"
                        SELECT id, name, email
                        FROM users
                        WHERE email = :email
                        ""\")
                    Promise<Option<UserRow>> findByEmail(String email);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/LiteralRepo.java", tempDir);

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.LiteralRepoFactory");
            assertThat(generated).isNotNull();

            var literal = sqlLiteralContent(generated, "FIND_BY_EMAIL");
            // The emitted literal must be a single, well-formed Java string literal:
            // no raw newline character inside it, and the source newlines preserved as `\\n` escapes.
            assertThat(literal).doesNotContain("\n");
            assertThat(literal).contains("\\n");
        }
    }

    @Nested
    class EquivalentToConcatenatedForm {
        @Test
        void textBlockQuery_producesSameSqlAsConcatenatedForm() throws Exception {
            var textBlock = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TbRepo {

                    record UserRow(long id, String name, String email) {}

                    @Query(""\"
                        SELECT id, name, email
                        FROM users
                        WHERE email = :email
                          AND name = :name
                        ""\")
                    Promise<Option<UserRow>> findByEmailAndName(String email, String name);
                }
                """;

            var concatenated = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface ConcatRepo {

                    record UserRow(long id, String name, String email) {}

                    @Query("SELECT id, name, email "
                         + "FROM users "
                         + "WHERE email = :email "
                         + "AND name = :name")
                    Promise<Option<UserRow>> findByEmailAndName(String email, String name);
                }
                """;

            var tbResult = TestCompilationHelper.compileAndCompileGenerated(textBlock, "test/TbRepo.java", tempDir.resolve("tb"));
            var concatResult = TestCompilationHelper.compileAndCompileGenerated(concatenated, "test/ConcatRepo.java", tempDir.resolve("concat"));

            assertThat(tbResult.success()).as("Text-block factory compiles: " + tbResult.diagnostics()).isTrue();
            assertThat(concatResult.success()).as("Concatenated factory compiles: " + concatResult.diagnostics()).isTrue();

            var tbSql = unescapeJavaLiteral(sqlLiteralContent(tbResult.generatedSource("test.TbRepoFactory"), "FIND_BY_EMAIL_AND_NAME"));
            var concatSql = unescapeJavaLiteral(sqlLiteralContent(concatResult.generatedSource("test.ConcatRepoFactory"), "FIND_BY_EMAIL_AND_NAME"));

            // PostgreSQL treats runs of whitespace (newlines included) as a single separator,
            // so the two forms are behaviourally identical iff they match after normalization.
            assertThat(normalizeWhitespace(tbSql)).isEqualTo(normalizeWhitespace(concatSql));
        }
    }

    @Nested
    class PreserveAggregateVersionAllocation {
        /// Atomic version-allocation pattern: a single-statement
        /// `INSERT … SELECT coalesce(max(version), 0) + 1 … RETURNING` with an aggregate.
        /// This validates fine in pg-codegen today and must keep working — the escapeSql change
        /// is purely an emission concern downstream of validation.
        @Test
        void aggregateInsertSelectReturning_validatesAndCompiles() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface VersionRepo {

                    @Query("INSERT INTO schema_versions (version) SELECT coalesce(max(version), 0) + 1 FROM schema_versions RETURNING version")
                    Promise<Long> allocateNextVersion();
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/VersionRepo.java", tempDir);

            assertThat(result.success()).as("Aggregate version-allocation must compile: " + result.diagnostics()).isTrue();
            // No schema validation error: the aggregate in a single INSERT...SELECT...RETURNING is accepted.
            assertThat(result.diagnostics()).doesNotContain("PG-VALIDATE");

            var generated = result.generatedSource("test.VersionRepoFactory");
            assertThat(generated).isNotNull();
            // The aggregate expression survives intact into the emitted SQL.
            assertThat(generated).contains("coalesce(max(version), 0) + 1");
            assertThat(generated).contains("RETURNING version");
        }
    }

    // --- Literal extraction helpers ---

    /// Returns the still-escaped content between the quotes of the `private static final String
    /// <constantName> = "…";` declaration in the generated factory. Test SQL contains no `"`
    /// characters, so the first `";` after the opening quote is the literal terminator.
    private static String sqlLiteralContent(String generated, String constantName) {
        var marker = "String " + constantName + " =";
        var markerIdx = generated.indexOf(marker);
        assertThat(markerIdx).as("SQL constant " + constantName + " present").isGreaterThanOrEqualTo(0);

        var open = generated.indexOf('"', markerIdx);
        var close = generated.indexOf("\";", open + 1);
        assertThat(close).as("closing quote for " + constantName).isGreaterThan(open);

        return generated.substring(open + 1, close);
    }

    /// Reverses the escaping applied by FactoryGenerator.escapeSql. Sufficient for test SQL,
    /// which contains no backslash characters of its own.
    private static String unescapeJavaLiteral(String content) {
        return content.replace("\\n", "\n")
                      .replace("\\r", "\r")
                      .replace("\\t", "\t")
                      .replace("\\\"", "\"")
                      .replace("\\\\", "\\");
    }

    private static String normalizeWhitespace(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
