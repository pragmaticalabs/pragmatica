// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the CTM provisioning circuit breaker (retained unchanged by the slot model,
/// slot-based-membership-convergence-spec §5.2). After `MAX_CONSECUTIVE_PROVISIONING_FAILURES`
/// (3) consecutive provider failures, slot-fill provisioning is halted until a successful node
/// arrival (`onNodeReady`), leader (re)activation, or operator `setDesiredSize`. The failure
/// signal is now a failing `provisionNode` (provider API rejection) rather than a slot-expiry
/// event — the gate logic (`provisioningCircuitTripped` / backoff) is identical.
class ClusterTopologyManagerCircuitBreakerTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());
    private static final NodeInfo INFO_C = NodeInfo.nodeInfo(PEER_C, NodeAddress.nodeAddress("localhost", 5003).unwrap());
    private static final NodeInfo INFO_D = NodeInfo.nodeInfo(PEER_D, NodeAddress.nodeAddress("localhost", 5004).unwrap());

    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();
    private static final long INITIAL_CLOCK_MS = 1_000_000_000L;

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterStore clusterStore;
    private AtomicLong clockMs;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        clusterStore.seed(5);
        clockMs = new AtomicLong(INITIAL_CLOCK_MS);
    }

    private ClusterTopologyManager createCtm(TimeSpan provisioningTimeout) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      provisioningTimeout,
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        return ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                         lifecycleManager,
                                                                         autoHeal,
                                                                         DeploymentMap.deploymentMap(),
                                                                         snapshotSource,
                                                                         clusterStore::current,
                                                                         clusterStore::lifecycle,
                                                                         clusterStore::slots,
                                                                         clusterStore::apply,
                                                                         new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                                         LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                              clusterStore::lifecycle,
                                                                                                              clockMs::get),
                                                                         () -> ClusterPhase.NORMAL,
                                                                         () -> true,
                                                                         clockMs::get);
    }

    /// Provider consistently fails; after 3 consecutive failures the breaker trips and further
    /// fill attempts are halted regardless of clock advance.
    @Test
    void circuitBreaker_haltsProvisioning_after3ConsecutiveFailures() {
        ctm = createCtm(timeSpan(100L).millis());
        activateConvergedThenDeficit();
        lifecycleManager.failProvision(true);
        driveDeficitReconciles(8);
        var plateauCount = lifecycleManager.provisionCount.get();
        assertThat(plateauCount).as("at least one provision attempt fired").isGreaterThanOrEqualTo(1);

        // Advance well past backoff but below 1h auto-reset; breaker stays tripped.
        clockMs.addAndGet(30 * 60 * 1000L);
        driveDeficitReconciles(3);
        assertThat(lifecycleManager.provisionCount.get())
                .as("provisioning halted after breaker trips, regardless of clock advance")
                .isEqualTo(plateauCount);
    }

    /// `setDesiredSize` is the operator-action reset — it re-opens the gate after a trip.
    @Test
    void circuitBreaker_resets_onSetDesiredSize() {
        ctm = createCtm(timeSpan(100L).millis());
        activateConvergedThenDeficit();
        lifecycleManager.failProvision(true);
        driveDeficitReconciles(8);
        var plateauCount = lifecycleManager.provisionCount.get();
        clockMs.addAndGet(30 * 60 * 1000L);
        driveDeficitReconciles(2);
        assertThat(lifecycleManager.provisionCount.get()).as("breaker tripped").isEqualTo(plateauCount);

        ctm.setDesiredSize(5).await();
        driveDeficitReconciles(1);
        assertThat(lifecycleManager.provisionCount.get())
                .as("provisioning resumes after setDesiredSize reset")
                .isGreaterThan(plateauCount);
    }

    /// `onNodeReady` (a successful provisioning / ON_DUTY arrival) clears the failure counter.
    @Test
    void circuitBreaker_resets_onNodeReady() {
        ctm = createCtm(timeSpan(100L).millis());
        activateConvergedThenDeficit();
        lifecycleManager.failProvision(true);
        driveDeficitReconciles(8);
        var plateauCount = lifecycleManager.provisionCount.get();
        clockMs.addAndGet(30 * 60 * 1000L);
        driveDeficitReconciles(2);
        assertThat(lifecycleManager.provisionCount.get()).isEqualTo(plateauCount);

        // A node reaches ON_DUTY — resets the breaker; provider now succeeds.
        lifecycleManager.failProvision(false);
        ctm.onNodeReady(PEER_C);
        driveDeficitReconciles(1);
        assertThat(lifecycleManager.provisionCount.get())
                .as("provisioning resumes after successful node arrival")
                .isGreaterThan(plateauCount);
    }

    /// Within the backoff window a fresh deficit reconcile does NOT dispatch a new fill.
    @Test
    void circuitBreaker_backoff_defersWithinWindow() {
        ctm = createCtm(timeSpan(100L).millis());
        activateConvergedThenDeficit();
        lifecycleManager.failProvision(true);
        driveDeficitReconciles(1);
        var afterFirstFailure = lifecycleManager.provisionCount.get();
        assertThat(afterFirstFailure).isGreaterThanOrEqualTo(1);

        // Within backoff (advance 5s — below 30s base backoff): no new dispatch.
        clockMs.addAndGet(5000L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
                .as("deficit reconcile within backoff window must not dispatch")
                .isEqualTo(afterFirstFailure);
    }

    @Test
    void autoHeal_isEnabledByDefault_andToggleReturnsPriorState() {
        ctm = createCtm(timeSpan(100L).millis());
        assertThat(ctm.isAutoHealEnabled()).isTrue();
        assertThat(ctm.setAutoHealEnabled(false, "test-disable")).isTrue();
        assertThat(ctm.isAutoHealEnabled()).isFalse();
        assertThat(ctm.setAutoHealEnabled(true, "test-enable")).isFalse();
        assertThat(ctm.isAutoHealEnabled()).isTrue();
    }

    /// Operator kill-switch: with auto-heal disabled, EMPTY slots are NOT filled.
    @Test
    void autoHeal_disabled_haltsDeficitProvisioning() {
        ctm = createCtm(timeSpan(100L).millis());
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        ctm.activate();
        ctm.setAutoHealEnabled(false, "test setup");
        // Create a deficit: STOP two peers (reducer-modeled) so their slots become DEAD→EMPTY.
        clusterStore.installStopped(PEER_C);
        clusterStore.installStopped(PEER_D);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B));
        for (var attempt = 0;attempt <5;attempt++) {
            clockMs.addAndGet(1_000L);
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        }
        assertThat(lifecycleManager.provisionCount.get())
                .as("auto-heal disabled halts all slot-fill provisioning")
                .isZero();
    }

    /// Circuit-breaker state is observable for operator tooling.
    @Test
    void circuitBreakerState_reportsFailuresAndThreshold() {
        ctm = createCtm(timeSpan(100L).millis());
        var state = ctm.circuitBreakerState();
        assertThat(state.trippedAt()).isEqualTo(3);
        assertThat(state.tripped()).isFalse();
    }

    /// Operator can reset the breaker explicitly.
    @Test
    void resetCircuitBreaker_clearsFailureCount() {
        ctm = createCtm(timeSpan(100L).millis());
        activateConvergedThenDeficit();
        lifecycleManager.failProvision(true);
        driveDeficitReconciles(8);
        // Provider recovers, then operator resets — the post-reset reconcile succeeds, leaving the
        // failure counter cleared.
        lifecycleManager.failProvision(false);
        ctm.resetCircuitBreaker("operator test");
        assertThat(ctm.circuitBreakerState().consecutiveFailures()).isZero();
    }

    private void driveDeficitReconciles(int cycles) {
        for (var attempt = 0;attempt <cycles;attempt++) {
            // Advance past the FILLING-marker deadline (100ms) AND the pre-trip backoff windows
            // (30s/60s/120s) but keep the cumulative advance well under the 1h auto-reset window
            // so the breaker stays tripped once it crosses the threshold.
            clockMs.addAndGet(130_000L);
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        }
    }

    /// Activate a converged 5/5 cluster (lands Converged, not Forming), then model the reducer
    /// STOPping one core so its slot goes DEAD → freed → EMPTY, leaving a single-slot deficit the
    /// reconcile loop tries (and, when `failProvision` is set, fails) to fill — one failure per
    /// reconcile so the breaker threshold (3) is crossed deterministically.
    private void activateConvergedThenDeficit() {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        ctm.activate();
        clusterStore.installStopped(PEER_D);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C));
    }

    private void publishOnDuty(Set<NodeId> onDuty) {
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        snapshotSource.publish(new StubView(all, onDuty, onDuty.size(), 5, all, Set.of()), snapshotSource.term.get() + 1L);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {
            if (onDuty.contains(id)) {clusterStore.installOnDuty(id, epoch++);}
        }
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {}

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());
        private final AtomicLong term = new AtomicLong(0L);

        void publish(MembershipView v, long rabiaTerm) {
            view.set(Option.some(v));
            term.set(rabiaTerm);
        }

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return term.get();
        }
    }

    private static final class RecordingClusterStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("", "", "1.0.0", coreCount, 3, 9, "test", 1L, System.currentTimeMillis())));
        }

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-" + nodeId.id(), 5000, Epoch.epoch(0L, epoch)));
        }

        void installStopped(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, "host-" + nodeId.id(), 5000));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new LinkedHashMap<>(slotKv);
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {applyOne(command);}
            return Promise.success(List.of());
        }

        private void applyOne(KVCommand<AetherKey> command) {
            switch (command) {
                case KVCommand.Put<AetherKey, ?> put -> applyPut(put);
                case KVCommand.Remove<AetherKey> remove -> applyRemove(remove);
                default -> {}
            }
        }

        private void applyPut(KVCommand.Put<AetherKey, ?> put) {
            if (put.key() instanceof ProvisioningSlotKey psk && put.value() instanceof ProvisioningSlotValue psv) {
                slotKv.put(psk, psv);
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                current.set(Option.some(cv));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> remove) {
            if (remove.key() instanceof ProvisioningSlotKey psk) {slotKv.remove(psk);}
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        private final AtomicReference<Boolean> fail = new AtomicReference<>(false);

        void failProvision(boolean value) {
            fail.set(value);
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            provisionCount.incrementAndGet();

            if (fail.get()) {return Causes.cause("simulated provider API rejection").promise();}

            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + provisionCount.get()).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            terminateCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }
}
