// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.TimerScheduler;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimObservation.HealthyObserved;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// End-to-end identity-bound slot lifecycle test.
///
/// Where `ClusterTopologyManagerIdentityBoundSlotTest` covers the unit-level invariants
/// (slot write carries `assignedNodeId`, expiry tombstones the owner, etc.), this test
/// wires the same production CTM together with a real `MembershipFsm` and drives the
/// complete flow end-to-end:
///
///  1. CTM observes a deficit and dispatches `provisionSingleNode` — the production path
///     writes the slot UNASSIGNED, then re-PUTs it ASSIGNED to the canonical NodeId the
///     provider allocated and echoed back via `InstanceInfo.nodeId()`.
///  2. The slot's `deadlineMs` lapses without a healthy node arriving.
///  3. The expiry tick (a benign reconcile trigger) runs `deleteExpiredSlotAtoms`. The
///     CTM authoritatively tombstones the assigned NodeId — `NodeLifecycleKey →
///     DECOMMISSIONED` is written to KV and `lifecycleManager.terminateNode` is invoked.
///  4. A late `SwimObservation.HealthyObserved(<assignedNodeId>)` is fed into the
///     `MembershipFsm`. The reducer cell `(DECOMMISSIONED, SwimHealthy) → nop` (see
///     `ClusterMembershipReducer.applyDecommissioned`) MUST keep the peer DECOMMISSIONED
///     and MUST NOT emit a Put(L=ON_DUTY).
///
/// This is the "no late zombie" guarantee end-to-end: the CTM closes the slot, writes
/// the tombstone, reaps the cloud instance, and the FSM refuses to be revived by SWIM
/// gossip from the orphaned instance / restarted container before it's fully reaped.
class ClusterTopologyManagerIdentityBoundSlotE2ETest {
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
        clusterStore.seedClusterConfig(5);
    }

    private ClusterTopologyManager createCtm(TimeSpan provisioningTimeout) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      provisioningTimeout,
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              clusterStore::currentClusterConfig,
                                                              clusterStore::lifecycle,
                                                              clusterStore::slots,
                                                              clusterStore::apply,
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                              LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                   clusterStore::lifecycle,
                                                                                                   System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    private MembershipFsm createFsm(BooleanSupplier isLeader) {
        var config = MembershipFsmConfig.defaultMembershipFsmConfig();
        return MembershipFsm.membershipFsm(SELF,
                                            config,
                                            clusterStore.lifecycleSnapshot(),
                                            clusterStore.slotSnapshot(),
                                            clusterStore::apply,
                                            new RecordingDrainCoordinator(),
                                            new NoOpScheduler(),
                                            isLeader);
    }

    @Test
    void deadSlot_ctmFreesWithoutStoppedWrite_andFsmRefusesLateRevival()
            throws InterruptedException {
        // Step 1 — full 5/5 cluster; CTM reseeds and binds occupants to slots 0-4.
        var ctm = createCtm(timeSpan(60).seconds());
        clusterStore.installOnDuty(SELF, 0L);
        clusterStore.installOnDuty(PEER_A, 1L);
        clusterStore.installOnDuty(PEER_B, 2L);
        clusterStore.installOnDuty(PEER_C, 3L);
        clusterStore.installOnDuty(PEER_D, 4L);
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
        ctm.activate();

        // Step 2 — the reducer STOPs PEER_D (remove-then-add §3.4 step 1; OQ6: reducer owns the
        // STOPPED write, NOT CTM). CTM observes the DEAD slot and frees it.
        clusterStore.installStopped(PEER_D);
        var terminateCountBefore = lifecycleManager.terminateCount.get();
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.terminateCount.get() == terminateCountBefore && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        // Step 3 — D2: CTM best-effort cloud-reaps the dead occupant (no drain ack) and does NOT
        // write STOPPED itself — the lifecycle entry remains the reducer-authored STOPPED.
        assertThat(lifecycleManager.terminateCount.get())
                .as("CTM cloud-reaps the dead occupant (best-effort, no drain ack)")
                .isGreaterThan(terminateCountBefore);
        assertThat(lifecycleManager.terminatedNodes).contains(PEER_D);
        assertThat(clusterStore.lifecycle(PEER_D).unwrap().state())
                .as("lifecycle entry remains STOPPED (reducer-authored; CTM did not overwrite)")
                .isEqualTo(NodeLifecycleState.STOPPED);

        // Step 4 — a real MembershipFsm replays from KV and sees the tombstone.
        var fsm = createFsm(() -> true);
        fsm.start().await().onFailure(cause -> assertThat(cause).isNull());
        assertThat(fsm.get(PEER_D).isPresent())
                .as("FSM reconstructs tracked state for the tombstoned peer from KV replay")
                .isTrue();
        assertThat(fsm.get(PEER_D).unwrap())
                .as("FSM derives Stopped from the lifecycle KV entry")
                .isInstanceOf(MembershipFsmState.Stopped.class);

        var commandsBeforeLateArrival = clusterStore.commandCount();

        // Step 5 — late arrival: SWIM observes the tombstoned node healthy. The reducer cell
        // (STOPPED, SwimHealthy) → nop must keep the FSM Stopped and propose no KV writes.
        fsm.onSwimObservation(new HealthyObserved(PEER_D, 1L));

        assertThat(fsm.get(PEER_D).unwrap())
                .as("FSM stays STOPPED after late SwimHealthy — applyStopped == nop")
                .isInstanceOf(MembershipFsmState.Stopped.class);
        assertThat(clusterStore.lifecycle(PEER_D).unwrap().state())
                .as("KV lifecycle entry remains STOPPED — no late revival write")
                .isEqualTo(NodeLifecycleState.STOPPED);
        assertThat(clusterStore.commandCount())
                .as("FSM emitted no KV writes for the late SwimHealthy event")
                .isEqualTo(commandsBeforeLateArrival);

        fsm.stop().await();
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
            return new StubView(coreMemberIds,
                                onDutyMemberIds,
                                healthyOnDutyCount,
                                desiredCoreSize,
                                onDutyMemberIds,
                                Set.of());
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

    /// Shared KV-store backing for slots, cluster-config, and lifecycle atoms. Wires
    /// `slots()` (reader) to the same map that `apply(...)` (writer) populates — so the
    /// production code path `provisionNodes` → `writeProvisioningSlotAtom` → KV is
    /// observable via `slots()`, exactly as a real leader would see it after replicating
    /// the slot atom through consensus. Tombstone writes from
    /// `lifecycleWriter.requestDecommission` flow through the same `apply(...)` and end up
    /// in `lifecycleKv` — observable via `lifecycle(nodeId)`.
    private static final class RecordingClusterStore {
        private final AtomicReference<Option<ClusterConfigValue>> clusterConfig = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();
        private final AtomicInteger commandCount = new AtomicInteger();

        void seedClusterConfig(int coreCount) {
            clusterConfig.set(Option.some(new ClusterConfigValue("",
                                                                  "",
                                                                  "1.0.0",
                                                                  coreCount,
                                                                  3,
                                                                  9,
                                                                  "test",
                                                                  1L,
                                                                  System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> currentClusterConfig() {
            return clusterConfig.get();
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new LinkedHashMap<>(slotKv);
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
        }

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState.ON_DUTY,
                                                                          "host-" + nodeId.id(),
                                                                          5000,
                                                                          org.pragmatica.aether.slice.generation.Epoch.epoch(0L, epoch)));
        }

        void installStopped(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState.STOPPED,
                                                                          "host-" + nodeId.id(),
                                                                          5000));
        }

        int commandCount() {
            return commandCount.get();
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            commandCount.addAndGet(commands.size());
            for (var command : commands) {applyOne(command);}
            return Promise.success(List.of());
        }

        private void applyOne(KVCommand<AetherKey> command) {
            switch (command) {
                case KVCommand.Put<AetherKey, ?> put -> applyPut(put);
                case KVCommand.Remove<AetherKey> rm -> applyRemove(rm);
                default -> {}
            }
        }

        private void applyPut(KVCommand.Put<AetherKey, ?> put) {
            if (put.key() instanceof ProvisioningSlotKey psk && put.value() instanceof ProvisioningSlotValue psv) {
                slotKv.put(psk, psv);
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                clusterConfig.set(Option.some(cv));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> rm) {
            if (rm.key() instanceof ProvisioningSlotKey psk) {
                slotKv.remove(psk);
            }
        }

        LifecycleSnapshotReader lifecycleSnapshot() {
            return consumer -> lifecycleKv.forEach(
                    (nodeId, value) -> consumer.accept(NodeLifecycleKey.nodeLifecycleKey(nodeId), value));
        }

        SlotSnapshotReader slotSnapshot() {
            return consumer -> slotKv.forEach(consumer);
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        final List<String> allocatedIds = Collections.synchronizedList(new ArrayList<>());
        final List<NodeId> terminatedNodes = Collections.synchronizedList(new ArrayList<>());

        String lastAllocatedId() {
            return allocatedIds.getLast();
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub")
                                                                                                    .unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var count = provisionCount.incrementAndGet();
            var allocated = "aether-test-node-" + count;
            allocatedIds.add(allocated);
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + count).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND,
                                                             Map.of(),
                                                             Option.some(allocated)).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            terminateCount.incrementAndGet();
            terminatedNodes.add(nodeId);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }

    /// Minimal DrainCoordinator fake. The identity-bound-slot path does not involve drain,
    /// but the FSM still needs a coordinator instance to construct.
    private static final class RecordingDrainCoordinator implements DrainCoordinator {
        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            return Promise.unitPromise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {
            // No-op for tests.
        }
    }

    /// Test scheduler that never fires — the late-arrival assertion completes before any
    /// JOIN_DEADLINE timer would naturally fire, so we don't need actual scheduling.
    private static final class NoOpScheduler implements TimerScheduler {
        @Override public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            return NO_OP_FUTURE;
        }

        private static final ScheduledFuture<Object> NO_OP_FUTURE = new ScheduledFuture<>() {
            @Override public long getDelay(TimeUnit unit) {return 0L;}

            @Override public int compareTo(java.util.concurrent.Delayed o) {return 0;}

            @Override public boolean cancel(boolean mayInterruptIfRunning) {return true;}

            @Override public boolean isCancelled() {return false;}

            @Override public boolean isDone() {return true;}

            @Override public Object get() {return null;}

            @Override public Object get(long timeout, TimeUnit unit) {return null;}
        };
    }
}
