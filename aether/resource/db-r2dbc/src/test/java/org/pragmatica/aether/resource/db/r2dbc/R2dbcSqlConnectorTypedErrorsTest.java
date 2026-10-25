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
import org.pragmatica.r2dbc.R2dbcError;

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
/// PostgreSQL 16 reached through the postgres-async R2DBC adapter. Before the fix a mapper failure was replaced by
/// `DatabaseFailure(new RuntimeException(message))`, and the adapter reduced every driver failure to its message, so a
/// constraint violation carried no SQLSTATE and a lost connection no transience. Driver failures now keep both
/// as `R2dbcError`, so `RetryOn.TRANSIENT` retries a lost connection and leaves a constraint violation alone.
/// Self-skips when no Docker daemon is reachable.
class R2dbcSqlConnectorTypedErrorsTest {
    private static final String KILL_OWN_BACKEND = "SELECT pg_terminate_backend(pg_backend_pid())";
    private static final String INSERT = "INSERT INTO typed_errors(id, name) VALUES (?, ?)";
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
        connector.update(INSERT, 1, "one").await().unwrap();
    }

    private static final RowMapper<String> UNDECODABLE = _ -> new RowDecodeError.RowDecode("name", Causes.cause("corrupt value")).result();

    @Test
    void queryOne_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        assertRowDecode(failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = ?", UNDECODABLE, 1)));
    }

    @Test
    void queryOptional_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        assertRowDecode(failureOf(connector.queryOptional("SELECT name FROM typed_errors WHERE id = ?", UNDECODABLE, 1)));
    }

    @Test
    void queryList_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        assertRowDecode(failureOf(connector.queryList("SELECT name FROM typed_errors", UNDECODABLE)));
    }

    @Test
    void queryList_severalRowsFailToDecode_firstFailureReachesCallerUnwrapped() {
        connector.update(INSERT, 2, "two").await().unwrap();

        assertRowDecode(failureOf(connector.queryList("SELECT name FROM typed_errors", UNDECODABLE)));
    }

    @Test
    void update_uniqueViolation_keepsItsSqlStateAndIsNotTransient() {
        var cause = failureOf(connector.update(INSERT, 1, "duplicate"));

        assertThat(cause).isInstanceOf(R2dbcError.ConstraintViolation.class);
        assertThat(((R2dbcError.ConstraintViolation) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void queryOne_lostConnection_isReportedTransient() {
        var cause = failureOf(connector.queryOne(KILL_OWN_BACKEND, row -> row.getBoolean("pg_terminate_backend")));

        assertThat(cause.isTransient()).as("a terminated backend is transient: %s", cause.message()).isTrue();
        assertThat(cause).isInstanceOf(R2dbcError.ConnectionFailed.class);
        assertThat(((R2dbcError.ConnectionFailed) cause).sqlState()).isEqualTo(some("57P01"));
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

            return connector.update(INSERT, 1, "duplicate");
        };

        failureOf(retrying().intercept(method).apply("x"));

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void queryOne_noRow_reachesCallerAsTheTypedNoResult() {
        var cause = failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = ?", row -> row.getString("name"), 99));

        assertThat(cause).isSameAs(R2dbcError.NoResult.INSTANCE);
    }

    @Test
    void queryOne_mapperFailsWithConnectorError_passesThroughUnchanged() {
        var typed = DatabaseConnectorError.configurationError("already typed");
        RowMapper<String> mapper = _ -> typed.result();

        var cause = failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = ?", mapper, 1));

        assertThat(cause).isSameAs(typed);
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
