// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.jdbc;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowDecodeError;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.aether.resource.interceptor.RetryConfig;
import org.pragmatica.aether.resource.interceptor.RetryInterceptorFactory;
import org.pragmatica.aether.resource.interceptor.RetryMethodInterceptor;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.postgresql.ds.PGSimpleDataSource;

import org.jooq.Record;
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

/// #2408: the typed cause a database failure carries reaches the caller of the jOOQ-over-JDBC connector, against a
/// LIVE PostgreSQL 16 through the real pgjdbc driver. Before the fix `ResultNotFound` and a failed transaction callback
/// were thrown through `Result.unwrap()` and surfaced as `DatabaseFailure(IllegalStateException)` (a callback failure as
/// a transient `ConnectionFailed`), and jOOQ's `DataAccessException` hid the driver's SQLSTATE. Self-skips when no Docker
/// daemon is reachable.
class JdbcJooqConnectorTypedErrorsTest {
    private static final String KILL_OWN_BACKEND = "SELECT pg_terminate_backend(pg_backend_pid()) AS killed";
    private static final String RAISE_SERIALIZATION_FAILURE = "DO $$ BEGIN RAISE EXCEPTION 'serialization failure' USING ERRCODE = '40001'; END $$";
    private static final String INSERT_ONE = "INSERT INTO typed_errors(id, name) VALUES (1, 'duplicate')";
    private static PostgreSQLContainer<?> postgres;
    private static JooqConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        connector = jdbcJooqConnector();
    }

    private static JooqConnector jdbcJooqConnector() {
        var dataSource = new PGSimpleDataSource();

        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var config = databaseConnectorConfigBuilder().withName("typed-errors")
                                                     .withJdbcUrl(postgres.getJdbcUrl())
                                                     .build()
                                                     .unwrap();

        return JdbcJooqConnector.jdbcJooqConnector(config, dataSource);
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
        run("DROP TABLE IF EXISTS typed_errors");
        run("CREATE TABLE typed_errors(id INT PRIMARY KEY, name TEXT)");
        run("INSERT INTO typed_errors(id, name) VALUES (1, 'one')");
    }

    @Test
    void transactional_rowDecodeFailure_reachesCallerAsRowDecodeError() {
        var decodeFailure = new RowDecodeError.RowDecode("name", Causes.cause("corrupt value"));

        var cause = failureOf(connector.transactional(_ -> decodeFailure.<String> promise()));

        assertThat(cause).isInstanceOf(RowDecodeError.RowDecode.class);
        assertThat(((RowDecodeError.RowDecode) cause).column()).isEqualTo("name");
        assertThat(cause).as("not replaced by a connection failure that would be retried").isSameAs(decodeFailure);
    }

    @Test
    void transactional_callbackFailsWithConnectorError_passesThroughUnchanged() {
        var typed = DatabaseConnectorError.configurationError("already typed");

        var cause = failureOf(connector.transactional(_ -> typed.<String> promise()));

        assertThat(cause).isSameAs(typed);
    }

    @Test
    void execute_uniqueViolation_keepsItsSqlStateAndIsNotTransient() {
        var cause = failureOf(connector.execute(connector.dsl().query(INSERT_ONE)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(cause.isTransient()).isFalse();
    }

    @Test
    void execute_syntaxError_keepsTheDriverFailureAsTheCause() {
        var cause = failureOf(connector.execute(connector.dsl().query("SELEC 1")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(((DatabaseConnectorError.QueryFailed) cause).cause().isPresent()).as("the driver failure is kept, not only its text").isTrue();
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("42601"));
    }

    @Test
    void fetchOne_lostConnection_keepsTheDriverFailureAsTheCause() {
        var cause = failureOf(killOwnBackend());

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
        assertThat(((DatabaseConnectorError.ConnectionFailed) cause).cause().isPresent()).isTrue();
    }

    @Test
    void execute_serverReportedSerializationFailure_keepsItsSqlStateAndIsTransient() {
        var cause = failureOf(connector.execute(connector.dsl().query(RAISE_SERIALIZATION_FAILURE)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.TransactionRolledBack.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("40001"));
        assertThat(cause.isTransient()).isTrue();
    }

    @Test
    void retryOnTransient_serverReportedSerializationFailure_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> attempts.incrementAndGet() == 1
                                                    ? connector.execute(connector.dsl().query(RAISE_SERIALIZATION_FAILURE))
                                                    : connector.execute(connector.dsl().query("UPDATE typed_errors SET name = 'retried' WHERE id = 1"));

        var updated = retrying().intercept(method).apply("x").await().fold(cause -> fail("must succeed: " + cause.message()), v -> v);

        assertThat(updated).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void fetchOne_lostConnection_isReportedTransient() {
        var cause = failureOf(killOwnBackend());

        assertThat(cause.isTransient()).as("a terminated backend is transient: %s", cause.message()).isTrue();
        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("57P01"));
    }

    @Test
    void retryOnTransient_lostConnection_isRetriedAndSucceeds() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Record>, String> method = _ -> attempts.incrementAndGet() == 1
                                                   ? killOwnBackend()
                                                   : connector.fetchOne(connector.dsl().resultQuery("SELECT 42 AS n"));

        var value = retrying().intercept(method).apply("x").await().fold(cause -> fail("must succeed: " + cause.message()), v -> v);

        assertThat(value.get("n", Integer.class)).isEqualTo(42);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retryOnTransient_uniqueViolation_isNotRetried() {
        var attempts = new AtomicInteger();
        Fn1<Promise<Integer>, String> method = _ -> {
            attempts.incrementAndGet();

            return connector.execute(connector.dsl().query(INSERT_ONE));
        };

        failureOf(retrying().intercept(method).apply("x"));

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void transactional_success_commitsAndReturnsTheCallbacksValue() {
        var updated = connector.transactional(tx -> tx.execute(tx.dsl().query("UPDATE typed_errors SET name = 'committed' WHERE id = 1"))).await().unwrap();
        var stored = connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM typed_errors WHERE id = 1")).await().unwrap();

        assertThat(updated).isEqualTo(1);
        assertThat(stored.get("name", String.class)).as("committed by the transaction").isEqualTo("committed");
    }

    @Test
    void transactional_failedCommit_keepsTheServersSqlState() {
        run("DROP TABLE IF EXISTS deferred_unique");
        run("CREATE TABLE deferred_unique(id INT, CONSTRAINT deferred_unique_pk UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)");

        var cause = failureOf(connector.transactional(tx -> tx.execute(tx.dsl().query("INSERT INTO deferred_unique VALUES (1)"))
                                                              .flatMap(_ -> tx.execute(tx.dsl().query("INSERT INTO deferred_unique VALUES (1)")))));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
    }

    @Test
    void transactional_refusedConnection_isTransientAndKeepsTheSqlState() {
        var dataSource = new PGSimpleDataSource();

        dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/none");
        var unreachable = JdbcJooqConnector.jdbcJooqConnector(connector.config(), dataSource);

        var cause = failureOf(unreachable.transactional(tx -> tx.execute(tx.dsl().query("SELECT 1"))));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("08001"));
        assertThat(cause.isTransient()).isTrue();
    }

    @Test
    void fetchOne_noRow_reachesCallerAsTheTypedResultNotFound() {
        var cause = failureOf(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM typed_errors WHERE id = 99")));

        assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
    }

    @Test
    void transactionalFetchOne_noRow_reachesCallerAsTheTypedResultNotFound() {
        var cause = failureOf(connector.transactional(tx -> tx.fetchOne(tx.dsl().resultQuery("SELECT name FROM typed_errors WHERE id = 99"))));

        assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
    }

    private static Promise<Record> killOwnBackend() {
        return connector.fetchOne(connector.dsl().resultQuery(KILL_OWN_BACKEND));
    }

    private static void run(String sql) {
        connector.execute(connector.dsl().query(sql)).await().unwrap();
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
