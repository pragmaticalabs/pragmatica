// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.generator;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the config-parse expression shape of {@link BatchedAll} — the single-expression
/// (no `var` declarations) batching used where a config-section fragment must stay an expression.
///
/// A flat `Result.all(a1..aN)` compiles only for N &lt;= 15 (core `Result.Mapper15`/`Fn15`/`Tuple15`),
/// so a config record with more than 15 factory parameters emitted non-compiling code. These tests
/// pin the inline batched shape, prove it compiles at 16 parameters (2 tuple parts) and confirm the
/// recursion kicks in past 225.
class BatchedAllTest {

    private static List<String> resultLiterals(int count) {
        return IntStream.range(0, count)
                        .mapToObj(i -> "Result.success(\"v" + i + "\")")
                        .collect(Collectors.toList());
    }

    private static List<String> leafNames(int count) {
        return IntStream.rangeClosed(1, count)
                        .mapToObj(i -> "c" + i)
                        .collect(Collectors.toList());
    }

    @Nested
    class InlineShape {
        @Test
        void sixteenParams_inlineTupleParts_cascadeToFactory() {
            var expr = BatchedAll.renderConfigExpression("Result", resultLiterals(16), leafNames(16),
                                                         "Widget", "widget", "");

            assertThat(expr).startsWith("Result.all(");
            assertThat(expr).contains(".id()");
            assertThat(expr).contains(".flatMap((t");
            assertThat(expr).contains(".map((c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15) ->");
            assertThat(expr).contains("Widget.widget(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16)");
            // No hoisted statements — must remain a single expression.
            assertThat(expr).doesNotContain("var part");
            // Two-part (not recursive) at 16: no tuple materialized inside another tuple.
            assertThat(expr).doesNotContain(".id()).id()");
        }

        @Test
        void suffixIsAppendedVerbatim() {
            var expr = BatchedAll.renderConfigExpression("Result", resultLiterals(16), leafNames(16),
                                                         "Widget", "widget", ".async()");
            assertThat(expr).endsWith(".async()");
        }

        @Test
        void twoHundredThirtyParams_recurseAtPartsLevel() {
            var expr = BatchedAll.renderConfigExpression("Result", resultLiterals(230), leafNames(230),
                                                         "Widget", "widget", "");
            // A tuple part materialized (.id()) as an argument of another tuple part (.id()) — recursion.
            assertThat(expr).contains(".id()).id()");
        }
    }

    @Nested
    class GeneratedExpressionCompiles {
        @Test
        void inlineBatchedConfigExpression_compilesToBytecode(@TempDir Path tempDir) throws Exception {
            var expr = BatchedAll.renderConfigExpression("Result", resultLiterals(16), leafNames(16),
                                                         "Widget", "widget", "");

            var diagnostics = compile(tempDir, "Probe", probeSource(expr, 16));

            assertThat(diagnostics)
                .as("Inline batched config expression must compile: %s", diagnostics)
                .isEmpty();
        }
    }

    private static String probeSource(String buildExpr, int fieldCount) {
        var params = IntStream.rangeClosed(1, fieldCount)
                              .mapToObj(i -> "String c" + i)
                              .collect(Collectors.joining(", "));
        var args = IntStream.rangeClosed(1, fieldCount)
                            .mapToObj(i -> "c" + i)
                            .collect(Collectors.joining(", "));
        return """
            import org.pragmatica.lang.Result;

            public final class Probe {
                record Widget(%s) {
                    static Result<Widget> widget(%s) {
                        return Result.success(new Widget(%s));
                    }
                }

                static Result<Widget> build() {
                    return %s;
                }
            }
            """.formatted(params, params, args, buildExpr);
    }

    private static String compile(Path tempDir, String name, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        var classesDir = Files.createDirectories(tempDir.resolve("classes"));
        var options = List.of("-d", classesDir.toString(),
                              "-classpath", System.getProperty("java.class.path"),
                              "-Xlint:none",
                              "--release", "25");
        var units = new ArrayList<JavaFileObject>();
        units.add(new InMemorySource(name, source));
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
