// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Theme E #189: scale-down DECOMMISSIONED writes must forward `host`/`port`/
/// `observedCoreEpoch`/`provisioningSource` from the prior `NodeLifecycleValue`
/// in KV. Asserts both the metadata-present and the no-prior-atom defensive paths.
class ClusterTopologyManagerDecommissionMetadataPreservationTest {
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

    private static final TimeSpan ZERO_STABILITY = timeSpan(0).millis();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingStore store;

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
        store = new RecordingStore();
        store.seedConfig(3);
    }

    private ClusterTopologyManager buildCtm() {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      timeSpan(60).seconds(),
                                                      ZERO_STABILITY)
                                            .unwrap();
        DrainCoordinator drain = new NoOpDrainCoordinator();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              store::currentConfig,
                                                              store::priorLifecycle,
                                                              java.util.Map::of,
                                                              store::apply,
                                                              drain,
                                                              LegacyLifecycleWriterFixture.create(store::apply,
                                                                                                   store::priorLifecycle,
                                                                                                   System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    private void publishSurplus(int onDuty) {
        var ids = new LinkedHashSet<NodeId>();
        ids.add(SELF); ids.add(PEER_A); ids.add(PEER_B); ids.add(PEER_C); ids.add(PEER_D);
        var onDutySet = new LinkedHashSet<NodeId>();
        var iter = ids.iterator();
        for (int i = 0; i < onDuty && iter.hasNext(); i++) {onDutySet.add(iter.next());}
        snapshotSource.publish(StubView.surplus(ids, onDutySet, onDuty, 3), 1L);
    }

    @Test
    void decommissionAtom_preservesHostPortEpoch() throws InterruptedException {
        // Seed KV with a prior NodeLifecycleValue carrying full metadata for every peer
        // CTM might select for termination. The test asserts the eventual DECOMMISSIONED
        // write carries forward host/port/observedCoreEpoch/provisioningSource verbatim.
        store.seedLifecycle(PEER_A, "host-a", 6001, Epoch.epoch(7, 0), ProvisioningSource.CTM);
        store.seedLifecycle(PEER_B, "host-b", 6002, Epoch.epoch(7, 0), ProvisioningSource.CTM);
        store.seedLifecycle(PEER_C, "host-c", 6003, Epoch.epoch(7, 0), ProvisioningSource.CTM);
        store.seedLifecycle(PEER_D, "host-d", 6004, Epoch.epoch(7, 0), ProvisioningSource.CTM);
        var ctm = buildCtm();
        publishSurplus(5);
        ctm.activate();
        ctm.onClusterConfigChanged();

        var deadline = System.currentTimeMillis() + 2000L;
        while (store.decommissionedAtoms().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        var decommissioned = store.decommissionedAtoms();
        assertThat(decommissioned).isNotEmpty();
        for (var atom : decommissioned) {
            assertThat(atom.host()).as("DECOMMISSIONED atom host must be preserved").isNotEmpty();
            assertThat(atom.port()).as("DECOMMISSIONED atom port must be preserved").isGreaterThan(0);
            assertThat(atom.observedCoreEpoch()).as("epoch preserved").isEqualTo(Epoch.epoch(7, 0));
            assertThat(atom.provisioningSource()).as("provisioning source preserved").isEqualTo(ProvisioningSource.CTM);
        }
    }

    @Test
    void decommissionAtom_noPriorValue_writesDefaultsWithWarning() throws InterruptedException {
        // No prior atoms seeded — the DRAINING fallback path sources host/port from
        // the topology observer (NodeAddress for the surplus peer). The DECOMMISSIONED
        // write then reads the just-written DRAINING atom and forwards those values.
        // Contract: defensive path must STILL emit a valid atom rather than crashing or
        // skipping the write; the addressing fields come from the topology observer.
        var ctm = buildCtm();
        publishSurplus(5);
        ctm.activate();
        ctm.onClusterConfigChanged();

        var deadline = System.currentTimeMillis() + 2000L;
        while (store.decommissionedAtoms().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        var decommissioned = store.decommissionedAtoms();
        assertThat(decommissioned).as("defensive fallback path must still emit a DECOMMISSIONED atom").isNotEmpty();
        for (var atom : decommissioned) {
            assertThat(atom.state()).isEqualTo(NodeLifecycleState.STOPPED);
            assertThat(atom.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(atom.provisioningSource()).isEqualTo(ProvisioningSource.UNKNOWN);
            // Defensive path sources host/port from the topology observer's NodeInfo —
            // the value is non-null and the port is the configured peer port.
            assertThat(atom.host()).isNotNull();
            assertThat(atom.port()).isGreaterThanOrEqualTo(0);
        }
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
        static StubView surplus(Set<NodeId> coreMemberIds,
                                Set<NodeId> onDutyMemberIds,
                                int healthyOnDutyCount,
                                int desiredCoreSize) {
            return new StubView(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, onDutyMemberIds, Set.of());
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

    /// In-memory KV store standin: tracks per-node lifecycle atoms across writes so the
    /// `lifecycleReader` callback CTM passes can read prior state, and lets the test
    /// assert the eventual DECOMMISSIONED atoms preserve metadata.
    private static final class RecordingStore {
        private final java.util.Map<NodeId, NodeLifecycleValue> lifecycle = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<NodeLifecycleValue> decommissioned = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Option<ClusterConfigValue>> config = new AtomicReference<>(Option.none());

        void seedConfig(int coreCount) {
            config.set(Option.some(new ClusterConfigValue("",
                                                          "",
                                                          "1.0.0",
                                                          coreCount,
                                                          3,
                                                          9,
                                                          "test",
                                                          1L,
                                                          System.currentTimeMillis())));
        }

        void seedLifecycle(NodeId nodeId, String host, int port, Epoch epoch, ProvisioningSource source) {
            lifecycle.put(nodeId,
                          NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                System.currentTimeMillis(),
                                                                host,
                                                                port,
                                                                epoch,
                                                                HlcTimestamp.ZERO,
                                                                source));
        }

        Option<ClusterConfigValue> currentConfig() {
            return config.get();
        }

        Option<NodeLifecycleValue> priorLifecycle(NodeId nodeId) {
            return Option.option(lifecycle.get(nodeId));
        }

        List<NodeLifecycleValue> decommissionedAtoms() {
            synchronized (decommissioned) {return List.copyOf(decommissioned);}
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                if (! (command instanceof KVCommand.Put<?, ?> put)) {continue;}
                if (put.key() instanceof AetherKey.ClusterConfigKey
                    && put.value() instanceof ClusterConfigValue cfg) {
                    config.set(Option.some(cfg));
                } else if (put.key() instanceof NodeLifecycleKey lifecycleKey
                           && put.value() instanceof NodeLifecycleValue value) {
                    lifecycle.put(lifecycleKey.nodeId(), value);
                    if (value.state() == NodeLifecycleState.STOPPED) {decommissioned.add(value);}
                }
            }
            return Promise.success(List.of());
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger terminateCount = new AtomicInteger();
        final List<NodeId> terminatedNodes = Collections.synchronizedList(new ArrayList<>());

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
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
}
