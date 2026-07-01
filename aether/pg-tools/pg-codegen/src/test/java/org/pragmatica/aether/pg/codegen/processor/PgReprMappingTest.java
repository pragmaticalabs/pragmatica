// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.db.RowDecodeError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end tests for value-object column mapping (`PgRepr`): value objects bind via `lower`,
/// row records decode via `lift` guarded by a typed `RowDecode` cause, missing/ambiguous/mismatched
/// descriptors are compile errors, and raw types keep working unchanged.
class PgReprMappingTest {

    @TempDir
    Path tempDir;

    @Nested
    class Bind {
        @Test
        void valueObjectParam_keepsSignatureType_andLowersInBody() throws Exception {
            var result = compile(EVENT_REPO, "test/EventRepo.java");

            assertThat(result.success()).as(result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.EventRepoFactory");
            assertThat(generated).isNotNull();
            // Signature keeps the value-object type; the interface method is overridden verbatim.
            assertThat(generated).contains("EventId id");
            // The bind lowers the value object to its raw column value.
            assertThat(generated).contains("EventId.pgRepr().lower().apply(id)");
        }

        @Test
        void valueObjectRecordField_lowersEachFieldInInsert() throws Exception {
            var source = """
                package test;

                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.aether.slice.repr.PgRepr;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Unit;

                record AccountId(UUID value) {
                    static Result<AccountId> accountId(UUID raw) { return Result.success(new AccountId(raw)); }
                    static PgRepr<AccountId, UUID> pgRepr() { return PgRepr.of(AccountId::value, AccountId::accountId); }
                }

                record LedgerEntry(AccountId accountId, java.math.BigDecimal amount) {}

                @PgSql
                public interface LedgerRepo {
                    @Query("INSERT INTO ledger VALUES(:entry)")
                    Promise<Unit> insert(LedgerEntry entry);
                }
                """;

            var result = compile(source, "test/LedgerRepo.java");

            assertThat(result.success()).as(result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.LedgerRepoFactory");
            assertThat(generated).isNotNull();
            // The value-object field is lowered; the scalar field is bound directly.
            assertThat(generated).contains("AccountId.pgRepr().lower().apply(entry.accountId())");
            assertThat(generated).contains("entry.amount()");
        }
    }

    @Nested
    class Decode {
        @Test
        void valueObjectField_decodesViaLift_guardedByTypedCause() throws Exception {
            var result = compile(EVENT_REPO, "test/EventRepo.java");

            assertThat(result.success()).as(result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.EventRepoFactory");
            assertThat(generated).isNotNull();
            // The value-object column is read as UUID, re-parsed through lift, and guarded.
            assertThat(generated).contains("RowDecodeError.guard(\"id\"");
            assertThat(generated).contains("EventId.pgRepr().lift()");
            assertThat(generated).contains("import org.pragmatica.aether.resource.db.RowDecodeError;");
            // The scalar column stays a plain read.
            assertThat(generated).contains("row.getObject(\"amount\", java.math.BigDecimal.class)");
        }
    }

    @Nested
    class CompileErrors {
        @Test
        void missingPgReprForParam_isCompileError() throws Exception {
            var source = """
                package test;

                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Promise;

                record PlainId(UUID value) {}

                @PgSql
                public interface PlainParamRepo {
                    @Query("SELECT count(*) FROM events_uuid WHERE id = :id")
                    Promise<Long> countById(PlainId id);
                }
                """;

            var result = compile(source, "test/PlainParamRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("PgRepr");
        }

        @Test
        void missingPgReprForReturnField_isCompileError() throws Exception {
            var source = """
                package test;

                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record PlainId(UUID value) {}
                record BadRow(PlainId id, java.math.BigDecimal amount) {}

                @PgSql
                public interface PlainFieldRepo {
                    @Query("SELECT id, amount FROM events_uuid WHERE id = :id")
                    Promise<Option<BadRow>> findEvent(long id);
                }
                """;

            var result = compile(source, "test/PlainFieldRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("PgRepr");
            assertThat(result.diagnostics()).contains("id");
        }

        @Test
        void reprPrimitiveTypeMismatchesColumn_isCompileError() throws Exception {
            var source = """
                package test;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.aether.slice.repr.PgRepr;
                import org.pragmatica.lang.Promise;
                import org.pragmatica.lang.Result;

                record TextId(String value) {
                    static Result<TextId> textId(String raw) { return Result.success(new TextId(raw)); }
                    static PgRepr<TextId, String> pgRepr() { return PgRepr.of(TextId::value, TextId::textId); }
                }

                @PgSql
                public interface MismatchRepo {
                    // events_uuid.id is UUID, but the PgRepr's P is String.
                    @Query("SELECT count(*) FROM events_uuid WHERE id = :id")
                    Promise<Long> countById(TextId id);
                }
                """;

            var result = compile(source, "test/MismatchRepo.java");

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("PG-VALIDATE");
            assertThat(result.diagnostics()).contains("Type mismatch");
        }
    }

    @Nested
    class GeneratedCompiles {
        @Test
        void valueObjectBindAndDecode_compileToValidBytecode() throws Exception {
            var result = TestCompilationHelper.compileAndCompileGenerated(EVENT_REPO, "test/EventRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();
        }
    }

    @Nested
    class RuntimeDecode {
        @Test
        void parsingColumnValue_decodesValueObject() throws Exception {
            var mapper = compileAndLoadMapper(ORDER_STATUS_REPO, "test/OrderStatusRepo.java", "test.OrderStatusRepoFactory");
            var decoded = (Result<?>) mapper.invoke(null, rowAccessorReturning("OPEN"));

            var succeeded = new AtomicReference<Boolean>(false);
            decoded.onSuccess(row -> succeeded.set(true));
            assertThat(succeeded.get()).as("decode of a valid column value should succeed").isTrue();
        }

        @Test
        void nonParsingColumnValue_yieldsTypedRowDecodeFailure_notException() throws Exception {
            var mapper = compileAndLoadMapper(ORDER_STATUS_REPO, "test/OrderStatusRepo.java", "test.OrderStatusRepoFactory");
            // Invocation returns a failed Result rather than throwing — no exception escapes decode.
            var decoded = (Result<?>) mapper.invoke(null, rowAccessorReturning("BOGUS"));

            var captured = new AtomicReference<Cause>();
            decoded.onFailure(captured::set);
            assertThat(captured.get()).isInstanceOf(RowDecodeError.RowDecode.class);
            assertThat(((RowDecodeError.RowDecode) captured.get()).column()).isEqualTo("status");
        }
    }

    @Nested
    class BackwardCompatibility {
        @Test
        void rawTypesKeepWorking_withoutPgReprMachinery() throws Exception {
            var source = """
                package test;

                import java.util.UUID;

                import org.pragmatica.aether.pg.codegen.annotation.Query;
                import org.pragmatica.aether.resource.db.PgSql;
                import org.pragmatica.lang.Option;
                import org.pragmatica.lang.Promise;

                record RawRow(UUID id, java.math.BigDecimal amount) {}

                @PgSql
                public interface RawRepo {
                    @Query("SELECT id, amount FROM events_uuid WHERE id = :id")
                    Promise<Option<RawRow>> findEvent(UUID id);
                }
                """;

            var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/RawRepo.java", tempDir);

            assertThat(result.success()).as(result.diagnostics()).isTrue();

            var generated = result.generatedSource("test.RawRepoFactory");
            assertThat(generated).isNotNull();
            // No value-object machinery leaks into raw-type factories.
            assertThat(generated).doesNotContain("RowDecodeError");
            assertThat(generated).doesNotContain("pgRepr()");
            assertThat(generated).contains("row.getObject(\"id\", java.util.UUID.class)");
        }
    }

    // --- shared sources ---

    private static final String EVENT_REPO = """
        package test;

        import java.util.UUID;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.aether.slice.repr.PgRepr;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;

        record EventId(UUID value) {
            static Result<EventId> eventId(UUID raw) { return Result.success(new EventId(raw)); }
            static PgRepr<EventId, UUID> pgRepr() { return PgRepr.of(EventId::value, EventId::eventId); }
        }

        record EventRow(EventId id, java.math.BigDecimal amount) {}

        @PgSql
        public interface EventRepo {
            @Query("SELECT id, amount FROM events_uuid WHERE id = :id")
            Promise<Option<EventRow>> findEvent(EventId id);
        }
        """;

    private static final String ORDER_STATUS_REPO = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.aether.slice.repr.PgRepr;
        import org.pragmatica.lang.Cause;
        import org.pragmatica.lang.utils.Causes;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;

        record OrderStatus(String value) {
            static final Cause BAD_STATUS = Causes.cause("unknown order status");
            static Result<OrderStatus> orderStatus(String raw) {
                return ("OPEN".equals(raw) || "CLOSED".equals(raw))
                       ? Result.success(new OrderStatus(raw))
                       : BAD_STATUS.result();
            }
            static PgRepr<OrderStatus, String> pgRepr() { return PgRepr.of(OrderStatus::value, OrderStatus::orderStatus); }
        }

        record StatusRow(OrderStatus status) {}

        @PgSql
        public interface OrderStatusRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<StatusRow>> statusOf(long id);
        }
        """;

    // --- helpers ---

    private TestCompilationHelper.CompilationResult compile(String source, String fileName) throws Exception {
        return TestCompilationHelper.compileWithProcessor(source, fileName, tempDir);
    }

    private java.lang.reflect.Method compileAndLoadMapper(String source, String fileName, String factoryName) throws Exception {
        var result = TestCompilationHelper.compileAndCompileGenerated(source, fileName, tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        var classesDir = tempDir.resolve("classes");
        var loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, getClass().getClassLoader());
        var factoryClass = loader.loadClass(factoryName);
        var mapper = Arrays.stream(factoryClass.getDeclaredMethods())
                           .filter(method -> method.getName().startsWith("map") && method.getParameterCount() == 1)
                           .findFirst()
                           .orElseThrow();

        mapper.setAccessible(true);

        return mapper;
    }

    private static RowMapper.RowAccessor rowAccessorReturning(String stringValue) {
        return new RowMapper.RowAccessor() {
            @Override public Result<String> getString(String column) { return Result.success(stringValue); }
            @Override public Result<Integer> getInt(String column) { return Result.success(0); }
            @Override public Result<Long> getLong(String column) { return Result.success(0L); }
            @Override public Result<Double> getDouble(String column) { return Result.success(0.0); }
            @Override public Result<Boolean> getBoolean(String column) { return Result.success(false); }
            @Override public Result<byte[]> getBytes(String column) { return Result.success(new byte[0]); }
            @Override public <V> Result<V> getObject(String column, Class<V> type) { return Result.success(type.cast(null)); }
        };
    }
}
