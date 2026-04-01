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
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnosticCollector = new DiagnosticCollector<JavaFileObject>();

        var outputDir = tempDir.resolve("generated");
        Files.createDirectories(outputDir);

        var classOutputDir = tempDir.resolve("classes");
        Files.createDirectories(classOutputDir);

        var sourceFile = new InMemoryJavaFileObject(fileName, sourceCode);
        var classpath = System.getProperty("java.class.path");

        var options = List.of(
            "-d", classOutputDir.toString(),
            "-s", outputDir.toString(),
            "-classpath", classpath,
            "-proc:only",
            "-Xlint:none",
            "--release", "25"
        );

        try (var fileManager = compiler.getStandardFileManager(diagnosticCollector, null, null)) {
            var task = compiler.getTask(
                null, fileManager, diagnosticCollector, options, null, List.of(sourceFile)
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
