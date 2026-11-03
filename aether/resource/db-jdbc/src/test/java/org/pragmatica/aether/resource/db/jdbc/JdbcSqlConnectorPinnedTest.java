// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.Unit.unit;

/// FDB-1 (#2591): the pinned session of the Jdbc connector, against a LIVE PostgreSQL 16 through the production
/// factory wiring and the real pool. A session-scoped database lock lives and dies with the physical connection it was
/// taken on, so the properties pinned here are the ones the lock depends on:
/// every call and nested transaction runs on ONE backend; the connection is not lent to anyone else while pinned;
/// it returns to the pool only after the release step succeeded; and a failed release destroys it, taking a session
/// lock the failed unlock left behind with it. Self-skips when no Docker daemon is reachable, unless
/// `-Daether.requireDocker=true` makes that a failure.
@Timeout(value = 45, unit = TimeUnit.SECONDS)
class JdbcSqlConnectorPinnedTest {
    private static final RowMapper<Integer> PID = row -> row.getInt("pid");
    private static final RowMapper<Integer> COUNT = row -> row.getInt("n");
    private static final RowMapper<Unit> IGNORED = _ -> Result.unitResult();
    private static final String SELECT_PID = "SELECT pg_backend_pid() AS pid";
    private static final String TAKE_LOCK = "SELECT pg_advisory_lock(7001)";
    private static final String COUNT_LOCK = "SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND objid = 7001";
    private static final Duration NO_BOUND = Duration.ZERO;
    private static final Cause UNLOCK_FAILED = Causes.cause("unlock failed (injected by the test)");
    private static final Cause BODY_FAILED = Causes.cause("body failed (injected by the test)");
    private static final SqlConnector.TransactionCallback<Unit> RELEASE_OK = _ -> Promise.success(unit());
    private static final SqlConnector.TransactionCallback<Unit> RELEASE_FAILS = _ -> UNLOCK_FAILED.promise();
    private static PostgreSQLContainer<?> postgres;
    private final List<SqlConnector> opened = new ArrayList<>();

    private record Probe(int first, int competing, int second, int inTransaction) {}

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

    @AfterEach
    void stopConnectors() {
        opened.forEach(connector -> connector.stop().await());
        opened.clear();
    }

    private SqlConnector connector(int poolSize) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(poolSize).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName("pinned")
                                                     .withJdbcUrl(postgres.getJdbcUrl())
                                                     .withUsername(postgres.getUsername())
                                                     .withPassword(postgres.getPassword())
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();
        var connector = new JdbcSqlConnectorFactory().provision(config).await().unwrap();

        opened.add(connector);

        return connector;
    }

    private static Promise<Probe> probe(SqlConnector base, SqlConnector session) {
        return session.queryOne(SELECT_PID, PID)
                      .flatMap(first -> probeNext(base, session, first));
    }

    private static Promise<Probe> probeNext(SqlConnector base, SqlConnector session, int first) {
        return base.queryOne(SELECT_PID, PID)
                   .flatMap(competing -> probeThen(session, first, competing));
    }

    private static Promise<Probe> probeThen(SqlConnector session, int first, int competing) {
        return session.queryOne(SELECT_PID, PID)
                      .flatMap(second -> probeInTransaction(session, first, competing, second));
    }

    private static Promise<Probe> probeInTransaction(SqlConnector session, int first, int competing, int second) {
        return session.transactional(tx -> tx.queryOne(SELECT_PID, PID))
                      .map(inTransaction -> new Probe(first, competing, second, inTransaction));
    }

    @Test
    void pinned_runsEveryCallOnOneBackend_whileAnotherBorrowGetsADifferentOne() {
        var base = connector(2);

        var probe = base.pinned(NO_BOUND, session -> probe(base, session), RELEASE_OK).await().unwrap();

        assertThat(probe.second()).as("a second call on the session").isEqualTo(probe.first());
        assertThat(probe.inTransaction()).as("a nested transactional on the session").isEqualTo(probe.first());
        assertThat(probe.competing()).as("control: a pooled borrow made while pinned is a DIFFERENT backend")
                                     .isNotEqualTo(probe.first());
    }

    @Test
    void pinned_poolOfOne_competingBorrowWaitsUntilReleaseThenReusesTheSameBackend() throws Exception {
        var base = connector(1);
        var competing = new AtomicReference<Promise<Integer>>();
        var inside = new CountDownLatch(1);
        var gate = Promise.<Unit> promise();

        var pinned = base.pinned(NO_BOUND,
                                 session -> session.queryOne(SELECT_PID, PID)
                                                   .flatMap(pid -> holdWhileBorrowing(base, competing, inside, gate, pid)),
                                 RELEASE_OK);

        assertThat(inside.await(30, TimeUnit.SECONDS)).as("the body reached the point where it holds the session").isTrue();
        Thread.sleep(1000);
        assertThat(competing.get().isResolved()).as("the pinned connection is NOT lent to a competing borrow").isFalse();

        gate.succeed(unit());
        var sessionPid = pinned.await().unwrap();

        assertThat(competing.get().await().unwrap()).as("after the release succeeded the SAME backend went back to the pool")
                                                    .isEqualTo(sessionPid);
    }

    private static Promise<Integer> holdWhileBorrowing(SqlConnector base,
                                                       AtomicReference<Promise<Integer>> competing,
                                                       CountDownLatch inside,
                                                       Promise<Unit> gate,
                                                       int pid) {
        competing.set(base.queryOne(SELECT_PID, PID));
        inside.countDown();

        return gate.map(_ -> pid);
    }

    @Test
    void pinned_releaseFails_destroysTheConnection_andTheLockItLeftBehindDiesWithIt() throws Exception {
        var base = connector(1);

        var sessionPid = base.pinned(NO_BOUND,
                                     session -> session.queryOne(SELECT_PID, PID)
                                                       .flatMap(pid -> takeLock(session, pid)),
                                     RELEASE_FAILS)
                             .await()
                             .unwrap();

        assertThat(base.queryOne(SELECT_PID, PID).await().unwrap())
            .as("a failed release must NOT return the session to the pool: the next borrow is another backend")
            .isNotEqualTo(sessionPid);
        assertEventuallyZero(base, COUNT_LOCK, "the advisory lock the failed unlock left behind");
        assertEventuallyZero(base, "SELECT count(*) AS n FROM pg_stat_activity WHERE pid = " + sessionPid, "the destroyed backend");
    }

    private static Promise<Integer> takeLock(SqlConnector session, int pid) {
        return session.queryList(TAKE_LOCK, IGNORED).map(_ -> pid);
    }

    private static void assertEventuallyZero(SqlConnector base, String countSql, String what) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var seen = base.queryOne(countSql, COUNT).await().unwrap();

        while (seen != 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
            seen = base.queryOne(countSql, COUNT).await().unwrap();
        }

        assertThat(seen).as(what + " must be gone once the session is destroyed").isZero();
    }

    @Test
    void pinned_releaseSucceeds_theBackendIsReused_andTheLockWasReleasedByTheCallback() {
        var base = connector(1);

        var sessionPid = base.pinned(NO_BOUND,
                                     session -> session.queryOne(SELECT_PID, PID)
                                                       .flatMap(pid -> takeLock(session, pid)),
                                     session -> session.queryList("SELECT pg_advisory_unlock(7001)", IGNORED).mapToUnit())
                             .await()
                             .unwrap();

        assertThat(base.queryOne(SELECT_PID, PID).await().unwrap()).as("control: a clean release recycles the backend")
                                                                   .isEqualTo(sessionPid);
        assertThat(base.queryOne(COUNT_LOCK, COUNT).await().unwrap()).isZero();
    }

    @Test
    void pinned_bodyFails_stillRunsReleaseOnce_returnsTheBodyFailure_andFreesThePool() {
        var base = connector(1);
        var releases = new AtomicInteger();

        var result = base.<Integer> pinned(NO_BOUND,
                                           _ -> BODY_FAILED.promise(),
                                           session -> countedRelease(releases))
                         .await();

        assertThat(result.isFailure()).isTrue();
        result.onSuccess(_ -> {}).onFailure(cause -> assertThat(cause).isSameAs(BODY_FAILED));
        assertThat(releases.get()).isEqualTo(1);
        assertThat(base.queryOne(SELECT_PID, PID).await().isSuccess()).as("the pool slot was freed").isTrue();
    }

    private static Promise<Unit> countedRelease(AtomicInteger releases) {
        releases.incrementAndGet();

        return Promise.success(unit());
    }

    @Test
    void pinned_transactionalOnTheSession_commitsOnSuccess_rollsBackOnFailure_andKeepsTheSession() {
        var base = connector(1);

        var rows = base.pinned(NO_BOUND, JdbcSqlConnectorPinnedTest::transactionalWork, RELEASE_OK).await().unwrap();

        assertThat(rows).as("rows after: the committed one only, the rolled-back one absent").isEqualTo(1);
    }

    private static Promise<Integer> transactionalWork(SqlConnector session) {
        return ddl(session, "DROP TABLE IF EXISTS pinned_rows")
                  .flatMap(_ -> ddl(session, "CREATE TABLE pinned_rows(id INT PRIMARY KEY)"))
                      .flatMap(_ -> session.transactional(tx -> tx.update("INSERT INTO pinned_rows VALUES (1)")))
                      .flatMap(_ -> rolledBack(session))
                      .flatMap(_ -> session.queryOne("SELECT count(*) AS n FROM pinned_rows", COUNT));
    }

    private static Promise<Unit> ddl(SqlConnector session, String sql) {
        return session.update(sql).mapToUnit();
    }

    private static Promise<Unit> rolledBack(SqlConnector session) {
        return session.<Integer> transactional(tx -> insertThenFail(tx)).fold(_ -> Promise.success(unit()));
    }

    private static Promise<Integer> insertThenFail(SqlConnector tx) {
        return tx.update("INSERT INTO pinned_rows VALUES (2)").flatMap(_ -> BODY_FAILED.promise());
    }

}
