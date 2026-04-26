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
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Theme K #3 — verifies that surplus-termination ordering reads the join epoch from the
/// `NodeLifecycleValue.observedCoreEpoch` atom (KV-derived) rather than from a transient
/// in-memory `nodeJoinTimes` map. The previous implementation reset the map on every
/// leader handoff, so a freshly-promoted leader could not preserve the original
/// "newest-first" termination order. The atom-based derivation survives leader transitions
/// because `observedCoreEpoch` is a stable monotonic identity advanced only at consensus
/// term transitions.
class ClusterTopologyManagerJoinEpochOrderingTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_OLDEST = nodeId("node-oldest").unwrap();
    private static final NodeId PEER_MIDDLE = nodeId("node-middle").unwrap();
    private static final NodeId PEER_NEW = nodeId("node-new").unwrap();
    private static final NodeId PEER_NEWEST = nodeId("node-newest").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_OLDEST = NodeInfo.nodeInfo(PEER_OLDEST, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_MIDDLE = NodeInfo.nodeInfo(PEER_MIDDLE, NodeAddress.nodeAddress("localhost", 5002).unwrap());
    private static final NodeInfo INFO_NEW = NodeInfo.nodeInfo(PEER_NEW, NodeAddress.nodeAddress("localhost", 5003).unwrap());
    private static final NodeInfo INFO_NEWEST = NodeInfo.nodeInfo(PEER_NEWEST, NodeAddress.nodeAddress("localhost", 5004).unwrap());

    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterConfigStore configStore;
    private Map<NodeId, NodeLifecycleValue> lifecycleAtoms;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_OLDEST, INFO_MIDDLE, INFO_NEW, INFO_NEWEST));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        configStore = new RecordingClusterConfigStore();
        configStore.seed(3);
        lifecycleAtoms = new HashMap<>();
    }

    /// `observedCoreEpoch` is a (rabiaTerm, localCounter) pair — the comparator must
    /// terminate the lexicographically-largest epochs first (newest joiners), preserving
    /// the originally-seeded older nodes. Surplus = 5 ON_DUTY → desired 3 = 2 to terminate.
    /// PEER_NEWEST (epoch (5,2)) and PEER_NEW (epoch (5,1)) should be selected; PEER_OLDEST
    /// and PEER_MIDDLE preserved.
    @Test
    void terminationOrdering_isStableAcrossSimulatedLeaderChange() throws InterruptedException {
        seedAtom(SELF, Epoch.epoch(1L, 0L));
        seedAtom(PEER_OLDEST, Epoch.epoch(1L, 1L));
        seedAtom(PEER_MIDDLE, Epoch.epoch(2L, 0L));
        seedAtom(PEER_NEW, Epoch.epoch(5L, 1L));
        seedAtom(PEER_NEWEST, Epoch.epoch(5L, 2L));

        var firstLeaderTerminations = runSurplusReconcile();
        assertThat(firstLeaderTerminations)
                .as("first leader: newest-epoch nodes selected first, oldest preserved")
                .containsExactlyInAnyOrder(PEER_NEW, PEER_NEWEST);
        assertThat(firstLeaderTerminations).doesNotContain(PEER_OLDEST, PEER_MIDDLE);

        // Simulate a leader change by recreating the CTM with the same KV-backed lifecycle
        // reader. The previous implementation lost `nodeJoinTimes` here; the atom-derived
        // comparator must observe the same ordering because `observedCoreEpoch` is
        // KV-persistent.
        var secondLeaderTerminations = runSurplusReconcile();
        assertThat(secondLeaderTerminations)
                .as("second leader after handoff: ordering survives because observedCoreEpoch is KV-derived")
                .containsExactlyInAnyOrder(PEER_NEW, PEER_NEWEST);
    }

    /// Defensive: when no lifecycle atom is present, the comparator falls back to
    /// `Epoch.ZERO`, which sorts as oldest. So an unknown node is preserved over a known
    /// recently-provisioned one.
    @Test
    void terminationOrdering_treatsMissingAtomAsOldest() throws InterruptedException {
        seedAtom(PEER_NEWEST, Epoch.epoch(5L, 2L));
        seedAtom(PEER_NEW, Epoch.epoch(5L, 1L));
        // PEER_OLDEST and PEER_MIDDLE intentionally have no lifecycle atom →
        // comparator treats them as Epoch.ZERO → oldest → preserved.

        var terminations = runSurplusReconcile();
        assertThat(terminations)
                .as("nodes with no lifecycle atom default to Epoch.ZERO and are preserved")
                .containsExactlyInAnyOrder(PEER_NEW, PEER_NEWEST);
    }

    private void seedAtom(NodeId nodeId, Epoch epoch) {
        var value = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                           nodeId.id(),
                                                           5000,
                                                           epoch);
        lifecycleAtoms.put(nodeId, value);
    }

    private List<NodeId> runSurplusReconcile() throws InterruptedException {
        lifecycleManager.terminatedNodes.clear();
        lifecycleManager.terminateCount.set(0);

        var ctm = createCtm();
        publishSurplus(5);
        ctm.activate();
        ctm.onClusterConfigChanged();

        var deadline = System.currentTimeMillis() + 2000L;
        while (lifecycleManager.terminateCount.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        synchronized (lifecycleManager.terminatedNodes) {
            return List.copyOf(lifecycleManager.terminatedNodes);
        }
    }

    private ClusterTopologyManager createCtm() {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      timeSpan(60).seconds(),
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader =
            id -> Option.option(lifecycleAtoms.get(id));
        DrainCoordinator coordinator = new ImmediateDrainCoordinator();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              configStore::current,
                                                              lifecycleReader,
                                                              configStore::apply,
                                                              coordinator);
    }

    private void publishSurplus(int onDuty) {
        var ids = new LinkedHashSet<NodeId>();
        ids.add(SELF); ids.add(PEER_OLDEST); ids.add(PEER_MIDDLE); ids.add(PEER_NEW); ids.add(PEER_NEWEST);
        var onDutySet = new LinkedHashSet<NodeId>();
        var iter = ids.iterator();
        for (int i = 0; i < onDuty && iter.hasNext(); i++) {onDutySet.add(iter.next());}
        snapshotSource.publish(new StubView(ids, onDutySet, onDuty, 3, onDutySet, Set.of()), 1L);
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
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

    private static final class RecordingClusterConfigStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("",
                                                           "",
                                                           "1.0.0",
                                                           coreCount,
                                                           3,
                                                           9,
                                                           "test",
                                                           1L,
                                                           System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                if (command instanceof KVCommand.Put<?, ?> put
                    && put.key() instanceof AetherKey.ClusterConfigKey
                    && put.value() instanceof ClusterConfigValue configValue) {
                    current.set(Option.some(configValue));
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

    private static final class ImmediateDrainCoordinator implements DrainCoordinator {
        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            return Promise.unitPromise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {}
    }
}
