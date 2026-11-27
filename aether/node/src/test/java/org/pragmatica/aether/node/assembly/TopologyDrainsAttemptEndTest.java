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

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ActionResult;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainGate;
import org.pragmatica.aether.deployment.cluster.DrainReason;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #2315 round 1, F2: every path that ends a drain attempt reaches the holder of the id the attempt took. The real topology manager
/// runs over the real `TopologyDrains` and `DrainCommandRegistry`, wired as `TopologyAssembly` wires them (the eviction end is pinned
/// by `TopologyDrainsAttemptEndTest`). Each end that clears the command leaves neither a held id nor a commanded drain; the
/// failed request, which leaves the command, leaves no held id.
class TopologyDrainsAttemptEndTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId TARGET = nodeId("node-d").unwrap();
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);
    private static final String CANCELLED = OperatorWarningCode.NODE_DRAIN_CANCELLED_REJOINED.code();

    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();
    private final TopologyDrains drains = TopologyDrains.topologyDrains(registry);
    private final CopyOnWriteArrayList<String> raised = new CopyOnWriteArrayList<>();
    private ClusterTopologyManager ctm;

    @AfterEach
    void tearDown() {
        ctm.deactivate();
    }

    private void start(AutoHealConfig config, DrainSink sink) {
        ctm = manager(config, sink);
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.setOperatorWarningSink(OperatorWarningSink.handingOffTo(this::record));
        ctm.activate();
    }

    private void record(OperatorWarning warning) {
        raised.add(warning.code().code());
    }

    private DrainSink productionSink() {
        return DrainSink.synchronous(drains::request,
                                     _ -> {},
                                     drains::release);
    }

    private void drainTheTarget() {
        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND).await();
        assertThat(registry.isDrainRequested(TARGET)).as("instrument: the attempt's drain is commanded").isTrue();
        assertThat(drains.held()).as("instrument: the attempt holds its id").isEqualTo(1);
    }

    /// Red when: deactivate clears the pending map without clearing the commands and releasing the ids.
    @Test
    void deactivate_withAPendingDrain_releasesTheIdAndClearsTheCommand() {
        start(AutoHealConfig.DEFAULT, productionSink());
        drainTheTarget();
        ctm.deactivate();
        assertThat(drains.held()).as("deactivate left an id held").isZero();
        assertThat(registry.isDrainRequested(TARGET)).as("deactivate left the drain commanded").isFalse();
    }

    /// Red when: the cancel of a pending drain (the node joined again) removes the attempt and keeps its id.
    @Test
    void cancelOfAPendingDrain_releasesTheIdAndClearsTheCommand() {
        start(AutoHealConfig.DEFAULT, productionSink());
        drainTheTarget();
        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(TARGET, "worker", HLC));
        await().atMost(Duration.ofSeconds(5)).until(() -> raised.contains(CANCELLED));
        assertThat(drains.held()).as("the cancel left an id held").isZero();
        assertThat(registry.isDrainRequested(TARGET)).as("the cancel left the drain commanded").isFalse();
    }

    /// Red when: the grace-terminate backstop removes the attempt and keeps its id.
    @Test
    void graceTerminate_releasesTheIdAndClearsTheCommand() {
        start(AutoHealConfig.DEFAULT.withProvisioningTimeout(timeSpan(1).seconds()),
              productionSink());
        drainTheTarget();
        await().atMost(Duration.ofSeconds(10)).until(() -> !registry.isDrainRequested(TARGET));
        assertThat(drains.held()).as("the grace-terminate left an id held").isZero();
    }

    /// Red when: a request that failed after the sink took its id leaves the attempt's id held. The synchronous sink fails before it
    /// takes an id; this sink takes the id and then fails, which is the order the manager's failure row has to survive.
    @Test
    void failedRequest_releasesTheIdItsAttemptTook() {
        start(AutoHealConfig.DEFAULT, failingAfterTakingTheId());
        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND).await();

        assertThat(outcome.isFailure()).as("instrument: the request failed").isTrue();
        assertThat(drains.held()).as("the failed attempt left an id held").isZero();
    }

    private DrainSink failingAfterTakingTheId() {
        return new DrainSink() {
            @Override
            public Promise<Unit> request(NodeId target, DrainGate gate) {
                drains.request(target);

                return Causes.cause("apply failed").promise();
            }

            @Override
            public Option<Promise<Unit>> inFlight(NodeId target) {
                return Option.none();
            }

            @Override
            public void release(NodeId target) {
                drains.release(target);
            }
        };
    }

    private ClusterTopologyManager manager(AutoHealConfig autoHeal, DrainSink sink) {
        var self = NodeInfo.nodeInfo(SELF,
                                     NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var peer = NodeInfo.nodeInfo(nodeId("node-a").unwrap(),
                                     NodeAddress.nodeAddress("localhost", 5001).unwrap());
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
                                                             new ReadmittingLifecycle(),
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshots,
                                                             TopologyDrainsAttemptEndTest::clusterConfig,
                                                             _ -> Promise.success(List.of()),
                                                             () -> ClusterPhase.NORMAL,
                                                             sink,
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

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class,
                        _ -> {});

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

    /// Answers every re-admission as done: the join is a new incarnation, not an eviction.
    private static final class ReadmittingLifecycle implements NodeLifecycleManager {
        @Override
        public Promise<Unit> readmitExternal(NodeId nodeId) {
            return Promise.unitPromise();
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
