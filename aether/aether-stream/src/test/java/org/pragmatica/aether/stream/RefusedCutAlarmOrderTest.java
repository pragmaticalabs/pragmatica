// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2004 for `StreamPartitionManager.refusedCuts`: a refused cut is recorded and raised on the repair path (a backfill thread); it is
/// closed from the reconcile tick that releases the replica or from the thread that destroys the stream, so the pair races in production.
/// The refusal is held between its record and its raise (a hook, inside the monitor) while the stream is destroyed on another thread: the
/// destroy must be observed BLOCKED on the monitor, and the delivered order is `[refused, resumed]`, never `[resumed, refused]`. A stress
/// over many streams adds a thread that keeps appending (taking the ring's append lock) and a deadlock detector: the monitor is a leaf.
class RefusedCutAlarmOrderTest {
    private static final int PARTITION = 0;
    private static final long WAIT_SECONDS = 10L;
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 3L);

    @TempDir
    Path walDir;

    private final List<OperatorWarning> delivered = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warning -> {
        if (warning.code() == OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_REFUSED || warning.code() == OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_RESUMED) {
            delivered.add(warning);
        }
    });

    private StreamPartitionManager quarantinedWithObstacle(String name) throws Exception {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        manager.operatorWarnings(sink);

        return withQuarantinedStream(manager, name);
    }

    private StreamPartitionManager withQuarantinedStream(StreamPartitionManager manager, String name) throws Exception {
        manager.createStream(StreamConfig.streamConfig(name)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 8; i++) {
            manager.appendRecovered(name, PARTITION, i, ("r" + i).getBytes(UTF_8), 1000L + i, EPOCH).unwrap();
        }
        manager.syncReplicated(name, PARTITION).await();
        manager.appendRecovered(name, PARTITION, 3, "different".getBytes(UTF_8), 1003L, EPOCH);
        blockSegmentsOf(name);

        return manager;
    }

    /// Puts a directory where the segment's temporary file goes, so the preserve fails with the platform's own error.
    private void blockSegmentsOf(String name) throws Exception {
        try (var files = Files.walk(walDir)) {
            for (var wal : files.filter(Files::isRegularFile).filter(file -> file.toString().contains(name) && file.getFileName().toString().endsWith(".wal")).toList()) {
                Files.createDirectories(RecoverySegment.temporaryFor(wal));
            }
        }
    }

    @Test
    void refusal_aDestroyDuringTheRaise_deliversRefusedThenResumed() throws Exception {
        var one = quarantinedWithObstacle("single");
        var destroying = new AtomicReference<Thread>();

        one.refusalWindowHook(() -> {
            var thread = new Thread(() -> one.destroyStream("single"), "destroying");

            destroying.set(thread);
            thread.start();
            awaitBlockedOrDone(thread);
            assertThat(thread.getState()).as("the destroy waits for the monitor").isEqualTo(Thread.State.BLOCKED);
        });

        assertThat(one.repairDivergence("single", PARTITION, _ -> true).isFailure()).isTrue();
        destroying.get().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitQuiet();

        assertThat(delivered).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_REFUSED, OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_RESUMED);
        one.close();
    }

    /// The third pair: a repair that goes through (the obstacle is gone) on another thread while the refusal is being raised. Its `resumed`
    /// waits for the monitor: `[refused, resumed]`. Mutation: `cutResumed` without the monitor turns this red.
    @Test
    void refusal_aCutThatGoesThroughDuringTheRaise_deliversRefusedThenResumed() throws Exception {
        var one = quarantinedWithObstacle("single");
        var repairing = new AtomicReference<Thread>();

        one.refusalWindowHook(() -> {
            try (var files = Files.walk(walDir)) {
                for (var obstacle : files.filter(Files::isDirectory).filter(dir -> dir.getFileName().toString().endsWith(".recovery.tmp")).toList()) {
                    Files.delete(obstacle);
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }

            var thread = new Thread(() -> one.repairDivergence("single", PARTITION, _ -> true), "repairing");

            repairing.set(thread);
            thread.start();
            awaitBlockedOrDone(thread);
            assertThat(thread.getState()).as("the resuming cut waits for the monitor").isEqualTo(Thread.State.BLOCKED);
        });

        assertThat(one.repairDivergence("single", PARTITION, _ -> true).isFailure()).isTrue();
        repairing.get().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitQuiet();

        assertThat(delivered).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_REFUSED, OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_RESUMED);
        one.close();
    }

    @Test
    void refusals_racingDestroys_neverDeadlock_andNeverResumeBeforeRefusing() throws Exception {
        var done = new AtomicBoolean();
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        manager.operatorWarnings(sink);
        for (var i = 0; i < 40; i++) {
            withQuarantinedStream(manager, "s" + i);
        }

        var repairer = new Thread(() -> {
            for (var i = 0; i < 40; i++) {
                manager.repairDivergence("s" + i, PARTITION, _ -> true);
            }
        }, "repairer");
        var destroyer = new Thread(() -> {
            for (var i = 0; i < 40; i++) {
                manager.destroyStream("s" + i);
            }
            done.set(true);
        }, "destroyer");
        var appender = new Thread(() -> {
            var offset = 100;

            while (!done.get()) {
                for (var i = 0; i < 40 && !done.get(); i++) {
                    manager.appendRecovered("s" + i, PARTITION, offset++, "x".getBytes(UTF_8), 2000L, EPOCH);
                }
            }
        }, "appender");

        for (var thread : List.of(repairer, destroyer, appender)) {
            thread.setDaemon(true);
            thread.start();
        }

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

        while ((repairer.isAlive() || destroyer.isAlive()) && System.nanoTime() < deadline) {
            assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).as("no deadlocked thread while racing").isNull();
            Thread.onSpinWait();
        }

        done.set(true);
        assertThat(repairer.isAlive() || destroyer.isAlive()).as("both finished inside the bound").isFalse();
        assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).isNull();
        awaitQuiet();

        var open = new java.util.HashMap<String, Integer>();

        for (var warning : delivered) {
            var now = open.merge(warning.subject(), warning.code() == OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_REFUSED ? 1 : -1, Integer::sum);

            assertThat(now).as("a recovery for %s was delivered with no open warning", warning.subject()).isGreaterThanOrEqualTo(0);
        }

        assertThat(delivered.stream().filter(w -> w.code() == OperatorWarningCode.STREAM_DIVERGENT_TAIL_CUT_REFUSED).count()).as("the race raised refusals").isPositive();
    }

    private static void awaitBlockedOrDone(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private void awaitQuiet() throws InterruptedException {
        var last = -1;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS * 3);

        while (delivered.size() != last && System.nanoTime() < deadline) {
            last = delivered.size();
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }
}
