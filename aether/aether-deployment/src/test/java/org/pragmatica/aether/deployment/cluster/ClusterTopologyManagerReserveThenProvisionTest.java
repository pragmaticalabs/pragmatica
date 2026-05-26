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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
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
import org.pragmatica.lang.Cause;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Reserve-then-provision contract (#230). Verifies the two structural fixes that close the
/// provision-before-reserve race:
///
/// 1. The provider spawn (`lifecycleManager.provisionNode`) is chained INSIDE the FILLING
///    reservation commit. A reservation-commit failure (consensus down) records a provisioning
///    failure and spawns NOTHING.
/// 2. A per-slot-index in-flight guard prevents two overlapping reconciles from double-spawning
///    into the same EMPTY slot: exactly one `provisionNode` per EMPTY slot index even when fill
///    is driven twice before the first completes.
///
/// The stability gate is 0ms so deficit dispatch is immediate.
class ClusterTopologyManagerReserveThenProvisionTest {
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

    private static final String PROVIDER_ID = "aether-a-node-6";
    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterStore clusterStore;

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
    }

    private ClusterTopologyManager createCtm() {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      timeSpan(60).seconds(),
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
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
                                                                                                   System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    @Nested
    class ReservationFailure {
        /// When the FILLING reservation commit fails (consensus down), NO container is spawned and
        /// the circuit-breaker observes the failure.
        @Test
        void reservationCommitFails_noProvisionNodeCall_andFailureRecorded() throws InterruptedException {
            var ctm = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B));
            // Fail every FILLING reservation Put (no occupant + spawnedAtMs>0). Reseed/seed/free
            // puts are untouched so activation still establishes the slot set.
            clusterStore.failFillingReservations(true);
            ctm.activate();
            // Give the (synchronous-but-failing) reservation path a moment to run through its
            // onFailure branch on every empty slot.
            Thread.sleep(150L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("reservation commit failed → provider must NOT be called")
                    .isZero();
            assertThat(ctm.circuitBreakerState().consecutiveFailures())
                    .as("reservation failure feeds the provisioning circuit-breaker")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    class InFlightSlotGuard {
        /// Two overlapping reconciles must not double-spawn into the same EMPTY slot. The provider
        /// hangs so the first wave's per-index claims stay held; a second reconcile driven before
        /// the first completes finds every EMPTY index claimed and skips. Net: exactly one
        /// provisionNode per EMPTY slot index (here slots 3-4 → 2 spawns max), never doubled.
        @Test
        void concurrentReconciles_oneProvisionPerEmptySlot() throws InterruptedException {
            var ctm = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B));
            lifecycleManager.hang(true);
            ctm.activate();
            awaitProvision(1);
            var afterFirstWave = lifecycleManager.provisionCount.get();
            // Drive a second reconcile while the first wave's provisions are still hung: the EMPTY
            // indices are claimed (in-flight) so this reconcile must add no new spawns.
            ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_B, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            Thread.sleep(150L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("in-flight slot guard: no extra spawn while first wave is still in flight")
                    .isEqualTo(afterFirstWave);
            assertThat(afterFirstWave)
                    .as("at most one spawn per EMPTY slot index (slots 3-4 of a 5-cluster)")
                    .isLessThanOrEqualTo(2);
        }
    }

    @Nested
    class HappyPath {
        /// Reservation commits → provisionNode → bind → assignOccupant; the occupant reaching
        /// ON_DUTY makes the slot HEALTHY and reconcile converges.
        @Test
        void reservationCommits_thenProvisionBindsAndSlotReachesHealthy() throws InterruptedException {
            lifecycleManager.echoedId.set(PROVIDER_ID);
            var ctm = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C));
            ctm.activate();
            // Slot 4 is EMPTY → reserved → provisioned → bound to the provider-echoed id.
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            awaitProvision(1);
            assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);
            var boundToProvider = clusterStore.slots()
                                              .values()
                                              .stream()
                                              .map(ProvisioningSlotValue::assignedNodeId)
                                              .filter(Option::isPresent)
                                              .map(Option::unwrap)
                                              .anyMatch(id -> id.id().equals(PROVIDER_ID));
            assertThat(boundToProvider).as("slot bound to the provider-allocated occupant after reservation").isTrue();
            // The provisioned occupant reaches ON_DUTY → slot HEALTHY → reconcile converges.
            var realId = nodeId(PROVIDER_ID).unwrap();
            clusterStore.installOnDuty(realId, 9L);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, realId));
            ctm.onNodeReady(realId);
            assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
        }
    }

    private void awaitProvision(int atLeast) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.provisionCount.get() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    private void publishOnDuty(Set<NodeId> onDuty) {
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, nodeId(PROVIDER_ID).unwrap());
        snapshotSource.publish(StubView.stubView(onDuty, onDuty, onDuty.size(), 5), snapshotSource.term.get() + 1L);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, nodeId(PROVIDER_ID).unwrap())) {
            if (onDuty.contains(id) && clusterStore.lifecycle(id).isEmpty()) {clusterStore.installOnDuty(id, epoch++);}
        }
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
        static StubView stubView(Set<NodeId> coreMemberIds,
                                 Set<NodeId> onDutyMemberIds,
                                 int healthyOnDutyCount,
                                 int desiredCoreSize) {
            return new StubView(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, coreMemberIds, Set.of());
        }
    }

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
        private final AtomicReference<Boolean> failFilling = new AtomicReference<>(false);

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("", "", "1.0.0", coreCount, 3, 9, "test", 1L, System.currentTimeMillis())));
        }

        void failFillingReservations(boolean value) {
            failFilling.set(value);
        }

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                          "host-" + nodeId.id(),
                                                                          5000,
                                                                          org.pragmatica.aether.slice.generation.Epoch.epoch(0L, epoch)));
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
            if (failFilling.get() && containsFillingReservation(commands)) {
                return RESERVATION_REJECTED.promise();
            }
            for (var command : commands) {applyOne(command);}
            return Promise.success(List.of());
        }

        private static boolean containsFillingReservation(List<KVCommand<AetherKey>> commands) {
            return commands.stream().anyMatch(RecordingClusterStore::isFillingReservation);
        }

        private static boolean isFillingReservation(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.Put<AetherKey, ?> put
                   && put.value() instanceof ProvisioningSlotValue psv
                   && psv.spawnedAtMs() > 0L
                   && psv.assignedNodeId().isEmpty();
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

    private static final Cause RESERVATION_REJECTED = Causes.cause("stub: FILLING reservation rejected (consensus down)");

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicReference<String> echoedId = new AtomicReference<>(PROVIDER_ID);
        private final AtomicReference<Boolean> hang = new AtomicReference<>(false);

        void hang(boolean value) {
            hang.set(value);
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var count = provisionCount.incrementAndGet();

            if (hang.get()) {return Promise.promise();}

            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + count).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND,
                                                             Map.of(),
                                                             Option.option(echoedId.get())).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
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
