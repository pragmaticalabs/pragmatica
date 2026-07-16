// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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
        void insertRecordExpansion_doesNotEmitUnusedParamWarnings() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface NoWarnRepo {

                    record CreateOrderRequest(long userId, java.math.BigDecimal total, String status) {}
                    record OrderRow(long id, long userId, java.math.BigDecimal total, String status) {}

                    @Query("INSERT INTO orders VALUES(:request) RETURNING *")
                    Promise<OrderRow> createOrder(CreateOrderRequest request);
                }
                """;

            var result = compileWithProcessor(source, "test/NoWarnRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();
            // After record expansion :request is gone; virtual fields (userId/total/status) must NOT
            // be reported as unused method parameters.
            var diag = result.diagnostics();
            assertThat(diag).doesNotContain("parameter 'userId' is not used");
            assertThat(diag).doesNotContain("parameter 'total' is not used");
            assertThat(diag).doesNotContain("parameter 'status' is not used");
            // And the original `request` parameter is used via the :request token pre-expansion.
            assertThat(diag).doesNotContain("parameter 'request' is not used");
        }

        @Test
        void queryReferencingUnknownNamedParam_stillWarns() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface MismatchRepo {

                    @Query("INSERT INTO users (name) VALUES (:bogus)")
                    Promise<Unit> insertUser(String name);
                }
                """;

            var result = compileWithProcessor(source, "test/MismatchRepo.java");

            // Compilation succeeds (warning, not error), but a diagnostic must flag :bogus and
            // flag the unused `name` method parameter.
            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();
            var diag = result.diagnostics();
            assertThat(diag).contains(":bogus");
            assertThat(diag).contains("has no matching method parameter");
            assertThat(diag).contains("parameter 'name' is not used");
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

    @Nested
    class ArrayColumnMapping {
        @Test
        void queryMethod_withStringArrayField_generatesGetObjectAccessor() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TagRepo {

                    record Post(long id, String[] tags) {}

                    @Query("SELECT id, tags FROM posts WHERE id = :id")
                    Promise<Option<Post>> findPost(long id);
                }
                """;

            var result = compileWithProcessor(source, "test/TagRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.TagRepoFactory");
            assertThat(generated).isNotNull();
            // Must NOT use getString for an array column (the bug)
            assertThat(generated).doesNotContain("row.getString(\"tags\")");
            // Must use getObject with a class literal
            assertThat(generated).contains("getObject(\"tags\"");
            assertThat(generated).contains("String[].class");
        }

        @Test
        void queryMethod_withIntegerArrayField_usesBoxedElementClass() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface ScoreRepo {

                    record Rank(long id, Integer[] scores) {}

                    @Query("SELECT id, scores FROM ranks WHERE id = :id")
                    Promise<Option<Rank>> findRank(long id);
                }
                """;

            var result = compileWithProcessor(source, "test/ScoreRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.ScoreRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).doesNotContain("row.getString(\"scores\")");
            assertThat(generated).contains("getObject(\"scores\"");
            assertThat(generated).contains("Integer[].class");
        }
    }

    @Nested
    class ParameterTypeImports {
        @Test
        void queryMethod_withNonJavaLangParamTypes_generatesImports() throws Exception {
            var source = """
                package test;

                import java.math.BigDecimal;
                import java.time.Instant;
                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface RichParamRepo {

                    record EventRow(UUID id, BigDecimal amount, Instant occurredAt) {}

                    @Query("SELECT id, amount, occurred_at FROM events_uuid WHERE id = :id AND amount > :threshold AND occurred_at >= :since")
                    Promise<Option<EventRow>> findEvent(UUID id, BigDecimal threshold, Instant since);
                }
                """;

            var result = compileWithProcessor(source, "test/RichParamRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.RichParamRepoFactory");
            assertThat(generated).isNotNull();
            // Imports must be present for non-java.lang types used in signatures
            assertThat(generated).contains("import java.math.BigDecimal;");
            assertThat(generated).contains("import java.time.Instant;");
            assertThat(generated).contains("import java.util.UUID;");
            // Generated method signature uses simple names
            assertThat(generated).contains("UUID id");
            assertThat(generated).contains("BigDecimal threshold");
            assertThat(generated).contains("Instant since");
        }

        @Test
        void queryMethod_withRecordParam_generatesImportsForFieldTypes() throws Exception {
            var source = """
                package test;

                import java.math.BigDecimal;
                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Unit;

                @PgSql
                public interface RecordParamRepo {

                    record NewLedgerEntry(UUID accountId, BigDecimal amount) {}

                    @Query("INSERT INTO ledger VALUES(:entry)")
                    Promise<Unit> insert(NewLedgerEntry entry);
                }
                """;

            var result = compileWithProcessor(source, "test/RecordParamRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.RecordParamRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.math.BigDecimal;");
            assertThat(generated).contains("import java.util.UUID;");
        }
    }

    @Nested
    class MetaAnnotationDiscovery {
        @Test
        void customQualifier_triggersFactoryGeneration() throws Exception {
            var customQualifier = """
                package test;

                import org.pragmatica.aether.resource.db.PgSqlConnector;
                import org.pragmatica.aether.slice.annotation.ResourceQualifier;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @ResourceQualifier(type = PgSqlConnector.class, config = "database")
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.PARAMETER, ElementType.TYPE})
                public @interface AnalyticsPgSql {}
                """;

            var repo = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @AnalyticsPgSql
                public interface AnalyticsRepo {

                    record UserRow(long id, String name, String email) {}

                    @Query("SELECT id, name, email FROM users WHERE id = :id")
                    Promise<Option<UserRow>> findById(long id);
                }
                """;

            var result = TestCompilationHelper.compileWithProcessor(
                java.util.List.of(
                    new TestCompilationHelper.SourceEntry("test/AnalyticsPgSql.java", customQualifier),
                    new TestCompilationHelper.SourceEntry("test/AnalyticsRepo.java", repo)
                ),
                tempDir
            );

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            // Despite the interface having no direct @PgSql, the processor picks it up via the
            // meta-annotation and emits the factory.
            var generated = result.generatedSource("test.AnalyticsRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("AnalyticsRepoFactory");
            assertThat(generated).contains("findById");
        }
    }

    @Nested
    class NonPrimitiveScalarReturn {
        @Test
        void promiseBigDecimal_generatesGetObjectMapperAndImport() throws Exception {
            var source = """
                package test;

                import java.math.BigDecimal;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TotalsRepo {

                    @Query("SELECT sum(total) AS total_amount FROM orders WHERE status = :status")
                    Promise<BigDecimal> totalByStatus(String status);
                }
                """;

            var result = compileWithProcessor(source, "test/TotalsRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.TotalsRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.math.BigDecimal;");
            assertThat(generated).contains("Promise<BigDecimal> totalByStatus");
            // Must use queryOne with an inline scalar mapper — no record mapper, no Result.all()
            assertThat(generated).contains("queryOne");
            assertThat(generated).contains("row.getObject(\"total_amount\", BigDecimal.class)");
            assertThat(generated).doesNotContain("BigDecimal::new");
            assertThat(generated).doesNotContain("Result.all()");
        }

        @Test
        void promiseInstant_generatesGetObjectMapperAndImport() throws Exception {
            var source = """
                package test;

                import java.time.Instant;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface TimeRepo {

                    @Query("SELECT max(created_at) AS latest FROM orders")
                    Promise<Instant> latestCreatedAt();
                }
                """;

            var result = compileWithProcessor(source, "test/TimeRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.TimeRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.time.Instant;");
            assertThat(generated).contains("Promise<Instant> latestCreatedAt");
            assertThat(generated).contains("row.getObject(\"latest\", Instant.class)");
        }

        @Test
        void promiseUUID_generatesGetObjectMapperAndImport() throws Exception {
            var source = """
                package test;

                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface UuidRepo {

                    @Query("SELECT correlation_id FROM orders WHERE id = :id")
                    Promise<UUID> correlationFor(Long id);
                }
                """;

            var result = compileWithProcessor(source, "test/UuidRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.UuidRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.util.UUID;");
            assertThat(generated).contains("Promise<UUID> correlationFor");
            assertThat(generated).contains("row.getObject(\"correlation_id\", UUID.class)");
        }

        @Test
        void promiseLocalDate_generatesGetObjectMapperAndImport() throws Exception {
            var source = """
                package test;

                import java.time.LocalDate;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface DateRepo {

                    @Query("SELECT event_date FROM events WHERE id = :id")
                    Promise<LocalDate> eventDate(Long id);
                }
                """;

            var result = compileWithProcessor(source, "test/DateRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.DateRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.time.LocalDate;");
            assertThat(generated).contains("Promise<LocalDate> eventDate");
            assertThat(generated).contains("row.getObject(\"event_date\", LocalDate.class)");
        }

        @Test
        void promiseByteArray_generatesGetBytesMapper() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface BlobRepo {

                    @Query("SELECT payload FROM blobs WHERE id = :id")
                    Promise<byte[]> blob(Long id);
                }
                """;

            var result = compileWithProcessor(source, "test/BlobRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.BlobRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("Promise<byte[]> blob");
            assertThat(generated).contains("row.getBytes(\"payload\")");
        }

        @Test
        void promiseOptionBigDecimal_generatesGetObjectMapperAndImport() throws Exception {
            var source = """
                package test;

                import java.math.BigDecimal;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface NullableTotalsRepo {

                    @Query("SELECT sum(total) AS total_amount FROM orders WHERE status = :status")
                    Promise<Option<BigDecimal>> totalByStatus(String status);
                }
                """;

            var result = compileWithProcessor(source, "test/NullableTotalsRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.NullableTotalsRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.math.BigDecimal;");
            assertThat(generated).contains("Promise<Option<BigDecimal>> totalByStatus");
            assertThat(generated).contains("queryOptional");
            assertThat(generated).contains("row.getObject(\"total_amount\", BigDecimal.class)");
        }

        @Test
        void promiseListUUID_generatesGetObjectMapperAndImport() throws Exception {
            var source = """
                package test;

                import java.util.List;
                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface UuidListRepo {

                    @Query("SELECT correlation_id FROM orders WHERE status = :status")
                    Promise<List<UUID>> correlationIds(String status);
                }
                """;

            var result = compileWithProcessor(source, "test/UuidListRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.UuidListRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("import java.util.UUID;");
            assertThat(generated).contains("Promise<List<UUID>> correlationIds");
            assertThat(generated).contains("queryList");
            assertThat(generated).contains("row.getObject(\"correlation_id\", UUID.class)");
        }

        @Test
        void promiseLong_stillUsesGetLong() throws Exception {
            // Regression: make sure the existing primitive scalar path isn't broken.
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface SumRepo {

                    @Query("SELECT sum(amount) AS total FROM items")
                    Promise<Long> totalAmount();
                }
                """;

            var result = compileWithProcessor(source, "test/SumRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.SumRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("row.getLong(\"total\")");
        }

        @Test
        void promiseBoolean_stillUsesGetBoolean() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface ExistsRepo {

                    @Query("SELECT EXISTS(SELECT 1 FROM orders WHERE id = :id)")
                    Promise<Boolean> existsById(Long id);
                }
                """;

            var result = compileWithProcessor(source, "test/ExistsRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.ExistsRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("row.getBoolean(\"exists\")");
        }

        @Test
        void promiseString_scalarReturnUsesGetString() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface StringRepo {

                    @Query("SELECT name FROM users WHERE id = :id")
                    Promise<String> nameById(Long id);
                }
                """;

            var result = compileWithProcessor(source, "test/StringRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.StringRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("Promise<String> nameById");
            assertThat(generated).contains("row.getString(\"name\")");
        }

        @Test
        void promiseUnknownClass_emitsCompileError() throws Exception {
            // A user class that isn't a record and isn't in JavaTypeAccessor.SCALARS.
            var other = """
                package test;

                public class Foo {}
                """;
            var repo = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface BadRepo {

                    @Query("SELECT f FROM foos")
                    Promise<Foo> fetch();
                }
                """;

            var result = TestCompilationHelper.compileWithProcessor(
                java.util.List.of(
                    new TestCompilationHelper.SourceEntry("test/Foo.java", other),
                    new TestCompilationHelper.SourceEntry("test/BadRepo.java", repo)
                ),
                tempDir
            );

            assertThat(result.diagnostics()).contains("unsupported scalar type");
        }
    }

    @Nested
    class AutoDiscovery {
        /// A migration with a name that no hardcoded dictionary ever knew (`V900__eventmanagement.sql`)
        /// and which the committed `migrations.list` does not mention must still be discovered by
        /// globbing — and its table must resolve at compile time. The absence-from-manifest must
        /// surface as a WARNING, not a silent skip.
        @Test
        void arbitrarilyNamedMigration_discoveredAndResolves_whenAbsentFromManifest() throws Exception {
            writeSchemaFile("schema", "V900__eventmanagement.sql",
                "CREATE TABLE event_management (id BIGINT PRIMARY KEY, title TEXT NOT NULL)");

            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @PgSql
                public interface EventRepo {

                    record EventRow(long id, String title) {}

                    @Query("SELECT id, title FROM event_management WHERE id = :id")
                    Promise<Option<EventRow>> findById(long id);
                }
                """;

            var result = compileWithProcessor(source, "test/EventRepo.java");

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.EventRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("findById");
            // V900 is on disk but absent from the committed migrations.list -> must WARN, not skip.
            assertThat(result.diagnostics()).contains("migrations.list");
        }

        /// The issue's acceptance: with NO `migrations.list` anywhere on the path, all migrations are
        /// still discovered via the glob. Uses a named datasource so the schema directory is isolated
        /// from the shared default test schema (which does carry a manifest).
        @Test
        void migrationWithoutAnyManifest_discoveredViaGlob() throws Exception {
            writeSchemaFile("schema/widgets", "V003__widgets.sql",
                "CREATE TABLE widgets (id BIGINT PRIMARY KEY, label TEXT NOT NULL)");

            var qualifier = """
                package test;

                import org.pragmatica.aether.resource.db.PgSqlConnector;
                import org.pragmatica.aether.slice.annotation.ResourceQualifier;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @ResourceQualifier(type = PgSqlConnector.class, config = "database.widgets")
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.PARAMETER, ElementType.TYPE})
                public @interface WidgetsPgSql {}
                """;

            var repo = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                @WidgetsPgSql
                public interface WidgetRepo {

                    record WidgetRow(long id, String label) {}

                    @Query("SELECT id, label FROM widgets WHERE id = :id")
                    Promise<Option<WidgetRow>> findById(long id);
                }
                """;

            var result = TestCompilationHelper.compileWithProcessor(
                java.util.List.of(
                    new TestCompilationHelper.SourceEntry("test/WidgetsPgSql.java", qualifier),
                    new TestCompilationHelper.SourceEntry("test/WidgetRepo.java", repo)
                ),
                tempDir
            );

            assertThat(result.success()).as("Compilation should succeed: " + result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.WidgetRepoFactory");
            assertThat(generated).isNotNull();
            assertThat(generated).contains("findById");
        }

        /// Writes a migration into the compiler output directory under the given schema-relative
        /// directory, so the processor discovers it via the CLASS_OUTPUT anchor exactly as it would
        /// find Maven-copied `src/main/resources/schema` resources in a real build.
        private void writeSchemaFile(String relativeDir, String fileName, String sql) throws Exception {
            var dir = tempDir.resolve("classes").resolve(relativeDir);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(fileName), sql);
        }
    }

    // --- Delegate to shared helper ---

    private TestCompilationHelper.CompilationResult compileWithProcessor(String sourceCode, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(sourceCode, fileName, tempDir);
    }
}
