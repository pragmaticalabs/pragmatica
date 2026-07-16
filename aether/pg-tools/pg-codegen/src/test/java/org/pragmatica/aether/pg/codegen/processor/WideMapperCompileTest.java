// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end tests for wide (`> 15`-column) `@Query` record row-mappers.
///
/// A flat `Result.all(a1..aN)` only compiles for N &lt;= 15 (core `Result.Mapper15`/`Fn15`/`Tuple15`
/// ceiling), so a record with more than 15 columns emitted non-compiling code before the fix. These
/// tests compile the generated factory all the way to bytecode (no `-proc:only`) — the only way to
/// prove the batched mapper is valid Java — at 16 columns (2 tuple parts) and 31 columns (3 parts,
/// uneven tail).
class WideMapperCompileTest {

    @TempDir
    Path tempDir;

    private static String wideRepoSource(int columnCount) {
        var fields = IntStream.range(0, columnCount)
                              .mapToObj(i -> "long f" + i)
                              .collect(Collectors.joining(", "));
        var selectList = IntStream.range(0, columnCount)
                                  .mapToObj(i -> "f" + i)
                                  .collect(Collectors.joining(", "));
        return """
            package test;

            import org.pragmatica.aether.pg.codegen.annotation.Query;
            import org.pragmatica.aether.resource.db.PgSql;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Promise;

            @PgSql
            public interface WideRepo {

                record WideRow(%s) {}

                @Query("SELECT %s FROM wide WHERE f0 = :f0")
                Promise<Option<WideRow>> findWide(long f0);
            }
            """.formatted(fields, selectList);
    }

    @Nested
    class GeneratedMapperCompiles {
        @Test
        void sixteenColumnRecord_batchedMapperCompiles() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(
                wideRepoSource(16), "test/WideRepo.java", tempDir);

            assertThat(result.success())
                .as("16-column batched mapper must compile: " + result.diagnostics())
                .isTrue();

            var generated = result.generatedSource("test.WideRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("var part1 = Result.all(");
            assertThat(generated).contains("var part2 = Result.all(");
            assertThat(generated).contains(").id();");
            assertThat(generated).contains("new WideRepo.WideRow(");
        }

        @Test
        void thirtyOneColumnRecord_batchedMapperCompiles() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(
                wideRepoSource(31), "test/WideRepo.java", tempDir);

            assertThat(result.success())
                .as("31-column batched mapper must compile: " + result.diagnostics())
                .isTrue();

            var generated = result.generatedSource("test.WideRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("var part3 = Result.all(");
            assertThat(generated).contains("return Result.all(part1, part2, part3)");
        }
    }
}
