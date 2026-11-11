// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.RowDecodeError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Generated row mappers build records through the record's type-named static factory, never `new` (#3018),
/// and a single-column `@Query` returning a value object decodes the column through `valueMapping().lift()` in
/// single, optional and list forms (#3015). Every case compiles the generated source to bytecode and runs the
/// generated mapper against a fake row.
class RecordFactoryMappingTest {
    private static final String HEADER = """
        package test;

        import org.pragmatica.aether.pg.codegen.annotation.Query;
        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.aether.slice.mapping.ValueMapping;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import org.pragmatica.lang.Result;
        import org.pragmatica.lang.utils.Causes;
        import java.util.List;

        """;

    private static final String PLAIN_REPO = HEADER + """
        record StatusRow(String status) {
            static StatusRow statusRow(String status) { return new StatusRow(status); }
        }

        @PgSql
        public interface PlainRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<StatusRow>> statusOf(long id);

            @Query("SELECT id, status FROM orders WHERE id = :id")
            Promise<Option<IdStatus>> idStatusOf(long id);

            record IdStatus(long id, String status) {
                static IdStatus idStatus(long id, String status) { return new IdStatus(id, status); }
            }
        }
        """;

    private static final String RESULT_REPO = HEADER + """
        record StatusRow(String status) {
            static Result<StatusRow> statusRow(String status) {
                return "BAD".equals(status)
                       ? Causes.cause("bad status").result()
                       : Result.success(new StatusRow(status));
            }
        }

        @PgSql
        public interface ResultRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<StatusRow>> statusOf(long id);

            @Query("SELECT id, status FROM orders WHERE id = :id")
            Promise<Option<IdStatus>> idStatusOf(long id);

            record IdStatus(long id, String status) {
                static Result<IdStatus> idStatus(long id, String status) {
                    return "BAD".equals(status)
                           ? Causes.cause("bad pair").result()
                           : Result.success(new IdStatus(id, status));
                }
            }
        }
        """;

    private static final String NO_FACTORY_REPO = HEADER + """
        record StatusRow(String status) {}

        @PgSql
        public interface NoFactoryRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<StatusRow>> statusOf(long id);
        }
        """;

    private static final String WRONG_RETURN_REPO = HEADER + """
        record StatusRow(String status) {
            static Option<StatusRow> statusRow(String status) { return Option.some(new StatusRow(status)); }
        }

        @PgSql
        public interface WrongReturnRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<StatusRow>> statusOf(long id);
        }
        """;

    private static final String FOREIGN_RESULT_REPO = HEADER + """
        record StatusRow(String status) {
            static Result<String> statusRow(String status) { return Result.success(status); }
        }

        @PgSql
        public interface ForeignResultRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<StatusRow>> statusOf(long id);
        }
        """;

    private static final String VALUE_OBJECT_REPO = HEADER + """
        record OrderStatus(String value) {
            static Result<OrderStatus> orderStatus(String raw) {
                return "OPEN".equals(raw) || "CLOSED".equals(raw)
                       ? Result.success(new OrderStatus(raw))
                       : Causes.cause("not a status: " + raw).result();
            }

            static ValueMapping<OrderStatus, String> valueMapping() {
                return ValueMapping.of(OrderStatus::value, OrderStatus::orderStatus);
            }
        }

        @PgSql
        public interface StatusRepo {
            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<OrderStatus> one(long id);

            @Query("SELECT status FROM orders WHERE id = :id")
            Promise<Option<OrderStatus>> maybe(long id);

            @Query("SELECT status FROM orders WHERE user_id = :userId")
            Promise<List<OrderStatus>> all(long userId);
        }
        """;

    @TempDir
    Path tempDir;

    @Nested
    class PlainFactory {
        @Test
        void singleColumn_callsFactoryReference_notNew() throws Exception {
            var generated = generated(PLAIN_REPO, "PlainRepo");

            assertThat(generated).contains(".map(StatusRow::statusRow)").doesNotContain("StatusRow::new");
        }

        @Test
        void multiColumn_callsFactoryReference_notNew() throws Exception {
            var generated = generated(PLAIN_REPO, "PlainRepo");

            assertThat(generated).contains(").map(PlainRepo.IdStatus::idStatus);").doesNotContain("::new");
        }

        @Test
        void singleColumn_decodesThroughFactory() throws Exception {
            var decoded = decode(PLAIN_REPO, "PlainRepo", "statusOf", "OPEN", 1L);

            assertThat(decoded.isSuccess()).isTrue();
        }
    }

    @Nested
    class ResultFactory {
        @Test
        void singleColumn_joinsWithFlatMap() throws Exception {
            var generated = generated(RESULT_REPO, "ResultRepo");

            assertThat(generated).contains(".flatMap(StatusRow::statusRow)");
        }

        @Test
        void multiColumn_joinsWithFlatMap() throws Exception {
            var generated = generated(RESULT_REPO, "ResultRepo");

            assertThat(generated).contains(").flatMap(ResultRepo.IdStatus::idStatus);");
        }

        @Test
        void validRow_succeeds() throws Exception {
            assertThat(decode(RESULT_REPO, "ResultRepo", "statusOf", "OPEN", 1L).isSuccess()).isTrue();
        }

        @Test
        void singleColumn_factoryFailure_isTheMapperFailure() throws Exception {
            assertThat(failure(decode(RESULT_REPO, "ResultRepo", "statusOf", "BAD", 1L)).message()).contains("bad status");
        }

        @Test
        void multiColumn_factoryFailure_isTheMapperFailure() throws Exception {
            assertThat(failure(decode(RESULT_REPO, "ResultRepo", "idStatusOf", "BAD", 1L)).message()).contains("bad pair");
        }
    }

    @Nested
    class WideRecord {
        @Test
        void sixteenColumns_resultFactory_batchedAndFlatMapped() throws Exception {
            var source = wideSource();
            var generated = generated(source, "WideRepo");

            assertThat(generated).contains("var part1 = Result.all(")
                                 .contains(".flatMap((t1, t2) ->")
                                 .contains("WideRepo.WideRow.wideRow(")
                                 .doesNotContain("::new")
                                 .doesNotContain("new WideRepo.WideRow(");
        }

        @Test
        void sixteenColumns_factoryFailure_isTheMapperFailure() throws Exception {
            var source = wideSource();

            assertThat(decode(source, "WideRepo", "findWide", "-", 1L).isSuccess()).isTrue();
            assertThat(failure(decode(source, "WideRepo", "findWide", "-", -1L)).message()).contains("negative f0");
        }

        private String wideSource() {
            var fields = IntStream.range(0, 16).mapToObj(i -> "long f" + i).collect(Collectors.joining(", "));
            var names = IntStream.range(0, 16).mapToObj(i -> "f" + i).collect(Collectors.joining(", "));

            return HEADER + """
                @PgSql
                public interface WideRepo {
                    record WideRow(%s) {
                        static Result<WideRow> wideRow(%s) {
                            return f0 < 0
                                   ? Causes.cause("negative f0").result()
                                   : Result.success(new WideRow(%s));
                        }
                    }

                    @Query("SELECT %s FROM wide WHERE f0 = :f0")
                    Promise<Option<WideRow>> findWide(long f0);
                }
                """.formatted(fields, fields, names, names);
        }
    }

    @Nested
    class FactoryContract {
        @Test
        void missingFactory_isCompileError_namingTheExpectedFactory() throws Exception {
            var result = TestCompilationHelper.compileWithProcessor(NO_FACTORY_REPO, "test/NoFactoryRepo.java", tempDir);

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("no type-named static factory")
                                            .contains("static StatusRow statusRow(java.lang.String status)");
        }

        @Test
        void factoryReturningNeitherRecordNorResult_isCompileError() throws Exception {
            var result = TestCompilationHelper.compileWithProcessor(WRONG_RETURN_REPO, "test/WrongReturnRepo.java", tempDir);

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("must return StatusRow or Result<StatusRow>");
        }
    }

    @Nested
    class ForeignResult {
        @Test
        void factoryReturningResultOfAnotherType_isCompileError() throws Exception {
            var result = TestCompilationHelper.compileWithProcessor(FOREIGN_RESULT_REPO, "test/ForeignResultRepo.java", tempDir);

            assertThat(result.success()).isFalse();
            assertThat(result.diagnostics()).contains("must return StatusRow or Result<StatusRow>");
        }
    }

    @Nested
    class ValueObjectScalar {
        @Test
        void allThreeForms_liftThroughValueMapping_notRecordColumn() throws Exception {
            var generated = generated(VALUE_OBJECT_REPO, "StatusRepo");

            assertThat(generated).contains("OrderStatus.valueMapping().lift()")
                                 .doesNotContain("getString(\"value\")");
        }

        @Test
        void single_validColumn_decodes() throws Exception {
            assertThat(decode(VALUE_OBJECT_REPO, "StatusRepo", "one", "OPEN", 1L).isSuccess()).isTrue();
        }

        @Test
        void optional_validColumn_decodes() throws Exception {
            assertThat(decode(VALUE_OBJECT_REPO, "StatusRepo", "maybe", "CLOSED", 1L).isSuccess()).isTrue();
        }

        @Test
        void list_validColumn_decodes() throws Exception {
            assertThat(decode(VALUE_OBJECT_REPO, "StatusRepo", "all", "OPEN", 1L).isSuccess()).isTrue();
        }

        @Test
        void liftFailure_isTypedRowDecode_inEveryForm() throws Exception {
            for (var method : new String[]{"one", "maybe", "all"}) {
                var cause = failure(decode(VALUE_OBJECT_REPO, "StatusRepo", method, "BOGUS", 1L));

                assertThat(cause).as(method).isInstanceOf(RowDecodeError.RowDecode.class);
                assertThat(((RowDecodeError.RowDecode) cause).column()).as(method).isEqualTo("status");
            }
        }
    }

    // --- helpers ---

    private String generated(String source, String repoName) throws Exception {
        var result = TestCompilationHelper.compileAndCompileGenerated(source, "test/" + repoName + ".java", tempDir);

        assertThat(result.success()).as(result.diagnostics()).isTrue();

        return result.generatedSource("test." + repoName + "Factory");
    }

    /// Runs the generated adapter method against a fake connector that captures the row mapper, then applies the
    /// mapper to a row whose string columns read `text` and whose long columns read `number`.
    private Result<?> decode(String source,
                             String repoName,
                             String method,
                             String text,
                             long number) throws Exception {
        generated(source, repoName);

        var loader = new URLClassLoader(new URL[]{tempDir.resolve("classes").toUri().toURL()}, getClass().getClassLoader());
        var factory = loader.loadClass("test." + repoName + "Factory");
        var captured = new AtomicReference<RowMapper<?>>();
        var db = (PgSqlConnector) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                         new Class<?>[]{PgSqlConnector.class},
                                                         (_, called, args) -> capture(captured, called.getName(), args));
        var repo = factory.getMethod(Character.toLowerCase(repoName.charAt(0)) + repoName.substring(1), PgSqlConnector.class)
                          .invoke(null, db);
        var declared = loader.loadClass("test." + repoName);
        var target = java.util.Arrays.stream(declared.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow();

        target.invoke(repo, number);

        return captured.get().map(row(text, number));
    }

    private static Object capture(AtomicReference<RowMapper<?>> captured, String name, Object[] args) {
        if (name.startsWith("query")) {
            captured.set((RowMapper<?>) args[1]);
        }

        return null;
    }

    private static Cause failure(Result<?> result) {
        var cause = new AtomicReference<Cause>();

        result.onFailure(cause::set);

        return cause.get();
    }

    private static RowMapper.RowAccessor row(String text, long number) {
        return new RowMapper.RowAccessor() {
            @Override public Result<String> getString(String column) { return Result.success(text); }
            @Override public Result<Integer> getInt(String column) { return Result.success((int) number); }
            @Override public Result<Long> getLong(String column) { return Result.success(number); }
            @Override public Result<Double> getDouble(String column) { return Result.success((double) number); }
            @Override public Result<Boolean> getBoolean(String column) { return Result.success(false); }
            @Override public Result<byte[]> getBytes(String column) { return Result.success(new byte[0]); }
            @Override public <V> Result<V> getObject(String column, Class<V> type) { return Result.success(type.cast(null)); }
        };
    }
}
