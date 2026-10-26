// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.replica.ReplicaFeedLimits;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.*;
import org.pragmatica.cluster.replica.ReplicaFeedServer;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.RingFixtures.*;

/// NT-O43 extended over the W2 server callers (I-W13): the real `ReplicaFeedServer` over a ranked-lock ring and a ranked store; subscribe, tick, heartbeat
/// and snapshot requests, and the closes that call back into the server (they read the ring), run concurrently with a writer that bumps the incarnation.
/// Any out-of-order acquire is recorded. Mutation: take the store (or `committedRevision`) from inside a ring callback, or call `ring.flush()` while holding the store monitor.
class ReplicaFeedLockOrderTest {
    private static final class RankedStore extends KVStore<StructuredKey, Object> {
        RankedStore() {
            super(org.pragmatica.messaging.MessageRouter.mutable(), RingFixtures.TEXT, RingFixtures.deserializerReturning(java.util.Map.of()),
                  ReplicaKeyFilter.includeAll(), RingFixtures.SOURCE,
                  CommitRing.commitRing(CommitRing.DEFAULT_BUDGET_BYTES, ValueSizeTable.valueSizeTable()::estimate, Runnable::run, new RankedLock(RankedLock.RING)));
        }
        @Override public <R> List<R> processCommitted(org.pragmatica.consensus.StateMachine.Batch<KVCommand<StructuredKey>> b, long n) {
            return RankedLock.underRank(RankedLock.MUTATION, () -> RankedLock.underRank(RankedLock.STORE, () -> super.processCommitted(b, n)));
        }
        @Override public org.pragmatica.lang.Unit onPhaseCompleted(long n) { return RankedLock.underRank(RankedLock.STORE, () -> super.onPhaseCompleted(n)); }
        @Override public org.pragmatica.lang.Option<Object> get(StructuredKey k) { return RankedLock.underRank(RankedLock.STORE, () -> super.get(k)); }
        @Override public long committedRevision() { return RankedLock.underRank(RankedLock.STORE, () -> super.committedRevision()); }
        @Override public org.pragmatica.lang.Result<ReplicaSnapshot> captureReplicaSnapshot(ReplicaKeyFilter f) { return RankedLock.underRank(RankedLock.STORE, () -> super.captureReplicaSnapshot(f)); }
    }

    @org.junit.jupiter.api.Timeout(60)
    @Test
    void rankedFeed_noOutOfOrderAcquire_underBumpsSubscribesTicksHeartbeatsSnapshots() throws Exception {
        var store = new RankedStore();
        RankedLock.clearViolations();
        var before = RankedLock.acquisitions();
        commit(store, 0, putIncarnation(1, "A"));
        var sent = new AtomicLong();
        var refused = new AtomicLong();
        var core = NodeId.nodeId("core-1").unwrap();
        var peers = new java.util.ArrayList<NodeId>();
        for (int i = 0; i < 8; i++) peers.add(NodeId.nodeId("pp-" + i).unwrap());
        var server = ReplicaFeedServer.replicaFeedServer(core, store, ReplicaKeyFilter.includeAll(),
            (p, m) -> { sent.incrementAndGet(); if (m instanceof ReplicaRefused) refused.incrementAndGet(); },
            () -> true, p -> true, p -> true, Runnable::run, System::nanoTime, ReplicaFeedLimits.DEFAULT);
        var stop = new AtomicBoolean();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var threads = new java.util.ArrayList<Thread>();
        threads.add(new Thread(() -> { long slot = 1, n = 2; while (!stop.get()) { if (slot % 7 == 0) { commit(store, slot, putIncarnation(n, "I" + n)); n++; } else commit(store, slot, put("k" + (slot % 40), "v" + slot)); slot++; } }, "w"));
        threads.add(new Thread(() -> { long id = 0; while (!stop.get()) for (var p : peers) server.onSubscribe(new ReplicaSubscribe(p, ++id, IncarnationTag.NONE, 0, List.of(), SubscribeMode.LIVE_ONLY)); }, "sub"));
        threads.add(new Thread(() -> { while (!stop.get()) { server.tick(); server.heartbeat(); } }, "tick"));
        threads.add(new Thread(() -> { long id = 0; while (!stop.get()) for (var p : peers) server.onSnapshotRequest(new ReplicaSnapshotRequest(p, ++id + 1_000_000, IncarnationTag.NONE, 0, "", 0)); }, "snap"));
        threads.forEach(t -> t.setUncaughtExceptionHandler((_, f) -> failures.add(f)));
        threads.forEach(Thread::start);
        Thread.sleep(2_000);
        stop.set(true);
        for (var t : threads) t.join(5_000);
        assertThat(RankedLock.violations()).as("no out-of-order lock acquisition").isEmpty();
        assertThat(failures).as("no thread died").isEmpty();
        assertThat(RankedLock.acquisitions()).as("control: the ranked locks were really taken").isGreaterThan(before);
        assertThat(refused.get()).as("control: closes (which call back into the server) really ran").isPositive();
        assertThat(threads).noneMatch(Thread::isAlive);
    }
}
