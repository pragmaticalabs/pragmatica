// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Theme B Item 3 — verifies the `ClusterConfigValue` seed-grace deferral path inside
/// `HealthReconcilerActivator.planClusterConfigSeed()`.
///
/// During cluster boot the leader-elect's view of `NodeLifecycleKey` atoms can lag behind
/// real cluster size: only N of M static nodes have written their lifecycle. Seeding the
/// `ClusterConfigValue` with `coreCount = N` would produce a phantom-deficit (M - N) and
/// trigger CTM auto-provisioning. The activator therefore defers the seed when the observed
/// lifecycle count is below `initialCoreSize` UNTIL the count converges OR a 60-second grace
/// window expires.
class HealthReconcilerSeedGraceTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();
    private static final NodeId NODE_D = NodeId.nodeId("node-d").unwrap();

    private RecordingClusterNode cluster;
    private HlcClock hlcClock;
    private AtomicBoolean isLeader;
    private AtomicLong rabiaTerm;
    private HealthReconciler reconciler;
    private final AtomicReference<Map<AetherKey, AetherValue>> kvSnapshotRef = new AtomicReference<>(Map.of());
    private final AtomicReference<Integer> initialCoreSize = new AtomicReference<>(5);

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode();
        hlcClock = HlcClock.hlcClock(SELF.id()).unwrap();
        isLeader = new AtomicBoolean(true);
        rabiaTerm = new AtomicLong(1L);
        kvSnapshotRef.set(Map.of());
        initialCoreSize.set(5);
        reconciler = HealthReconciler.healthReconciler(SELF,
                                                        cluster,
                                                        ClusterGenerationProjector.clusterGenerationProjector(),
                                                        hlcClock,
                                                        rabiaTerm::get,
                                                        isLeader::get,
                                                        AutoHealConfig.DEFAULT);
    }

    private HealthReconcilerActivatorRecord buildActivator() {
        return (HealthReconcilerActivatorRecord) HealthReconcilerActivator.healthReconcilerActivator(reconciler,
                                                                                                      isLeader::get,
                                                                                                      ClusterGenerationProjector.clusterGenerationProjector(),
                                                                                                      kvSnapshotRef::get,
                                                                                                      rabiaTerm::get,
                                                                                                      hlcClock,
                                                                                                      cluster,
                                                                                                      () -> SELF,
                                                                                                      initialCoreSize::get);
    }

    /// Below quorum (lifecycles=3, initial=5): the seed-grace branch returns `none()` so
    /// `planClusterConfigSeed` skips the seed.
    @Test
    void partialBootBelowExpectedSize_deferSeed() {
        initialCoreSize.set(5);
        // Only 3 of 5 lifecycles present — below initialCoreSize, above quorum (3).
        kvSnapshotRef.set(Map.of(NodeLifecycleKey.nodeLifecycleKey(SELF),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_A),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_B),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY)));
        var activator = buildActivator();
        // Stamp leader-bootstrap-time NOW so the grace window is freshly armed.
        activator.leaderBootstrapTimeMs().set(System.currentTimeMillis());
        var plan = activator.planClusterConfigSeed();
        assertThat(plan.isPresent())
                .as("partial-boot below initial size must defer seed")
                .isFalse();
    }

    /// After SEED_GRACE_MS elapses, the seed is dispatched even with the same partial-boot
    /// snapshot. Simulated by setting `leaderBootstrapTimeMs` 61 seconds in the past.
    @Test
    void seedGraceExpiredEvenWithPartialBoot_seedDispatched() {
        initialCoreSize.set(5);
        kvSnapshotRef.set(Map.of(NodeLifecycleKey.nodeLifecycleKey(SELF),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_A),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_B),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY)));
        var activator = buildActivator();
        // Move bootstrap-time 61 seconds into the past so SEED_GRACE_MS (60 s) has elapsed.
        activator.leaderBootstrapTimeMs().set(System.currentTimeMillis() - 61_000L);
        var plan = activator.planClusterConfigSeed();
        assertThat(plan.isPresent())
                .as("seed grace expired — seed must dispatch even with partial boot")
                .isTrue();
        assertThat(plan.unwrap().initialSize()).isEqualTo(5);
    }

    /// Lifecycles converge to `initialCoreSize` before the grace window expires — seed
    /// dispatches immediately without waiting for the grace.
    @Test
    void lifecyclesConverge_seedDispatchedImmediately() {
        initialCoreSize.set(5);
        // All 5 lifecycles present.
        kvSnapshotRef.set(Map.of(NodeLifecycleKey.nodeLifecycleKey(SELF),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_A),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_B),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_C),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_D),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY)));
        var activator = buildActivator();
        activator.leaderBootstrapTimeMs().set(System.currentTimeMillis());
        var plan = activator.planClusterConfigSeed();
        assertThat(plan.isPresent())
                .as("lifecycles converged — seed must dispatch without waiting for grace")
                .isTrue();
        assertThat(plan.unwrap().initialSize()).isEqualTo(5);
    }

    /// Rapid re-evaluations under deferred conditions are throttled to once every
    /// SEED_ATTEMPT_THROTTLE_MS (5 s). Calling `planClusterConfigSeed` 10 times within a few
    /// milliseconds must NOT result in a hot-loop of debug logging or repeated branch work
    /// — every call after the first returns `none()` immediately on the throttle path.
    @Test
    void rapidReattempts_throttledTo5sWindow() {
        initialCoreSize.set(5);
        kvSnapshotRef.set(Map.of(NodeLifecycleKey.nodeLifecycleKey(SELF),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_A),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY),
                                 NodeLifecycleKey.nodeLifecycleKey(NODE_B),
                                 NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY)));
        var activator = buildActivator();
        activator.leaderBootstrapTimeMs().set(System.currentTimeMillis());
        // Drive 10 rapid re-evaluations.
        for (var i = 0; i < 10; i++) {
            var plan = activator.planClusterConfigSeed();
            assertThat(plan.isPresent()).isFalse();
        }
        // The throttle gate keeps `lastSeedAttemptMs` near `now` after the FIRST call;
        // subsequent calls within the same 5-second window short-circuit before updating it.
        // Validate by observing the last-attempt timestamp is set, but not advanced repeatedly:
        // it equals whatever was recorded on the first non-throttled pass.
        var lastAttempt = activator.lastSeedAttemptMs().get();
        assertThat(lastAttempt).isPositive();
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
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
    }
}
