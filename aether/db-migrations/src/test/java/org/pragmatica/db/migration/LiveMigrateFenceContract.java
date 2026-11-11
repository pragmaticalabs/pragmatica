// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [LiveFenceContract] plus the end-to-end MIGRATE runs on an engine whose history tables can be bootstrapped:
/// run by [MigrationFenceOracleTest] only. SQL Server is excluded because its history bootstrap
/// (`CREATE TABLE IF NOT EXISTS`) is not valid T-SQL, so no SQL Server migration gets as far as its first script
/// (pre-existing, found by [MigrationFenceSqlServerTest]; see the PR's residuals).
abstract class LiveMigrateFenceContract extends LiveFenceContract {
    /// No fence outlives the run: after a success and after a failing script another session can take it.
    @Test
    void migrate_leavesNoFenceBehind_onSuccessAndOnFailure() throws Exception {
        freshSchema();
        var connector = connector(2);

        assertThat(migrate(connector, "node-a", script(1, "ok", APPLY_SCRIPT)).isSuccess()).isTrue();
        assertThat(canTakeAndDropFence()).as("fence free after a successful run").isTrue();

        assertThat(migrate(connector, "node-a", script(2, "bad", "INSERT INTO missing_table VALUES (1);\n")).isFailure()).isTrue();
        assertThat(canTakeAndDropFence()).as("fence free after a failing run").isTrue();
    }

    /// Two concurrent migrators on a fresh schema: the script is applied exactly once; the other runner is either
    /// fenced or, arriving after the first finished, skips what is applied. Never a double apply, never another failure.
    @Test
    void migrate_twoConcurrentRunners_applyTheScriptExactlyOnce() throws Exception {
        freshSchema();
        var first = connector(2);
        var second = connector(2);
        var start = new CountDownLatch(1);
        var script = script(1, "t3", APPLY_SCRIPT);
        var a = runners.submit(() -> awaitThenMigrate(start, first, "node-a", script));
        var b = runners.submit(() -> awaitThenMigrate(start, second, "node-b", script));

        start.countDown();

        var results = List.of(a.get(WAIT_MS, TimeUnit.MILLISECONDS), b.get(WAIT_MS, TimeUnit.MILLISECONDS));

        results.forEach(result -> result.onFailure(cause -> assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class)));
        assertThat(results.stream().filter(Result::isSuccess).count()).as("at least one runner completed: %s", results).isPositive();

        try (var probe = rawConnection()) {
            assertThat(count(probe, "entries")).as("the script ran once").isEqualTo(1);
            assertThat(count(probe, "aether_schema_history")).as("one history row").isEqualTo(1);
        }
    }

    /// The fence survives the runner's own commits (Oracle `release_on_commit => FALSE`, SQL Server `@LockOwner =
    /// 'Session'`): a runner parked inside its script, after the bootstrap and history commits, still excludes a
    /// competitor. The test parks it on a row lock it holds itself.
    @Test
    void migrate_fenceSurvivesTheRunnersOwnCommits_competitorIsFencedMidRun() throws Exception {
        freshSchema();

        var blocker = held(rawConnection());

        blocker.setAutoCommit(true);
        execute(blocker, "CREATE TABLE gate (v INT)");
        execute(blocker, "INSERT INTO gate VALUES (1)");
        blocker.setAutoCommit(false);
        execute(blocker, "UPDATE gate SET v = 1");

        var a = runners.submit(() -> migrate(connector(2), "node-a", script(1, "t4", "UPDATE gate SET v = 2;\n")));

        awaitBlocked(a);

        var competitor = migrate(connector(2), "node-b", script(1, "t4", "UPDATE gate SET v = 2;\n"));

        assertThat(causeOf(competitor)).as("a competitor mid-run").isInstanceOf(MigrationError.MigrationFencedInDatabase.class);

        blocker.rollback();

        var finished = a.get(WAIT_MS, TimeUnit.MILLISECONDS);

        assertThat(finished.isSuccess()).as("the parked runner completes: %s", finished).isTrue();
        assertThat(canTakeAndDropFence()).as("fence free after the parked run").isTrue();
    }

    private boolean canTakeAndDropFence() throws SQLException {
        try (var session = rawConnection()) {
            return tryHoldFence(session);
        }
    }

    private void awaitBlocked(Future<Result<SchemaResult>> runner) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (blockedSessions() > 0) {
                return;
            }

            Thread.sleep(100);
        }

        throw new AssertionError("the runner never blocked on the test's row lock; runner: " + (runner.isDone()
                                                                                              ? runner.get()
                                                                                              : "still running"));
    }
}
