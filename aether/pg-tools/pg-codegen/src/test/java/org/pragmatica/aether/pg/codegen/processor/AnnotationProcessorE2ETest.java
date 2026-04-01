package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end tests for QueryAnnotationProcessor using the javac compiler API.
///
/// Compiles source files with the processor and verifies generated factory output.
class AnnotationProcessorE2ETest {

    @TempDir
    Path tempDir;

    @Nested
    class QueryWithNamedParams {
        @Test
        void queryMethod_generatesFactoryWithRewrittenParams() throws IOException {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface UserRepo {

                    record UserRow(String name, String email) {}

                    @Query("SELECT name, email FROM users WHERE email = :email AND name = :name")
                    Promise<Option<UserRow>> findByEmailAndName(String email, String name);
                }
                """;

            var result = compileWithProcessor(source, "test/UserRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.UserRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("UserRepoFactory");
            // Named params :email and :name rewritten to $1 and $2
            assertThat(generated).contains("$1");
            assertThat(generated).contains("$2");
            assertThat(generated).doesNotContain(":email");
            assertThat(generated).doesNotContain(":name");
            // Contains method implementation
            assertThat(generated).contains("findByEmailAndName");
            // Contains mapper for UserRow (qualified as UserRepoUserRow since it's an inner record)
            assertThat(generated).contains("mapUserRepoUserRow");
        }
    }

    @Nested
    class CrudMethodGeneration {
        @Test
        void findById_generatesFindSql() throws IOException {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface OrderRepo {

                    record OrderRow(long id, String status) {}

                    Promise<Option<OrderRow>> findById(Long id);
                }
                """;

            var result = compileWithProcessor(source, "test/OrderRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.OrderRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("OrderRepoFactory");
            assertThat(generated).contains("findById");
            // Should generate SELECT with WHERE id = $1
            assertThat(generated).contains("SELECT");
            assertThat(generated).contains("WHERE");
            assertThat(generated).contains("$1");
        }
    }

    @Nested
    class ScalarReturn {
        @Test
        void promiseLong_usesQueryOneConnectorMethod() throws IOException {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface StatsRepo {

                    @Query("SELECT COUNT(*) FROM events WHERE type = :eventType")
                    Promise<Long> countByType(String eventType);
                }
                """;

            var result = compileWithProcessor(source, "test/StatsRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.StatsRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("StatsRepoFactory");
            assertThat(generated).contains("countByType");
            assertThat(generated).contains("$1");
            assertThat(generated).doesNotContain(":eventType");
            // Should use queryOne for scalar Long
            assertThat(generated).contains("queryOne");
        }
    }

    @Nested
    class UnitReturn {
        @Test
        void promiseUnit_usesUpdateConnectorMethod() throws IOException {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface WriteRepo {

                    @Query("INSERT INTO events (name) VALUES (:name)")
                    Promise<Unit> insertEvent(String name);
                }
                """;

            var result = compileWithProcessor(source, "test/WriteRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.WriteRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("update");
            assertThat(generated).contains("$1");
        }
    }

    // --- Test infrastructure ---

    private record CompilationResult(boolean success, String diagnostics, Path outputDir) {
        String generatedSource(String qualifiedName) throws IOException {
            var relativePath = qualifiedName.replace('.', '/') + ".java";
            var generatedFile = outputDir.resolve(relativePath);

            if (Files.exists(generatedFile)) {
                return Files.readString(generatedFile);
            }
            return null;
        }
    }

    private CompilationResult compileWithProcessor(String sourceCode, String fileName) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnosticCollector = new DiagnosticCollector<JavaFileObject>();

        var outputDir = tempDir.resolve("generated");
        Files.createDirectories(outputDir);

        var classOutputDir = tempDir.resolve("classes");
        Files.createDirectories(classOutputDir);

        var sourceFile = new InMemoryJavaFileObject(fileName, sourceCode);

        // Build classpath from the current test classpath
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
                null,
                fileManager,
                diagnosticCollector,
                options,
                null,
                List.of(sourceFile)
            );

            task.setProcessors(List.of(new QueryAnnotationProcessor()));

            var success = task.call();
            var diagMessages = diagnosticCollector.getDiagnostics().stream()
                .map(d -> d.getKind() + ": " + d.getMessage(null))
                .reduce("", (a, b) -> a + "\n" + b);

            return new CompilationResult(success, diagMessages, outputDir);
        }
    }

    private static class InMemoryJavaFileObject extends SimpleJavaFileObject {
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
