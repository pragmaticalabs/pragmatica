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

/// Negative tests for the compile-time SQL parsing + validation phase.
///
/// Each test compiles a `@PgSql` interface whose `@Query` or CRUD method references
/// schema entities that do not exist (or is syntactically invalid), then asserts the
/// processor emits the expected `[PG-VALIDATE]` diagnostic and compilation fails.
///
/// The test schema (see `src/test/resources/schema/V001__init.sql`) defines tables
/// `users(id, name, email, active, created_at)`, `orders(id, user_id, status, total, created_at)`,
/// `events(id, name, type, created_at)`, and `items(id, name)`.
class CompileFailureValidationTest {

    @TempDir
    Path tempDir;

    @Nested
    class Select {
        @Test
        void selectWithNonExistentColumn_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface BadSelectRepo {
                    record Row(String email) {}

                    @Query("SELECT u.typo_col FROM users u WHERE u.id = :id")
                    Promise<Option<Row>> find(long id);
                }
                """;

            var result = compile(source, "test/BadSelectRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("typo_col");
        }

        @Test
        void selectFromUnknownTable_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface UnknownTableRepo {
                    record Row(String name) {}

                    @Query("SELECT name FROM nonexistent_table WHERE id = :id")
                    Promise<Option<Row>> find(long id);
                }
                """;

            var result = compile(source, "test/UnknownTableRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("nonexistent_table");
        }

        @Test
        void whereReferencingNonExistentColumn_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface BadWhereRepo {
                    record Row(long id) {}

                    @Query("SELECT u.id FROM users u WHERE u.bogus_col = :value")
                    Promise<Option<Row>> find(String value);
                }
                """;

            var result = compile(source, "test/BadWhereRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("bogus_col");
        }
    }

    @Nested
    class Join {
        @Test
        void joinOnNonExistentColumn_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import java.util.List;

                @PgSql
                public interface BadJoinRepo {
                    record Row(long id) {}

                    @Query("SELECT u.id FROM users u JOIN orders o ON u.missing_fk = o.user_id")
                    Promise<List<Row>> findAll();
                }
                """;

            var result = compile(source, "test/BadJoinRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("missing_fk");
        }

        @Test
        void joinReferencingUnknownAlias_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import java.util.List;

                @PgSql
                public interface UnknownAliasRepo {
                    record Row(long id) {}

                    @Query("SELECT x.id FROM users u JOIN orders o ON u.id = o.user_id")
                    Promise<List<Row>> findAll();
                }
                """;

            var result = compile(source, "test/UnknownAliasRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]");
        }

        @Test
        void aliasResolutionFailure_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface AliasFailRepo {
                    record Row(String name) {}

                    @Query("SELECT z.name FROM users u WHERE u.id = :id")
                    Promise<Option<Row>> find(long id);
                }
                """;

            var result = compile(source, "test/AliasFailRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]");
        }
    }

    @Nested
    class WriteStatements {
        @Test
        void updateSetWithNonExistentColumn_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface BadUpdateRepo {
                    @Query("UPDATE users SET not_a_column = :val WHERE id = :id")
                    Promise<Unit> update(long id, String val);
                }
                """;

            var result = compile(source, "test/BadUpdateRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("not_a_column");
        }

        @Test
        void deleteFromNonExistentTable_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface BadDeleteRepo {
                    @Query("DELETE FROM ghost_table WHERE id = :id")
                    Promise<Unit> delete(long id);
                }
                """;

            var result = compile(source, "test/BadDeleteRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("ghost_table");
        }

        @Test
        void insertWithNonExistentColumn_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface BadInsertRepo {
                    @Query("INSERT INTO users (id, ghost_column) VALUES (:id, :value)")
                    Promise<Unit> insert(long id, String value);
                }
                """;

            var result = compile(source, "test/BadInsertRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]").contains("ghost_column");
        }
    }

    @Nested
    class Subqueries {
        @Test
        void subqueryReferencingUnknownTable_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import java.util.List;

                @PgSql
                public interface SubqueryRepo {
                    record Row(long id) {}

                    @Query("SELECT u.id FROM users u WHERE u.id IN (SELECT o.user_id FROM ghost_orders o)")
                    Promise<List<Row>> findAll();
                }
                """;

            var result = compile(source, "test/SubqueryRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]");
        }
    }

    @Nested
    class ParseFailures {
        @Test
        void syntacticallyInvalidSql_failsCompilation() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface BrokenSqlRepo {
                    @Query("SELEKT FRUM users WERE")
                    Promise<Unit> broken();
                }
                """;

            var result = compile(source, "test/BrokenSqlRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("[PG-VALIDATE]");
        }
    }

    private TestCompilationHelper.CompilationResult compile(String sourceCode, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(sourceCode, fileName, tempDir);
    }
}
