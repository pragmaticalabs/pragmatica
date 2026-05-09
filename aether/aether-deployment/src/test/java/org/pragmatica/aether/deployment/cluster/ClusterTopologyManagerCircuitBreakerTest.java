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
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the CTM provisioning circuit breaker that bounds runaway provisioning when
/// replacement VMs consistently fail to reach ON_DUTY. After `MAX_CONSECUTIVE_PROVISIONING_FAILURES`
/// (3) consecutive slot-expiry events, further deficit-driven provisioning is halted until
/// a successful node arrival, phase NORMAL transition, leader (re)activation, or operator
/// `setDesiredSize`.
///
/// Cloud failure signature this guards against: replacement VMs that don't join within the
/// 70 s slot deadline (cloud-init speed) cause CTM to spawn another, and another, indefinitely
/// — observed last session as 7+ orphan VMs in 7 minutes.
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
    private StubClusterConfigStore configStore;
    private AtomicLong clockMs;

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
        configStore = new StubClusterConfigStore();
        configStore.seed(5);
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
                                                                         configStore::current,
                                                                         nodeId -> Option.none(),
                                                                         java.util.Map::of,
                                                                         configStore::apply,
                                                                         new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                                         LegacyLifecycleWriterFixture.create(configStore::apply,
                                                                                                              nodeId -> Option.none(),
                                                                                                              clockMs::get),
                                                                         () -> ClusterPhase.NORMAL,
                                                                         clockMs::get);
    }

    /// Establish a 5-node cluster, drop two peers, dispatch the first wave, expire it,
    /// repeat enough times to cross the failure threshold. After the breaker trips, further
    /// deficit-driven reconciles MUST NOT dispatch additional provisioning calls regardless
    /// of how much wall-clock time advances.
    @Test
    void circuitBreaker_haltsProvisioning_after3ConsecutiveSlotExpiries() {
        var slotTimeoutMs = 100L;
        var ctm = createCtm(timeSpan(slotTimeoutMs).millis());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();

        // Drop two peers — desired=5, healthyOnDuty=3, deficit=2
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);

        // Wave 1 — initial deficit, dispatches first provisioning attempt(s).
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        var afterWave1 = lifecycleManager.provisionCount.get();
        assertThat(afterWave1).isGreaterThanOrEqualTo(1);

        // Drive the breaker through its threshold — each cycle expires the slot(s), records a
        // failure, sets a fresh backoff. We advance the clock past slot deadline AND the maximum
        // backoff window each iteration to guarantee the next reconcile re-enters handleDeficit.
        for (var attempt = 0;attempt <5;attempt++) {
            clockMs.addAndGet(slotTimeoutMs + (5 * 60 * 1000L));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        }

        // After enough cycles, provisioning count plateaus — circuit tripped.
        var plateauCount = lifecycleManager.provisionCount.get();
        clockMs.addAndGet(60 * 60 * 1000L);  // advance 1 h — backoff fully clear
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
            .as("provisioning must NOT continue after circuit breaker trips, regardless of clock advance")
            .isEqualTo(plateauCount);
    }

    /// After the breaker trips, reset paths must clear it and allow provisioning to resume.
    /// `setDesiredSize` is the operator-action reset (per Fix #2 §4 in the handover).
    @Test
    void circuitBreaker_resets_onSetDesiredSize() {
        var slotTimeoutMs = 100L;
        var ctm = createCtm(timeSpan(slotTimeoutMs).millis());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));

        // Trip the breaker by repeated expirations.
        for (var attempt = 0;attempt <6;attempt++) {
            clockMs.addAndGet(slotTimeoutMs + (5 * 60 * 1000L));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        }
        var plateauCount = lifecycleManager.provisionCount.get();
        clockMs.addAndGet(60 * 60 * 1000L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
            .as("breaker must be tripped before reset")
            .isEqualTo(plateauCount);

        // Operator-driven reset: setDesiredSize re-opens the gate.
        ctm.setDesiredSize(5).await();
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
            .as("provisioning must resume after setDesiredSize reset")
            .isGreaterThan(plateauCount);
    }

    /// `onNodeReady` (slot.JOIN) is the canonical "successful provisioning" signal.
    /// A node reaching ON_DUTY clears the failure counter, allowing future deficits to
    /// dispatch provisioning normally.
    @Test
    void circuitBreaker_resets_onNodeReady() {
        var slotTimeoutMs = 100L;
        var ctm = createCtm(timeSpan(slotTimeoutMs).millis());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));

        // Trip the breaker.
        for (var attempt = 0;attempt <6;attempt++) {
            clockMs.addAndGet(slotTimeoutMs + (5 * 60 * 1000L));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        }
        var plateauCount = lifecycleManager.provisionCount.get();
        clockMs.addAndGet(60 * 60 * 1000L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        assertThat(lifecycleManager.provisionCount.get()).isEqualTo(plateauCount);

        // Replacement node arrives: snapshot reflects a successful join, then onNodeReady fires.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                            4,
                                            5),
                               3L);
        ctm.onNodeReady(PEER_C);

        // Now create a fresh deficit and verify provisioning resumes.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                            4,
                                            5),
                               4L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
            .as("provisioning must resume after successful node arrival")
            .isGreaterThan(plateauCount);
    }

    /// Backoff between failures defers the next provisioning attempt — within the backoff
    /// window, deficit-driven reconciles do NOT dispatch new provisions.
    @Test
    void circuitBreaker_backoff_defersWithinWindow() {
        var slotTimeoutMs = 100L;
        var ctm = createCtm(timeSpan(slotTimeoutMs).millis());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        var afterWave1 = lifecycleManager.provisionCount.get();
        assertThat(afterWave1).isGreaterThanOrEqualTo(1);

        // Expire slots — failure recorded, backoff window starts (>= 30 s for first failure).
        clockMs.addAndGet(slotTimeoutMs + 1L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var afterFirstExpire = lifecycleManager.provisionCount.get();

        // Within backoff (advance only 5 s — well below 30 s base backoff).
        clockMs.addAndGet(5000L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
            .as("deficit reconciles WITHIN backoff window must not dispatch new provisions")
            .isEqualTo(afterFirstExpire);
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

    private static final class StubClusterConfigStore {
        final AtomicInteger applyCount = new AtomicInteger();
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
            applyCount.incrementAndGet();
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
