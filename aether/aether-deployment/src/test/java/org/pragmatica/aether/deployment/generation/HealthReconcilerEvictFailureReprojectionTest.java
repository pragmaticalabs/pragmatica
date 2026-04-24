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
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// A4 regression — when `HealthReconciler.evictNode` batches DECOMMISSIONED +
/// DHT rebalance into one consensus apply and the apply fails,
/// `recordConsensusApplyFailure` must request a re-projection so the in-memory
/// snapshot cannot drift from what Rabia actually committed.
class HealthReconcilerEvictFailureReprojectionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private FailingClusterNode cluster;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        cluster = new FailingClusterNode();
        var hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader::get,
                                                        AutoHealConfig.DEFAULT);
        reconciler.start(Epoch.epoch(1L, 0L));
        reconciler.seedSnapshot(snapshotForNodes(NODE_A, NODE_B));
    }

    @AfterEach
    void tearDown() {
        if (reconciler.isActive()) {
            reconciler.stop(StopReason.SHUTDOWN);
        }
    }

    @Test
    void evictNode_consensusApplyFailure_invokesStoredReprojectionSupplier() throws Exception {
        // Stash the "after-failure" supplier that counts invocations.
        var supplierInvocations = new AtomicInteger();
        Supplier<ClusterGenerationSnapshot> capturedSupplier = () -> {
            supplierInvocations.incrementAndGet();
            return snapshotForNodes(NODE_A, NODE_B);
        };
        reconciler.requestReprojection(capturedSupplier, "test-seed");
        // Give the initial drain a moment to run (bounded poll).
        waitForInvocationCount(supplierInvocations, 1);

        var invocationsBeforeFailure = supplierInvocations.get();
        var baselineFailed = reconciler.consensusApplyFailedCount();

        // Arm cluster to fail the next apply, then trigger eviction of NODE_B.
        // evictNode → cluster.apply → .onFailure → recordConsensusApplyFailure
        //    → requestReprojection("consensus-apply-failure") → drain runs stored supplier.
        cluster.armForFailure();
        reconciler.onSignal(new HealthSignal.SwimHint(NODE_B, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 10; i++) {
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_B, i, Epoch.epoch(1L, 0L)));
        }

        // Because the failure submits a fresh drain task to the executor, and the stored
        // supplier counts invocations, wait for the supplier to be invoked again.
        assertThat(waitForInvocationGreaterThan(supplierInvocations, invocationsBeforeFailure))
                .as("failure branch must invoke the captured supplier (re-projection)")
                .isTrue();

        assertThat(reconciler.consensusApplyFailedCount())
                .as("evictNode apply failure must bump consensusApplyFailedCount")
                .isGreaterThan(baselineFailed);
    }

    @Test
    void evictNode_consensusApplySuccess_doesNotTriggerExtraReprojection() throws Exception {
        // Regression guard: the success path must not trigger the new failure-recovery path.
        var supplierInvocations = new AtomicInteger();
        Supplier<ClusterGenerationSnapshot> capturedSupplier = () -> {
            supplierInvocations.incrementAndGet();
            return snapshotForNodes(NODE_A, NODE_B);
        };
        reconciler.requestReprojection(capturedSupplier, "test-seed");
        waitForInvocationCount(supplierInvocations, 1);

        var baselineFailed = reconciler.consensusApplyFailedCount();
        // Do NOT arm for failure — apply succeeds.
        reconciler.onSignal(new HealthSignal.SwimHint(NODE_B, HealthHint.FAULTY, Epoch.epoch(1L, 0L)));
        for (int i = 1; i <= 10; i++) {
            reconciler.onSignal(new HealthSignal.PingTimeout(NODE_B, i, Epoch.epoch(1L, 0L)));
        }
        // Drain any pending executor work deterministically.
        var invocationsBeforeStop = supplierInvocations.get();
        reconciler.stop(StopReason.SHUTDOWN);

        assertThat(reconciler.consensusApplyFailedCount())
                .as("success path leaves consensusApplyFailedCount unchanged")
                .isEqualTo(baselineFailed);
        assertThat(supplierInvocations.get())
                .as("success path must not invoke the captured supplier a second time via failure-recovery")
                .isEqualTo(invocationsBeforeStop);
    }

    private static void waitForInvocationCount(AtomicInteger counter, int expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (counter.get() <expected && System.nanoTime() <deadline) {
            Thread.sleep(10);
        }
        assertThat(counter.get()).as("initial supplier drain").isGreaterThanOrEqualTo(expected);
    }

    private static boolean waitForInvocationGreaterThan(AtomicInteger counter, int threshold) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (counter.get() <= threshold && System.nanoTime() <deadline) {
            Thread.sleep(10);
        }
        return counter.get() > threshold;
    }

    private static ClusterGenerationSnapshot snapshotForNodes(NodeId... nodes) {
        var base = ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(nodes.length);
        var members = new LinkedHashMap<NodeId, CoreMember>();
        for (var nodeId : nodes) {
            members.put(nodeId,
                         CoreMember.coreMember(nodeId,
                                               "h-" + nodeId.id(),
                                               9001,
                                               NodeLifecycleState.ON_DUTY,
                                               HealthHint.HEALTHY,
                                               Epoch.epoch(1L, 0L),
                                               Epoch.epoch(1L, 0L)));
        }
        return base.withCoreMembers(members);
    }

    /// ClusterNode stand-in that returns `Promise.failure` for `apply` when armed.
    private static final class FailingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        void armForFailure() {failNext.set(true);}

        @Override public NodeId self() {return SELF;}
        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("not used");}
        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            if (failNext.getAndSet(false)) {
                return (Promise) Causes.cause("simulated consensus apply failure").promise();
            }
            return (Promise) Promise.success(List.of());
        }
    }
}
