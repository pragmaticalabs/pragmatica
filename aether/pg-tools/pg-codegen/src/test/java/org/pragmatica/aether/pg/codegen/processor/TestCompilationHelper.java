// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    /// Like {@link #compileWithProcessor(String, String, Path)} but compiles the GENERATED
    /// factory all the way to bytecode (no `-proc:only`). This is what proves the emitted
    /// factory source is valid Java — the default helper stops after processing and never
    /// compiles the generated file, so a mis-emitted string literal would slip through.
    static CompilationResult compileAndCompileGenerated(String sourceCode, String fileName, Path tempDir) throws Exception {
        return compile(List.of(new SourceEntry(fileName, sourceCode)), tempDir, false);
    }

    record SourceEntry(String fileName, String sourceCode) {}

    /// Completes an empty-bodied fixture record with its type-named static factory: generated row mappers build records
    /// through `Type.type(...)`, never `new` (#3018). `fields` are the plain components, e.g. `long id, String name`.
    static String rowRecord(String name, String fields) {
        var names = Arrays.stream(fields.split(","))
                          .map(field -> field.trim().substring(field.trim().lastIndexOf(' ') + 1))
                          .collect(Collectors.joining(", "));

        return "record %s(%s) { static %s %s(%s) { return new %s(%s); } }".formatted(name,
                                                                                    fields,
                                                                                    name,
                                                                                    Character.toLowerCase(name.charAt(0)) + name.substring(1),
                                                                                    fields,
                                                                                    name,
                                                                                    names);
    }

    /// Several sources, compiled with the processor and the generated sources all the way to bytecode.
    static CompilationResult compileAndCompileGenerated(List<SourceEntry> sources, Path tempDir) throws Exception {
        return compile(sources, tempDir, false);
    }

    static CompilationResult compileWithProcessor(List<SourceEntry> sources, Path tempDir) throws Exception {
        return compile(sources, tempDir, true);
    }

    /// Compiles with a caller-supplied processor, e.g. a subclass that forces a failure.
    static CompilationResult compileWithProcessor(QueryAnnotationProcessor processor,
                                                  String sourceCode,
                                                  String fileName,
                                                  Path tempDir) throws Exception {
        return compile(List.of(new SourceEntry(fileName, sourceCode)), tempDir, true, processor);
    }

    private static CompilationResult compile(List<SourceEntry> sources, Path tempDir, boolean procOnly) throws Exception {
        return compile(sources, tempDir, procOnly, new QueryAnnotationProcessor());
    }

    private static CompilationResult compile(List<SourceEntry> sources,
                                             Path tempDir,
                                             boolean procOnly,
                                             QueryAnnotationProcessor processor) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnosticCollector = new DiagnosticCollector<JavaFileObject>();

        var outputDir = tempDir.resolve("generated");
        Files.createDirectories(outputDir);

        var classOutputDir = tempDir.resolve("classes");
        Files.createDirectories(classOutputDir);

        var classpath = System.getProperty("java.class.path");

        var options = new ArrayList<>(List.of(
            "-d", classOutputDir.toString(),
            "-s", outputDir.toString(),
            "-classpath", classpath,
            "-Xlint:none",
            "--release", "25"
        ));
        if (procOnly) {
            options.add("-proc:only");
        }

        var sourceFiles = sources.stream()
                                 .map(s -> (JavaFileObject) new InMemoryJavaFileObject(s.fileName(), s.sourceCode()))
                                 .toList();

        try (var fileManager = compiler.getStandardFileManager(diagnosticCollector, null, null)) {
            var task = compiler.getTask(
                null, fileManager, diagnosticCollector, options, null, sourceFiles
            );
            task.setProcessors(List.of(processor));
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
