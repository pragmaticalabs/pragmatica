// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.r2dbc;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
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
import org.pragmatica.r2dbc.R2dbcOperations;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2408: a failure a real PostgreSQL 16 server produces reaches the callers of the jOOQ R2DBC connector as a typed
/// `DatabaseConnectorError` that keeps its SQLSTATE and transience, and a cause that is already typed (a `RowDecodeError`,
/// a connector error) passes through `toConnectorError` unchanged. Before the fix the pass-through was
/// `DatabaseFailure(new RuntimeException(message))`, and the postgres R2DBC adapter reduced every driver failure to its
/// message, so a constraint violation carried no SQLSTATE and a lost connection no transience.
///
/// The failures are driven through the adapter's `R2dbcOperations` and the connector's own `toConnectorError`, not through
/// `fetchOne`/`execute`: jOOQ refuses blocking `fetch()` on an R2DBC connection, so those cannot run against a database
/// at all (reported separately). Self-skips when no Docker daemon is reachable.
class R2dbcJooqConnectorTypedErrorsTest {
    private static final String KILL_OWN_BACKEND = "SELECT pg_terminate_backend(pg_backend_pid())";
    private static final String RAISE_SERIALIZATION_FAILURE = "DO $$ BEGIN RAISE EXCEPTION 'serialization failure' USING ERRCODE = '40001'; END $$";
    private static final String INSERT_ONE = "INSERT INTO typed_errors(id, name) VALUES (1, 'duplicate')";
    private static PostgreSQLContainer<?> postgres;
    private static Connectible connectible;
    private static R2dbcOperations operations;

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
                                                   .maxConnections(2)
                                                   .pool();
        operations = R2dbcOperations.r2dbcOperations(PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible));
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
        operations.update("DROP TABLE IF EXISTS typed_errors").await().unwrap();
        operations.update("CREATE TABLE typed_errors(id INT PRIMARY KEY, name TEXT)").await().unwrap();
        operations.update("INSERT INTO typed_errors(id, name) VALUES (1, 'one')").await().unwrap();
    }

    @Test
    void toConnectorError_uniqueViolation_keepsItsSqlStateAndIsNotTransient() {
        var cause = failureOf(mapped(operations.update(INSERT_ONE)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void toConnectorError_syntaxError_isQueryFailedKeepingItsSqlStateAndIsNotTransient() {
        var cause = failureOf(mapped(operations.update("SELEC 1")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("42601"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void toConnectorError_lostConnection_isReportedTransient() {
        var cause = failureOf(killOwnBackend());

        assertThat(cause.isTransient()).as("a terminated backend is transient: %s", cause.message()).isTrue();
        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
    }

    @Test
    void toConnectorError_serverReportedSerializationFailure_keepsItsSqlStateAndIsTransient() {
        var cause = failureOf(mapped(operations.update(RAISE_SERIALIZATION_FAILURE)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("40001"));
        assertThat(cause.isTransient()).isTrue();
    }

    @Test
    void retryOnTransient_serverReportedSerializationFailure_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Long>, String> method = _ -> attempts.incrementAndGet() == 1
                                                 ? mapped(operations.update(RAISE_SERIALIZATION_FAILURE))
                                                 : mapped(operations.update("UPDATE typed_errors SET name = 'retried' WHERE id = 1"));

        var updated = retrying().intercept(method).apply("x").await().fold(cause -> fail("must succeed: " + cause.message()), v -> v);

        assertThat(updated).isEqualTo(1L);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retryOnTransient_lostConnection_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> attempts.incrementAndGet() == 1
                                                    ? killOwnBackend().map(_ -> 0)
                                                    : mapped(operations.queryOne("SELECT 42 AS n", (row, _) -> row.get("n", Integer.class)));

        var value = retrying().intercept(method).apply("x").await().fold(cause -> fail("must succeed: " + cause.message()), v -> v);

        assertThat(value).isEqualTo(42);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retryOnTransient_uniqueViolation_isNotRetried() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Long>, String> method = _ -> {
            attempts.incrementAndGet();

            return mapped(operations.update(INSERT_ONE));
        };

        failureOf(retrying().intercept(method).apply("x"));

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void toConnectorError_noRow_isTheTypedResultNotFound() {
        var cause = failureOf(mapped(operations.queryOne("SELECT name FROM typed_errors WHERE id = 99", (row, _) -> row.get("name", String.class))));

        assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
    }

    @Test
    void toConnectorError_rowDecodeFailure_passesThroughUnchanged() {
        var decodeFailure = new RowDecodeError.RowDecode("name", Causes.cause("corrupt value"));

        assertThat(R2dbcJooqConnector.toConnectorError(decodeFailure)).isSameAs(decodeFailure);
    }

    @Test
    void toConnectorError_aCauseThatIsAlreadyTyped_passesThroughUnchanged() {
        var typed = DatabaseConnectorError.configurationError("already typed");

        assertThat(R2dbcJooqConnector.toConnectorError(typed)).isSameAs(typed);
    }

    /// The statement outcomes reach the connector as `R2dbcError` from the same adapter the connector is wired to, so the
    /// connector's own mapping is applied to the failure a real server produced.
    private static <T> Promise<T> mapped(Promise<T> promise) {
        return promise.mapError(R2dbcJooqConnector::toConnectorError);
    }

    private static Promise<Boolean> killOwnBackend() {
        return mapped(operations.queryOne(KILL_OWN_BACKEND, (row, _) -> row.get("pg_terminate_backend", Boolean.class)));
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
