// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
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
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Surplus-occupant reaping refinement: a SURPLUS orphan is an ON_DUTY node bound to NO durable
/// slot. Slot-routed membership never targets it, but slice placement (ON_DUTY-filtered) still can,
/// so a LIVE orphan is drained — under a SHORT bounded budget, not the full graceful-drain timeout —
/// before termination, while a STOPPED orphan is fast-terminated with NO drain. The 5 genuine slot
/// occupants are never reaped.
class ClusterTopologyManagerSurplusReapTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();
    private static final NodeId ORPHAN = nodeId("node-zzz-orphan").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());
    private static final NodeInfo INFO_C = NodeInfo.nodeInfo(PEER_C, NodeAddress.nodeAddress("localhost", 5003).unwrap());
    private static final NodeInfo INFO_D = NodeInfo.nodeInfo(PEER_D, NodeAddress.nodeAddress("localhost", 5004).unwrap());
    private static final NodeInfo INFO_ORPHAN = NodeInfo.nodeInfo(ORPHAN, NodeAddress.nodeAddress("localhost", 5005).unwrap());

    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();
    private static final TimeSpan FULL_PROVISIONING_TIMEOUT = timeSpan(60).seconds();

    private static final List<NodeId> FIVE_KEEPERS = List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterConfigStore configStore;
    private RecordingSlotStore slotStore;
    private final Map<NodeId, NodeLifecycleValue> lifecycleByNode = new HashMap<>();

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D, INFO_ORPHAN));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        configStore = new RecordingClusterConfigStore();
        configStore.seed(5);
        slotStore = new RecordingSlotStore();
        lifecycleByNode.clear();
    }

    private ClusterTopologyManager createCtm(DrainCoordinator drainCoordinator) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      FULL_PROVISIONING_TIMEOUT,
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              configStore::current,
                                                              this::lifecycleOf,
                                                              slotStore::snapshot,
                                                              this::applyCommands,
                                                              drainCoordinator,
                                                              LegacyLifecycleWriterFixture.create(configStore::apply,
                                                                                                  this::lifecycleOf,
                                                                                                  System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    private Option<NodeLifecycleValue> lifecycleOf(NodeId nodeId) {
        return Option.option(lifecycleByNode.get(nodeId));
    }

    /// Both the cluster-config store and the slot store observe every command, so a slot PUT and a
    /// lifecycle PUT in the same write-set are each routed to the right recorder.
    private Promise<List<Object>> applyCommands(List<KVCommand<AetherKey>> commands) {
        slotStore.apply(commands);

        return configStore.apply(commands);
    }

    /// Five slots fully bound to the five keepers; ORPHAN is ON_DUTY but bound to no slot — a stable
    /// surplus orphan the periodic reaper must squeeze out.
    private void seedFullSlotsAndSurplus(NodeLifecycleState orphanState) {
        slotStore.bindKeepers(FIVE_KEEPERS);
        lifecycleByNode.put(ORPHAN, lifecycleValue(orphanState));
        FIVE_KEEPERS.forEach(node -> lifecycleByNode.put(node, lifecycleValue(NodeLifecycleState.ON_DUTY)));
        var onDuty = new LinkedHashSet<>(FIVE_KEEPERS);
        onDuty.add(ORPHAN);
        snapshotSource.publish(new StubView(onDuty, onDuty, 6, 5), 1L);
    }

    private static NodeLifecycleValue lifecycleValue(NodeLifecycleState state) {
        return new NodeLifecycleValue(state,
                                      System.currentTimeMillis(),
                                      "localhost",
                                      5000,
                                      org.pragmatica.aether.slice.generation.Epoch.ZERO,
                                      org.pragmatica.hlc.HlcTimestamp.ZERO,
                                      org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource.CTM);
    }

    private void awaitTerminate(long timeoutMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (lifecycleManager.terminateCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    @Nested
    class LiveSurplusOrphan {
        @Test
        void liveOrphan_drainsUnderShortTimeout_notFullProvisioningBudget() throws InterruptedException {
            var coordinator = new TimeoutRecordingDrainCoordinator();
            var ctm = createCtm(coordinator);
            seedFullSlotsAndSurplus(NodeLifecycleState.ON_DUTY);
            ctm.activate();
            ctm.onClusterConfigChanged();

            awaitTerminate(3000L);

            assertThat(lifecycleManager.terminatedNodes)
                    .as("live surplus orphan must be terminated")
                    .contains(ORPHAN);
            assertThat(coordinator.lastTimeout.get())
                    .as("live surplus orphan must drain under the SHORT bounded budget, not the full provisioning timeout")
                    .isNotNull();
            assertThat(coordinator.lastTimeout.get().millis())
                    .as("short surplus-drain budget must be well below the 60s provisioning timeout")
                    .isLessThan(FULL_PROVISIONING_TIMEOUT.millis());
            assertThat(configStore.observedLifecycleStates())
                    .as("live orphan must drain (DRAINING written) so slices migrate before terminate")
                    .contains(NodeLifecycleState.DRAINING);
        }
    }

    @Nested
    class DeadSurplusOrphan {
        @Test
        void deadOrphan_fastTerminated_noDrain() throws InterruptedException {
            var coordinator = new TimeoutRecordingDrainCoordinator();
            var ctm = createCtm(coordinator);
            seedFullSlotsAndSurplus(NodeLifecycleState.STOPPED);
            ctm.activate();
            ctm.onClusterConfigChanged();

            awaitTerminate(3000L);

            assertThat(lifecycleManager.terminatedNodes)
                    .as("dead surplus orphan must be terminated")
                    .contains(ORPHAN);
            assertThat(coordinator.awaitCount.get())
                    .as("dead surplus orphan must take the fast-free path (NO drain ack awaited)")
                    .isZero();
            assertThat(configStore.observedLifecycleStates())
                    .as("dead surplus orphan must NOT be drained")
                    .doesNotContain(NodeLifecycleState.DRAINING);
        }
    }

    @Nested
    class KeepersUntouched {
        @Test
        void keepers_areNeverReaped_onSurplusReap() throws InterruptedException {
            var coordinator = new TimeoutRecordingDrainCoordinator();
            var ctm = createCtm(coordinator);
            seedFullSlotsAndSurplus(NodeLifecycleState.ON_DUTY);
            ctm.activate();
            ctm.onClusterConfigChanged();

            awaitTerminate(3000L);

            assertThat(lifecycleManager.terminatedNodes)
                    .as("the 5 bound slot occupants must never be reaped — only the unbound orphan")
                    .doesNotContainAnyElementsOf(FIVE_KEEPERS);
            assertThat(lifecycleManager.terminatedNodes)
                    .as("convergence reaps exactly the surplus orphan")
                    .containsExactly(ORPHAN);
        }
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
        StubView(Set<NodeId> coreMemberIds, Set<NodeId> onDutyMemberIds, int healthyOnDutyCount, int desiredCoreSize) {
            this(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, onDutyMemberIds, Set.of());
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

    /// Durable slot map keyed by stable integer index, accepting slot PUT/Remove commands so the CTM
    /// reseed write-set is observed and reflected back (the reseed binds keepers; the post-reseed
    /// reconcile reads the bound slots back from here).
    private static final class RecordingSlotStore {
        private final Map<ProvisioningSlotKey, ProvisioningSlotValue> slots = Collections.synchronizedMap(new LinkedHashMap<>());

        void bindKeepers(List<NodeId> keepers) {
            synchronized (slots) {
                slots.clear();
                for (var index = 0; index < keepers.size(); index++) {
                    slots.put(ProvisioningSlotKey.provisioningSlotKey(Integer.toString(index)),
                              new ProvisioningSlotValue(1L, Long.MAX_VALUE, Option.some(keepers.get(index)), 1L, Option.none()));
                }
            }
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> snapshot() {
            synchronized (slots) {return Map.copyOf(slots);}
        }

        void apply(List<KVCommand<AetherKey>> commands) {
            synchronized (slots) {
                for (var command : commands) {
                    applyOne(command);
                }
            }
        }

        private void applyOne(KVCommand<AetherKey> command) {
            if (command instanceof KVCommand.Put<?, ?> put
                && put.key() instanceof ProvisioningSlotKey key
                && put.value() instanceof ProvisioningSlotValue value) {
                slots.put(key, value);
            } else if (command instanceof KVCommand.Remove<?> remove && remove.key() instanceof ProvisioningSlotKey key) {
                slots.remove(key);
            }
        }
    }

    private static final class RecordingClusterConfigStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());
        private final List<NodeLifecycleState> lifecycleWrites = Collections.synchronizedList(new ArrayList<>());

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("", "", "1.0.0", coreCount, 3, 9, "test", 1L, System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        List<NodeLifecycleState> observedLifecycleStates() {
            synchronized (lifecycleWrites) {return List.copyOf(lifecycleWrites);}
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                if (command instanceof KVCommand.Put<?, ?> put) {
                    if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue configValue) {
                        current.set(Option.some(configValue));
                    } else if (put.key() instanceof NodeLifecycleKey && put.value() instanceof NodeLifecycleValue lifecycle) {
                        lifecycleWrites.add(lifecycle.state());
                    }
                }
            }
            return Promise.success(List.of());
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        final List<NodeId> terminatedNodes = Collections.synchronizedList(new ArrayList<>());

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            provisionCount.incrementAndGet();
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + provisionCount.get()).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
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

    /// Records the timeout passed to `awaitDrainAck` so the test can assert the SHORT surplus budget
    /// was used (vs the full provisioning timeout) and resolves immediately.
    private static final class TimeoutRecordingDrainCoordinator implements DrainCoordinator {
        final AtomicInteger awaitCount = new AtomicInteger();
        final AtomicReference<TimeSpan> lastTimeout = new AtomicReference<>();

        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            awaitCount.incrementAndGet();
            lastTimeout.set(timeout);
            return Promise.unitPromise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {
        }
    }
}
