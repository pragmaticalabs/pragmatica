// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2004 / #1946: the gate's alarm-ordering monitor is a leaf lock, so it cannot be one side of a lock-order inversion. This runs the
/// REAL [OwnerActivation] with the node's REAL alarm over a REAL hand-off sink, with the quorum-loss clear racing the Nth-refusal
/// raise over many episodes, bounded joins, and asserts the JVM reports no deadlocked thread. The delivered events also never
/// resolve more than they raised at any prefix (a recovery with no open warning is dropped by the aggregator).
class OwnerPromotionAlarmLockOrderTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 3L);
    private static final int EPISODES = 300;
    /// `OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER`, which is package-private; a drift only makes the raise rarer, and the test asserts it fired.
    private static final int REFUSALS_PER_EPISODE = 5;
    private static final long BOUND_SECONDS = 30L;

    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);
    private final OwnerActivation gate = gate();

    private OwnerActivation gate() {
        var record = Option.some(new StreamPartitionOwnershipValue(SELF,
                                                                   EPOCH,
                                                                   3L,
                                                                   HlcTimestamp.ZERO,
                                                                   List.of(SELF),
                                                                   1L,
                                                                   false,
                                                                   List.of(),
                                                                   List.of(new EpochStart(EPOCH, 5L))));

        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record,
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(SELF),
                                               (_, _, _) -> Promise.success(-1L),
                                               (_, _) -> 9L,
                                               (_, _, _, tail) -> Promise.success(tail),
                                               () -> true,
                                               (_, _, _, _, _) -> Promise.success(List.of()),
                                               AetherNode.ownerPromotionAlarm(sink),
                                               TimeSpan.timeSpan(1).hours(),
                                               (_, _) -> 1L,
                                               (_, _, _, _, _) -> OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED.<Unit> promise());
    }

    @Test
    void quorumLossRacingTheRaise_neverDeadlocks_andNeverResolvesBeforeRaising() throws InterruptedException {
        var done = new AtomicBoolean();
        var armed = new AtomicInteger();
        var started = new CountDownLatch(2);
        var activator = new Thread(() -> {
            started.countDown();
            for (var episode = 0; episode < EPISODES; episode++) {
                for (var refusal = 0; refusal < REFUSALS_PER_EPISODE; refusal++) {
                    if (refusal == REFUSALS_PER_EPISODE - 1) {
                        armed.incrementAndGet();
                    }
                    gate.activate(STREAM, PARTITION).await();
                }
            }
            done.set(true);
        }, "activator");
        var clearer = new Thread(() -> {
            started.countDown();
            var seen = 0;

            while (!done.get()) {
                var now = armed.get();

                if (now != seen) {
                    seen = now;
                    gate.onQuorumStateChange(ClusterStateNotification.passive());
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "clearer");

        activator.setDaemon(true);
        clearer.setDaemon(true);
        activator.start();
        clearer.start();
        assertThat(started.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);

        while ((activator.isAlive() || clearer.isAlive()) && System.nanoTime() < deadline) {
            assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).as("no deadlocked thread while racing").isNull();
            Thread.onSpinWait();
        }

        assertThat(activator.isAlive() || clearer.isAlive()).as("both threads finished inside the bound").isFalse();
        assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).isNull();
        awaitQuiet();
        assertThat(published.stream().filter(w -> w.code() == OperatorWarningCode.STREAM_OWNER_LINEAGE_REFUSED).count())
            .as("the race exercised the raise path at least once")
            .isPositive();
        assertNoResolveBeforeRaise();
    }

    /// Positive control for the detector: two threads taking two locks in opposite orders ARE reported. A deadlock probe whose
    /// detector cannot see one proves nothing. The locks are interruptible so the threads are freed afterwards.
    @Test
    void controlTheDetectorSeesAnInversion() throws InterruptedException {
        var first = new ReentrantLock();
        var second = new ReentrantLock();
        var bothHold = new CountDownLatch(2);
        var left = new Thread(() -> crossLock(first, second, bothHold), "control-left");
        var right = new Thread(() -> crossLock(second, first, bothHold), "control-right");

        left.start();
        right.start();
        assertThat(bothHold.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);

        while (ManagementFactory.getThreadMXBean().findDeadlockedThreads() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        var found = ManagementFactory.getThreadMXBean().findDeadlockedThreads();

        left.interrupt();
        right.interrupt();
        left.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
        right.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS));

        assertThat(found).as("the control inversion is detected").isNotNull();
    }

    private static void crossLock(ReentrantLock outer, ReentrantLock inner, CountDownLatch bothHold) {
        outer.lock();
        try {
            bothHold.countDown();
            awaitBoth(bothHold);
            inner.lockInterruptibly();
            inner.unlock();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            outer.unlock();
        }
    }

    private static void awaitBoth(CountDownLatch latch) throws InterruptedException {
        latch.await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    /// The hand-off queue drains on its own thread; wait until it stops growing.
    private void awaitQuiet() throws InterruptedException {
        var last = -1;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);

        while (published.size() != last && System.nanoTime() < deadline) {
            last = published.size();
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }

    private void assertNoResolveBeforeRaise() {
        var open = 0;
        var seen = new ArrayList<String>();

        for (var warning : published) {
            seen.add(warning.code().code());
            open += warning.code() == OperatorWarningCode.STREAM_OWNER_LINEAGE_REFUSED ? 1 : -1;
            assertThat(open).as("a recovery was delivered with no open warning; delivered so far: %s", seen).isGreaterThanOrEqualTo(0);
        }
    }
}
