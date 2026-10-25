// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

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
import org.postgresql.ds.PGSimpleDataSource;

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

/// #2408: the typed cause a database failure carries reaches the caller of the JDBC connector, against a LIVE
/// PostgreSQL 16 through the real pgjdbc driver. Before the fix a mapper failure (and `ResultNotFound`) was thrown
/// through `Result.unwrap()` and surfaced as `DatabaseFailure(IllegalStateException)`; a unique violation lost its
/// SQLSTATE. Driver failures now keep their SQLSTATE and transience, so `RetryOn.TRANSIENT` retries a lost connection
/// and leaves a constraint violation alone.
/// Self-skips when no Docker daemon is reachable.
class JdbcSqlConnectorTypedErrorsTest {
    private static final String KILL_OWN_BACKEND = "SELECT pg_terminate_backend(pg_backend_pid())";
    private static final String RAISE_SERIALIZATION_FAILURE = "DO $$ BEGIN RAISE EXCEPTION 'serialization failure' USING ERRCODE = '40001'; END $$";
    private static final String INSERT = "INSERT INTO typed_errors(id, name) VALUES (?, ?)";
    private static PostgreSQLContainer<?> postgres;
    private static SqlConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        var dataSource = new PGSimpleDataSource();

        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var config = databaseConnectorConfigBuilder().withName("typed-errors")
                                                     .withJdbcUrl(postgres.getJdbcUrl())
                                                     .build()
                                                     .unwrap();

        connector = JdbcSqlConnector.jdbcSqlConnector(config, dataSource);
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

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConstraintViolation.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("23505"));
        assertThat(cause.isTransient()).isFalse();
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
    void queryOne_lostConnection_isReportedTransient() {
        var cause = failureOf(connector.queryOne(KILL_OWN_BACKEND, row -> row.getBoolean("pg_terminate_backend")));

        assertThat(cause.isTransient()).as("a terminated backend is transient: %s", cause.message()).isTrue();
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("57P01"));
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
    void queryOptional_noRow_isEmpty() {
        var value = connector.queryOptional("SELECT name FROM typed_errors WHERE id = ?", row -> row.getString("name"), 99).await().unwrap();

        assertThat(value.isPresent()).isFalse();
    }

    @Test
    void transactional_success_commitsAndReturnsTheCallbacksValue() {
        var updated = connector.transactional(tx -> tx.update("UPDATE typed_errors SET name = 'committed' WHERE id = 1")).await().unwrap();
        var stored = connector.queryOne("SELECT name FROM typed_errors WHERE id = ?", row -> row.getString("name"), 1).await().unwrap();

        assertThat(updated).isEqualTo(1);
        assertThat(stored).as("committed by the transaction").isEqualTo("committed");
    }

    @Test
    void queryOne_severalRows_reachesCallerAsTheTypedMultipleResults() {
        connector.update(INSERT, 2, "two").await().unwrap();

        var cause = failureOf(connector.queryOne("SELECT name FROM typed_errors", row -> row.getString("name")));

        assertThat(cause).isEqualTo(DatabaseConnectorError.multipleResults(2));
    }

    @Test
    void queryOptional_mapperFailure_isNotSwallowedAsAnEmptyResult() {
        var cause = failureOf(connector.queryOptional("SELECT name FROM typed_errors WHERE id = ?", UNDECODABLE, 1));

        assertRowDecode(cause);
    }

    @Test
    void transactional_rowDecodeFailureInCallback_reachesCallerUnchanged() {
        var decodeFailure = new RowDecodeError.RowDecode("name", Causes.cause("corrupt value"));

        var cause = failureOf(connector.transactional(tx -> decodeFailure.<String> promise()));

        assertThat(cause).as("not replaced by a connection failure that a retry would re-drive").isSameAs(decodeFailure);
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
    void transactional_refusedConnection_isTransientAndKeepsTheSqlState() {
        var dataSource = new PGSimpleDataSource();

        dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/none");
        var unreachable = JdbcSqlConnector.jdbcSqlConnector(connector.config(), dataSource);

        var cause = failureOf(unreachable.transactional(tx -> tx.update("SELECT 1")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.ConnectionFailed.class);
        assertThat(((DatabaseConnectorError) cause).sqlState()).isEqualTo(some("08001"));
        assertThat(cause.isTransient()).isTrue();
    }

    @Test
    void queryOne_noRow_reachesCallerAsTheTypedResultNotFound() {
        var cause = failureOf(connector.queryOne("SELECT name FROM typed_errors WHERE id = ?", row -> row.getString("name"), 99));

        assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
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
