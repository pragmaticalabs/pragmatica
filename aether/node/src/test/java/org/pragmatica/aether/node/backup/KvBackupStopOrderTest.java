// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.BackupWarning.Code;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The order of the stop (#2924, v-2979 F3): the service is marked stopped before the repository is closed to git, so a git that the closed repository
/// refuses is the stop's own doing and never a `BACKUP_COMMIT_FAILED`. The interleaving is made deterministic: a flush is held past its leader check,
/// then released from inside the repository's close, i.e. exactly between the close and whatever the stop does after it.
class KvBackupStopOrderTest {
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(NODE_CODEC);
    private static final String LINEAGE = "01K4ZT9Q6W3X8Y2B7C5D1E0F9G";
    private static final String WORKER = "stop-order-worker";

    @TempDir
    Path temp;

    /// The registry the repository runs on, whose close lets the held flush go on and waits for it: the flush meets the closed gate while the stop is
    /// still inside its own close.
    private static final class ReleasingOnClose extends GitProcesses {
        private final CountDownLatch released;
        private final AtomicReference<Thread> worker;
        final AtomicInteger refused = new AtomicInteger();

        ReleasingOnClose(CountDownLatch released, AtomicReference<Thread> worker) {
            this.released = released;
            this.worker = worker;
        }

        @Override
        Result<Process> start(ProcessBuilder builder) {
            var result = super.start(builder);

            if (result.isFailure()) {
                refused.incrementAndGet();
            }

            return result;
        }

        @Override
        List<Process> close() {
            var inFlight = super.close();

            released.countDown();
            joinWorker();

            return inFlight;
        }

        private void joinWorker() {
            try {
                worker.get().join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void aGitRefusedByTheClosedRepository_isNotACommitFailure_whenTheStopIsUnderway() throws Exception {
        var released = new CountDownLatch(1);
        var inClock = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var processes = new ReleasingOnClose(released, worker);
        var repository = new GitBackupRepository(temp.resolve("local"), Option.none(), "backup", TimeSpan.timeSpan(30).seconds(), Map.of(), processes);
        var warnings = new CopyOnWriteArrayList<BackupWarning>();
        var tasks = new CopyOnWriteArrayList<Runnable>();
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        var service = KvBackupService.kvBackupService(store,
                                                      CODEC,
                                                      repository,
                                                      (task, delay) -> tasks.add(task),
                                                      heldOnTheWorker(inClock, released),
                                                      warnings::add,
                                                      KvBackupService.Timing.timing(500, 5_000, 1_000, 8_000, 3_000));

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                             ClusterIncarnationValue.clusterIncarnationValue(LINEAGE, 1, "01K4ZT9Q6W3X8Y2B7C5D1INST0")))),
                               store.committedRevision() + 1);
        service.onLeaderChange(LeaderNotification.leaderChange(Option.some(NodeId.nodeId("node-1").unwrap()), true));
        assertThat(tasks).as("control: the leadership reset and the flush tick are scheduled").hasSize(2);
        tasks.getFirst().run();
        worker.set(Thread.ofPlatform().name(WORKER).start(tasks.getLast()));
        assertThat(inClock.await(10, TimeUnit.SECONDS)).as("control: the flush is past its leader check, held").isTrue();

        service.stop();

        assertThat(processes.refused.get()).as("control: the held flush reached git and was refused by the closed repository").isGreaterThanOrEqualTo(1);
        assertThat(warnings).extracting(BackupWarning::code).as("the stop's own refusal is not a failing repository").doesNotContain(Code.BACKUP_COMMIT_FAILED);
    }

    /// The stop releases the worker exactly once and ends the leadership, so no later leader-gated flush is scheduled (v-2979b ks03, ks05).
    @Test
    void stop_releasesTheWorkerOnce_andEndsTheLeadership() {
        var releases = new AtomicInteger();
        var repository = new GitBackupRepository(temp.resolve("local"), Option.none(), "backup", TimeSpan.timeSpan(30).seconds(), Map.of(), new GitProcesses());
        var tasks = new CopyOnWriteArrayList<Runnable>();
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        var service = KvBackupService.kvBackupService(store,
                                                      CODEC,
                                                      repository,
                                                      (task, delay) -> tasks.add(task),
                                                      () -> 0L,
                                                      _ -> {},
                                                      KvBackupService.Timing.timing(500, 5_000, 1_000, 8_000, 3_000),
                                                      releases::incrementAndGet);

        service.onLeaderChange(LeaderNotification.leaderChange(Option.some(NodeId.nodeId("node-1").unwrap()), true));
        assertThat(service.isLeader()).as("control: the service leads before the stop").isTrue();

        service.stop();

        assertThat(releases.get()).as("the stop released the worker once").isOne();
        assertThat(service.isLeader()).as("the stop ended the leadership").isFalse();
    }

    /// A clock that holds the worker's read of the time until released, then reports a time far past any due time.
    private static LongSupplier heldOnTheWorker(CountDownLatch inClock, CountDownLatch released) {
        var held = new AtomicBoolean();

        return () -> isWorkerFirstRead(held) ? awaitRelease(inClock, released) : 0L;
    }

    private static boolean isWorkerFirstRead(AtomicBoolean held) {
        return WORKER.equals(Thread.currentThread().getName()) && held.compareAndSet(false, true);
    }

    private static long awaitRelease(CountDownLatch inClock, CountDownLatch released) {
        inClock.countDown();
        try {
            released.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 1_000_000L;
    }
}
