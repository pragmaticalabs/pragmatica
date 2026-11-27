// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ActionResult;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainSink;
import org.pragmatica.aether.deployment.cluster.MembershipLiveness;
import org.pragmatica.aether.deployment.cluster.NodeAction;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2315 round 1, F2: every path that ends a drain attempt releases what it holds. The real topology manager runs over the real
/// `TopologyDrains` and `DrainCommandRegistry`, wired as `TopologyAssembly` wires them. A rejoin eviction ends its attempt at the
/// gate (the EVICTION arm of `armOrRelease`) while its drain stays commanded until the node's DEAD edge; the id the manager's
/// holder keeps for the attempt must go with the attempt, or it stays for the life of the process.
class TopologyDrainsEvictionReleaseTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId TARGET = nodeId("node-d").unwrap();
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);
    private static final String EVICTED = OperatorWarningCode.NODE_RETIRED_IDENTITY_REJOINED.code();

    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();
    private final TopologyDrains drains = TopologyDrains.topologyDrains(registry);
    private final CopyOnWriteArrayList<String> raised = new CopyOnWriteArrayList<>();
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        ctm = manager();
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.setOperatorWarningSink(OperatorWarningSink.handingOffTo(this::record));
        ctm.activate();
    }

    @AfterEach
    void tearDown() {
        ctm.deactivate();
    }

    private void record(OperatorWarning warning) {
        raised.add(warning.code().code());
    }

    /// Red when: the EVICTION arm removes the attempt and leaves its id in the holder.
    @Test
    void evictionOfARejoinedRetiredNode_endsItsAttempt_andReleasesTheIdItHeld() {
        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(TARGET, "worker", HLC));

        await().atMost(Duration.ofSeconds(5)).until(() -> raised.contains(EVICTED));
        assertThat(registry.isDrainRequested(TARGET)).as("instrument: the eviction's drain is commanded and stays so").isTrue();
        assertThat(drains.held()).as("the attempt ended; its id is still held").isZero();
    }

    private ClusterTopologyManager manager() {
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var peer = NodeInfo.nodeInfo(nodeId("node-a").unwrap(), NodeAddress.nodeAddress("localhost", 5001).unwrap());
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self, peer));
        var snapshots = new EmptySnapshotSource();
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshots).unwrap();
        var liveness = MembershipLiveness.membershipLiveness(Set::of,
                                                             Set::of,
                                                             _ -> false,
                                                             _ -> false,
                                                             Set::of,
                                                             () -> 5,
                                                             _ -> Option.none());

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                             new RetiringLifecycle(),
                                                             AutoHealConfig.DEFAULT,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshots,
                                                             TopologyDrainsEvictionReleaseTest::clusterConfig,
                                                             _ -> Promise.success(List.of()),
                                                             () -> ClusterPhase.NORMAL,
                                                             DrainSink.synchronous(drains::request, _ -> {}, drains::release),
                                                             drains::clear,
                                                             Option::none,
                                                             liveness);
    }

    private static Option<ClusterConfigValue> clusterConfig() {
        return Option.some(new ClusterConfigValue(Option.none(),
                                                  "",
                                                  "1.0.0",
                                                  List.of(new TopologyEntry("primary", "core", 5)),
                                                  3,
                                                  9,
                                                  "test",
                                                  1L,
                                                  System.currentTimeMillis()));
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class EmptySnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Answers every re-admission as a retired identity, which is what makes the join an EVICTION.
    private static final class RetiringLifecycle implements NodeLifecycleManager {
        @Override
        public Promise<Unit> readmitExternal(NodeId nodeId) {
            return new RetiredIdentityRejoined("rejoined", "its-replacement").promise();
        }

        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<InstanceInfo>> instancesForNode(NodeId nodeId) {
            return Promise.success(List.of());
        }

        @Override
        public boolean isCloudManaged() {
            return true;
        }
    }
}
