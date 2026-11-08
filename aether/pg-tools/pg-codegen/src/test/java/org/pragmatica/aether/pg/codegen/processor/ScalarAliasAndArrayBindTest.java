// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// #2885: an array parameter is one bind parameter, never spread into the connector's varargs.
/// #2886: the scalar column is the alias of the top-level select item, not the `AS` of a `CAST`.
class ScalarAliasAndArrayBindTest {
    @TempDir
    Path tempDir;

    @Test
    void castAsInt_withAlias_inferredAsAlias() {
        assertThat(column("SELECT CAST(count(*) AS int) AS n FROM t")).isEqualTo("n");
    }

    @Test
    void nestedParenthesesWithAs_inferredAsOuterAlias() {
        assertThat(column("SELECT COALESCE(CAST((SELECT max(x) AS m FROM u) AS bigint), 0) AS total FROM t"))
            .isEqualTo("total");
    }

    @Test
    void lowercaseAs_inferredAsAlias() {
        assertThat(column("select cast(count(*) as int) as n from t")).isEqualTo("n");
    }

    @Test
    void fromAliasWithAs_isNotTheSelectAlias() {
        assertThat(column("SELECT count(*) FROM orders AS o")).isEqualTo("count");
    }

    @Test
    void castWithoutAlias_followsFunctionRule_documentedAsCast() {
        assertThat(column("SELECT CAST(count(*) AS bigint) FROM t")).isEqualTo("cast");
    }

    @Test
    void returningCast_withAlias_inferredAsAlias() {
        assertThat(column("INSERT INTO t (a) VALUES (1) RETURNING CAST(a AS int) AS n")).isEqualTo("n");
    }

    @Test
    void unitMethod_withSingleArrayParam_bindsOneParameter() throws Exception {
        var generated = generated("""
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;

            @PgSql
            public interface TagRepo {
                @Query("DELETE FROM tags WHERE name = ANY(:names)")
                Promise<Unit> remove(String[] names);
            }
            """);

        assertThat(generated).contains("(Object) (names)");
    }

    @Test
    void queryMethod_withSingleArrayParam_bindsOneParameter() throws Exception {
        var generated = generated("""
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Promise;

            @PgSql
            public interface TagRepo {
                @Query("SELECT count(*) FROM tags WHERE name = ANY(:names)")
                Promise<Long> countBy(String[] names);
            }
            """);

        assertThat(generated).contains("(Object) (names)");
    }

    private static String column(String sql) {
        return FactoryGenerator.inferScalarColumnName(sql, MethodAnalyzer.ReturnKind.LONG);
    }

    private String generated(String source) throws Exception {
        var result = TestCompilationHelper.compileWithProcessor(source, "test/TagRepo.java", tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        return result.generatedSource("test.TagRepoFactory");
    }
}
