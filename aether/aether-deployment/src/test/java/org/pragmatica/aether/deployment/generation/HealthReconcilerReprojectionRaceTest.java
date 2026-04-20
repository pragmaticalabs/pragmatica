// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Spec §8 "Snapshot race A2" — two KV-Store atom listeners used to each run
/// `projectFromCommittedAtoms()` directly on arbitrary notification threads and
/// overwrite `snapshotRef` with whichever projection finished last. The fix routes
/// all re-projection triggers through `HealthReconciler.requestReprojection`, which
/// coalesces requests and runs projections on the reconciler's dedicated worker.
/// This test simulates a burst of concurrent requests and asserts the final
/// snapshot reflects the last write to the source of truth.
class HealthReconcilerReprojectionRaceTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();

    private NoopClusterNode cluster;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        cluster = new NoopClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader,
                                                        AutoHealConfig.DEFAULT);
        reconciler.start(Epoch.epoch(1L, 0L));
        reconciler.seedSnapshot(snapshotWithCoreSize(1));
    }

    @AfterEach
    void tearDown() {
        reconciler.stop(StopReason.SHUTDOWN);
    }

    @Test
    void requestReprojection_burstFromManyThreads_finalSnapshotMatchesLatestSourceState() throws Exception {
        var totalRequests = 200;
        var threads = 4;
        var invocationCount = new AtomicInteger();
        var sourceState = new AtomicReference<>(snapshotWithCoreSize(1));
        Supplier<ClusterGenerationSnapshot> supplier = () -> {
            invocationCount.incrementAndGet();
            return sourceState.get();
        };

        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var driver = Executors.newFixedThreadPool(threads);
        fireBurst(driver, ready, start, threads, totalRequests, supplier, sourceState);
        ready.await();
        start.countDown();
        driver.shutdown();
        assertThat(driver.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        // After all workers finished, `sourceState` is stable. Fire one final trigger so the
        // reconciler's drain observes the final source value — this closes the window where
        // the last worker's requestReprojection could coalesce into an already-running drain
        // that captured an earlier sourceState.
        var lastSourceSize = sourceState.get().desiredCoreSize();
        waitForProjection(reconciler, supplier, lastSourceSize);
        // Shut down the reconciler-owned executor and wait for drain to complete deterministically.
        reconciler.stop(StopReason.SHUTDOWN);

        var finalDesiredSize = reconciler.currentSnapshot().desiredCoreSize();
        assertThat(finalDesiredSize)
            .as("final snapshot must reflect the last source state, not an earlier projection")
            .isEqualTo(lastSourceSize);

        assertThat(invocationCount.get())
            .as("supplier must be invoked at least once and coalescing must prevent per-request invocation")
            .isGreaterThanOrEqualTo(1)
            .isLessThan(totalRequests);
    }

    private void fireBurst(ExecutorService driver,
                           CountDownLatch ready,
                           CountDownLatch start,
                           int threads,
                           int totalRequests,
                           Supplier<ClusterGenerationSnapshot> supplier,
                           AtomicReference<ClusterGenerationSnapshot> sourceState) {
        var perThread = totalRequests / threads;
        for (int t = 0; t < threads; t++) {
            var threadIndex = t;
            driver.submit(() -> runBurstWorker(ready, start, perThread, threadIndex, supplier, sourceState));
        }
    }

    @SuppressWarnings("SameReturnValue")
    private Unit runBurstWorker(CountDownLatch ready,
                                CountDownLatch start,
                                int perThread,
                                int threadIndex,
                                Supplier<ClusterGenerationSnapshot> supplier,
                                AtomicReference<ClusterGenerationSnapshot> sourceState) {
        ready.countDown();
        awaitStart(start);
        for (int i = 0; i < perThread; i++) {
            // Mutate the source in lockstep so projections running during the burst see
            // advancing state. `i + 1` ensures strictly monotonic growth per thread.
            sourceState.set(snapshotWithCoreSize(threadIndex * perThread + i + 1));
            reconciler.requestReprojection(supplier, "race-test-t" + threadIndex + "-i" + i);
        }
        return Unit.unit();
    }

    @SuppressWarnings("BusyWait")
    private static void waitForProjection(HealthReconciler reconciler,
                                          Supplier<ClusterGenerationSnapshot> supplier,
                                          int expectedSize) throws InterruptedException {
        // Keep firing reprojection triggers until the reconciler's snapshot catches up to the
        // stable post-burst source value. Bounded loop to avoid infinite wait on real failure.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reconciler.currentSnapshot().desiredCoreSize() != expectedSize && System.nanoTime() < deadline) {
            reconciler.requestReprojection(supplier, "race-test-drain");
            Thread.sleep(5);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static ClusterGenerationSnapshot snapshotWithCoreSize(int desiredCoreSize) {
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(desiredCoreSize);
        var members = new LinkedHashMap<NodeId, CoreMember>();
        members.put(NODE_A, CoreMember.coreMember(NODE_A,
                                                   "host-a",
                                                   9001,
                                                   NodeLifecycleState.ON_DUTY,
                                                   HealthHint.HEALTHY,
                                                   Epoch.epoch(1L, 0L),
                                                   Epoch.epoch(1L, 0L)));
        return base.withCoreMembers(members);
    }

    /// Minimal ClusterNode stand-in: the race test never exercises consensus.
    private static final class NoopClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}

        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }

        @SuppressWarnings("unused")
        List<List<KVCommand<AetherKey>>> batches() {
            return List.copyOf(batches);
        }
    }
}
