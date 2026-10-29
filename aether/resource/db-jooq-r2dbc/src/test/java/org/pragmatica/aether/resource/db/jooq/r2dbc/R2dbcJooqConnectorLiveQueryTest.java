// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.r2dbc;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.RowDecodeError;
import org.pragmatica.aether.resource.interceptor.RetryConfig;
import org.pragmatica.aether.resource.interceptor.RetryInterceptorFactory;
import org.pragmatica.aether.resource.interceptor.RetryMethodInterceptor;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.postgres.r2dbc.PgAsyncConnectionFactory;

import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2431: the connector runs real queries against a real PostgreSQL 16 server end to end, and the #2408 rows hold through
/// `fetchOne` / `execute` / `transactional` themselves (the typed-errors test could only drive the error mapping, because
/// no query could run): a constraint violation keeps its SQLSTATE, a lost connection is transient and retried, a row-decode
/// failure reaches the caller as the same `RowDecodeError` and rolls the transaction back. Self-skips when no Docker daemon
/// is reachable.
class R2dbcJooqConnectorLiveQueryTest {
    private static final String KILL_OWN_BACKEND = "SELECT pg_terminate_backend(pg_backend_pid())";
    private static final String INSERT_DUPLICATE = "INSERT INTO live_items(id, name) VALUES (1, 'duplicate')";
    private static PostgreSQLContainer<?> postgres;
    private static Connectible connectible;
    private static R2dbcJooqConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        connectible = new NettyConnectibleBuilder().hostname(postgres.getHost())
                                                   .port(postgres.getMappedPort(5432))
                                                   .database(postgres.getDatabaseName())
                                                   .username(postgres.getUsername())
                                                   .password(postgres.getPassword())
                                                   .maxConnections(4)
                                                   .pool();
        var config = databaseConnectorConfigBuilder().withName("db").withHost("localhost").withDatabase("db").withType(DatabaseType.POSTGRESQL).build().unwrap();

        connector = R2dbcJooqConnector.r2dbcJooqConnector(config, PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible));
    }

    @AfterAll
    static void stopPostgres() {
        if (connectible != null) {
            connectible.close().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void freshTable() {
        success(connector.execute(connector.dsl().query("DROP TABLE IF EXISTS live_items")));
        success(connector.execute(connector.dsl().query("CREATE TABLE live_items(id INT PRIMARY KEY, name TEXT)")));
        success(connector.execute(connector.dsl().query("INSERT INTO live_items(id, name) VALUES (1, 'one'), (2, 'two')")));
    }

    @Test
    void fetchOne_fetchOptional_fetch_returnTheirRows() {
        var one = success(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM live_items WHERE id = 1")));
        var optional = success(connector.fetchOptional(connector.dsl().resultQuery("SELECT name FROM live_items WHERE id = 99")));
        var all = success(connector.fetch(connector.dsl().resultQuery("SELECT name FROM live_items ORDER BY id")));

        assertThat(one.get(0, String.class)).isEqualTo("one");
        assertThat(optional.isPresent()).isFalse();
        assertThat(all.stream().map(r -> r.get(0, String.class))).containsExactly("one", "two");
    }

    @Test
    void execute_returnsAffectedRows() {
        assertThat(success(connector.execute(connector.dsl().query("UPDATE live_items SET name = 'x'")))).isEqualTo(2);
    }

    @Test
    void execute_uniqueViolation_keepsItsSqlStateAndIsNotTransient() {
        var cause = failure(connector.execute(connector.dsl().query(INSERT_DUPLICATE)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void fetchOne_lostConnection_isReportedTransient() {
        var cause = failure(connector.fetchOne(connector.dsl().resultQuery(KILL_OWN_BACKEND)));

        assertThat(cause.isTransient()).as("a terminated backend is transient: %s", cause.message()).isTrue();
    }

    @Test
    void retryOnTransient_lostConnection_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> attempts.incrementAndGet() == 1
                                                    ? connector.fetchOne(connector.dsl().resultQuery(KILL_OWN_BACKEND)).map(_ -> 0)
                                                    : connector.fetchOne(connector.dsl().resultQuery("SELECT 42")).map(r -> r.get(0, Integer.class));

        var value = success(retrying().intercept(method).apply("x"));

        assertThat(value).isEqualTo(42);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void transactional_rowDecodeFailure_reachesCallerAsRowDecodeError_andRollsBack() {
        var decodeFailure = new RowDecodeError.RowDecode("name", Causes.cause("corrupt value"));

        var cause = failure(connector.<String> transactional(tx -> tx.execute(tx.dsl().query("DELETE FROM live_items"))
                                                                   .flatMap(_ -> decodeFailure.promise())));

        assertThat(cause).isSameAs(decodeFailure);
        assertThat(success(connector.fetch(connector.dsl().resultQuery("SELECT name FROM live_items"))).size()).isEqualTo(2);
    }

    @Test
    void transactional_commitsOnSuccess() {
        success(connector.transactional(tx -> tx.execute(tx.dsl().query("UPDATE live_items SET name = 'x' WHERE id = 1"))));

        var name = success(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM live_items WHERE id = 1")));

        assertThat(name.get(0, String.class)).isEqualTo("x");
    }

    @Test
    void transactional_runsEveryOperationOnItsConnection() {
        var summary = success(connector.transactional(tx -> tx.execute(tx.dsl().query("UPDATE live_items SET name = 'x' WHERE id = 1"))
                                                            .flatMap(updated -> tx.fetchOne(tx.dsl().resultQuery("SELECT name FROM live_items WHERE id = 1"))
                                                                                  .map(r -> updated + r.get(0, String.class)))
                                                            .flatMap(text -> tx.fetchOptional(tx.dsl().resultQuery("SELECT name FROM live_items WHERE id = 2"))
                                                                              .map(o -> text + o.map(r -> r.get(0, String.class)).or("none")))
                                                            .flatMap(text -> tx.fetch(tx.dsl().resultQuery("SELECT name FROM live_items"))
                                                                               .map(rows -> text + rows.size()))));

        assertThat(summary).isEqualTo("1xtwo2");
    }

    @Test
    void transactional_failingStatement_keepsItsSqlStateAndRollsBack() {
        var cause = failure(connector.<Integer> transactional(tx -> tx.execute(tx.dsl().query("DELETE FROM live_items WHERE id = 2"))
                                                                    .flatMap(_ -> tx.execute(tx.dsl().query(INSERT_DUPLICATE)))));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(success(connector.fetch(connector.dsl().resultQuery("SELECT name FROM live_items"))).size()).isEqualTo(2);
    }

    @Test
    void transactional_fetchOneOnAMissingTable_keepsItsSqlState() {
        var cause = failure(connector.<Integer> transactional(tx -> tx.fetchOne(tx.dsl().resultQuery("SELECT * FROM no_such_table")).map(_ -> 1)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("42P01"));
    }

    @Test
    void transactional_fetchOne_noRowOrManyRows_areTyped() {
        var none = failure(connector.<Integer> transactional(tx -> tx.fetchOne(tx.dsl().resultQuery("SELECT name FROM live_items WHERE id = 99")).map(_ -> 1)));
        var many = failure(connector.<Integer> transactional(tx -> tx.fetchOne(tx.dsl().resultQuery("SELECT name FROM live_items")).map(_ -> 1)));

        assertThat(none).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
        assertThat(many).isInstanceOf(DatabaseConnectorError.MultipleResults.class);
    }

    @Test
    void transactional_fetchOptional_takesTheFirstRow() {
        var name = success(connector.transactional(tx -> tx.fetchOptional(tx.dsl().resultQuery("SELECT name FROM live_items ORDER BY id"))
                                                           .map(o -> o.map(r -> r.get(0, String.class)).or("none"))));

        assertThat(name).isEqualTo("one");
    }

    @Test
    void fetchOne_manyRows_isMultipleResults() {
        var cause = failure(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM live_items")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.MultipleResults.class);
    }

    @Test
    void fetchOne_typedFieldsAndBoundValue_keepTheirTypes() {
        var id = DSL.field("id", Integer.class);
        var record = success(connector.fetchOne(connector.dsl().select(DSL.field("name", String.class), id).from(DSL.table("live_items")).where(id.eq(2))));

        assertThat(record.get(0, String.class)).isEqualTo("two");
        assertThat(record.get(1, Integer.class)).isEqualTo(2);
        assertThat(success(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM live_items WHERE id = ?", 1))).get(0, String.class)).isEqualTo("one");
    }

    private static final java.util.UUID SAMPLE_UUID = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private static void freshUuidTable() {
        success(connector.execute(connector.dsl().query("DROP TABLE IF EXISTS live_uuids")));
        success(connector.execute(connector.dsl().query("CREATE TABLE live_uuids(id INT PRIMARY KEY, u UUID, tags TEXT[], nums INT[], ua UUID[], doc JSONB)")));
        success(connector.execute(connector.dsl().query("INSERT INTO live_uuids(id, u, tags, nums, ua, doc) VALUES (1, '123e4567-e89b-12d3-a456-426614174000', ARRAY['a','b'], ARRAY[1,2], ARRAY['123e4567-e89b-12d3-a456-426614174000'::uuid, '00000000-0000-0000-0000-000000000001'::uuid], '{}')")));
    }

    /// #2481: jOOQ asks the driver for a uuid or array column as `Object`; the driver refused that target.
    @Test
    void fetchOne_typedUuidAndArrayColumns_areReadTyped() {
        freshUuidTable();

        var u = DSL.field("u", SQLDataType.UUID);
        var tags = DSL.field("tags", SQLDataType.VARCHAR.array());
        var nums = DSL.field("nums", SQLDataType.INTEGER.array());
        var record = success(connector.fetchOne(connector.dsl().select(u, tags, nums).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(1))));

        assertThat(record.get(u)).isEqualTo(SAMPLE_UUID);
        assertThat(record.get(tags)).containsExactly("a", "b");
        assertThat(record.get(nums)).containsExactly(1, 2);
    }

    @Test
    void fetchOne_untypedUuidAndArrayColumns_returnTheDriversNativeValue() {
        freshUuidTable();

        var record = success(connector.fetchOne(connector.dsl().select(DSL.field("u"), DSL.field("tags"), DSL.field("nums")).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(1))));

        assertThat(record.get(0)).isEqualTo(SAMPLE_UUID);
        assertThat((Object[]) record.get(1)).containsExactly("a", "b");
        assertThat((Object[]) record.get(2)).containsExactly(1, 2);
    }

    private static final java.util.UUID OTHER_UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void fetchOne_typedUuidArray_isReadTyped() {
        freshUuidTable();

        var ua = DSL.field("ua", SQLDataType.UUID.array());
        var record = success(connector.fetchOne(connector.dsl().select(ua).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(1))));

        assertThat(record.get(ua)).containsExactly(SAMPLE_UUID, OTHER_UUID);
    }

    @Test
    void fetchOne_untypedUuidArray_returnsTheDriversNativeValue() {
        freshUuidTable();

        var record = success(connector.fetchOne(connector.dsl().select(DSL.field("ua")).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(1))));

        assertThat((Object[]) record.get(0)).containsExactly(SAMPLE_UUID, OTHER_UUID);
    }

    @Test
    void uuidArray_roundTripsThroughABoundParameter_emptyIncluded() {
        freshUuidTable();
        success(connector.execute(connector.dsl().query("INSERT INTO live_uuids(id, ua) VALUES (2, ?), (3, ?)", new java.util.UUID[]{OTHER_UUID}, new java.util.UUID[0])));

        var ua = DSL.field("ua", SQLDataType.UUID.array());
        var two = success(connector.fetchOne(connector.dsl().select(ua).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(2))));
        var three = success(connector.fetchOne(connector.dsl().select(ua).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(3))));

        assertThat(two.get(ua)).containsExactly(OTHER_UUID);
        assertThat(three.get(ua)).isEmpty();
    }

    @Test
    void fetchOne_untypedColumnTheDriverCannotConvert_failsWithAMessageNamingTheColumnTypeAndTarget() {
        freshUuidTable();

        var cause = failure(connector.fetchOne(connector.dsl().select(DSL.field("doc")).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(1))));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(cause.message()).contains("'doc'").contains("JSONB").contains("java.lang.Object").doesNotContain("null");
    }

    @Test
    void fetchOne_untypedPlainField_returnsTheDriversNativeValue() {
        var record = success(connector.fetchOne(connector.dsl().select(DSL.field("name")).from(DSL.table("live_items")).where(DSL.field("id").eq(1))));

        assertThat(record.get(0)).isEqualTo("one");
    }

    @Test
    void uuidAndArrayValues_roundTripThroughBoundParameters() {
        freshUuidTable();
        success(connector.execute(connector.dsl().query("INSERT INTO live_uuids(id, u, tags, nums) VALUES (2, ?, ?, ?)", SAMPLE_UUID, new String[]{"x", "y"}, new Integer[]{3, 4})));

        var u = DSL.field("u", SQLDataType.UUID);
        var tags = DSL.field("tags", SQLDataType.VARCHAR.array());
        var nums = DSL.field("nums", SQLDataType.INTEGER.array());
        var record = success(connector.fetchOne(connector.dsl().select(u, tags, nums).from(DSL.table("live_uuids")).where(DSL.field("id", Integer.class).eq(2))));

        assertThat(record.get(u)).isEqualTo(SAMPLE_UUID);
        assertThat(record.get(tags)).containsExactly("x", "y");
        assertThat(record.get(nums)).containsExactly(3, 4);
    }

    /// v-2475 D-3: jOOQ plain SQL takes `?` for a bind placeholder, so the jsonb "has key" operator is written `??`.
    @Test
    void fetchOne_jsonbHasKeyOperator_writtenWithTheEscape_works() {
        var record = success(connector.fetchOne(connector.dsl().resultQuery("SELECT '{\"a\":1}'::jsonb ?? 'a' AS has_a")));

        assertThat(record.get(0, Boolean.class)).isTrue();
    }

    @Test
    void fetchOne_jsonbHasKeyOperator_withABoundKey_works() {
        var record = success(connector.fetchOne(connector.dsl().resultQuery("SELECT '{\"a\":1}'::jsonb ?? ? AS has_a", "a")));

        assertThat(record.get(0, Boolean.class)).isTrue();
    }

    /// A lone `?` with no bind is not an escape and is left to the server.
    @Test
    void fetchOne_loneQuestionMarkWithoutABind_isLeftToTheServer() {
        var record = success(connector.fetchOne(connector.dsl().resultQuery("SELECT '{\"a\":1}'::jsonb ? 'a' AS has_a")));

        assertThat(record.get(0, Boolean.class)).isTrue();
    }

    @Test
    void execute_boundValue_updatesOnlyTheMatchingRow() {
        var updated = success(connector.execute(connector.dsl().query("UPDATE live_items SET name = 'bound' WHERE id = ?", 2)));

        assertThat(updated).isEqualTo(1);
        assertThat(success(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM live_items WHERE id = 1"))).get(0, String.class)).isNotEqualTo("bound");
    }

    @Test
    void fetchOne_fieldDeclaredWiderThanTheColumn_isCoercedToItsDeclaredType() {
        var wide = DSL.field("id", Long.class);
        var record = success(connector.fetchOne(connector.dsl().select(wide).from(DSL.table("live_items")).where(DSL.field("id", Integer.class).eq(1))));
        Long id = record.value1();

        assertThat(id).isEqualTo(1L);
    }

    @Test
    void execute_queryThatIsNotARowCountQuery_failsWithATypedConnectorError() {
        var cause = failure(connector.execute(connector.dsl().resultQuery("SELECT 1")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.class);
        assertThat(cause.message()).contains("row-count");
    }

    /// F-1: a query object shared by concurrent calls (a `static final` constant is an ordinary pattern) must run on the
    /// connection of ITS OWN call; attaching the caller's object to a connection made it run on another call's.
    @Test
    void sharedQueryObject_runsOnTheConnectionOfItsOwnCall() throws Exception {
        var shared = connector.dsl().resultQuery("SELECT pg_backend_pid()");
        var wrong = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(16);

        try {
            var futures = IntStream.range(0, 16).mapToObj(_ -> executor.submit(() -> runSharedQuery(shared, wrong))).toList();

            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(wrong.get()).as("calls whose shared query ran on another call's connection").isZero();
    }

    private static void runSharedQuery(org.jooq.ResultQuery<org.jooq.Record> shared, AtomicInteger wrong) {
        for (var i = 0; i < 600; i++) {
            var sameBackend = success(connector.transactional(tx -> tx.fetchOne(tx.dsl().resultQuery("SELECT pg_backend_pid()"))
                                                                    .flatMap(own -> tx.fetchOne(shared).map(other -> own.get(0, Integer.class).equals(other.get(0, Integer.class))))));

            if (!sameBackend) {
                wrong.incrementAndGet();
            }
        }
    }

    private static RetryMethodInterceptor retrying() {
        return RetryConfig.retryConfig(3, timeSpan(1).millis())
                          .flatMap(config -> new RetryInterceptorFactory().provision(config).await())
                          .fold(cause -> fail("provision must succeed: " + cause.message()), interceptor -> interceptor);
    }

    private static <T> T success(Promise<T> promise) {
        return promise.await().fold(cause -> fail("expected success but got " + cause.message()), value -> value);
    }

    private static <T> Cause failure(Promise<T> promise) {
        return promise.await().fold(cause -> cause, value -> fail("expected a failure but got " + value));
    }
}
