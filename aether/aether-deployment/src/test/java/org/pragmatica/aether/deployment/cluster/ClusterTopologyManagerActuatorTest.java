// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            clusterStore::current,
                                                            clusterStore::apply,
                                                            () -> ClusterPhase.NORMAL,
                                                            drainCommandSinkCalls::add,
                                                            _ -> {},
                                                            Option::none,
                                                            clusterStore::autoHealState);
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
