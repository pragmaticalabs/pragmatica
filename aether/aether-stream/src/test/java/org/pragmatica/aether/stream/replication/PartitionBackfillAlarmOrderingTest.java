// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #2004 at the promoted owner's backfill gate: the oversized-peer block it records and raises is never resolved BEFORE it is raised.
/// The backfill of a partition is single-flight, but a flight that timed out can still be running when the next starts, so a clear can
/// land between the record and the raise. The raise is held open on one thread while a clear runs on another; the clear waits on the
/// gate's ordering monitor (observed from its thread state, not by sleeping) and the delivered order is `[raise, resolved]`.
/// Mutation: recording and raising, or removing and resolving, without the monitor turns this red.
class PartitionBackfillAlarmOrderingTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId PEER = NodeId.nodeId("node-bb").unwrap();
    private static final long WAIT_SECONDS = 10L;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> duringRaise = new AtomicReference<>(() -> {});

    private PartitionBackfill backfill() {
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

        backfill.blockAlarm(new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                duringRaise.get().run();
                events.add("raise");

                return Unit.unit();
            }

            @Override
            public Unit resolved(OwnerActivation.ActivationBlock block) {
                events.add("resolved");

                return Unit.unit();
            }
        });

        return backfill;
    }

    @Test
    void oversizedPeer_aClearDuringTheRaise_deliversRaiseThenResolve() throws InterruptedException {
        var backfill = backfill();
        var inRaise = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        duringRaise.set(() -> {
            inRaise.countDown();
            awaitUninterruptibly(release);
        });

        var raising = new Thread(() -> backfill.oversizedPeer(STREAM, PARTITION, PEER, new OwnerPeerReads.EventExceedsReadCap(7L)), "raising");
        var clearing = new Thread(() -> backfill.clearOversized(STREAM, PARTITION), "clearing");

        raising.start();
        assertThat(inRaise.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the raise started").isTrue();
        clearing.start();
        awaitState(clearing);
        release.countDown();
        raising.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        clearing.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertThat(raising.isAlive() || clearing.isAlive()).as("both finished").isFalse();
        assertThat(events).containsExactly("raise", "resolved");
    }

    /// A clear with nothing recorded resolves nothing; the same peer and offset reported twice raises once.
    @Test
    void clearWithNothingRecorded_resolvesNothing_andARepeatedReportRaisesOnce() {
        var backfill = backfill();

        backfill.clearOversized(STREAM, PARTITION);
        assertThat(events).isEmpty();

        backfill.oversizedPeer(STREAM, PARTITION, PEER, new OwnerPeerReads.EventExceedsReadCap(7L));
        backfill.oversizedPeer(STREAM, PARTITION, PEER, new OwnerPeerReads.EventExceedsReadCap(7L));
        backfill.clearOversized(STREAM, PARTITION);

        assertThat(events).containsExactly("raise", "resolved");
    }

    private static void awaitState(Thread clearing) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (clearing.getState() != Thread.State.BLOCKED && clearing.getState() != Thread.State.TERMINATED && System.nanoTime() < deadline) {
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
}
