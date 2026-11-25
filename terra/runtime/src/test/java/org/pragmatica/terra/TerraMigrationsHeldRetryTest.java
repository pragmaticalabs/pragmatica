// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.db.migration.MigrationError;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.db.migration.SchemaMigrations;
import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

/// #2787: Terra reuses the A6 ruling of the migration fence (FDB-2a, #2591). A run that meets the fence held by another
/// database session (another Terra instance) is no failure: it waits the A6 backoff and re-runs, for as long as the
/// fence is held. Every other cause ends the run as before. The engine is scripted here; the live two-instance proof is
/// [TerraMigrationsFenceTest].
class TerraMigrationsHeldRetryTest {
    private static final String DATASOURCE = "database";
    private static final Cause HELD = MigrationError.MigrationFencedInDatabase.migrationFencedInDatabase(DATASOURCE, "aether-mig:terra (pid 42)");
    private static final SchemaResult APPLIED = SchemaResult.schemaResult(1, 1, 3);
    private static final long BACKOFF_MS = 300;
    private static final TimeSpan AWAIT = timeSpan(10).seconds();

    @Test
    void apply_fenceHeldTwice_waitsAndReRunsUntilTheFenceIsFree() {
        var engine = new ScriptedEngine(HELD.result(), HELD.result(), Result.success(APPLIED));
        var asked = new ArrayList<Integer>();

        var reports = run(engine, asked).apply().await(AWAIT);

        assertThat(reports.unwrap()).containsExactly(new TerraMigrations.Report(DATASOURCE, APPLIED));
        assertThat(engine.calls()).as("one run per Held, then the run that holds the fence").isEqualTo(3);
        assertThat(asked).as("the backoff is asked for the consecutive-Held count").containsExactly(1, 2);
    }

    @Test
    void apply_otherFailure_endsTheRunWithoutRetry() {
        var engine = new ScriptedEngine(cause("checksum mismatch").result(), Result.success(APPLIED));
        var asked = new ArrayList<Integer>();

        var reports = run(engine, asked).apply().await(AWAIT);

        assertThat(reports.isFailure()).isTrue();
        assertThat(reports.fold(Cause::message, _ -> "")).contains("checksum mismatch");
        assertThat(engine.calls()).isEqualTo(1);
        assertThat(asked).isEmpty();
    }

    @Test
    void apply_failureAfterHeld_endsTheRunWithThatFailure() {
        var engine = new ScriptedEngine(HELD.result(), cause("ownership conflict").result(), Result.success(APPLIED));
        var asked = new ArrayList<Integer>();

        var reports = run(engine, asked).apply().await(AWAIT);

        assertThat(reports.fold(Cause::message, _ -> "")).contains("ownership conflict");
        assertThat(engine.calls()).isEqualTo(2);
        assertThat(asked).containsExactly(1);
    }

    @Test
    void apply_thirdConsecutiveHeld_isLoggedAsAWarningNamingTheHolder() {
        var engine = new ScriptedEngine(HELD.result(), HELD.result(), HELD.result(), Result.success(APPLIED));

        try (var capture = LogCapture.capture(TerraMigrations.class, Level.INFO, false)) {
            run(engine, new ArrayList<>()).apply().await(AWAIT).unwrap();

            assertThat(capture.messages(Level.INFO)).as("the first and second Held").hasSize(2);
            assertThat(capture.messages(Level.WARN)).as("from the third consecutive Held on").hasSize(1);
            assertThat(capture.messages(Level.WARN).getFirst()).contains("3 consecutive times").contains("aether-mig:terra (pid 42)");
        }
    }

    @Test
    void apply_fenceHeld_waitsAtLeastTheBackoffBeforeTheNextRun() {
        var engine = new ScriptedEngine(HELD.result(), Result.success(APPLIED));

        run(engine, _ -> timeSpan(BACKOFF_MS).millis()).apply().await(AWAIT).unwrap();

        assertThat(engine.nanosBetweenCalls()).as("a timer never fires early, so the gap is a lower bound, not a guess")
                                              .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(BACKOFF_MS));
    }

    @Test
    void terraMigrations_productionRun_firstRetryWaitsFifteenSeconds() {
        var scripts = Map.of(DATASOURCE, List.of(MigrationScript.migrationScript("V001__one.sql", "SELECT 1", 1)));

        var run = (MigrationRun) TerraMigrations.terraMigrations("g:app", scripts, new StubProvider()).unwrap();

        assertThat(run.heldBackoff().apply(1)).as("the wiring carries the A6 schedule, not a stub").isEqualTo(timeSpan(15).seconds());
        assertThat(run.heldBackoff().apply(2)).isEqualTo(timeSpan(45).seconds());
    }

    @Test
    void heldBackoff_followsTheA6Schedule() {
        assertThat(TerraMigrations.heldBackoff(1)).isEqualTo(timeSpan(15).seconds());
        assertThat(TerraMigrations.heldBackoff(2)).isEqualTo(timeSpan(45).seconds());
        assertThat(TerraMigrations.heldBackoff(3)).isEqualTo(timeSpan(45).seconds());
        assertThat(TerraMigrations.heldBackoff(Integer.MAX_VALUE)).as("the exponent is clamped before it can overflow")
                                                                  .isEqualTo(timeSpan(45).seconds());
    }

    private static MigrationRun run(SchemaMigrations engine, List<Integer> asked) {
        return run(engine, held -> recordAsk(asked, held));
    }

    private static MigrationRun run(SchemaMigrations engine, Fn1<TimeSpan, Integer> heldBackoff) {
        var scripts = List.of(MigrationScript.migrationScript("V001__one.sql", "SELECT 1", 1));

        return MigrationRun.migrationRun("g:app",
                                         List.of(MigrationTask.migrationTask(DATASOURCE, scripts)),
                                         new StubProvider(),
                                         engine,
                                         heldBackoff);
    }

    private static TimeSpan recordAsk(List<Integer> asked, int held) {
        asked.add(held);
        return timeSpan(1).millis();
    }

    /// Answers each `migrate` with the next scripted outcome; the connector is never used.
    private static final class ScriptedEngine implements SchemaMigrations {
        private final Deque<Result<SchemaResult>> outcomes;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Long> callNanos = new CopyOnWriteArrayList<>();

        @SafeVarargs
        ScriptedEngine(Result<SchemaResult>... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        int calls() {
            return calls.get();
        }

        long nanosBetweenCalls() {
            return callNanos.get(1) - callNanos.get(0);
        }

        @Override
        public Promise<SchemaResult> migrate(String datasource, List<MigrationScript> scripts, SqlConnector connector, String nodeId, String owner) {
            calls.incrementAndGet();
            callNanos.add(System.nanoTime());
            return outcomes.removeFirst().async();
        }

        @Override
        public Promise<SchemaResult> undo(String datasource, int targetVersion, List<MigrationScript> scripts, SqlConnector connector, String nodeId,
                                          String owner) {
            return cause("unused").promise();
        }

        @Override
        public Promise<SchemaResult> baseline(String datasource, int baselineVersion, List<MigrationScript> scripts, SqlConnector connector,
                                              String nodeId, String owner) {
            return cause("unused").promise();
        }
    }

    private static final class StubProvider implements DatasourceConnectionProvider {
        private static final SqlConnector UNUSED = (SqlConnector) Proxy.newProxyInstance(SqlConnector.class.getClassLoader(),
                                                                                       new Class<?>[] {SqlConnector.class},
                                                                                       (_, method, _) -> {
                                                                                           throw new AssertionError("connector used: " + method);
                                                                                       });

        @Override
        public Promise<SqlConnector> connector(String datasourceName) {
            return Promise.success(UNUSED);
        }

        @Override
        public Promise<Unit> release(String datasourceName) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> releaseAll() {
            return Promise.unitPromise();
        }
    }
}
