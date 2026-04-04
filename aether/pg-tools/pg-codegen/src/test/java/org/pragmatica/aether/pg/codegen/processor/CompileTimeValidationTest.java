package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.aether.pg.schema.model.Schema;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests compile-time validation logic using real schema models built from migration SQL.
class CompileTimeValidationTest {

    private Schema schema;

    @BeforeEach
    void setUp() {
        var ddl = """
            CREATE TABLE users (
                id BIGINT PRIMARY KEY,
                name TEXT NOT NULL,
                email TEXT NOT NULL,
                active BOOLEAN NOT NULL DEFAULT false,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            CREATE TABLE orders (
                id BIGINT PRIMARY KEY,
                user_id BIGINT NOT NULL,
                status TEXT NOT NULL,
                total NUMERIC NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            """;
        schema = SchemaLoader.loadFromScripts(List.of(ddl)).unwrap();
    }

    @Nested
    class ColumnExistence {
        @Test
        void existingColumn_isFoundInTable() {
            var table = schema.table("users").unwrap();
            assertThat(table.column("email").isPresent()).isTrue();
        }

        @Test
        void nonExistentColumn_isNotFound() {
            var table = schema.table("users").unwrap();
            assertThat(table.column("nonexistent").isPresent()).isFalse();
        }

        @Test
        void crudConditionColumns_validatedAgainstSchema() {
            var parsed = MethodNameParser.parse("findByEmail");
            assertThat(parsed).isNotNull();
            assertThat(parsed.conditions()).hasSize(1);

            var table = schema.table("users").unwrap();
            var columnName = parsed.conditions().getFirst().columnName();
            assertThat(table.column(columnName).isPresent()).isTrue();
        }

        @Test
        void crudConditionColumns_nonExistent_detected() {
            var parsed = MethodNameParser.parse("findByNonexistent");
            assertThat(parsed).isNotNull();

            var table = schema.table("users").unwrap();
            var columnName = parsed.conditions().getFirst().columnName();
            assertThat(table.column(columnName).isPresent()).isFalse();
        }

        @Test
        void orderByColumn_validatedAgainstSchema() {
            var parsed = MethodNameParser.parse("findByActiveOrderByCreatedAtDesc");
            assertThat(parsed).isNotNull();
            assertThat(parsed.orderBy()).hasSize(1);

            var table = schema.table("users").unwrap();
            assertThat(table.column(parsed.orderBy().getFirst().columnName()).isPresent()).isTrue();
        }

        @Test
        void orderByColumn_nonExistent_detected() {
            var parsed = MethodNameParser.parse("findByActiveOrderByFooDesc");
            assertThat(parsed).isNotNull();

            var table = schema.table("users").unwrap();
            assertThat(table.column(parsed.orderBy().getFirst().columnName()).isPresent()).isFalse();
        }
    }

    @Nested
    class NotNullCoverage {
        @Test
        void allRequiredColumns_present_noMissing() {
            var table = schema.table("users").unwrap();
            // id, name, email are NOT NULL without default; active has DEFAULT, created_at has DEFAULT
            var inputFields = List.of("id", "name", "email");
            var missing = MethodAnalyzer.findMissingRequiredColumns(table, inputFields);
            assertThat(missing).isEmpty();
        }

        @Test
        void missingRequiredColumn_detected() {
            var table = schema.table("users").unwrap();
            // Missing 'email' which is NOT NULL without DEFAULT
            var inputFields = List.of("id", "name");
            var missing = MethodAnalyzer.findMissingRequiredColumns(table, inputFields);
            assertThat(missing).containsExactly("email");
        }

        @Test
        void columnsWithDefault_notRequired() {
            var table = schema.table("users").unwrap();
            // 'active' and 'created_at' have DEFAULT, so omitting them is fine
            var inputFields = List.of("id", "name", "email");
            var missing = MethodAnalyzer.findMissingRequiredColumns(table, inputFields);
            assertThat(missing).isEmpty();
        }

        @Test
        void insertWithRecordFieldNames_convertedToSnakeCase() {
            var table = schema.table("orders").unwrap();
            // Simulate record with camelCase fields: userId -> user_id
            var inputFields = List.of("userId", "status", "total");
            var inputColumns = inputFields.stream().map(NamingConvention::toSnakeCase).toList();
            // 'id' is NOT NULL PK without DEFAULT
            var missing = MethodAnalyzer.findMissingRequiredColumns(table, inputColumns);
            assertThat(missing).containsExactly("id");
        }
    }

    @Nested
    class TypeMapping {
        @Test
        void tableResolution_fromTypeName() {
            var resolved = MethodAnalyzer.resolveTableFromTypeName("UserRow", schema);
            assertThat(resolved.isPresent()).isTrue();
            assertThat(resolved.unwrap()).isEqualTo("users");
        }

        @Test
        void tableResolution_fromTypeNameWithoutSuffix() {
            var resolved = MethodAnalyzer.resolveTableFromTypeName("OrderRow", schema);
            assertThat(resolved.isPresent()).isTrue();
            assertThat(resolved.unwrap()).isEqualTo("orders");
        }

        @Test
        void schemaHasExpectedTables() {
            assertThat(schema.table("users").isPresent()).isTrue();
            assertThat(schema.table("orders").isPresent()).isTrue();
        }

        @Test
        void usersTable_hasExpectedColumns() {
            var table = schema.table("users").unwrap();
            assertThat(table.column("id").isPresent()).isTrue();
            assertThat(table.column("name").isPresent()).isTrue();
            assertThat(table.column("email").isPresent()).isTrue();
            assertThat(table.column("active").isPresent()).isTrue();
            assertThat(table.column("created_at").isPresent()).isTrue();
        }

        @Test
        void columnTypes_matchExpected() {
            var table = schema.table("users").unwrap();
            assertThat(table.column("id").unwrap().type().name()).isEqualTo("bigint");
            assertThat(table.column("name").unwrap().type().name()).isEqualTo("text");
            assertThat(table.column("email").unwrap().type().name()).isEqualTo("text");
            assertThat(table.column("active").unwrap().type().name()).isEqualTo("boolean");
        }
    }

    @Nested
    class E2EWithSchema {
        @Test
        void crudColumnExistence_errorForNonexistent() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface ValidationRepo {

                    record UserRow(long id, String name, String email) {}

                    Promise<Option<UserRow>> findByNonexistent(String value);
                }
                """;

            var result = compileWithProcessor(source, "test/ValidationRepo.java");

            // Should fail because 'nonexistent' column does not exist in 'users' table
            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("nonexistent");
        }

        @Test
        void crudColumnExistence_validColumnsSucceed() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface ValidRepo {

                    record UserRow(long id, String name, String email) {}

                    Promise<Option<UserRow>> findByEmail(String email);
                }
                """;

            var result = compileWithProcessor(source, "test/ValidRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();
        }

        @Test
        void insertCoverage_errorForMissingNotNull() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface InsertValidationRepo {

                    record UserRow(long id, String name, String email) {}

                    // Missing 'name' and 'email' which are NOT NULL without DEFAULT
                    record IncompleteUser(long id) {}

                    Promise<UserRow> insert(IncompleteUser user);
                }
                """;

            var result = compileWithProcessor(source, "test/InsertValidationRepo.java");

            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("NOT NULL");
        }

        @Test
        void typeMismatch_errorForWrongParamType() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TypeMismatchRepo {

                    record UserRow(long id, String name, String email) {}

                    // id is bigint in schema but String param here
                    Promise<Option<UserRow>> findById(String id);
                }
                """;

            var result = compileWithProcessor(source, "test/TypeMismatchRepo.java");

            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("Type mismatch");
        }

        @Test
        void queryReturnFieldValidation_warnsForMismatch() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface ReturnFieldRepo {

                    record UserSummary(long id, String name, int orderCount) {}

                    @Query("SELECT id, name FROM users WHERE id = :id")
                    Promise<Option<UserSummary>> findSummary(long id);
                }
                """;

            var result = compileWithProcessor(source, "test/ReturnFieldRepo.java");

            // Should warn that orderCount has no matching SELECT column
            assertThat(result.diagnostics()).contains("orderCount");
        }

        @Test
        void queryParamTypeValidation_errorForMismatch() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import java.util.List;

                @PgSql
                public interface QueryTypeRepo {

                    record UserRow(long id, String name, String email) {}

                    @Query("SELECT id, name, email FROM users WHERE id = :id")
                    Promise<List<UserRow>> findUsersById(String id);
                }
                """;

            var result = compileWithProcessor(source, "test/QueryTypeRepo.java");

            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("Type mismatch");
        }

        private TestCompilationHelper.CompilationResult compileWithProcessor(String sourceCode, String fileName) throws Exception {
            var tempDir = Files.createTempDirectory("pg-validation-test");
            return TestCompilationHelper.compileWithProcessor(sourceCode, fileName, tempDir);
        }
    }
}
