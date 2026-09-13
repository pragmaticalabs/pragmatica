// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AutoHealStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaProjector.membershipDeltaProjector;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Membership v2 / E2: the CTM is now a pure ACTUATOR driven by the `LeaderReconciler`
/// (spec §7). The slot-occupancy reconcile loop is retired — `reconcile()` is a no-op and
/// `provisionReplacement`/`drainNode` route directly to `NodeLifecycleManager`. These tests
/// pin the surviving actuator surface plus the `setDesiredSize` config-atom write and the
/// auto-heal toggle, replacing the deleted slot-era `SnapshotDrivenDeficitTest`.
class ClusterTopologyManagerActuatorTest {
    /// RFC-0017 C1 — desired topology replaced the core-only scalar; tests that only care about a
    /// core count build a single-source entry.
    private static java.util.List<org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry> coreTopology(int count) {
        return java.util.List.of(new org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry("primary", "core", count));
    }

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

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterStore clusterStore;
    private ClusterTopologyManager ctm;
    private final CopyOnWriteArrayList<NodeId> drainCommandSinkCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> drainCommandClearCalls = new CopyOnWriteArrayList<>();
    /// #1050 — the reconciler's drain-decision inputs as the CTM re-reads them at grace expiry. Mutable
    /// so a test can move the cluster between the drain and the expiry, which is the defect's shape.
    private final AtomicReference<Set<NodeId>> coreCountedMembers = new AtomicReference<>(Set.of());
    private final AtomicInteger configuredCoreCount = new AtomicInteger(5);

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
        observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        clusterStore.seed(5);
        ctm = ctmWithDrainGrace(AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT);
    }

    /// The production-wired CTM with a chosen drain grace (`provisioningTimeout` schedules the
    /// grace-terminate backstop). Shared by `setUp` and the #1050 grace tests, which need a short grace
    /// to reach expiry through the real `drainNode` → scheduler path rather than a direct call.
    private ClusterTopologyManager ctmWithDrainGrace(TimeSpan drainGrace) {
        return ctmWithDrainGrace(drainGrace, drainCommandSinkCalls::add, coreCountedMembers::get);
    }

    /// Variant whose DRAIN sink and membership read are supplied by the caller — the real-membership
    /// tests route the drain through a real `MembershipFsm` and read its counted set, as `AetherNode` does.
    private ClusterTopologyManager ctmWithDrainGrace(TimeSpan drainGrace,
                                                     Consumer<NodeId> drainSink,
                                                     Supplier<Set<NodeId>> members) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      drainGrace,
                                                      timeSpan(0).millis())
                                            .unwrap();

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                             lifecycleManager,
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshotSource,
                                                             clusterStore::current,
                                                             clusterStore::apply,
                                                             () -> ClusterPhase.NORMAL,
                                                             drainSink,
                                                             drainCommandClearCalls::add,
                                                             Option::none,
                                                             clusterStore::autoHealState,
                                                             members,
                                                             configuredCoreCount::get);
    }

    @Test
    void provisionReplacement_invokesProvisionNodeOnce_withThreePartPeers() {
        ctm.activate();
        var result = ctm.provisionReplacement(NodeId.randomNodeId(), Option.some(PEER_C), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(lifecycleManager.provisionCount.get())
                .as("non-empty topology yields a single provisionNode call")
                .isEqualTo(1);
        var peers = lifecycleManager.lastSpec().context().peers().or("");
        assertThat(peers)
                .as("PEERS list is populated from the live topology")
                .isNotEmpty();
        for (var entry : peers.split(",")) {
            assertThat(entry.split(":"))
                    .as("each PEERS entry is a 3-part id:host:port tuple")
                    .hasSize(3);
        }
    }

    /// #678 — the cold-path fallback (`clusterMembers` empty) must not seed a discovered-but-dead
    /// peer into PEERS. PEER_A and PEER_B are both discovered (SWIM gossip added them to the dial
    /// set), but the latched snapshot's `coreMemberIds` — the observed-reachability projection
    /// (`MembershipFsm.coreObservedMembers` in production) — only carries SELF and PEER_A. PEER_B
    /// models a just-killed host that stays "discovered" forever but never completed a QUIC
    /// handshake / SWIM ALIVE: before the fix, `isDiscoveredPeer` alone would have let it into the
    /// replacement's PEERS list regardless.
    @Test
    void provisionReplacement_coldPath_excludesDiscoveredButUnreachablePeer() {
        ctm.activate();
        observer.handleDiscoveredNodes(new NetworkMessage.DiscoveredNodes(SELF, List.of(INFO_A, INFO_B)));
        snapshotSource.setCoreMembers(Set.of(SELF, PEER_A));

        var result = ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), Set.of(), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        var peers = lifecycleManager.lastSpec().context().peers().or("");
        assertThat(peers)
                .as("cold-path PEERS includes self and the reachable peer")
                .contains(SELF.id(), PEER_A.id());
        assertThat(peers)
                .as("cold-path PEERS excludes a discovered peer with no observed reachability")
                .doesNotContain(PEER_B.id());
    }

    /// Wave 2 / W4 (cluster-topology-overhaul spec): the intended role passed to
    /// `provisionReplacement` is stamped into the `ProvisionContext` verbatim — the provider
    /// boundary derives `AETHER_ROLE` / the `aether.role` label from it, never from a hardcode
    /// or the provisioning host's environment.
    @Test
    void provisionReplacement_stampsIntendedRole_intoProvisionContext() {
        ctm.activate();
        var result = ctm.provisionReplacement(NodeId.randomNodeId(), Option.some(PEER_C), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(lifecycleManager.lastSpec().context().role())
                .as("ProvisionContext carries the caller's intended role")
                .isEqualTo("core");
    }

    /// Wave 2 / W4: a WORKER intent flows through unchanged — when worker provisioning lands
    /// (#241), the same path stamps `worker` end-to-end.
    @Test
    void provisionReplacement_workerIntent_stampsWorkerRole() {
        ctm.activate();
        var result = ctm.provisionReplacement(NodeId.randomNodeId(), Option.some(PEER_C), Set.of(SELF, PEER_A, PEER_B), NodeRole.WORKER).await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(lifecycleManager.lastSpec().context().role())
                .as("ProvisionContext carries the caller's intended worker role")
                .isEqualTo("worker");
    }

    @Test
    void drainNode_enqueuesDrainCommand_forTarget_withoutSynchronousTerminate() {
        ctm.activate();
        var result = ctm.drainNode(PEER_D, DrainReason.OVERPROVISION_SCALE_DOWN).await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(drainCommandSinkCalls)
                .as("drainNode enqueues the target into the DRAIN command sink (leader ping carries DRAIN)")
                .containsExactly(PEER_D);
        assertThat(lifecycleManager.terminateCount.get())
                .as("terminate is deferred to the grace backstop, not invoked synchronously")
                .isZero();
    }

    @Test
    void reconcile_isNoOpSuccess_invokesNeitherProvisionNorTerminate() {
        ctm.activate();
        var result = ctm.reconcile().await();
        assertThat(result.isSuccess()).isTrue();
        assertThat(lifecycleManager.provisionCount.get()).isZero();
        assertThat(lifecycleManager.terminateCount.get()).isZero();
    }

    /// #148 — runaway-provisioning cap. After MAX consecutive provision failures the circuit
    /// trips and further `provisionReplacement` calls are suppressed (no new `provisionNode`),
    /// preventing the crash-loop container storm. The cap is 3 (MAX_CONSECUTIVE_PROVISIONING_FAILURES).
    @Test
    void provisionReplacement_consecutiveFailures_tripCircuit_andSuppressFurtherProvisioning() {
        ctm.activate();
        lifecycleManager.failProvisions();
        var members = Set.of(SELF, PEER_A, PEER_B);

        for (var i = 0; i < 3; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        }
        var countAfterTrip = lifecycleManager.provisionCount.get();
        // Further calls while the circuit is open must NOT reach provisionNode.
        ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();

        assertThat(countAfterTrip)
                .as("three failing provisions each reach provisionNode before the cap trips")
                .isEqualTo(3);
        assertThat(lifecycleManager.provisionCount.get())
                .as("once the circuit is open, no further provisionNode calls are made")
                .isEqualTo(3);
        assertThat(ctm.circuitBreakerState().tripped())
                .as("the circuit breaker is reported tripped after the cap")
                .isTrue();
    }

    /// #148 — operator reset clears the tripped circuit so provisioning resumes.
    @Test
    void resetCircuitBreaker_afterTrip_allowsProvisioningAgain() {
        ctm.activate();
        lifecycleManager.failProvisions();
        var members = Set.of(SELF, PEER_A, PEER_B);

        for (var i = 0; i < 3; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        }
        assertThat(ctm.circuitBreakerState().tripped()).isTrue();

        ctm.resetCircuitBreaker("test-reset");

        assertThat(ctm.circuitBreakerState().tripped())
                .as("operator reset clears the tripped circuit")
                .isFalse();
        ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        assertThat(lifecycleManager.provisionCount.get())
                .as("provisioning resumes after the reset (4th provisionNode reached)")
                .isEqualTo(4);
    }

    /// #148 — a node becoming ready resets the failure run (the crash-loop ended), re-opening
    /// provisioning.
    @Test
    void onNodeReady_afterTrip_resetsCircuit() {
        ctm.activate();
        lifecycleManager.failProvisions();
        var members = Set.of(SELF, PEER_A, PEER_B);

        for (var i = 0; i < 3; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        }
        assertThat(ctm.circuitBreakerState().tripped()).isTrue();

        ctm.onNodeReady(PEER_A);

        assertThat(ctm.circuitBreakerState().tripped())
                .as("a node becoming ready resets the provisioning circuit")
                .isFalse();
    }

    /// #148 / reconciler-under-load — a confirmed `NodeJoined` membership decision on the active
    /// (leader) CTM routes to `onNodeReady`, resetting the provisioning circuit. A genuine
    /// replacement reaching live MEMBER is provisioning-success evidence: it clears the
    /// consecutive-failure run that a rapid multi-node loss tripped, un-stalling auto-heal — the
    /// previously-dead wire (`onNodeReady` had zero production callers) the symmetric `NodeRemoved`
    /// reap edge already had.
    @Test
    void onMembershipDecision_nodeJoined_whileActive_resetsTrippedCircuit() {
        ctm.activate();
        lifecycleManager.failProvisions();
        var members = Set.of(SELF, PEER_A, PEER_B);

        for (var i = 0; i < 3; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        }
        assertThat(ctm.circuitBreakerState().tripped()).isTrue();

        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_C, List.of(SELF, PEER_A, PEER_B)));

        assertThat(ctm.circuitBreakerState().tripped())
                .as("a confirmed core join resets the tripped provisioning circuit")
                .isFalse();
        lifecycleManager.allowProvisions();
        ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        assertThat(lifecycleManager.provisionCount.get())
                .as("provisioning resumes after the confirmed-join reset (4th provisionNode reached)")
                .isEqualTo(4);
    }

    /// #148 / reconciler-under-load — the confirmed-join reset is leader-owned (single-writer
    /// rule), mirroring the `NodeRemoved` reap gate. An inactive (non-leader) CTM ignores
    /// membership decisions, so a `NodeJoined` never resets its circuit.
    @Test
    void onMembershipDecision_nodeJoined_whileInactive_doesNotResetCircuit() {
        ctm.activate();
        lifecycleManager.failProvisions();
        var members = Set.of(SELF, PEER_A, PEER_B);

        for (var i = 0; i < 3; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        }
        assertThat(ctm.circuitBreakerState().tripped()).isTrue();

        ctm.deactivate();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_C, List.of(SELF, PEER_A, PEER_B)));

        assertThat(ctm.circuitBreakerState().tripped())
                .as("an inactive (non-leader) CTM never resets — the active leader owns the join edge")
                .isTrue();
    }

    /// #148 — a successful provision keeps the circuit closed (no false trip).
    @Test
    void provisionReplacement_success_keepsCircuitClosed() {
        ctm.activate();
        var members = Set.of(SELF, PEER_A, PEER_B);

        ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();

        assertThat(ctm.circuitBreakerState().tripped())
                .as("a successful provision never trips the circuit")
                .isFalse();
        assertThat(ctm.circuitBreakerState().consecutiveFailures())
                .as("a successful provision records no failures")
                .isZero();
    }

    /// Auto-heal-wedge fix — once the circuit is OPEN a suppressed `provisionReplacement` resolves to
    /// a success-valued `Deferred(CIRCUIT_OPEN)` disposition (NOT a phantom Dispatched, NOT a failure),
    /// reaches NO `provisionNode`, and records NO new provisioning failure. This is the disposition the
    /// `LeaderReconciler` keys on to REMOVE its in-flight placeholder so the deficit stays visible — the
    /// fix that un-wedges auto-heal once a transient failure burst trips the breaker.
    @Test
    void provisionReplacement_circuitOpen_resolvesDeferred_withoutBootOrNewFailure() {
        ctm.activate();
        lifecycleManager.failProvisions();
        var members = Set.of(SELF, PEER_A, PEER_B);

        for (var i = 0; i < 3; i++) {
            ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();
        }
        assertThat(ctm.circuitBreakerState().tripped())
                .as("three failing provisions trip the circuit")
                .isTrue();
        var failuresAfterTrip = ctm.circuitBreakerState().consecutiveFailures();
        var provisionCountAfterTrip = lifecycleManager.provisionCount.get();

        var disposition = ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();

        assertThat(disposition.isSuccess())
                .as("a circuit-open deferral is a success-valued disposition, not a failure")
                .isTrue();
        disposition.onSuccess(value -> assertThat(value)
                .as("the disposition is a Deferred(CIRCUIT_OPEN), so the reconciler removes its placeholder")
                .isEqualTo(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.CIRCUIT_OPEN)));
        assertThat(lifecycleManager.provisionCount.get())
                .as("a circuit-open deferral boots nothing — no new provisionNode call")
                .isEqualTo(provisionCountAfterTrip);
        assertThat(ctm.circuitBreakerState().consecutiveFailures())
                .as("a deferral is NOT a failure — the consecutive-failure count is unchanged")
                .isEqualTo(failuresAfterTrip);
    }

    /// Auto-heal-wedge fix — a real boot resolves to a success-valued `Dispatched` disposition (a VM is
    /// coming), which is what the `LeaderReconciler` keys on to KEEP its in-flight placeholder.
    @Test
    void provisionReplacement_realBoot_resolvesDispatched() {
        ctm.activate();
        var members = Set.of(SELF, PEER_A, PEER_B);

        var disposition = ctm.provisionReplacement(NodeId.randomNodeId(), Option.none(), members, NodeRole.CORE).await();

        assertThat(disposition.isSuccess()).isTrue();
        disposition.onSuccess(value -> assertThat(value)
                .as("a real boot resolves to Dispatched, so the reconciler keeps its placeholder")
                .isEqualTo(ProvisionDisposition.dispatched()));
    }

    /// #166 — a confirmed `NodeRemoved` membership decision on the active (leader) CTM reaps the
    /// departed node's container so a phantom that would otherwise restart-loop back into
    /// SWIM-HEALTHY cannot resurrect. The reap is idempotent: `terminateNode` is a no-op on an
    /// already-exited node.
    @Test
    void onMembershipDecision_nodeRemoved_whileActive_reapsDepartedContainer() {
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of(SELF, PEER_A, PEER_B)));
        assertThat(lifecycleManager.terminatedNodeIds())
                .as("confirmed removal reaps the departed node's container")
                .containsExactly(PEER_C);
    }

    /// #166 — a `NodeDecommissioned` decision (permanent departure) reaps the container for the
    /// same phantom-prevention reason as `NodeRemoved`.
    @Test
    void onMembershipDecision_nodeDecommissioned_whileActive_reapsDepartedContainer() {
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeDecommissioned(PEER_D, List.of(SELF, PEER_A, PEER_B)));
        assertThat(lifecycleManager.terminatedNodeIds())
                .as("confirmed decommission reaps the departed node's container")
                .containsExactly(PEER_D);
    }

    /// #166 — the reap is leader-owned (single-writer rule). A non-active CTM (deactivated / not
    /// leader) ignores membership decisions entirely, so no reap is issued.
    @Test
    void onMembershipDecision_nodeRemoved_whileInactive_doesNotReap() {
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of(SELF, PEER_A, PEER_B)));
        assertThat(lifecycleManager.terminateCount.get())
                .as("an inactive (non-leader) CTM never reaps — the active leader owns the prune")
                .isZero();
    }

    @Test
    void setDesiredSize_writesClusterConfigValueAtom_withIncrementedVersion() {
        ctm.activate();
        var before = clusterStore.currentVersion();
        var result = ctm.setDesiredCount(sourceNameOrDefault("primary"), NodeRole.CORE, 7).await();
        assertThat(result.isSuccess()).isTrue();
        var after = clusterStore.current().unwrap();
        assertThat(after.coreCount()).isEqualTo(7);
        assertThat(after.configVersion()).isEqualTo(before + 1);
    }

    @Test
    void setDesiredSize_belowQuorum_rejectedWithoutAtomWrite() {
        ctm.activate();
        var before = clusterStore.currentVersion();
        var result = ctm.setDesiredCount(sourceNameOrDefault("primary"), NodeRole.CORE, 2).await();
        assertThat(result.isFailure()).isTrue();
        assertThat(clusterStore.currentVersion()).isEqualTo(before);
    }

    @Test
    void setAutoHealEnabled_toggleReturnsPriorState() {
        assertThat(ctm.isAutoHealEnabled()).isTrue();
        assertThat(ctm.setAutoHealEnabled(false, "test-disable").await().unwrap()).isTrue();
        assertThat(ctm.isAutoHealEnabled()).isFalse();
        assertThat(ctm.setAutoHealEnabled(true, "test-enable").await().unwrap()).isFalse();
        assertThat(ctm.isAutoHealEnabled()).isTrue();
    }

    /// #1022 — auto-heal creates a BILLABLE server that the operator's cleanup ledger structurally
    /// cannot hold: the ledger is `~/.aether/clusters/<name>/bootstrap-state.json` on the operator's
    /// machine, written by `aether/cli`, while this code runs in `aether-deployment` on a cloud VM in a
    /// module `cli` depends ON. The leader's own journal is the only record it can make, so the
    /// provider's instance id has to appear there — until this fix `asDispatched` took the
    /// `InstanceInfo` and ignored it, and the id of every replacement was dropped on the floor.
    ///
    /// This drives the REAL `provisionReplacement` and captures what the production logger emits. It
    /// deliberately does NOT hand the logger the expected line itself: a fixture that feeds in the
    /// string it then asserts states the intended behaviour rather than probing the actual one, and
    /// would stay green with the production call deleted.
    @Nested
    class ProvisionedInstanceRecording {
        private static final String LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.ClusterTopologyManager";

        private CapturingAppender appender;
        private LoggerConfig loggerConfig;
        private Level originalLevel;

        @BeforeEach
        void attachAppender() {
            appender = CapturingAppender.create("CtmProvisionRecordCapture");
            appender.start();
            var ctx = (LoggerContext) LogManager.getContext(false);
            loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
            originalLevel = loggerConfig.getLevel();
            loggerConfig.addAppender(appender, Level.WARN, null);
            loggerConfig.setLevel(Level.WARN);
            ctx.updateLoggers();
        }

        @AfterEach
        void detachAppender() {
            var ctx = (LoggerContext) LogManager.getContext(false);

            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(originalLevel);
            ctx.updateLoggers();
            appender.stop();
        }

        private LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
            var existing = configuration.getLoggerConfig(LOGGER_NAME);

            if (LOGGER_NAME.equals(existing.getName())) {
                return existing;
            }

            var created = new LoggerConfig(LOGGER_NAME, Level.WARN, false);

            configuration.addLogger(LOGGER_NAME, created);

            return created;
        }

        /// The stub provisioner mints `stub-1` as the provider instance id; that id, the new node id and
        /// the role must all reach the journal, because an operator reconciling a bill against a cluster
        /// has only the id to act on.
        @Test
        void provisionReplacement_success_recordsTheProviderInstanceId_withNodeIdAndRole() {
            ctm.activate();
            var newNodeId = NodeId.randomNodeId();

            var result = ctm.provisionReplacement(newNodeId,
                                                  Option.some(PEER_C),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isSuccess()).as("the stub provisioner succeeds: %s", result).isTrue();
            assertThat(appender.capturedWarns())
                    .as("the provider instance id of a billable auto-heal VM must reach the leader's journal")
                    .anyMatch(msg -> msg.contains("instanceId=stub-1")
                                     && msg.contains("nodeId=" + newNodeId.id())
                                     && msg.contains("role=core"));
        }

        /// A provisioning attempt that FAILED created nothing, so recording an instance for it would put
        /// a phantom server in front of an operator. Nothing was billed and nothing may be claimed.
        @Test
        void provisionReplacement_failure_recordsNoInstance() {
            ctm.activate();
            lifecycleManager.failProvisions();

            var result = ctm.provisionReplacement(NodeId.randomNodeId(),
                                                  Option.some(PEER_C),
                                                  Set.of(SELF, PEER_A, PEER_B),
                                                  NodeRole.CORE)
                            .await();

            assertThat(result.isFailure()).as("the stub was told to fail: %s", result).isTrue();
            assertThat(appender.capturedWarns())
                    .as("a failed provision created no server, so it must claim no instance id")
                    .noneMatch(msg -> msg.contains("instanceId="));
        }
    }

    /// #1050 — a surplus drain is decided while the cluster has a surplus, but its grace-terminate reap
    /// fires `provisioningTimeout` later, by which time nodes may have died or leadership may have moved.
    /// Every test here drives the REAL path — `drainNode` schedules the backstop on the shared scheduler
    /// with a short grace — and moves the cluster between the drain and the expiry, which is the defect's
    /// shape. The DRAIN-command clear is the backstop's LAST action, so awaiting it proves the expiry ran
    /// before a "not reaped" assertion is read; without that control a zero terminate count would equally
    /// describe a backstop that never fired.
    @Nested
    class DrainGraceRecheck {
        private static final NodeId PEER_E = nodeId("node-e").unwrap();
        private static final Set<NodeId> SPARE_FIVE = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_E);

        private ClusterTopologyManager shortGraceCtm;

        @BeforeEach
        void activateShortGraceCtm() {
            shortGraceCtm = ctmWithDrainGrace(timeSpan(150).millis());
            shortGraceCtm.activate();
        }

        /// The no-regression half: on a stable cluster the drained target is DEPARTING and uncounted, and
        /// the five members that remain still cover the configured five, so the surplus trim completes.
        @Test
        void surplusDrain_graceExpiry_reapsTarget_whenClusterCanStillSpareIt() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitDrainCommandCleared(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds())
                    .as("a surplus trim on a cluster that can still spare the node reaps it")
                    .containsExactly(PEER_D);
        }

        /// The observed shape, quorum still held: one member dies during the grace, leaving four of five.
        @Test
        void surplusDrain_graceExpiry_keepsTarget_whenClusterFellIntoDeficit() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            coreCountedMembers.set(Set.of(SELF, PEER_A, PEER_B, PEER_C));
            awaitDrainCommandCleared(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds())
                    .as("a surplus decision gone stale by expiry (deficit) must not reap")
                    .isEmpty();
        }

        /// The observed log: grace expiry fired one second after this CTM was deactivated.
        @Test
        void surplusDrain_graceExpiry_keepsTarget_whenIssuerNoLongerLeader() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            shortGraceCtm.deactivate();
            awaitDrainCommandCleared(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds())
                    .as("a deposed issuer's backstop must not reap, even with spare capacity")
                    .isEmpty();
        }

        /// The observed counts: `clusterMembershipCount=2 quorumSafe=false` before expiry.
        @Test
        void surplusDrain_graceExpiry_keepsTarget_whenNotQuorumSafe() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            coreCountedMembers.set(Set.of(SELF, PEER_A));
            awaitDrainCommandCleared(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds())
                    .as("a surplus trim must not reap once the remaining members are below quorum")
                    .isEmpty();
        }

        /// The scope guard: a join-grace zombie is reaped DURING the deficit its replacement was meant to
        /// fill, and nothing else reaps it, so the re-check must not apply to it.
        @Test
        void joinGraceReapDrain_graceExpiry_reapsZombie_evenDuringDeficit() {
            coreCountedMembers.set(Set.of(SELF, PEER_A, PEER_B));

            shortGraceCtm.drainNode(PEER_D, DrainReason.JOIN_GRACE_REAP).await();
            awaitDrainCommandCleared(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds())
                    .as("a JOIN_GRACE_REAP zombie has no other reaper and must be reaped regardless of deficit")
                    .containsExactly(PEER_D);
        }

        @Test
        void graceReapVerdict_spareCapacity_reaps() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, SPARE_FIVE, 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.REAP);
        }

        @Test
        void graceReapVerdict_deposedIssuer_isNotLeader_evenWithSpareCapacity() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(false, SPARE_FIVE, 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.NOT_LEADER);
        }

        @Test
        void graceReapVerdict_belowQuorum_isNotQuorumSafe() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, Set.of(SELF, PEER_A), 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.NOT_QUORUM_SAFE);
        }

        @Test
        void graceReapVerdict_quorateButShort_isDeficit() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, Set.of(SELF, PEER_A, PEER_B, PEER_C), 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.DEFICIT);
        }

        /// A target that refuted its drain is counted again; it must not count toward covering its own removal.
        @Test
        void graceReapVerdict_targetStillCounted_doesNotCoverItsOwnRemoval() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.DEFICIT);
        }

        private void awaitDrainCommandCleared(NodeId target) {
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> drainCommandClearCalls.contains(target));
            assertThat(drainCommandClearCalls).as("the backstop clears the DRAIN command exactly once, in every branch")
                                              .containsExactly(target);
        }
    }

    /// #1050 CTO ruling: a refused reap must never leave a billed orphan VM. Here the stub membership
    /// supplier is replaced by the REAL producers:
    /// - a boot-seeded [MembershipFsm] whose DRAIN routing mirrors `AetherNode.requestDrainThroughFsm`,
    ///   read through `coreCountedMembers` as `AetherNode.drainGraceCoreMemberSupplier` reads it;
    /// - a synchronous `MembershipDeltaProjector` whose decisions feed `onMembershipDecision`, as
    ///   AetherNode's router does.
    ///
    /// Deaths and departures go through the FSM's SWIM ingress (`onSwimDeparted`), so any reap observed
    /// after one has come through FSM REMOVED edge → projector `NodeRemoved` → `reapDepartedNode`. The
    /// FSM's own DEPARTING timeout (#1054) is set to an hour, so it cannot reap inside a test and mask what
    /// the backstop did. One node-local FSM stands in for every node's FSM; each node sees the same
    /// REMOVED edge for a departed target.
    @Nested
    class DrainGraceWithRealMembership {
        private static final NodeId PEER_E = nodeId("node-e").unwrap();
        private static final TimeSpan HOUR = timeSpan(1).hours();
        private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);

        private MembershipFsm fsm;

        @BeforeEach
        void createFsm() {
            fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis, Long.MAX_VALUE, HOUR, HOUR, HOUR);
        }

        /// The stable-cluster no-regression, on real membership: six seeded cores for a configured five.
        /// After the drain the target is DEPARTING and uncounted, five remain, and the trim reaps.
        @Test
        void surplusDrain_realMembership_stableCluster_reapsAtGraceExpiry() {
            var issuer = issuerCtm();

            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// The orphan pin, deficit arm. A peer dies during the grace, so the reap is refused at expiry.
        /// The target then departs, and its instance is terminated EXACTLY ONCE, by the departure path.
        @Test
        void surplusDrain_realMembership_skippedInDeficit_targetDeparts_reapedExactlyOnceViaNodeRemoved() {
            var issuer = issuerCtm();

            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            fsm.onSwimDeparted(PEER_E, 1L);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(fsm.coreCountedMembers()).as("arming: the cluster really is four of five at expiry")
                                                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C);
            assertThat(lifecycleManager.terminatedNodeIds()).as("the backstop refused; only the dead peer has been reaped")
                                                            .containsExactly(PEER_E);

            fsm.onSwimDeparted(PEER_D, 2L);

            assertThat(lifecycleManager.terminatedNodeIds()).as("the departed target is reaped once, via NodeRemoved; no orphan, no double reap")
                                                            .containsExactly(PEER_E, PEER_D);
        }

        /// The orphan pin, deposed-issuer arm. The issuer loses leadership before expiry, so its backstop
        /// refuses. When the target departs, the NEW leader's active CTM reaps it exactly once, and the
        /// deposed issuer ignores the `NodeRemoved`.
        @Test
        void surplusDrain_realMembership_skippedByDeposedIssuer_targetDeparts_reapedExactlyOnceByNewLeader() {
            var issuer = issuerCtm();
            var newLeader = ctmWithDrainGrace(timeSpan(150).millis(), _ -> {}, fsm::coreCountedMembers);

            wireAndSeed(((Consumer<MembershipDecision>) issuer::onMembershipDecision).andThen(newLeader::onMembershipDecision));
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            issuer.deactivate();
            newLeader.activate();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).as("a deposed issuer's backstop refused")
                                                            .isEmpty();

            fsm.onSwimDeparted(PEER_D, 2L);

            assertThat(lifecycleManager.terminatedNodeIds()).as("the active new leader reaps the departed target once; the deposed issuer does not")
                                                            .containsExactly(PEER_D);
        }

        /// The live-member arm. The target refuted its drain (DEPARTING→MEMBER at a higher incarnation)
        /// and is a counted member again, while a peer died. Five are counted only WITH the target, so
        /// the cluster needs it: the reap is refused and the node stays.
        @Test
        void surplusDrain_realMembership_targetStillLiveCountedMember_clusterNeedsIt_notReaped() {
            var issuer = issuerCtm();

            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            fsm.onSwimDeparted(PEER_E, 1L);
            fsm.onSwimHealthy(PEER_D, 5L);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(fsm.coreCountedMembers()).as("arming: the target is a live counted member again, and five are counted only with it")
                                                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
            assertThat(lifecycleManager.terminatedNodeIds()).as("a live target the cluster needs is never reaped by the backstop")
                                                            .containsExactly(PEER_E);
        }

        private ClusterTopologyManager issuerCtm() {
            return ctmWithDrainGrace(timeSpan(150).millis(), fsmRoutedDrainSink(), fsm::coreCountedMembers);
        }

        /// Mirrors `AetherNode.requestDrainThroughFsm`: the DRAIN command registry, then the FSM's
        /// DrainRequested, which moves the target to DEPARTING.
        private Consumer<NodeId> fsmRoutedDrainSink() {
            return ((Consumer<NodeId>) drainCommandSinkCalls::add).andThen(fsm::onDrainRequested);
        }

        /// Attach the projector BEFORE seeding. The seed's JOINED edges must be announced, because an
        /// unannounced member's death emits no `NodeRemoved`. Then seed six cores for a configured five.
        private void wireAndSeed(Consumer<MembershipDecision> decisionSink) {
            var projector = membershipDeltaProjector(() -> true,
                                                     () -> 1L,
                                                     () -> HLC,
                                                     decisionSink,
                                                     _ -> {},
                                                     _ -> {},
                                                     _ -> {},
                                                     Runnable::run,
                                                     SharedScheduler::schedule);

            fsm.onMembershipDelta(projector::onDelta);
            fsm.seed(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, PEER_E));
        }

        private void awaitClearedExactlyOnce(NodeId target) {
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> drainCommandClearCalls.contains(target));
            assertThat(drainCommandClearCalls).as("the backstop clears the DRAIN command exactly once, in every branch")
                                              .containsExactly(target);
        }
    }

    /// Collects WARN messages so a test can assert on what production actually emitted. Mirrors the
    /// appender in `ClusterTopologyManagerCasLossLoggingTest`.
    static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
            super(name, filter, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, null, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return List.copyOf(messages);
        }
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());
        private final AtomicLong term = new AtomicLong(0L);

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return term.get();
        }

        /// #678 test hook — latches a snapshot whose `coreMemberIds()` is exactly the given set,
        /// simulating the production `PresenceGenerationSnapshotSource` wiring where that set is
        /// `MembershipFsm.coreObservedMembers` (discovered peers narrowed to observed reachability).
        void setCoreMembers(Set<NodeId> coreMemberIds) {
            view.set(Option.some(new FixedMembershipView(coreMemberIds)));
        }
    }

    private record FixedMembershipView(Set<NodeId> coreMemberIds) implements MembershipView {
        @Override public Set<NodeId> onDutyMemberIds() {
            return coreMemberIds;
        }

        @Override public int healthyOnDutyCount() {
            return coreMemberIds.size();
        }

        @Override public int desiredCoreSize() {
            return coreMemberIds.size();
        }
    }

    private static final class RecordingClusterStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());
        private final AtomicReference<Option<AutoHealStateValue>> autoHealState = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("", "", "1.0.0", coreTopology(coreCount), 3, 9, "test",
                                                           current.get().map(ClusterConfigValue::configVersion).or(0L) + 1L,
                                                           System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Option<AutoHealStateValue> autoHealState() {
            return autoHealState.get();
        }

        long currentVersion() {
            return current.get().map(ClusterConfigValue::configVersion).or(0L);
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
            } else if (put.key() instanceof ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                current.set(Option.some(cv));
            } else if (put.key() instanceof AutoHealStateKey && put.value() instanceof AutoHealStateValue ahv) {
                autoHealState.set(Option.some(ahv));
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> remove) {
            if (remove.key() instanceof ProvisioningSlotKey psk) {slotKv.remove(psk);}
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        private final CopyOnWriteArrayList<NodeId> terminatedIds = new CopyOnWriteArrayList<>();
        private final AtomicReference<ProvisionSpec> lastSpec = new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean failProvision = new java.util.concurrent.atomic.AtomicBoolean(false);

        List<NodeId> terminatedNodeIds() {
            return List.copyOf(terminatedIds);
        }

        ProvisionSpec lastSpec() {
            return lastSpec.get();
        }

        void failProvisions() {
            failProvision.set(true);
        }

        void allowProvisions() {
            failProvision.set(false);
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var count = provisionCount.incrementAndGet();
            lastSpec.set(spec);
            if (failProvision.get()) {
                return org.pragmatica.lang.utils.Causes.cause("stub provision failure").promise();
            }
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + count).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            terminateCount.incrementAndGet();
            terminatedIds.add(nodeId);
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
