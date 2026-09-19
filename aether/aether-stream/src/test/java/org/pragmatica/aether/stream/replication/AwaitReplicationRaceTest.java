// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;


/// #1259 / #1260: the pending-ack machinery of [DefaultReplicationManager#awaitReplication].
///
/// Every interleaving here is forced through the `betweenSteps` seam (it runs right after the await
/// takes its registry snapshot), and every timer goes through an injected scheduler that NEVER fires on
/// its own — so nothing depends on sleeps or on the 5 s ack timeout, and "the await resolved" means an
/// ack resolved it, never the clock.
class AwaitReplicationRaceTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId REPLICA = new NodeId("replica-r");
    private static final String STREAM = "orders";
    private static final int P1 = 1;
    private static final int P2 = 2;
    private static final long OFFSET = 5L;

    private ScheduledExecutorService neverFires;
    private List<ScheduledFuture<?>> timers;
    private ReplicaRegistry registry;

    @BeforeEach
    void setUp() {
        neverFires = Executors.newSingleThreadScheduledExecutor();
        timers = new CopyOnWriteArrayList<>();
        registry = replicaRegistry();
        registry.registerReplica(STREAM, P1, OWNER);
        registry.registerReplica(STREAM, P1, REPLICA);
    }

    @AfterEach
    void tearDown() {
        neverFires.shutdownNow();
    }

    @Nested
    class LostAck {
        /// The ticket's interleaving: the await snapshots the registry (REPLICA below 5), the ack for 5
        /// lands and finds no registered waiter, then the await registers seeded from the stale snapshot.
        /// Before the fix nothing re-read the registry, so the await hung until the 5 s timeout and a
        /// replicated write was reported failed.
        @Test
        void awaitReplication_resolves_whenAckLandsBetweenSnapshotAndRegistration() throws Exception {
            var inSeam = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var manager = manager(() -> pauseInSeam(inSeam, release));
            var awaited = new AtomicReference<Promise<Unit>>();
            var waiter = Thread.ofPlatform().start(() -> awaited.set(manager.awaitReplication(STREAM, P1, OFFSET, 1)));

            assertThat(inSeam.await(10, TimeUnit.SECONDS)).as("await reached the seam").isTrue();
            manager.handleAck(replicateAck(REPLICA, STREAM, P1, OFFSET));
            release.countDown();
            waiter.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(awaited.get().isResolved()).as("resolved by the ack, not by a timer (timers never fire here)")
                                                  .isTrue();
            assertThat(awaited.get().await().isSuccess()).isTrue();
        }

        /// Side finding: `put` let a second await on the same `(stream, partition, offset)` overwrite the
        /// first, which then could never resolve.
        @Test
        void awaitReplication_resolvesBoth_whenTwoAwaitsShareOneKey() {
            var manager = manager(() -> {});

            var first = manager.awaitReplication(STREAM, P1, OFFSET, 1);
            var second = manager.awaitReplication(STREAM, P1, OFFSET, 1);
            manager.handleAck(replicateAck(REPLICA, STREAM, P1, OFFSET));

            assertThat(first.isResolved()).as("first waiter on the key").isTrue();
            assertThat(second.isResolved()).as("second waiter on the key").isTrue();
            assertThat(first.await().isSuccess()).isTrue();
            assertThat(second.await().isSuccess()).isTrue();
        }

        /// The timeout still resolves an unacked await, and exactly once.
        @Test
        void awaitReplication_resolvesReplicationTimeout_whenTimerFiresWithoutAck() {
            var fired = new AtomicReference<Runnable>();
            var manager = new DefaultReplicationManager(OWNER,
                                                        registry,
                                                        (_, _) -> {},
                                                        () -> {},
                                                        capturingTimer(fired));

            var pending = manager.awaitReplication(STREAM, P1, OFFSET, 1);
            fired.get().run();

            assertThat(pending.isResolved()).isTrue();
            pending.await()
                   .onSuccessRun(() -> fail("an unacked await must time out"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(ReplicationError.General.REPLICATION_TIMEOUT));
        }
    }

    @Nested
    class AckCost {
        /// #1260: an ack is resolved against its OWN partition's waiters only. Before the fix every ack
        /// walked every pending await on the node.
        @Test
        void handleAck_visitsNoWaiter_whenAllWaitersAreOnAnotherPartition() {
            var manager = manager(() -> {});

            for (long offset = 0; offset < 10_000; offset++) {
                manager.awaitReplication(STREAM, P1, offset, 1);
            }
            manager.handleAck(replicateAck(REPLICA, STREAM, P2, OFFSET));

            assertThat(manager.ackVisitCount()).isZero();
        }

        /// #1260: a resolved await cancels its timer instead of leaving it queued for the full 5 s.
        @Test
        void awaitReplication_leavesNoLiveTimer_afterSuccessfulAck() {
            var manager = manager(() -> {});

            var pending = manager.awaitReplication(STREAM, P1, OFFSET, 1);
            manager.handleAck(replicateAck(REPLICA, STREAM, P1, OFFSET));

            assertThat(pending.await().isSuccess()).isTrue();
            assertThat(timers).hasSize(1);
            assertThat(timers).allMatch(ScheduledFuture::isCancelled);
        }
    }

    // === fixtures ===

    private DefaultReplicationManager manager(Runnable betweenSteps) {
        return new DefaultReplicationManager(OWNER, registry, (_, _) -> {}, betweenSteps, recordingTimer());
    }

    /// Schedules far in the future (never fires during a test) and records the handle, so a test can
    /// see whether the manager cancelled it.
    private Fn2<ScheduledFuture<?>, Runnable, TimeSpan> recordingTimer() {
        return (task, _) -> record(neverFires.schedule(task, 1, TimeUnit.HOURS));
    }

    private ScheduledFuture<?> record(ScheduledFuture<?> timer) {
        timers.add(timer);

        return timer;
    }

    /// Hands the timer task to the test instead of scheduling it, so the test fires it on demand.
    private Fn2<ScheduledFuture<?>, Runnable, TimeSpan> capturingTimer(AtomicReference<Runnable> sink) {
        return (task, _) -> capture(sink, task);
    }

    private ScheduledFuture<?> capture(AtomicReference<Runnable> sink, Runnable task) {
        sink.set(task);

        return neverFires.schedule(() -> {}, 1, TimeUnit.HOURS);
    }

    private static void pauseInSeam(CountDownLatch inSeam, CountDownLatch release) {
        inSeam.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
