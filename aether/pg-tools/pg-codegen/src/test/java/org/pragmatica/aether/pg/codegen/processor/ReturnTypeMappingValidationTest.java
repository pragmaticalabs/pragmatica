// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests for the CST-based return-type field mapping check in `QueryAnnotationProcessor`.
///
/// The processor warns (never errors) when a return-row record field maps to no output column of a
/// `@Query` SELECT. Output columns are resolved precisely from the parsed CST via
/// `QueryValidator.selectOutputColumnNames`, so an aliased column (`status AS state`) resolves to
/// its alias — the behavior the earlier regex heuristic got wrong — and `SELECT *` (whose output
/// set is unknown at compile time) skips the check entirely.
///
/// The test schema (see `src/test/resources/schema/V001__init.sql`) defines table
/// `orders(id, user_id, status, total, correlation_id, created_at)`.
class ReturnTypeMappingValidationTest {

    private static final String NO_MATCH = "has no matching SELECT column";

    @TempDir
    Path tempDir;

    @Test
    void returnFieldWithoutMatchingColumn_emitsWarning() throws Exception {
        var source = """
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Promise;
            import java.util.List;

            @PgSql
            public interface UnmappedFieldRepo {
                record Row(long id, String state) {}

                @Query("SELECT id, status FROM orders")
                Promise<List<Row>> findAll();
            }
            """;

        var result = compile(source, "test/UnmappedFieldRepo.java");

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics())
            .contains("[PG-VALIDATE]")
            .contains(NO_MATCH)
            .contains("state");
    }

    @Test
    void aliasedColumnMatchingField_emitsNoWarning() throws Exception {
        var source = """
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Promise;
            import java.util.List;

            @PgSql
            public interface AliasedColumnRepo {
                record Row(long id, String state) {}

                @Query("SELECT id, status AS state FROM orders")
                Promise<List<Row>> findAll();
            }
            """;

        var result = compile(source, "test/AliasedColumnRepo.java");

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).doesNotContain(NO_MATCH);
    }

    @Test
    void selectStar_emitsNoWarning() throws Exception {
        var source = """
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Promise;
            import java.util.List;

            @PgSql
            public interface SelectStarRepo {
                record Row(long id, String state) {}

                @Query("SELECT * FROM orders")
                Promise<List<Row>> findAll();
            }
            """;

        var result = compile(source, "test/SelectStarRepo.java");

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).doesNotContain(NO_MATCH);
    }

    private TestCompilationHelper.CompilationResult compile(String sourceCode, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(sourceCode, fileName, tempDir);
    }
}
