// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// #2885: a reference-array parameter is one bind parameter, never spread into the connector's varargs,
/// on every generated kind. #2886: the scalar column is the output column the query's parse tree names,
/// never a text scan; an unaliased expression is a build error.
class ScalarAliasAndArrayBindTest {
    @TempDir
    Path tempDir;

    @Nested
    class ArrayBinding {
        @Test
        void unit() throws Exception {
            assertThat(factory("Promise<Unit>", "DELETE FROM users WHERE name = ANY(:names)")).contains("(Object) (names)");
        }

        @Test
        void single() throws Exception {
            assertThat(factory("Promise<String>", "SELECT name AS n FROM users WHERE name = ANY(:names)")).contains("(Object) (names)");
        }

        @Test
        void optional() throws Exception {
            assertThat(factory("Promise<Option<String>>", "SELECT name AS n FROM users WHERE name = ANY(:names)")).contains("(Object) (names)");
        }

        @Test
        void list() throws Exception {
            assertThat(factory("Promise<List<String>>", "SELECT name AS n FROM users WHERE name = ANY(:names)")).contains("(Object) (names)");
        }

        @Test
        void longKind() throws Exception {
            assertThat(factory("Promise<Long>", "SELECT count(*) AS n FROM users WHERE name = ANY(:names)")).contains("(Object) (names)");
        }

        @Test
        void booleanKind() throws Exception {
            assertThat(factory("Promise<Boolean>", "SELECT EXISTS(SELECT 1 FROM users WHERE name = ANY(:names)) AS found")).contains("(Object) (names)");
        }
    }

    @Nested
    class ScalarColumn {
        @Test
        void castWithAlias_isTheAlias() throws Exception {
            assertThat(factory("Promise<Long>", "SELECT CAST(count(*) AS int) AS n FROM users WHERE name = ANY(:names)"))
                .contains("row.getLong(\"n\")");
        }

        @Test
        void nestedParenthesesWithAs_isTheOuterAlias() throws Exception {
            assertThat(factory("Promise<Long>", "SELECT COALESCE(CAST((SELECT max(id) AS m FROM users) AS bigint), 0) AS total FROM users WHERE name = ANY(:names)"))
                .contains("row.getLong(\"total\")");
        }

        @Test
        void lowercaseAs_isTheAlias() throws Exception {
            assertThat(factory("Promise<Long>", "select cast(count(*) as int) as n from users where name = any(:names)"))
                .contains("row.getLong(\"n\")");
        }

        @Test
        void tableAlias_isNotTheColumn() throws Exception {
            assertThat(factory("Promise<String>", "SELECT u.name FROM users AS u WHERE u.name = ANY(:names)"))
                .contains("\"name\"")
                .doesNotContain("\"u\"");
        }

        @Test
        void quotedAlias_isTheColumn() throws Exception {
            assertThat(factory("Promise<Long>", "SELECT count(*) AS \"total\" FROM users WHERE name = ANY(:names)"))
                .contains("row.getLong(\"total\")");
        }

        @Test
        void returningCastWithAlias_isTheAlias() throws Exception {
            assertThat(factory("Promise<Long>", "UPDATE users SET active = true WHERE name = ANY(:names) RETURNING CAST(id AS bigint) AS v"))
                .contains("row.getLong(\"v\")");
        }

        @Test
        void literalContainingAs_doesNotConfuse() throws Exception {
            assertThat(factory("Promise<Long>", "SELECT count(*) AS n FROM users WHERE name = ANY(:names) AND email <> ' x AS y '"))
                .contains("row.getLong(\"n\")");
        }

        /// #2894 regression: an alias the lexer reads as a keyword / type word generated `row.getString("???")`.
        @ParameterizedTest
        @ValueSource(strings = {"name", "text", "user", "Name", "timestamp", "interval", "char", "varchar",
                                "boolean", "int", "integer", "bigint", "numeric", "json", "jsonb", "bit"})
        void keywordLikeAlias_isTheColumn(String alias) throws Exception {
            var source = factory("Promise<String>", "SELECT lower(name) AS " + alias + " FROM users WHERE name = ANY(:names)");

            assertThat(source).contains("row.getString(\"" + alias.toLowerCase() + "\")").doesNotContain("???");
        }

        @Test
        void quotedKeywordLikeAlias_keepsCase() throws Exception {
            assertThat(factory("Promise<String>", "SELECT lower(name) AS \"Name\" FROM users WHERE name = ANY(:names)"))
                .contains("row.getString(\"Name\")")
                .doesNotContain("???");
        }

        @Test
        void unaliasedCast_failsTheBuildAskingForAnAlias() throws Exception {
            var result = compile("Promise<Long>", "SELECT CAST(count(*) AS bigint) FROM users WHERE name = ANY(:names)");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("add an alias");
        }

        @Test
        void unaliasedFunction_failsTheBuildAskingForAnAlias() throws Exception {
            var result = compile("Promise<Long>", "SELECT count(*) FROM users WHERE name = ANY(:names)");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("add an alias");
        }
    }

    /// Generated CRUD `count` / `exists` SQL is unaliased by construction; its column is the one PostgreSQL names.
    @Test
    void crudCountAndExists_readThePostgresNamedColumn() throws Exception {
        var result = TestCompilationHelper.compileWithProcessor("""
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Table;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Promise;

            record UserRow(long id, String name, String email, boolean active) {}

            @PgSql
            public interface UserCrud {
                @Table(UserRow.class)
                Promise<Long> countByActive(boolean active);

                @Table(UserRow.class)
                Promise<Boolean> existsByEmail(String email);
            }
            """, "test/UserCrud.java", tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();
        assertThat(result.generatedSource("test.UserCrudFactory")).contains("row.getLong(\"count\")")
                                                                  .contains("row.getBoolean(\"exists\")");
    }

    private String factory(String returnType, String sql) throws Exception {
        var result = compile(returnType, sql);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        return result.generatedSource("test.TagRepoFactory");
    }

    private TestCompilationHelper.CompilationResult compile(String returnType, String sql) throws Exception {
        return TestCompilationHelper.compileWithProcessor("""
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import java.util.List;

            @PgSql
            public interface TagRepo {
                @Query("%s")
                %s run(String[] names);
            }
            """.formatted(sql.replace("\"", "\\\""), returnType), "test/TagRepo.java", tempDir);
    }
}
