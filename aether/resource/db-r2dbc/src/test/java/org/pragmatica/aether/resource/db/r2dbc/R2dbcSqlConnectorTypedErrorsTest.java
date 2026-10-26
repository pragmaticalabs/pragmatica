// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.r2dbc;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowDecodeError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.interceptor.RetryConfig;
import org.pragmatica.aether.resource.interceptor.RetryInterceptorFactory;
import org.pragmatica.aether.resource.interceptor.RetryMethodInterceptor;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.postgres.r2dbc.PgAsyncConnectionFactory;

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

/// #2408: the typed cause a database failure carries reaches the caller of the R2DBC connector, against a LIVE
/// PostgreSQL 16 reached through the postgres-async R2DBC adapter (statements are literal SQL: the adapter's
/// placeholder handling cannot run a parameterised statement). Before the fix a mapper failure was replaced by
/// `DatabaseFailure(new RuntimeException(message))`, and the adapter reduced every driver failure to its message, so a
/// constraint violation carried no SQLSTATE and a lost connection no transience. Driver failures now keep both
/// as `DatabaseConnectorError`, so `RetryOn.TRANSIENT` retries a lost connection and leaves a constraint violation alone.
/// Self-skips when no Docker daemon is reachable.
class R2dbcSqlConnectorTypedErrorsTest {
    private static final String KILL_OWN_BACKEND = "SELECT pg_terminate_backend(pg_backend_pid())";
    private static final String RAISE_SERIALIZATION_FAILURE = "DO $$ BEGIN RAISE EXCEPTION 'serialization failure' USING ERRCODE = '40001'; END $$";
    private static final String RAISE_STATE = "DO $$ BEGIN RAISE EXCEPTION 'unretryable' USING ERRCODE = '%s'; END $$";
    private static final String INSERT_ONE = "INSERT INTO typed_errors(id, name) VALUES (1, 'duplicate')";
    private static final String INSERT_TWO = "INSERT INTO typed_errors(id, name) VALUES (2, 'two')";
    private static PostgreSQLContainer<?> postgres;
    private static SqlConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        var config = databaseConnectorConfigBuilder().withName("typed-errors")
                                                     .withAsyncUrl("postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName())
                                                     .build()
                                                     .unwrap();
        var connectible = new NettyConnectibleBuilder().hostname(postgres.getHost())
                                                       .port(postgres.getMappedPort(5432))
                                                       .database(postgres.getDatabaseName())
                                                       .username(postgres.getUsername())
                                                       .password(postgres.getPassword())
                                                       .maxConnections(2)
                                                       .pool();

        connector = R2dbcSqlConnector.r2dbcSqlConnector(config, PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible));
    }

    @AfterAll
    static void stopPostgres() {
        if (connector != null) {
            connector.stop().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void freshTable() {
        connector.update("DROP TABLE IF EXISTS typed_errors").await().unwrap();
        connector.update("CREATE TABLE typed_errors(id INT PRIMARY KEY, name TEXT)").await().unwrap();
        connector.update("INSERT INTO typed_errors(id, name) VALUES (1, 'one')").await().unwrap();
    }

    private static final RowMapper<String> UNDECODABLE = _ -> new RowDecodeError.RowDecode("name", Causes.cause("corrupt value")).result();

    @Test
    void queryOne_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        assertRowDecode(failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = 1", UNDECODABLE)));
    }

    @Test
    void queryOptional_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        assertRowDecode(failureOf(connector.queryOptional("SELECT name FROM typed_errors WHERE id = 1", UNDECODABLE)));
    }

    @Test
    void queryList_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        assertRowDecode(failureOf(connector.queryList("SELECT name FROM typed_errors", UNDECODABLE)));
    }

    @Test
    void queryList_severalRowsFailToDecode_firstFailureReachesCallerUnwrapped() {
        connector.update(INSERT_TWO).await().unwrap();

        assertRowDecode(failureOf(connector.queryList("SELECT name FROM typed_errors", UNDECODABLE)));
    }

    @Test
    void update_uniqueViolation_keepsItsSqlStateAndIsNotTransient() {
        var cause = failureOf(connector.update(INSERT_ONE));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void queryOne_lostConnection_isReportedTransient() {
        var cause = failureOf(connector.queryOne(KILL_OWN_BACKEND, row -> row.getBoolean("pg_terminate_backend")));

        assertThat(cause.isTransient()).as("a terminated backend is transient: %s", cause.message()).isTrue();
        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
    }


    @Test
    void update_unretryableStates_areQueryFailuresThatAreNotTransient() {
        for (var state : new String[]{"08007", "08P01", "57014"}) {
            var cause = failureOf(connector.update(RAISE_STATE.formatted(state)));

            assertThat(cause).as(state).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
            assertThat(((DatabaseConnectorError) cause).sqlState()).as(state).isEqualTo(some(state));
            assertThat(cause.isTransient()).as(state).isFalse();
        }
    }

    @Test
    void retryOnTransient_connectionStateThatIsUnretryable_isNotRetried() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> {
            attempts.incrementAndGet();

            return connector.update(RAISE_STATE.formatted("08007"));
        };

        failureOf(retrying().intercept(method).apply("x"));

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void update_serverReportedSerializationFailure_keepsItsSqlStateAndIsTransient() {
        var cause = failureOf(connector.update(RAISE_SERIALIZATION_FAILURE));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.TransactionRolledBack.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("40001"));
        assertThat(cause.isTransient()).isTrue();
    }

    @Test
    void retryOnTransient_serverReportedSerializationFailure_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> attempts.incrementAndGet() == 1
                                                    ? connector.update(RAISE_SERIALIZATION_FAILURE)
                                                    : connector.update("UPDATE typed_errors SET name = 'retried' WHERE id = 1");

        var updated = retrying().intercept(method).apply("x").await().fold(cause -> fail("must succeed: " + cause.message()), v -> v);

        assertThat(updated).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retryOnTransient_lostConnection_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Long>, String> method = _ -> attempts.incrementAndGet() == 1
                                                 ? connector.queryOne(KILL_OWN_BACKEND, row -> row.getLong("n"))
                                                 : connector.queryOne("SELECT 42 AS n", row -> row.getLong("n"));

        var value = retrying().intercept(method).apply("x").await().fold(cause -> fail("must succeed: " + cause.message()), v -> v);

        assertThat(value).isEqualTo(42L);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retryOnTransient_uniqueViolation_isNotRetried() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> {
            attempts.incrementAndGet();

            return connector.update(INSERT_ONE);
        };

        failureOf(retrying().intercept(method).apply("x"));

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void update_syntaxError_isQueryFailedKeepingItsSqlStateAndIsNotTransient() {
        var cause = failureOf(connector.update("SELEC 1"));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("42601"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void everyOperation_driverFailure_reachesCallerAsAQueryFailureKeepingItsSqlState() {
        RowMapper<String> mapper = row -> row.getString("name");

        assertSyntaxError(connector.queryOne("SELEC 1", mapper));
        assertSyntaxError(connector.queryOptional("SELEC 1", mapper));
        assertSyntaxError(connector.queryList("SELEC 1", mapper));
        assertSyntaxError(connector.update("SELEC 1"));
        assertSyntaxError(connector.batch("SELEC 1", java.util.List.<Object[]> of(new Object[0])));
    }

    @Test
    void transactional_driverFailureOfEachOperation_reachesCallerAsAQueryFailureKeepingItsSqlState() {
        RowMapper<String> mapper = row -> row.getString("name");

        assertSyntaxError(connector.transactional(tx -> tx.queryOne("SELEC 1", mapper)));
        assertSyntaxError(connector.transactional(tx -> tx.queryOptional("SELEC 1", mapper)));
        assertSyntaxError(connector.transactional(tx -> tx.queryList("SELEC 1", mapper)));
        assertSyntaxError(connector.transactional(tx -> tx.update("SELEC 1")));
        assertSyntaxError(connector.transactional(tx -> tx.batch("SELEC 1", java.util.List.<Object[]> of(new Object[0]))));
    }

    @Test
    void transactional_callbackSeesTheConnectorErrorOfEachOperation() {
        RowMapper<String> mapper = row -> row.getString("name");

        assertThat(kindSeenByCallback(tx -> tx.queryOne("SELEC 1", mapper))).isEqualTo("QueryFailed");
        assertThat(kindSeenByCallback(tx -> tx.queryOptional("SELEC 1", mapper))).isEqualTo("QueryFailed");
        assertThat(kindSeenByCallback(tx -> tx.queryList("SELEC 1", mapper))).isEqualTo("QueryFailed");
        assertThat(kindSeenByCallback(tx -> tx.update("SELEC 1"))).isEqualTo("QueryFailed");
        assertThat(kindSeenByCallback(tx -> tx.batch("SELEC 1", java.util.List.<Object[]> of(new Object[0])))).isEqualTo("QueryFailed");
    }

    @Test
    void transactional_failedCommit_keepsTheServersSqlState() {
        connector.update("DROP TABLE IF EXISTS deferred_unique").await().unwrap();
        connector.update("CREATE TABLE deferred_unique(id INT, CONSTRAINT deferred_unique_pk UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)").await().unwrap();

        var cause = failureOf(connector.transactional(tx -> tx.update("INSERT INTO deferred_unique VALUES (1)")
                                                              .flatMap(_ -> tx.update("INSERT INTO deferred_unique VALUES (1)"))));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
    }

    @Test
    void transactional_rowDecodeFailureInCallback_reachesCallerUnchanged() {
        var decodeFailure = new RowDecodeError.RowDecode("name", Causes.cause("corrupt value"));

        var cause = failureOf(connector.transactional(tx -> decodeFailure.<String> promise()));

        assertThat(cause).isSameAs(decodeFailure);
    }

    @Test
    void toConnectorError_driverErrors_keepTheirSqlStateOnTheConnectorError() {
        var constraint = R2dbcSqlConnector.toConnectorError(new org.pragmatica.r2dbc.R2dbcError.ConstraintViolation("duplicate", some("23505")));
        var connection = R2dbcSqlConnector.toConnectorError(new org.pragmatica.r2dbc.R2dbcError.ConnectionFailed("lost", some("57P01")));
        var query = R2dbcSqlConnector.toConnectorError(new org.pragmatica.r2dbc.R2dbcError.QueryFailed("bad", some("42601")));

        assertThat(constraint).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) constraint).sqlState()).isEqualTo(some("23505"));
        assertThat(connection).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
        assertThat(((DatabaseConnectorError) connection).sqlState()).isEqualTo(some("57P01"));
        assertThat(query).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(((DatabaseConnectorError) query).sqlState()).isEqualTo(some("42601"));
    }

    @Test
    void queryOne_noRow_reachesCallerAsTheTypedResultNotFound() {
        var cause = failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = 99", row -> row.getString("name")));

        assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
    }

    @Test
    void queryOne_mapperFailsWithConnectorError_passesThroughUnchanged() {
        var typed = DatabaseConnectorError.configurationError("already typed");
        RowMapper<String> mapper = _ -> typed.result();

        var cause = failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = 1", mapper));

        assertThat(cause).isSameAs(typed);
    }

    /// What the transaction callback itself observes when an operation fails and it recovers, before the failure leaves
    /// the transaction: the connector error, not the driver layer's.
    private static String kindSeenByCallback(Fn1<Promise<?>, SqlConnector> operation) {
        return connector.transactional(tx -> operation.apply(tx).map(_ -> "succeeded").recover(cause -> Promise.success(cause.getClass().getSimpleName())))
                        .await()
                        .unwrap();
    }

    private static void assertSyntaxError(Promise<?> promise) {
        var cause = failureOf(promise);

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("42601"));
    }

    private static void assertRowDecode(Cause cause) {
        assertThat(cause).isInstanceOf(RowDecodeError.RowDecode.class);
        assertThat(((RowDecodeError.RowDecode) cause).column()).isEqualTo("name");
    }

    private static RetryMethodInterceptor retrying() {
        return RetryConfig.retryConfig(3, timeSpan(1).millis())
                          .flatMap(config -> new RetryInterceptorFactory().provision(config).await())
                          .fold(cause -> fail("provision must succeed: " + cause.message()), interceptor -> interceptor);
    }

    private static <T> Cause failureOf(Promise<T> promise) {
        return promise.await().fold(cause -> cause, value -> fail("expected a failure but got " + value));
    }
}
