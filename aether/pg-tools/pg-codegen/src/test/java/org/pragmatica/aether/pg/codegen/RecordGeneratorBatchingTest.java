// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Table;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the row-mapper batching that lifts the record generator past the core `Result.all`
/// arity-15 ceiling (`Result.Mapper15`/`Fn15`/`Tuple15`).
///
/// The pre-fix generator dispatched at a stale `<= 11` threshold and, above it, emitted an
/// unsalvageable `renderNestedRowMapper` stub (`// TODO: extract tuple fields`, `MapperN` values fed
/// into an outer `Result.all`). These fixtures cross the batch boundary at 12 (now flat — the
/// formerly-broken zone), 16 (first batched: 2 parts) and 31 (3 parts, uneven tail), asserting the
/// generated SHAPE and, at 16, that the emitted record actually compiles.
class RecordGeneratorBatchingTest {

    private static final PgType BIGINT = new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC);
    private static final PgType TEXT = new PgType.BuiltinType("text", PgType.TypeCategory.STRING);

    private static RecordGenerator generator(Path dir) {
        return new RecordGenerator(CodegenConfig.defaults("com.example.db", dir));
    }

    private static Table wideTable(int columnCount) {
        return tableOf(columnCount, BIGINT);
    }

    private static Table tableOf(int columnCount, PgType type) {
        var columns = new ArrayList<Column>();
        for (var i = 0; i < columnCount; i++) {
            columns.add(Column.column("c" + i, type, false));
        }
        return Table.table("wide", "", columns, List.of());
    }

    @Nested
    class FlatFastPath {
        @Test
        void twelveColumns_emitFlatResultAll_notBrokenStub(@TempDir Path tempDir) {
            var content = generator(tempDir).generate(wideTable(12)).unwrap().content();

            assertThat(content).contains("Result.all(");
            // Flat path: no batching machinery, and none of the old broken-stub leftovers.
            assertThat(content).doesNotContain(".id()");
            assertThat(content).doesNotContain("var part1");
            assertThat(content).doesNotContain("TODO");
            assertThat(content).contains(").map(WideRow::new);");
        }
    }

    @Nested
    class BatchedCascade {
        @Test
        void sixteenColumns_emitTwoTupleParts_thenCascade(@TempDir Path tempDir) {
            var content = generator(tempDir).generate(wideTable(16)).unwrap().content();

            assertThat(content).contains("var part1 = Result.all(");
            assertThat(content).contains("var part2 = Result.all(");
            assertThat(content).contains(").id();");
            assertThat(content).contains("return Result.all(part1, part2)");
            assertThat(content).contains(".map((t1, t2) ->");
            assertThat(content).contains("t1.map((c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15) ->");
            assertThat(content).contains("t2.map((c16) ->");
            assertThat(content).contains("new WideRow(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16)");
            assertThat(content).doesNotContain("TODO");
        }

        @Test
        void thirtyOneColumns_emitThreeParts_withUnevenTail(@TempDir Path tempDir) {
            var content = generator(tempDir).generate(wideTable(31)).unwrap().content();

            assertThat(content).contains("var part1 = Result.all(");
            assertThat(content).contains("var part2 = Result.all(");
            assertThat(content).contains("var part3 = Result.all(");
            assertThat(content).contains("return Result.all(part1, part2, part3)");
            assertThat(content).contains(".map((t1, t2, t3) ->");
            // Uneven tail: the third part carries the single 31st column.
            assertThat(content).contains("t3.map((c31) ->");
            assertThat(content).contains("c31)");
            assertThat(content).doesNotContain("TODO");
        }

        @Test
        void twoHundredThirtyColumns_recurseAtPartsLevel(@TempDir Path tempDir) {
            // 230 columns -> 16 parts (> 15) -> the parts themselves batch into 2 super-parts, proving
            // the recursion does not reintroduce a cliff at 225.
            var content = generator(tempDir).generate(wideTable(230)).unwrap().content();

            assertThat(content).contains("var part16 = Result.all(");
            assertThat(content).contains("var part18 = Result.all(");
            // A super-part is a Result.all over other PARTS (bare part names, not accessors).
            assertThat(content).contains("            part1,");
            assertThat(content).contains("return Result.all(part16, part18)");
        }
    }

    @Nested
    class GeneratedCodeCompiles {
        @Test
        void batchedRowMapper_compilesToBytecode(@TempDir Path tempDir) throws Exception {
            var generated = generator(tempDir).generate(wideTable(16)).unwrap();

            var diagnostics = compile(tempDir,
                                      new Src(generated.className(), generated.content()),
                                      new Src("RowAccessor", rowAccessorShim()));

            assertThat(diagnostics)
                .as("Generated 16-column batched mapper must compile: %s", diagnostics)
                .isEmpty();
        }

        @Test
        void recursivelyBatchedRowMapper_compilesToBytecode(@TempDir Path tempDir) throws Exception {
            // 230 columns forces the parts level itself to batch (recursion). Use text (1 JVM slot per
            // param) so the generated record constructor stays within the 255-slot limit — that limit is
            // an orthogonal property of wide records (the flat Type::new constructor has the same arity),
            // not of the batching, so this isolates the recursion cascade's validity.
            var generated = generator(tempDir).generate(tableOf(230, TEXT)).unwrap();

            var diagnostics = compile(tempDir,
                                      new Src(generated.className(), generated.content()),
                                      new Src("RowAccessor", rowAccessorShim()));

            assertThat(diagnostics)
                .as("Generated 230-column recursively-batched mapper must compile: %s", diagnostics)
                .isEmpty();
        }
    }

    private static String rowAccessorShim() {
        return """
            package com.example.db;

            import org.pragmatica.lang.Result;

            public interface RowAccessor {
                Result<String> getString(String column);
                Result<Integer> getInt(String column);
                Result<Long> getLong(String column);
                Result<Double> getDouble(String column);
                Result<Boolean> getBoolean(String column);
                Result<byte[]> getBytes(String column);
                <V> Result<V> getObject(String column, Class<V> type);
            }
            """;
    }

    private record Src(String name, String code) {}

    private static String compile(Path tempDir, Src... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        var classesDir = Files.createDirectories(tempDir.resolve("classes"));
        var options = List.of("-d", classesDir.toString(),
                              "-classpath", System.getProperty("java.class.path"),
                              "-Xlint:none",
                              "--release", "25");
        var units = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            units.add(new InMemorySource(source.name(), source.code()));
        }
        try (var fileManager = compiler.getStandardFileManager(collector, null, null)) {
            compiler.getTask(null, fileManager, collector, options, null, units).call();
        }
        return collector.getDiagnostics()
                        .stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(d -> d.getMessage(null))
                        .reduce("", (a, b) -> a + "\n" + b);
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        private InMemorySource(String name, String code) {
            super(URI.create("string:///" + name + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
