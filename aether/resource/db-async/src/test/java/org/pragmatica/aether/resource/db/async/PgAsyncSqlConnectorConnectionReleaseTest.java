// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2640: `transactional`, `batch` and `subscribe` borrow a pooled connection; every exit path must hand it back. The pool
/// here holds TWO connections and each test runs far more operations than that, so a connection that is never returned
/// starves the third borrower: its promise never resolves and the bounded await reports a timeout. The assertion is
/// therefore "every one of the operations completed", with the outcome of each (success, the callback's own cause, the
/// database's refusal) checked alongside. Self-skips when no Docker daemon is reachable, unless
/// `-Daether.requireDocker=true`.
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PgAsyncSqlConnectorConnectionReleaseTest {
    private static final int POOL_SIZE = 2;
    private static final int OPERATIONS = 8;
    private static final RowMapper<Integer> COUNT = row -> row.getInt("n");
    private static final Cause BODY_FAILED = Causes.cause("body failed (injected by the test)");
    private static final String IDLE_IN_TRANSACTION = "SELECT count(*) AS n FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'";
    private static final String INSERT_UNIQUE = "INSERT INTO release_probe(id) VALUES (?)";
    private static PostgreSQLContainer<?> postgres;
    private AsyncSqlConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /// A pool per test: a leak in one test must starve only that test's own pool, not its successors'.
    @BeforeEach
    void freshPoolAndTables() {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(POOL_SIZE).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName("release")
                                                     .withAsyncUrl("postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName())
                                                     .withUsername(postgres.getUsername())
                                                     .withPassword(postgres.getPassword())
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();

        connector = (AsyncSqlConnector) new AsyncSqlConnectorFactory().provision(config).await().unwrap();
        connector.update("DROP TABLE IF EXISTS release_probe").await(timeSpan(10).seconds()).unwrap();
        connector.update("CREATE TABLE release_probe(id INT PRIMARY KEY)").await(timeSpan(10).seconds()).unwrap();
        connector.update("DROP TABLE IF EXISTS release_deferred").await(timeSpan(10).seconds()).unwrap();
        connector.update("CREATE TABLE release_deferred(id INT, CONSTRAINT release_deferred_uq UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)")
                 .await(timeSpan(10).seconds())
                 .unwrap();
    }

    /// The stop is bounded and its outcome is not the test's verdict: `PgConnectionPool.close` only shuts down idle connections.
    @AfterEach
    void abandonPool() {
        connector.stop().await(timeSpan(2).seconds());
    }

    private static <T> List<Result<T>> runAll(Supplier<Promise<T>> operation) {
        var results = new ArrayList<Result<T>>();

        for (int i = 0; i < OPERATIONS; i++) {
            results.add(operation.get().await(timeSpan(5).seconds()));
        }

        return results;
    }

    private static <T> void assertAllSucceeded(List<Result<T>> results) {
        assertThat(results).hasSize(OPERATIONS).allSatisfy(result -> assertThat(result.isSuccess()).as("outcome: %s", result).isTrue());
    }

    private static <T> void assertAllFailedWith(List<Result<T>> results, String message) {
        assertThat(results).hasSize(OPERATIONS)
                           .allSatisfy(result -> assertThat(result.isFailure()).as("outcome: %s", result).isTrue())
                           .allSatisfy(result -> result.onFailure(cause -> assertThat(cause.message()).contains(message)));
    }

    private void assertPoolStillServes() {
        var count = connector.queryOne("SELECT 1 AS n", COUNT).await(timeSpan(5).seconds());

        assertThat(count.isSuccess()).as("a plain query after the run: %s", count).isTrue();
    }

    @Test
    void transactional_manyMoreThanThePool_successfulCallbacksAllReturnTheirConnection() {
        var results = runAll(() -> connector.transactional(tx -> tx.queryOne("SELECT 1 AS n", COUNT)));

        assertAllSucceeded(results);
        assertPoolStillServes();
    }

    @Test
    void transactional_failingCallback_returnsTheConnection_andKeepsTheCallbacksCause() {
        var results = runAll(() -> connector.<Integer>transactional(_ -> BODY_FAILED.promise()));

        assertAllFailedWith(results, BODY_FAILED.message());
        assertPoolStillServes();
    }

    @Test
    void transactional_throwingCallback_returnsTheConnection_andRollsBack() {
        var results = runAll(() -> connector.<Integer>transactional(PgAsyncSqlConnectorConnectionReleaseTest::throwing));

        assertAllFailedWith(results, "injected throw");
        assertPoolStillServes();
        assertNoBackendIdleInTransaction();
    }

    private static Promise<Integer> throwing(SqlConnector tx) {
        throw new IllegalStateException("injected throw");
    }

    /// Asked of the unpooled `pinned` session: a pooled connection would be the very one that may be sitting in the transaction.
    private void assertNoBackendIdleInTransaction() {
        var count = connector.pinned(Duration.ZERO, session -> session.queryOne(IDLE_IN_TRANSACTION, COUNT), _ -> Promise.unitPromise())
                             .await(timeSpan(10).seconds());

        assertThat(count.unwrap()).as("backends left inside the abandoned transaction").isZero();
    }

    @Test
    void transactional_commitFails_returnsTheConnection_andReportsTheDatabasesCause() {
        var results = runAll(() -> connector.transactional(tx -> tx.update("INSERT INTO release_deferred(id) VALUES (1)")
                                                                   .flatMap(_ -> tx.update("INSERT INTO release_deferred(id) VALUES (1)"))));

        assertAllFailedWith(results, "release_deferred_uq");
        assertPoolStillServes();
    }

    @Test
    void transactional_rolledBackWork_isNotVisibleToTheNextBorrower() {
        var results = runAll(() -> connector.<Integer>transactional(tx -> tx.update(INSERT_UNIQUE, 1)
                                                                          .flatMap(_ -> BODY_FAILED.<Integer>promise())));

        assertAllFailedWith(results, BODY_FAILED.message());
        assertThat(connector.queryOne("SELECT count(*) AS n FROM release_probe", COUNT).await(timeSpan(5).seconds()).unwrap()).isZero();
    }

    @Test
    void batch_manyMoreThanThePool_successfulBatchesAllReturnTheirConnection() {
        var counter = new int[]{0};
        var results = runAll(() -> connector.batch(INSERT_UNIQUE, List.of(new Object[]{++counter[0] * 10}, new Object[]{counter[0] * 10 + 1})));

        assertAllSucceeded(results);
        assertThat(connector.queryOne("SELECT count(*) AS n FROM release_probe", COUNT).await(timeSpan(5).seconds()).unwrap()).isEqualTo(OPERATIONS * 2);
    }

    @Test
    void batch_failingStatement_returnsTheConnection_andReportsTheDatabasesCause() {
        var results = runAll(() -> connector.batch(INSERT_UNIQUE, List.of(new Object[]{1}, new Object[]{1})));

        assertAllFailedWith(results, "duplicate key");
        assertPoolStillServes();
        assertThat(connector.queryOne("SELECT count(*) AS n FROM release_probe", COUNT).await(timeSpan(5).seconds()).unwrap()).isZero();
    }

    @Test
    void subscribe_rejectedChannel_returnsTheConnection() {
        var results = runAll(() -> connector.subscribe("not a channel!", _ -> {}));

        assertThat(results).hasSize(OPERATIONS).allSatisfy(result -> assertThat(result.isFailure()).as("outcome: %s", result).isTrue());
        assertPoolStillServes();
    }

    @Test
    void subscribe_unlisten_returnsTheConnection() {
        var results = runAll(() -> connector.subscribe("release_channel", _ -> {}).flatMap(listening -> listening.unlisten()));

        assertAllSucceeded(results);
        assertPoolStillServes();
    }

    /// A second `unlisten` must not release the connection a second time: the pool would then hold it twice and two
    /// borrowers would share one socket (protocol interleave).
    @Test
    void subscribe_unlistenTwice_secondIsNoOp_andConcurrentBorrowersGetDistinctConnections() {
        var listening = connector.subscribe("release_channel", _ -> {}).await(timeSpan(5).seconds()).unwrap();

        assertThat(listening.unlisten().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(listening.unlisten().await(timeSpan(5).seconds()).isSuccess()).as("second unlisten is a no-op success").isTrue();

        var first = connector.transactional(SLOW_PID);
        var second = connector.transactional(SLOW_PID);
        var pidA = first.await(timeSpan(10).seconds());
        var pidB = second.await(timeSpan(10).seconds());

        assertThat(pidA.isSuccess()).as("first: %s", pidA).isTrue();
        assertThat(pidB.isSuccess()).as("second: %s", pidB).isTrue();
        assertThat(pidA.unwrap()).isNotEqualTo(pidB.unwrap());
    }

    private static final SqlConnector.TransactionCallback<Integer> SLOW_PID = tx -> tx.queryOne("SELECT pg_sleep(0.5), 1 AS n", COUNT)
                                                                          .flatMap(_ -> tx.queryOne("SELECT pg_backend_pid() AS n", COUNT));

    /// A failed `unlisten` (dead backend) must free the pool slot: with a pool of two, two such failures must leave the
    /// pool able to serve a plain query.
    @Test
    void subscribe_failedUnlistens_discardTheConnections_andFreeThePoolSlots() throws InterruptedException {
        var one = connector.subscribe("release_channel_a", _ -> {}).await(timeSpan(5).seconds()).unwrap();
        var two = connector.subscribe("release_channel_b", _ -> {}).await(timeSpan(5).seconds()).unwrap();

        killOtherBackends();
        Thread.sleep(1000);

        assertThat(one.unlisten().await(timeSpan(5).seconds()).isFailure()).as("unlisten on a dead backend fails").isTrue();
        assertThat(two.unlisten().await(timeSpan(5).seconds()).isFailure()).as("unlisten on a dead backend fails").isTrue();
        assertPoolStillServes();
    }

    private void killOtherBackends() {
        connector.pinned(Duration.ZERO,
                         session -> session.queryList("SELECT pg_terminate_backend(pid)::int AS n FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()",
                                                      COUNT),
                         _ -> Promise.unitPromise())
                 .await(timeSpan(10).seconds())
                 .unwrap();
    }

    private static final String OTHER_BACKENDS = "SELECT count(*) AS n FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()";

    /// Evaluating the arguments after the connection was borrowed can throw before any promise exists; the connection
    /// must still go back.
    @Test
    void batch_throwingBeforeTheStatementRuns_returnsTheConnection() {
        var results = runAll(() -> connector.batch(null, List.<Object[]>of(new Object[]{1})));

        assertThat(results).hasSize(OPERATIONS).allSatisfy(result -> assertThat(result.isFailure()).as("outcome: %s", result).isTrue());
        assertPoolStillServes();
    }

    /// The backend dies inside the callback, so the rollback fails: the caller must still see the callback's cause.
    @Test
    void transactional_failingRollback_doesNotReplaceTheCallbacksCause() {
        var result = connector.<Integer>transactional(tx -> tx.queryOne("SELECT pg_backend_pid() AS n", COUNT)
                                                              .flatMap(this::terminateBackend)
                                                              .flatMap(_ -> tx.queryOne("SELECT 1 AS n", COUNT))
                                                              .fold(_ -> BODY_FAILED.<Integer>promise()))
                              .await(timeSpan(10).seconds());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).isEqualTo(BODY_FAILED.message()));
        assertPoolStillServes();
    }

    private Promise<Integer> terminateBackend(int pid) {
        return connector.pinned(Duration.ZERO,
                                session -> session.queryOne("SELECT pg_terminate_backend(" + pid + ")::int AS n", COUNT),
                                _ -> Promise.unitPromise());
    }

    /// The pool is closed by `stop()` and would keep a released connection open for good: a later `unlisten` must close it.
    @Test
    void subscribe_unlistenAfterStop_succeeds_andLeavesNoBackendBehind() throws InterruptedException {
        var listening = connector.subscribe("release_channel", _ -> {}).await(timeSpan(5).seconds()).unwrap();

        connector.stop().await(timeSpan(5).seconds());

        assertThat(listening.unlisten().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        Thread.sleep(1000);

        var others = connector.pinned(Duration.ZERO, session -> session.queryOne(OTHER_BACKENDS, COUNT), _ -> Promise.unitPromise())
                              .await(timeSpan(10).seconds());

        assertThat(others.unwrap()).as("backends left open after stop and unlisten").isZero();
    }
}
