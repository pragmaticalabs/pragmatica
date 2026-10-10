// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #2004, the sibling sites of the owner activation gate that record a warning and raise it, and remove it and resolve it:
///   - `ForwardCatchupTransport` (the not-answering episode and its restoration),
///   - `PartitionBackfill`'s superseded flight (the oversized-peer block).
/// (`StreamPartitionManager.refusedCuts` is in `RefusedCutAlarmOrderTest`.)
///
/// Each pair is raced deterministically (a hook between the record and the raise, inside the monitor; the clear must be observed BLOCKED on
/// the monitor, never finished) and stressed with real threads, the real hand-off sink and a deadlock detector. The monitors are leaf locks:
/// while one is held only a map and the warning's log line plus bounded hand-off run, so none can be one side of a lock-order inversion.
class CatchupAndBackfillAlarmOrderTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId PEER = NodeId.nodeId("node-bb").unwrap();
    private static final long WAIT_SECONDS = 10L;
    private static final int EPISODES = 300;

    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);
    private final AtomicLong clock = new AtomicLong();

    private ForwardCatchupTransport transport() {
        return ForwardCatchupTransport.forwardCatchupTransport(null, 4, sink, clock::get);
    }

    private static Method method(String name) throws Exception {
        var method = ForwardCatchupTransport.class.getDeclaredMethod(name, NodeId.class, ReplicationMessage.CatchupRequest.class);

        method.setAccessible(true);

        return method;
    }

    private static ReplicationMessage.CatchupRequest request(String stream) {
        return ReplicationMessage.CatchupRequest.catchupRequest(SELF, stream, PARTITION, 0L);
    }

    private static void invoke(Method method, ForwardCatchupTransport t, ReplicationMessage.CatchupRequest request) {
        try {
            method.invoke(t, PEER, request);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /// F1a: a vouched answer that lands while the not-answering report is being raised waits for it: `[not answering, restored]`.
    /// Mutation: recording and raising, or removing and resolving, without the monitor turns this red (`[restored, not answering]`).
    @Test
    void forwardCatchup_aVouchedAnswerDuringTheReport_deliversNotAnsweringThenRestored() throws Exception {
        var t = transport();
        var note = method("noteUnvouched");
        var end = method("endEpisode");
        var req = request(STREAM);
        var clearing = new AtomicReference<Thread>();

        t.reportWindowHook(() -> {
            var thread = new Thread(() -> invoke(end, t, req), "restoring");

            clearing.set(thread);
            thread.start();
            awaitBlockedOrDone(thread);
        });
        clock.set(0L);
        invoke(note, t, req);
        clock.set(ForwardCatchupTransport.NOT_ANSWERED_REPORT_AFTER_MS);
        invoke(note, t, req);
        clearing.get().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitQuiet();

        assertThat(published).extracting(w -> w.code()).containsExactly(OperatorWarningCode.STREAM_CATCHUP_SOURCE_NOT_ANSWERING,
                                                                       OperatorWarningCode.STREAM_CATCHUP_SOURCE_ANSWERING_RESTORED);
    }

    /// F2: a flight superseded between its check and the monitor raises nothing. X holds the monitor in a raise; the stale flight S (current
    /// at its start) waits for it, is superseded, and must find `current` false INSIDE the monitor. Before the fix S raised a second block
    /// after X's raise and the successor's clear resolved only that one.
    @Test
    void backfill_aFlightSupersededWhileWaitingForTheMonitor_raisesNothing() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var inRaise = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        var base = partitionBackfill(replicaRegistry(),
                                     manager.alignedRecovery(),
                                     CatchupTransport.NOOP,
                                     (_, _, _) -> ReplicationError.General.REPLICATION_TIMEOUT.promise(),
                                     (_, _) -> 3L,
                                     SELF,
                                     TimeSpan.timeSpan(10).seconds(),
                                     () -> 0L);

        base.blockAlarm(new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                events.add("raise:" + ((OwnerActivation.ActivationBlock.PeerEventExceedsReadCap) block).offset());
                inRaise.countDown();
                awaitUninterruptibly(release);

                return Unit.unit();
            }

            @Override
            public Unit resolved(OwnerActivation.ActivationBlock block) {
                events.add("resolved:" + ((OwnerActivation.ActivationBlock.PeerEventExceedsReadCap) block).offset());

                return Unit.unit();
            }
        });

        var staleIsCurrent = new AtomicBoolean(true);
        var successor = base.withCurrent(() -> true);
        var stale = base.withCurrent(staleIsCurrent::get);
        var holding = new Thread(() -> successor.oversizedPeer(STREAM, PARTITION, PEER, new OwnerPeerReads.EventExceedsReadCap(7L)), "holding");
        var late = new Thread(() -> stale.oversizedPeer(STREAM, PARTITION, PEER, new OwnerPeerReads.EventExceedsReadCap(9L)), "late");

        holding.start();
        assertThat(inRaise.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the first raise started").isTrue();
        late.start();
        awaitBlockedOrDone(late);
        assertThat(late.getState()).as("the stale flight waits for the monitor").isEqualTo(Thread.State.BLOCKED);
        staleIsCurrent.set(false);
        release.countDown();
        holding.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        late.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        successor.clearOversized(STREAM, PARTITION);

        assertThat(events).containsExactly("raise:7", "resolved:7");
    }

    /// Lock order and delivery order under real threads: raise and clear race over many episodes at the two sites; the deadlock detector sees no
    /// cycle, every thread finishes inside the bound, and no key ever delivers a recovery before its raise.
    @Test
    void forwardCatchupAndBackfill_racingRaiseAndClear_neverDeadlock_andNeverResolveBeforeRaising() throws Exception {
        var t = transport();
        var note = method("noteUnvouched");
        var end = method("endEpisode");
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        var backfill = partitionBackfill(replicaRegistry(),
                                         manager.alignedRecovery(),
                                         CatchupTransport.NOOP,
                                         (_, _, _) -> ReplicationError.General.REPLICATION_TIMEOUT.promise(),
                                         (_, _) -> 3L,
                                         SELF,
                                         TimeSpan.timeSpan(10).seconds(),
                                         () -> 0L);
        var oversizedEvents = new CopyOnWriteArrayList<String>();

        backfill.blockAlarm(new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                oversizedEvents.add("raise");

                return Unit.unit();
            }

            @Override
            public Unit resolved(OwnerActivation.ActivationBlock block) {
                oversizedEvents.add("resolved");

                return Unit.unit();
            }
        });

        var armed = new AtomicInteger();
        var done = new AtomicBoolean();
        var raiser = new Thread(() -> {
            for (var episode = 0; episode < EPISODES; episode++) {
                var req = request("s" + episode);

                clock.set(0L);
                invoke(note, t, req);
                clock.set(ForwardCatchupTransport.NOT_ANSWERED_REPORT_AFTER_MS);
                armed.incrementAndGet();
                invoke(note, t, req);
                backfill.oversizedPeer(STREAM, PARTITION, PEER, new OwnerPeerReads.EventExceedsReadCap(episode));
            }
            done.set(true);
        }, "raiser");
        var clearer = new Thread(() -> {
            var seen = 0;

            while (!done.get()) {
                var now = armed.get();

                if (now != seen) {
                    seen = now;
                    invoke(end, t, request("s" + (now - 1)));
                    backfill.clearOversized(STREAM, PARTITION);
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "clearer");

        raiser.setDaemon(true);
        clearer.setDaemon(true);
        raiser.start();
        clearer.start();

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

        while ((raiser.isAlive() || clearer.isAlive()) && System.nanoTime() < deadline) {
            assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).as("no deadlocked thread while racing").isNull();
            Thread.onSpinWait();
        }

        assertThat(raiser.isAlive() || clearer.isAlive()).as("both threads finished inside the bound").isFalse();
        assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).isNull();
        awaitQuiet();

        var perKey = new HashMap<String, Integer>();

        for (var warning : published) {
            var open = perKey.merge(warning.subject(), warning.code() == OperatorWarningCode.STREAM_CATCHUP_SOURCE_NOT_ANSWERING ? 1 : -1, Integer::sum);

            assertThat(open).as("a recovery for %s was delivered with no open warning", warning.subject()).isGreaterThanOrEqualTo(0);
        }

        assertThat(published.stream().filter(w -> w.code() == OperatorWarningCode.STREAM_CATCHUP_SOURCE_NOT_ANSWERING).count()).as("the race exercised the raise").isPositive();

        var open = 0;

        for (var event : oversizedEvents) {
            open += event.equals("raise") ? 1 : -1;
            assertThat(open).as("an oversized recovery was delivered with no open block: %s", oversizedEvents).isGreaterThanOrEqualTo(0);
        }
    }

    /// Positive control for the detector: two locks taken in opposite orders ARE reported.
    @Test
    void controlTheDetectorSeesAnInversion() throws InterruptedException {
        var first = new ReentrantLock();
        var second = new ReentrantLock();
        var bothHold = new CountDownLatch(2);
        var left = new Thread(() -> crossLock(first, second, bothHold), "control-left");
        var right = new Thread(() -> crossLock(second, first, bothHold), "control-right");

        left.start();
        right.start();
        assertThat(bothHold.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (ManagementFactory.getThreadMXBean().findDeadlockedThreads() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        var found = ManagementFactory.getThreadMXBean().findDeadlockedThreads();

        left.interrupt();
        right.interrupt();
        left.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        right.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertThat(found).as("the control inversion is detected").isNotNull();
    }

    private static void crossLock(ReentrantLock outer, ReentrantLock inner, CountDownLatch bothHold) {
        outer.lock();
        try {
            bothHold.countDown();
            awaitUninterruptibly(bothHold);
            inner.lockInterruptibly();
            inner.unlock();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            outer.unlock();
        }
    }

    private static void awaitBlockedOrDone(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The hand-off queue drains on its own thread; wait until it stops growing.
    private void awaitQuiet() throws InterruptedException {
        var last = -1;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS * 3);

        while (published.size() != last && System.nanoTime() < deadline) {
            last = published.size();
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }
}
