// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Shared compilation infrastructure for annotation processor tests.
final class TestCompilationHelper {
    private TestCompilationHelper() {}

    record CompilationResult(boolean success, String diagnostics, Path outputDir) {
        String generatedSource(String qualifiedName) throws IOException {
            var relativePath = qualifiedName.replace('.', '/') + ".java";
            var generatedFile = outputDir.resolve(relativePath);
            return Files.exists(generatedFile)
                   ? Files.readString(generatedFile)
                   : null;
        }
    }

    static CompilationResult compileWithProcessor(String sourceCode, String fileName, Path tempDir) throws Exception {
        return compileWithProcessor(List.of(new SourceEntry(fileName, sourceCode)), tempDir);
    }

    record SourceEntry(String fileName, String sourceCode) {}

    static CompilationResult compileWithProcessor(List<SourceEntry> sources, Path tempDir) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnosticCollector = new DiagnosticCollector<JavaFileObject>();

        var outputDir = tempDir.resolve("generated");
        Files.createDirectories(outputDir);

        var classOutputDir = tempDir.resolve("classes");
        Files.createDirectories(classOutputDir);

        var classpath = System.getProperty("java.class.path");

        var options = List.of(
            "-d", classOutputDir.toString(),
            "-s", outputDir.toString(),
            "-classpath", classpath,
            "-proc:only",
            "-Xlint:none",
            "--release", "25"
        );

        var sourceFiles = sources.stream()
                                 .map(s -> (JavaFileObject) new InMemoryJavaFileObject(s.fileName(), s.sourceCode()))
                                 .toList();

        try (var fileManager = compiler.getStandardFileManager(diagnosticCollector, null, null)) {
            var task = compiler.getTask(
                null, fileManager, diagnosticCollector, options, null, sourceFiles
            );
            task.setProcessors(List.of(new QueryAnnotationProcessor()));
            var success = task.call();
            var diagMessages = diagnosticCollector.getDiagnostics().stream()
                .map(d -> d.getKind() + ": " + d.getMessage(null))
                .reduce("", (a, b) -> a + "\n" + b);
            return new CompilationResult(success, diagMessages, outputDir);
        }
    }

    static final class InMemoryJavaFileObject extends SimpleJavaFileObject {
        private final String code;

        InMemoryJavaFileObject(String fileName, String code) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
