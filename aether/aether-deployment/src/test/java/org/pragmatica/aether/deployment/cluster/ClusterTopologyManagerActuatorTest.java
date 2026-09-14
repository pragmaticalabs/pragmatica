// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ClusterName;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final NodeId PEER_E = nodeId("node-e").unwrap();
    private static final Set<NodeId> SPARE_FIVE = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_E);
    private static final Set<NodeId> ALL_SIX = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, PEER_E);
    private static final String CLUSTER = "c1050";
    private static final TimeSpan HOUR = timeSpan(1).hours();
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);
    /// #1050 / #1062 — the membership and liveness evidence the CTM consults before an irreversible reap.
    /// Mutable, so a test can move the cluster between a drain and its expiry, which is the defect's shape.
    private final AtomicReference<Set<NodeId>> coreCountedMembers = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<NodeId>> trackedMembers = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<NodeId>> swimAliveNodes = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<NodeId>> transportConnectedNodes = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<NodeId>> inFlightNodes = new AtomicReference<>(Set.of());
    private final AtomicInteger configuredCoreCount = new AtomicInteger(5);
    /// Counts reads of the transport evidence, so a test can prove a deferral's re-checks STOP.
    private final AtomicInteger transportReads = new AtomicInteger();
    /// Same for the raw-SWIM evidence: a reap that stopped reading it was ABANDONED, not merely deferred.
    private final AtomicInteger swimReads = new AtomicInteger();

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

    /// The production-wired CTM with a chosen drain grace (`provisioningTimeout` schedules the grace-terminate
    /// backstop, and also derives the activation-replay grace and the reap re-check interval). Reads the stub
    /// evidence in the mutable fields above.
    private ClusterTopologyManager ctmWithDrainGrace(TimeSpan drainGrace) {
        return ctmWithDrainGrace(drainGrace, drainCommandSinkCalls::add, stubLiveness());
    }

    private MembershipLiveness stubLiveness() {
        return MembershipLiveness.membershipLiveness(coreCountedMembers::get,
                                                     trackedMembers::get,
                                                     this::swimAlive,
                                                     this::transportConnected,
                                                     inFlightNodes::get,
                                                     configuredCoreCount::get,
                                                     _ -> Option.none());
    }

    private boolean transportConnected(NodeId nodeId) {
        transportReads.incrementAndGet();

        return transportConnectedNodes.get().contains(nodeId);
    }

    private boolean swimAlive(NodeId nodeId) {
        swimReads.incrementAndGet();

        return swimAliveNodes.get().contains(nodeId);
    }

    /// Variant whose DRAIN sink and evidence are supplied by the caller — the real-membership tests route the
    /// drain through a real `MembershipFsm` and read its projections, as `AetherNode` does.
    private ClusterTopologyManager ctmWithDrainGrace(TimeSpan drainGrace,
                                                     Consumer<NodeId> drainSink,
                                                     MembershipLiveness liveness) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), drainGrace).unwrap();

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
                                                             liveness);
    }

    /// Lets scheduled work run for `window` before an ABSENCE is asserted. Used only where no positive signal
    /// exists; each such test states the margin it relies on.
    private static void settleFor(Duration window) {
        await().pollDelay(window)
               .atMost(window.plusSeconds(5))
               .until(() -> true);
    }

    private void awaitClearedExactlyOnce(NodeId target) {
        await().atMost(Duration.ofSeconds(5))
               .until(() -> drainCommandClearCalls.contains(target));
        assertThat(drainCommandClearCalls).as("the backstop clears the DRAIN command exactly once, in every branch")
                                          .containsExactly(target);
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

    /// #1050 R1′ — a surplus drain's grace-terminate reap fires `provisioningTimeout` after the drain, by which
    /// time nodes may have died, leadership may have moved, or the target may have returned to service. Every
    /// drive-level test here goes through the REAL path: `drainNode` schedules the backstop on the shared
    /// scheduler with a short grace. The DRAIN-command clear is the backstop's LAST action, so awaiting it
    /// proves the expiry ran before a "not reaped" assertion is read.
    @Nested
    class DrainGraceRecheck {
        private ClusterTopologyManager shortGraceCtm;

        @BeforeEach
        void activateShortGraceCtm() {
            shortGraceCtm = ctmWithDrainGrace(timeSpan(150).millis());
            shortGraceCtm.activate();
        }

        /// No regression: the drained target is DEPARTING or gone (uncounted, not SWIM-alive), the issuer is
        /// active, and the members that remain are quorum-safe, so the surplus trim reaps.
        @Test
        void surplusDrain_graceExpiry_reapsNonLiveTarget_whenActiveAndQuorumSafe() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// R1′(b): a deficit no longer blocks the reap of a node that is NOT live — terminating it removes no
        /// capacity. Four of five remain, which is quorum-safe.
        @Test
        void surplusDrain_graceExpiry_reapsNonLiveTarget_evenInDeficit() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            coreCountedMembers.set(Set.of(SELF, PEER_A, PEER_B, PEER_C));
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// R1′(a): a live, counted target is never reaped — here with the issuer active and spare capacity, the
        /// case in which the round-2 verdict still reaped.
        @Test
        void surplusDrain_graceExpiry_keepsLiveCountedTarget_evenWhenActiveAndQuorumSafe() {
            coreCountedMembers.set(ALL_SIX);
            swimAliveNodes.set(Set.of(PEER_D));

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).as("a surplus trim never kills a live, counted node")
                                                            .isEmpty();
        }

        /// A counted target that raw SWIM no longer sees alive is NOT a live counted member, so the verdict permits
        /// the reap. The reap itself still defers while the node is counted (evidence of life), and completes once
        /// the FSM stops counting it.
        @Test
        void surplusDrain_graceExpiry_countedButSwimDeadTarget_reapedOnceUncounted() {
            var slowCtm = ctmWithDrainGrace(timeSpan(1200).millis());

            slowCtm.activate();
            coreCountedMembers.set(ALL_SIX);
            swimAliveNodes.set(SPARE_FIVE);
            slowCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).as("still counted: the reap is deferred, not executed")
                                                            .isEmpty();

            coreCountedMembers.set(SPARE_FIVE);
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// R1′(a) ordering: a target that is live at expiry is skipped outright, and the grace path schedules no
        /// deferred reap. If it dies moments later, the backstop does not reap it; its death goes through the
        /// departure path or the activation replay, like any other death. The margin is five re-check intervals.
        @Test
        void surplusDrain_graceExpiry_liveTargetAtExpiry_isNotReapedByTheBackstopEvenIfItDiesLater() {
            var slowCtm = ctmWithDrainGrace(timeSpan(1200).millis());

            slowCtm.activate();
            coreCountedMembers.set(ALL_SIX);
            swimAliveNodes.set(ALL_SIX);
            slowCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);
            coreCountedMembers.set(SPARE_FIVE);
            swimAliveNodes.set(SPARE_FIVE);
            settleFor(Duration.ofMillis(500));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// R1′(a) refined (verify-1058): LIVE means liveness evidence, not counted membership. The target is DEPARTING
        /// (uncounted) but still SWIM-alive and reachable by the leader's transport, as a target whose DRAIN was never
        /// delivered is. It is never reaped by the backstop, even once it dies afterwards. A verdict keyed on counted
        /// membership would call it not live, defer the reap on its evidence, and reap it when the evidence cleared.
        @Test
        void surplusDrain_graceExpiry_departingButAliveTarget_isNeverReapedByTheBackstop() {
            var slowCtm = ctmWithDrainGrace(timeSpan(1200).millis());

            slowCtm.activate();
            coreCountedMembers.set(SPARE_FIVE);
            swimAliveNodes.set(ALL_SIX);
            transportConnectedNodes.set(Set.of(PEER_D));
            slowCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);
            swimAliveNodes.set(SPARE_FIVE);
            transportConnectedNodes.set(Set.of());
            settleFor(Duration.ofMillis(500));

            assertThat(lifecycleManager.terminatedNodeIds()).as("a DEPARTING-but-alive target is live: the backstop skips it outright")
                                                            .isEmpty();
        }

        /// R1′(b): a deposed issuer never reaps, even a target that is not live.
        @Test
        void surplusDrain_graceExpiry_keepsTarget_whenIssuerNoLongerLeader() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            shortGraceCtm.deactivate();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// R1′(b): a minority view never reaps.
        @Test
        void surplusDrain_graceExpiry_keepsTarget_whenNotQuorumSafe() {
            coreCountedMembers.set(SPARE_FIVE);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            coreCountedMembers.set(Set.of(SELF, PEER_A));
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// S2: an unknown configured core size (a count read as 0) is NOT quorum-safe — fail-closed, where the
        /// round-2 formula floored it to 1 and reaped.
        @Test
        void surplusDrain_graceExpiry_keepsTarget_whenConfiguredCoreCountUnknown() {
            coreCountedMembers.set(SPARE_FIVE);
            configuredCoreCount.set(0);

            shortGraceCtm.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// The scope guard: a join-grace zombie is reaped DURING the deficit its replacement was meant to fill,
        /// and nothing else reaps it, so the re-check must not apply to it.
        @Test
        void joinGraceReapDrain_graceExpiry_reapsZombie_evenDuringDeficit() {
            coreCountedMembers.set(Set.of(SELF, PEER_A, PEER_B));

            shortGraceCtm.drainNode(PEER_D, DrainReason.JOIN_GRACE_REAP).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// N4: the zombie path never reads membership, so no membership read can delay or break it. Driven on a
        /// NON-activated CTM, because activation itself reads membership for the replay.
        @Test
        void joinGraceReapDrain_graceExpiry_neverReadsMembership() {
            var membershipReads = new AtomicInteger();
            var countingLiveness = MembershipLiveness.membershipLiveness(() -> countRead(membershipReads),
                                                                         () -> countRead(membershipReads),
                                                                         _ -> false,
                                                                         _ -> false,
                                                                         Set::of,
                                                                         () -> countRead(membershipReads).size(),
                                                                         _ -> Option.none());
            var zombieCtm = ctmWithDrainGrace(timeSpan(150).millis(), drainCommandSinkCalls::add, countingLiveness);

            zombieCtm.drainNode(PEER_D, DrainReason.JOIN_GRACE_REAP).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
            assertThat(membershipReads.get()).as("a JOIN_GRACE_REAP reap must not depend on any membership read")
                                             .isZero();
        }

        private Set<NodeId> countRead(AtomicInteger reads) {
            reads.incrementAndGet();

            return Set.of();
        }

        @Test
        void graceReapVerdict_nonLiveTarget_activeAndQuorumSafe_reaps() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, false, SPARE_FIVE, 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.REAP);
        }

        @Test
        void graceReapVerdict_liveCountedTarget_isTargetLive_evenWhenDeposed() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(false, true, ALL_SIX, 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.TARGET_LIVE);
        }

        @Test
        void graceReapVerdict_deposedIssuer_isNotLeader() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(false, false, SPARE_FIVE, 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.NOT_LEADER);
        }

        @Test
        void graceReapVerdict_belowQuorum_isNotQuorumSafe() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, false, Set.of(SELF, PEER_A), 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.NOT_QUORUM_SAFE);
        }

        @Test
        void graceReapVerdict_unknownConfiguredCount_isNotQuorumSafe() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, false, SPARE_FIVE, 0, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.NOT_QUORUM_SAFE);
        }

        /// The target never counts toward the quorum that permits its own removal: three counted including the
        /// target would be quorum-safe for five, two without it is not.
        @Test
        void graceReapVerdict_targetExcludedFromItsOwnQuorumCount() {
            assertThat(ClusterTopologyManagerRecord.graceReapVerdict(true, false, Set.of(SELF, PEER_A, PEER_D), 5, PEER_D))
                    .isEqualTo(ClusterTopologyManagerRecord.GraceReapVerdict.NOT_QUORUM_SAFE);
        }

        @Test
        void quorumSafe_isAStrictMajorityOfAKnownConfiguredSize() {
            assertThat(ClusterTopologyManagerRecord.quorumSafe(3, 5)).isTrue();
            assertThat(ClusterTopologyManagerRecord.quorumSafe(2, 5)).isFalse();
            assertThat(ClusterTopologyManagerRecord.quorumSafe(2, 3)).isTrue();
            assertThat(ClusterTopologyManagerRecord.quorumSafe(1, 3)).isFalse();
            assertThat(ClusterTopologyManagerRecord.quorumSafe(9, 0)).as("an unknown size is never quorum-safe")
                                                                      .isFalse();
        }
    }

    /// The same grace path over REAL producers: a boot-seeded [MembershipFsm] whose DRAIN routing mirrors
    /// `AetherNode.requestDrainThroughFsm`, read through its counted and tracked projections as
    /// `AetherNode.drainGraceLiveness` reads them, and a synchronous `MembershipDeltaProjector` feeding
    /// `onMembershipDecision`. Raw SWIM liveness is the `swimAliveNodes` field, since no SWIM detector runs
    /// here. The FSM's own DEPARTING timeout (#1054) is set to an hour, so it cannot reap inside a test and mask
    /// what the backstop did.
    @Nested
    class DrainGraceWithRealMembership {
        private MembershipFsm fsm;

        @BeforeEach
        void createFsm() {
            fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis, Long.MAX_VALUE, HOUR, HOUR, HOUR);
        }

        @Test
        void surplusDrain_realMembership_stableCluster_reapsNonLiveTargetAtGraceExpiry() {
            var issuer = issuerCtm();

            swimAliveNodes.set(SPARE_FIVE);
            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// R1′(a) with the target RETURNED to MEMBER (the #1058 interaction: an unacknowledged drain withdrawn
        /// back into the counted set). Spare capacity beside it and an active issuer — the round-2 verdict
        /// reaped here — but a live counted node is never killed by a surplus trim.
        @Test
        void surplusDrain_realMembership_targetReturnedToMember_isNeverReaped_evenWithSpareCapacity() {
            var issuer = issuerCtm();

            swimAliveNodes.set(ALL_SIX);
            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            fsm.onSwimHealthy(PEER_D, 5L);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(fsm.coreCountedMembers()).as("arming: the target is back in the counted set, beside spare capacity")
                                                .containsExactlyInAnyOrderElementsOf(ALL_SIX);
            assertThat(lifecycleManager.terminatedNodeIds()).as("a surplus trim never abruptly kills a live node")
                                                            .isEmpty();
        }

        /// A peer died during the grace AND the target refuted its drain: live and counted, so never reaped.
        @Test
        void surplusDrain_realMembership_targetStillLiveCountedMember_clusterNeedsIt_notReaped() {
            var issuer = issuerCtm();

            swimAliveNodes.set(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            fsm.onSwimDeparted(PEER_E, 1L);
            fsm.onSwimHealthy(PEER_D, 5L);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(fsm.coreCountedMembers()).as("arming: the target is a live counted member again")
                                                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
            assertThat(lifecycleManager.terminatedNodeIds()).as("only the dead peer is reaped; the live target is kept")
                                                            .containsExactly(PEER_E);
        }

        /// R1′(b): a peer died during the grace and the target is NOT live. Four of five remain (quorum-safe), so
        /// the target is reaped — a deficit does not keep a halted node's instance alive.
        @Test
        void surplusDrain_realMembership_deficitAtExpiry_nonLiveTargetStillReaped() {
            var issuer = issuerCtm();

            swimAliveNodes.set(Set.of(SELF, PEER_A, PEER_B, PEER_C));
            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            fsm.onSwimDeparted(PEER_E, 1L);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_E, PEER_D);
        }

        /// The deposed issuer refuses; the target departs AFTER the new leader activated, so `NodeRemoved` finds
        /// an active CTM and it reaps once.
        @Test
        void surplusDrain_realMembership_skippedByDeposedIssuer_targetDeparts_reapedOnceByNewLeader() {
            var issuer = issuerCtm();
            var newLeader = ctmWithDrainGrace(timeSpan(150).millis(), _ -> {}, fsmLiveness());

            swimAliveNodes.set(SPARE_FIVE);
            wireAndSeed(((Consumer<MembershipDecision>) issuer::onMembershipDecision).andThen(newLeader::onMembershipDecision));
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            issuer.deactivate();
            newLeader.activate();
            awaitClearedExactlyOnce(PEER_D);

            assertThat(lifecycleManager.terminatedNodeIds()).as("a deposed issuer's backstop refused")
                                                            .isEmpty();

            fsm.onSwimDeparted(PEER_D, 2L);

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// The CTO-required case on REAL membership (verify-1058). The drained target is withdrawn to MEMBER, as #1058's
        /// DrainUnacknowledged does, and the reconciler re-drains it, so it is DEPARTING at grace expiry. It is still
        /// SWIM-alive, and the leader's transport to it is connected. It is NOT reaped, and the backstop leaves it alone
        /// even after it halts. A verdict keyed on counted membership reddens this test.
        @Test
        void surplusDrain_realMembership_reDrainedDepartingTarget_swimAliveAndConnected_isNotReaped() {
            var issuer = ctmWithDrainGrace(timeSpan(1200).millis(), fsmRoutedDrainSink(), fsmLiveness());

            swimAliveNodes.set(ALL_SIX);
            transportConnectedNodes.set(Set.of(PEER_D));
            wireAndSeed(issuer::onMembershipDecision);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            fsm.onSwimHealthy(PEER_D, 5L);
            fsm.onDrainRequested(PEER_D);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(fsm.memberStates().get(PEER_D)).as("arming: the re-drained target is DEPARTING at expiry")
                                                      .isEqualTo("Departing");
            assertThat(fsm.coreCountedMembers()).as("arming: DEPARTING is not counted")
                                                .doesNotContain(PEER_D);
            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();

            swimAliveNodes.set(SPARE_FIVE);
            transportConnectedNodes.set(Set.of());
            settleFor(Duration.ofMillis(500));

            assertThat(lifecycleManager.terminatedNodeIds()).as("live at expiry: the backstop never schedules a reap of it")
                                                            .isEmpty();
        }

        private ClusterTopologyManager issuerCtm() {
            return ctmWithDrainGrace(timeSpan(150).millis(), fsmRoutedDrainSink(), fsmLiveness());
        }

        private MembershipLiveness fsmLiveness() {
            return realMembershipLiveness(fsm);
        }

        private Consumer<NodeId> fsmRoutedDrainSink() {
            return ((Consumer<NodeId>) drainCommandSinkCalls::add).andThen(fsm::onDrainRequested);
        }

        /// Attach the projector BEFORE seeding: the seed's JOINED edges must be announced, because an unannounced
        /// member's death emits no `NodeRemoved`. Seeds six cores for a configured five.
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
            fsm.seed(ALL_SIX);
        }
    }

    /// The evidence as `AetherNode.drainGraceLiveness` builds it over a real FSM; raw SWIM and transport come
    /// from the stub fields.
    private MembershipLiveness realMembershipLiveness(MembershipFsm membershipFsm) {
        return MembershipLiveness.membershipLiveness(membershipFsm::coreCountedMembers,
                                                     membershipFsm::broadcastEligibleMembers,
                                                     id -> swimAliveNodes.get().contains(id),
                                                     id -> transportConnectedNodes.get().contains(id),
                                                     inFlightNodes::get,
                                                     configuredCoreCount::get,
                                                     id -> membershipFsm.memberDescriptor(id)
                                                                        .map(MemberDescriptor::role));
    }

    /// verify-1057 B1, committed. A refused surplus reap must never leave a billed orphan, even when leadership or
    /// quorum moves before `NodeRemoved` reaches an active CTM. Both tests are the reviewer's orphan probes with
    /// the assertion inverted: at `c974cb3f4` the instance was terminated by nobody. The instance is now reaped
    /// exactly once, by the successor's activation replay (R4).
    @Nested
    class OrphanFreeAcrossLeadershipAndQuorum {
        private static final TimeSpan GRACE = timeSpan(600).millis();

        /// Real FSM and projector with quorum toggled. The issuer loses quorum and deactivates. The halted target
        /// departs while non-quorate, so its REMOVED edge queues. The grace fires on the deposed issuer (refused).
        /// Quorum returns and the projector delivers `NodeRemoved` while EVERY CTM is inactive, so it is dropped.
        /// Only then does the successor activate.
        @Test
        void refusedReap_quorumLostThenRestored_removalDroppedByInactiveCtms_reapedOnceByActivationReplay() {
            var quorate = new AtomicBoolean(true);
            var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis, Long.MAX_VALUE, HOUR, HOUR, HOUR);
            var removedDelivered = new CopyOnWriteArrayList<NodeId>();
            var issuer = ctmWithDrainGrace(GRACE,
                                           ((Consumer<NodeId>) drainCommandSinkCalls::add).andThen(fsm::onDrainRequested),
                                           realMembershipLiveness(fsm));
            var successor = ctmWithDrainGrace(GRACE, _ -> {}, realMembershipLiveness(fsm));
            var projector = membershipDeltaProjector(quorate::get,
                                                     () -> 1L,
                                                     () -> HLC,
                                                     decision -> deliver(decision, issuer, successor, removedDelivered),
                                                     _ -> {},
                                                     _ -> {},
                                                     _ -> {},
                                                     Runnable::run,
                                                     SharedScheduler::schedule);

            clusterStore.seedNamed(5, CLUSTER);
            ALL_SIX.forEach(id -> lifecycleManager.addInstance(id, CLUSTER, "core"));
            swimAliveNodes.set(ALL_SIX);
            fsm.onMembershipDelta(projector::onDelta);
            fsm.seed(ALL_SIX);
            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();

            quorate.set(false);
            issuer.deactivate();
            swimAliveNodes.set(SPARE_FIVE);
            fsm.onSwimDeparted(PEER_D, 2L);
            awaitClearedExactlyOnce(PEER_D);

            assertThat(removedDelivered).as("arming: the REMOVED edge is still queued while non-quorate")
                                        .isEmpty();
            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: the deposed issuer's backstop refused")
                                                            .isEmpty();

            quorate.set(true);
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> removedDelivered.contains(PEER_D));

            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: NodeRemoved reached only inactive CTMs and was dropped")
                                                            .isEmpty();

            successor.activate();
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));

            assertThat(lifecycleManager.terminatedNodeIds()).as("the successor's activation replay reaps the orphan exactly once")
                                                            .containsExactly(PEER_D);
        }

        /// Stub membership, no quorum loss: the successor processed the death while inactive, then the issuer was
        /// deposed and the successor activated.
        @Test
        void refusedReap_successorSawDeathWhileInactive_reapedOnceByActivationReplay() {
            clusterStore.seedNamed(5, CLUSTER);
            ALL_SIX.forEach(id -> lifecycleManager.addInstance(id, CLUSTER, "core"));
            coreCountedMembers.set(SPARE_FIVE);
            trackedMembers.set(ALL_SIX);
            swimAliveNodes.set(ALL_SIX);

            var issuer = ctmWithDrainGrace(GRACE);
            var successor = ctmWithDrainGrace(GRACE);

            issuer.activate();
            issuer.drainNode(PEER_D, DrainReason.OVERPROVISION_PARTITION_HEAL).await();
            trackedMembers.set(SPARE_FIVE);
            swimAliveNodes.set(SPARE_FIVE);
            successor.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_E)));
            issuer.deactivate();
            successor.activate();
            awaitClearedExactlyOnce(PEER_D);
            await().atMost(Duration.ofSeconds(10))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));

            assertThat(lifecycleManager.terminatedNodeIds()).as("the successor's activation replay reaps the orphan exactly once")
                                                            .containsExactly(PEER_D);
        }

        private void deliver(MembershipDecision decision,
                             ClusterTopologyManager issuer,
                             ClusterTopologyManager successor,
                             List<NodeId> removedDelivered) {
            issuer.onMembershipDecision(decision);
            successor.onMembershipDecision(decision);
            if (decision instanceof MembershipDecision.NodeRemoved removed) {
                removedDelivered.add(removed.nodeId());
            }
        }
    }

    /// #1050 R4 — the one-shot activation replay over the labelled provider inventory. Quorum-safe stub evidence:
    /// five counted for a configured five.
    @Nested
    class ActivationReplay {
        private static final TimeSpan GRACE = timeSpan(150).millis();
        private static final NodeId DEAD = nodeId("node-dead").unwrap();
        private static final NodeId BOOTING = nodeId("node-booting").unwrap();
        private static final NodeId WORKER = nodeId("node-worker").unwrap();
        /// Quorum-safe for a configured five, and deliberately disjoint from every node that owns an instance below
        /// except PEER_E, so each protection in `terminatesOnlyTheUnprotectedCoreInstance` is the ONLY thing guarding
        /// its node. An overlap would let the counted set mask the tracked, SWIM, transport and self checks.
        private static final Set<NodeId> QUORUM_THREE = Set.of(PEER_E, nodeId("node-f").unwrap(), nodeId("node-g").unwrap());

        @BeforeEach
        void quorumSafeNamedCluster() {
            clusterStore.seedNamed(5, CLUSTER);
            coreCountedMembers.set(QUORUM_THREE);
        }

        /// Each protected node carries exactly ONE protection: tracked only (A), alive only by raw SWIM (B), reachable
        /// only by the leader's transport (C), counted only (E), in flight only (BOOTING), and self with no evidence at
        /// all. Also present: a worker instance, and a node with no protection (DEAD). Only DEAD's instance is
        /// terminated, exactly once. Because every protection is individually load-bearing here, dropping any one of
        /// them reddens this test.
        @Test
        void activationReplay_terminatesOnlyTheUnprotectedCoreInstance() {
            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_E, BOOTING, DEAD).forEach(id -> lifecycleManager.addInstance(id, CLUSTER, "core"));
            lifecycleManager.addInstance(WORKER, CLUSTER, "worker");
            trackedMembers.set(Set.of(PEER_A));
            swimAliveNodes.set(Set.of(PEER_B));
            transportConnectedNodes.set(Set.of(PEER_C));
            inFlightNodes.set(Set.of(BOOTING));

            ctmWithDrainGrace(GRACE).activate();
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(DEAD));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(DEAD);
            assertThat(lifecycleManager.lastListFilter()).as("the listing is scoped to THIS cluster's core instances")
                                                         .isEqualTo(Map.of("aether-cluster", CLUSTER, "aether-role", "core"));
        }

        /// "Absent past a short grace": a node unprotected at the first read that becomes tracked before the second
        /// read is never terminated.
        @Test
        void activationReplay_nodeTrackedBeforeSecondRead_isNotTerminated() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");

            ctmWithDrainGrace(GRACE).activate();

            assertThat(lifecycleManager.listCalls.get()).as("arming: the first read ran at activation").isEqualTo(1);

            trackedMembers.set(Set.of(DEAD));
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.listCalls.get() >= 2);
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// A minority view never lists or terminates. Checked synchronously: the first read happens inside
        /// `activate()`.
        @Test
        void activationReplay_notQuorumSafe_listsAndTerminatesNothing() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            coreCountedMembers.set(Set.of(SELF, PEER_A));

            ctmWithDrainGrace(GRACE).activate();
            settleFor(Duration.ofMillis(600));

            assertThat(lifecycleManager.listCalls.get()).isZero();
            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// Without a committed cluster name there is no safe scope, so nothing is listed.
        @Test
        void activationReplay_noClusterName_listsNothing() {
            clusterStore.seed(5);
            lifecycleManager.addInstance(DEAD, "", "core");

            ctmWithDrainGrace(GRACE).activate();

            assertThat(lifecycleManager.listCalls.get()).isZero();
        }

        /// A CTM deactivated between the two reads terminates nothing: a deposed view never reaps. The margin is
        /// four times the grace.
        @Test
        void activationReplay_deactivatedBeforeSecondRead_terminatesNothing() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            var replayCtm = ctmWithDrainGrace(GRACE);

            replayCtm.activate();
            replayCtm.deactivate();
            settleFor(Duration.ofMillis(600));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// A re-activation invalidates the first activation's pending second read, so the orphan is terminated
        /// once, by the current activation, not twice.
        @Test
        void activationReplay_reactivatedBeforeSecondRead_terminatesExactlyOnce() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            var replayCtm = ctmWithDrainGrace(GRACE);

            replayCtm.activate();
            replayCtm.deactivate();
            replayCtm.activate();
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(DEAD));
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(DEAD);
        }

        /// S2 (verify-1057-r2): the second listing is ISSUED while the activation is current but RESOLVES after
        /// `deactivate()`. The terminate re-checks the activation at resolution, so a deposed view terminates
        /// nothing. At `ff50a3274` this terminated `DEAD`.
        @Test
        void replay_secondListingResolvesAfterDeactivation_terminatesNothing() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            lifecycleManager.holdListingsAfter(1);
            var replayCtm = ctmWithDrainGrace(GRACE);

            replayCtm.activate();
            await().atMost(Duration.ofSeconds(5))
                   .until(lifecycleManager::listingHeld);

            assertThat(lifecycleManager.listCalls.get()).as("arming: the second read was issued while active").isEqualTo(2);

            replayCtm.deactivate();
            lifecycleManager.releaseHeldListing();
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).as("a listing that resolves on a deposed CTM terminates nothing")
                                                            .isEmpty();
        }

        /// Control for the held-listing fixture: released with the activation still current, the same second
        /// read terminates the orphan. Proves the hold itself does not suppress the terminate.
        @Test
        void replay_secondListingResolvesWhileStillActive_terminatesTheOrphan() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            lifecycleManager.holdListingsAfter(1);
            var replayCtm = ctmWithDrainGrace(GRACE);

            replayCtm.activate();
            await().atMost(Duration.ofSeconds(5))
                   .until(lifecycleManager::listingHeld);

            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: nothing terminated while the read is pending").isEmpty();

            lifecycleManager.releaseHeldListing();
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(DEAD));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(DEAD);
        }

        /// SF-1 (verify-1057-r3, the reviewer's probeB): the old leader abandoned and parked the reap of `DEAD`, then
        /// lost leadership — its park died with it. The new leader's own SWIM still holds `DEAD` SUSPECTED at its
        /// replay's first read, so the instance is protected and would have been forgotten until the next activation.
        /// The replay parks it instead, and the FAULTY edge that ends the suspicion window reaps it, exactly once. At
        /// `93e7dd349` the FAULTY edge re-armed nothing.
        @Test
        void replay_leadershipChangeInsideSuspicionWindow_parkedOrphanReapedAtTheFaultyEdge() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            swimAliveNodes.set(Set.of(DEAD));
            var oldLeader = ctmWithDrainGrace(GRACE);

            oldLeader.activate();
            oldLeader.onMembershipDecision(MembershipDecision.nodeRemoved(DEAD, List.of(SELF, PEER_A, PEER_B)));
            settleFor(Duration.ofMillis(600));
            oldLeader.deactivate();

            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: the old leader abandoned the reap").isEmpty();

            var newLeader = ctmWithDrainGrace(GRACE);

            newLeader.activate();
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.listCalls.get() >= 2);
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: still SUSPECTED at the new leader's reads — protected, not terminated")
                                                            .isEmpty();

            swimAliveNodes.set(Set.of());
            newLeader.onSwimFaulty(DEAD);
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(DEAD));
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(DEAD);
        }

        /// The replay parks ONLY a node protected by nothing but SWIM life. A node the FSM still tracks is the FSM's
        /// to depart; a FAULTY edge for it re-arms nothing here — otherwise a tracked, uncounted node would be reaped
        /// on SWIM's say-so alone.
        @Test
        void replay_trackedAndSwimAliveInstance_isNotParked() {
            lifecycleManager.addInstance(DEAD, CLUSTER, "core");
            swimAliveNodes.set(Set.of(DEAD));
            trackedMembers.set(Set.of(DEAD));
            var replayCtm = ctmWithDrainGrace(GRACE);

            replayCtm.activate();
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.listCalls.get() >= 1);

            swimAliveNodes.set(Set.of());
            replayCtm.onSwimFaulty(DEAD);
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }
    }

    /// #1062 / R5 — `reapDepartedNode` re-checks liveness before an irreversible terminate. A DEAD verdict on a node
    /// that still shows independent evidence of life is DEFERRED, with bounded re-checks every
    /// `provisioningTimeout / 12`; a genuinely departed node is reaped at once, exactly once.
    @Nested
    class DepartedReapLivenessRecheck {
        private static final TimeSpan SLOW_GRACE = timeSpan(1200).millis();
        private static final TimeSpan FAST_GRACE = timeSpan(150).millis();

        private ClusterTopologyManager activeCtm(TimeSpan grace) {
            var reaper = ctmWithDrainGrace(grace);

            reaper.activate();

            return reaper;
        }

        private static MembershipDecision removedD() {
            return MembershipDecision.nodeRemoved(PEER_D, List.of(SELF, PEER_A, PEER_B));
        }

        /// The #1062 acceptance: a DEAD verdict while the leader's transport is still connected is not terminated
        /// within the deferral, then terminated exactly once after the connection drops.
        @Test
        void nodeRemoved_transportStillConnected_deferred_thenReapedOnceWhenLinkDrops() {
            transportConnectedNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(SLOW_GRACE);

            reaper.onMembershipDecision(removedD());

            assertThat(lifecycleManager.terminatedNodeIds()).as("a node the leader still reaches is not reaped on a DEAD verdict")
                                                            .isEmpty();

            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).as("still deferred across several re-checks").isEmpty();

            transportConnectedNodes.set(Set.of());
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// Raw SWIM life defers the reap the same way.
        @Test
        void nodeRemoved_swimStillAlive_deferred_thenReapedWhenSwimGivesUp() {
            swimAliveNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(SLOW_GRACE);

            reaper.onMembershipDecision(removedD());
            settleFor(Duration.ofMillis(250));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();

            swimAliveNodes.set(Set.of());
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));
        }

        /// A counted membership defers the reap the same way.
        @Test
        void nodeRemoved_stillCounted_deferred_thenReapedWhenUncounted() {
            coreCountedMembers.set(Set.of(PEER_D));
            var reaper = activeCtm(SLOW_GRACE);

            reaper.onMembershipDecision(removedD());
            settleFor(Duration.ofMillis(250));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();

            coreCountedMembers.set(Set.of());
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));
        }

        /// A genuinely departed node — no evidence of life — is reaped at once, with no added delay, and once.
        @Test
        void nodeRemoved_genuinelyDeparted_reapedImmediatelyOnce() {
            var reaper = activeCtm(SLOW_GRACE);

            reaper.onMembershipDecision(removedD());

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// Bounded: a node still live after every re-check is never terminated. Margin: the 12 re-checks span the
        /// 150ms grace; the test waits four times that.
        @Test
        void nodeRemoved_liveThroughoutDeferral_isNeverTerminated() {
            transportConnectedNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(FAST_GRACE);

            reaper.onMembershipDecision(removedD());
            settleFor(Duration.ofMillis(600));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();

            var readsAfterBound = transportReads.get();

            settleFor(Duration.ofMillis(300));

            assertThat(transportReads.get()).as("the re-checks stop once exhausted; the deferral is bounded")
                                            .isEqualTo(readsAfterBound);
        }

        /// A CTM deactivated during the deferral drops the reap — a deposed view never reaps — even once the
        /// evidence of life clears.
        @Test
        void nodeRemoved_deactivatedDuringDeferral_reapDropped() {
            transportConnectedNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(SLOW_GRACE);

            reaper.onMembershipDecision(removedD());
            reaper.deactivate();
            transportConnectedNodes.set(Set.of());
            settleFor(Duration.ofMillis(400));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// N2 (verify-1057-r2): a deferral started under one activation does not survive deactivate→activate. The
        /// stale chain drops out at its next re-check even though the CTM is active again; the new activation's
        /// replay owns the instance (here it lists nothing — the cluster is unnamed). At `ff50a3274` the stale chain
        /// terminated `PEER_D` once the evidence cleared.
        @Test
        void nodeRemoved_reactivatedDuringDeferral_staleDeferralDropped() {
            transportConnectedNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(SLOW_GRACE);

            reaper.onMembershipDecision(removedD());
            reaper.deactivate();
            reaper.activate();
            transportConnectedNodes.set(Set.of());
            settleFor(Duration.ofMillis(400));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// Waits until the reap of `PEER_D` has been ABANDONED: the re-checks stop reading the evidence. Margin: the
        /// 12 re-checks span the 150ms grace; the wait is four times that, then a quiet interval twice the grace.
        private void awaitAbandoned() {
            settleFor(Duration.ofMillis(600));

            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: still live at every re-check, never terminated")
                                                            .isEmpty();

            var readsAfterBound = swimReads.get();

            settleFor(Duration.ofMillis(300));

            assertThat(swimReads.get()).as("arming: the re-checks have stopped — the reap is ABANDONED, not deferred")
                                       .isEqualTo(readsAfterBound);
        }

        /// S1 (verify-1057-r2): SWIM's suspicion window is LHM-scaled and can outlast both the 60s grace and the
        /// whole re-check budget, so a dead node can still read SUSPECTED when the last re-check runs. The reap is
        /// abandoned — and re-armed by the FAULTY edge that ends the window. Terminated exactly once. At `ff50a3274`
        /// nothing retried and the instance stayed billed until an unrelated leadership change.
        @Test
        void nodeRemoved_swimSuspectedPastEveryRecheck_reapedOnceWhenSwimReportsFaulty() {
            swimAliveNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(FAST_GRACE);

            reaper.onMembershipDecision(removedD());
            awaitAbandoned();

            swimAliveNodes.set(Set.of());
            reaper.onSwimFaulty(PEER_D);
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// The control (72e179cfe: "not live" needs POSITIVE evidence): a node SWIM keeps reporting SUSPECTED, with no
        /// FAULTY edge ever, is never terminated — the abandoned reap stays parked.
        @Test
        void nodeRemoved_swimSuspectedForever_isNeverTerminated() {
            swimAliveNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(FAST_GRACE);

            reaper.onMembershipDecision(removedD());
            awaitAbandoned();
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// FAULTY re-arms only a reap that was abandoned. A FAULTY edge for a node whose reap was never abandoned — a
        /// live member SWIM condemns and later refutes — reads no evidence and terminates nothing; its death, if
        /// real, arrives as `NodeRemoved`.
        @Test
        void swimFaulty_forANodeWhoseReapWasNeverAbandoned_terminatesNothing() {
            var reaper = activeCtm(FAST_GRACE);
            var readsBefore = swimReads.get() + transportReads.get();

            reaper.onSwimFaulty(PEER_D);
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
            assertThat(swimReads.get() + transportReads.get()).as("no reap chain was started").isEqualTo(readsBefore);
        }

        /// The re-armed reap is still evidence-gated: FAULTY with the leader's transport link still up defers, and
        /// the terminate follows the link dropping — never the FAULTY edge alone.
        @Test
        void swimFaulty_reArmedReap_stillDefersWhileTransportConnected() {
            swimAliveNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(FAST_GRACE);

            reaper.onMembershipDecision(removedD());
            awaitAbandoned();

            swimAliveNodes.set(Set.of());
            transportConnectedNodes.set(Set.of(PEER_D));
            reaper.onSwimFaulty(PEER_D);
            settleFor(Duration.ofMillis(100));

            assertThat(lifecycleManager.terminatedNodeIds()).as("FAULTY alone, link still up: deferred").isEmpty();

            transportConnectedNodes.set(Set.of());
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
        }

        /// A parked reap dies with its activation: after deactivate→activate the FAULTY edge re-arms nothing, because
        /// the new activation's replay owns the instance (here it lists nothing — the cluster is unnamed).
        @Test
        void swimFaulty_afterReactivation_doesNotReviveTheAbandonedReap() {
            swimAliveNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(FAST_GRACE);

            reaper.onMembershipDecision(removedD());
            awaitAbandoned();

            reaper.deactivate();
            reaper.activate();
            swimAliveNodes.set(Set.of());
            reaper.onSwimFaulty(PEER_D);
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// NIT-1 (verify-1057-r3): a rejoin under the same id ends the parked episode. The new incarnation's death
        /// arrives as its own `NodeRemoved`; the stale park must not run a second chain beside it.
        @Test
        void nodeJoined_clearsTheParkedReap_faultyAfterRejoinReArmsNothing() {
            swimAliveNodes.set(Set.of(PEER_D));
            var reaper = activeCtm(FAST_GRACE);

            reaper.onMembershipDecision(removedD());
            awaitAbandoned();

            reaper.onMembershipDecision(MembershipDecision.nodeJoined(PEER_D, List.of(SELF, PEER_A, PEER_B, PEER_D)));
            swimAliveNodes.set(Set.of());
            reaper.onSwimFaulty(PEER_D);
            settleFor(Duration.ofMillis(300));

            assertThat(lifecycleManager.terminatedNodeIds()).isEmpty();
        }

        /// NIT-4 (verify-1057-r3): the activation epoch is bumped FIRST in `activate()`. A `NodeRemoved` delivered
        /// while `activate()` is still running (here through the lifecycle manager's `resetProvisionerState`, which
        /// `activate()` calls synchronously) starts a deferral under the NEW epoch, so it survives and reaps once the
        /// evidence clears. With the bump inside `scheduleActivationReplay` the chain carries the old epoch and is
        /// dropped at its first re-check.
        @Test
        void nodeRemoved_deliveredInsideActivate_deferralBelongsToThatActivation() {
            transportConnectedNodes.set(Set.of(PEER_D));
            var reaper = ctmWithDrainGrace(SLOW_GRACE);

            lifecycleManager.onResetProvisionerState(() -> reaper.onMembershipDecision(removedD()));
            reaper.activate();
            lifecycleManager.onResetProvisionerState(() -> {});

            assertThat(lifecycleManager.terminatedNodeIds()).as("arming: deferred on the live link").isEmpty();

            transportConnectedNodes.set(Set.of());
            await().atMost(Duration.ofSeconds(5))
                   .until(() -> lifecycleManager.terminatedNodeIds().contains(PEER_D));

            assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
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

        /// #1050 R4 — the activation replay scopes its listing by the committed cluster name, so its tests need one.
        void seedNamed(int coreCount, String clusterName) {
            current.set(Option.some(new ClusterConfigValue("", clusterName, "1.0.0", coreTopology(coreCount), 3, 9, "test",
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
        /// #1050 R4 — the labelled provider inventory the activation replay lists. `terminateNode` removes from it.
        private final ConcurrentHashMap<NodeId, InstanceInfo> inventory = new ConcurrentHashMap<>();
        final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicReference<Map<String, String>> lastListFilter = new AtomicReference<>(Map.of());
        /// S2 (verify-1057-r2): listings after the `holdListingsAfter` count stay PENDING until released, so a test
        /// can change the CTM's state between a listing being issued and its resolution — the provider latency window.
        private final AtomicInteger holdListingsAfter = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicReference<Promise<List<InstanceInfo>>> heldListing = new AtomicReference<>();
        private final AtomicReference<Map<String, String>> heldFilter = new AtomicReference<>(Map.of());

        void holdListingsAfter(int completedListings) {
            holdListingsAfter.set(completedListings);
        }

        /// NIT-4 (verify-1057-r3): `resetProvisionerState` is called synchronously INSIDE `activate()`, after the CTM
        /// is active and before its replay is scheduled — the only seam through which a test can deliver a
        /// membership decision at that instant.
        private final AtomicReference<Runnable> onResetProvisionerState = new AtomicReference<>(() -> {});

        void onResetProvisionerState(Runnable hook) {
            onResetProvisionerState.set(hook);
        }

        @Override public void resetProvisionerState(Option<ClusterName> clusterName) {
            onResetProvisionerState.get().run();
        }

        boolean listingHeld() {
            return heldListing.get() != null;
        }

        /// Resolves the held listing with the inventory as it stands NOW.
        void releaseHeldListing() {
            var held = heldListing.getAndSet(null);

            held.succeed(matching(heldFilter.get()));
        }

        private List<InstanceInfo> matching(Map<String, String> tagFilter) {
            return inventory.values()
                            .stream()
                            .filter(instance -> instance.tags().entrySet().containsAll(tagFilter.entrySet()))
                            .toList();
        }

        void addInstance(NodeId nodeId, String cluster, String role) {
            inventory.put(nodeId,
                          InstanceInfo.instanceInfo(InstanceId.instanceId("i-" + nodeId.id()).unwrap(),
                                                    InstanceStatus.RUNNING,
                                                    List.of("127.0.0.1"),
                                                    InstanceType.ON_DEMAND,
                                                    Map.of("aether-cluster", cluster, "aether-role", role),
                                                    Option.some(nodeId.id()))
                                      .unwrap());
        }

        Map<String, String> lastListFilter() {
            return lastListFilter.get();
        }

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
            inventory.remove(nodeId);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            var call = listCalls.incrementAndGet();
            lastListFilter.set(Map.copyOf(tagFilter));
            if (call > holdListingsAfter.get()) {
                var pending = Promise.<List<InstanceInfo>> promise();
                heldFilter.set(Map.copyOf(tagFilter));
                heldListing.set(pending);
                return pending;
            }
            return Promise.success(matching(tagFilter));
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }
}
