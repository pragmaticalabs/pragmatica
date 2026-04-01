package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
        void queryMethod_generatesFactoryWithRewrittenParams() throws Exception {
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
        void findById_generatesFindSql() throws Exception {
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
        void promiseLong_usesQueryOneConnectorMethod() throws Exception {
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
        void promiseUnit_usesUpdateConnectorMethod() throws Exception {
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

    @Nested
    class RecordExpansion {
        @Test
        void insertRecordExpansion_expandsValuesPattern() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface InsertRepo {

                    record CreateUserRequest(String name, String email, boolean active) {}
                    record UserRow(long id, String name, String email, boolean active) {}

                    @Query("INSERT INTO users VALUES(:request) RETURNING *")
                    Promise<UserRow> createUser(CreateUserRequest request);
                }
                """;

            var result = compileWithProcessor(source, "test/InsertRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.InsertRepoFactory");
            assertThat(generated).isNotNull();
            // Record expansion: VALUES(:request) -> (name, email, active) VALUES ($1, $2, $3)
            assertThat(generated).contains("name, email, active");
            assertThat(generated).contains("$1, $2, $3");
            assertThat(generated).doesNotContain("VALUES($1)");
            // Body should use accessor expressions: request.name(), request.email(), etc.
            assertThat(generated).contains("request.name()");
            assertThat(generated).contains("request.email()");
            assertThat(generated).contains("request.active()");
        }

        @Test
        void updateRecordExpansion_expandsSetPattern() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface UpdateRepo {

                    record UpdateRequest(String name, String email) {}

                    @Query("UPDATE users SET :request WHERE id = :id")
                    Promise<Unit> updateUser(long id, UpdateRequest request);
                }
                """;

            var result = compileWithProcessor(source, "test/UpdateRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.UpdateRepoFactory");
            assertThat(generated).isNotNull();
            // SET :request -> SET name = $N, email = $N+1
            assertThat(generated).contains("SET name =");
            assertThat(generated).contains("email =");
            assertThat(generated).doesNotContain("SET $");
        }
    }

    @Nested
    class CrudInsertWithRecord {
        @Test
        void insert_withRecordParam_expandsFields() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface UserCrudRepo {

                    record UserRow(long id, String name, String email) {}

                    Promise<UserRow> insert(UserRow user);
                }
                """;

            var result = compileWithProcessor(source, "test/UserCrudRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.UserCrudRepoFactory");
            assertThat(generated).isNotNull();
            // Should expand record fields, not use param name as column
            assertThat(generated).contains("id, name, email");
            assertThat(generated).doesNotContain("(user)");
            // Body should use accessor expressions
            assertThat(generated).contains("user.id()");
            assertThat(generated).contains("user.name()");
            assertThat(generated).contains("user.email()");
        }
    }

    @Nested
    class TypeNameSimplification {
        @Test
        void generatedCode_usesSimpleTypeNames() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TypeRepo {

                    record Row(long id, String name) {}

                    @Query("SELECT id, name FROM items WHERE id = :id")
                    Promise<Option<Row>> findById(Long id);

                    @Query("SELECT id, name FROM items WHERE name = :name")
                    Promise<Option<Row>> findByName(String name);
                }
                """;

            var result = compileWithProcessor(source, "test/TypeRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.TypeRepoFactory");
            assertThat(generated).isNotNull();
            // Should use simple type names, not FQCN
            assertThat(generated).doesNotContain("java.lang.Long");
            assertThat(generated).doesNotContain("java.lang.String");
            assertThat(generated).contains("Long id");
            assertThat(generated).contains("String name");
        }
    }

    // --- Delegate to shared helper ---

    private TestCompilationHelper.CompilationResult compileWithProcessor(String sourceCode, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(sourceCode, fileName, tempDir);
    }
}
