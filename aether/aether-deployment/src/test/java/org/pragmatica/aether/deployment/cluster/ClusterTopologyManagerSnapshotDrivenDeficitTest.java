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
import org.pragmatica.aether.slice.generation.Epoch;
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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Slot-based-membership-convergence-spec §5: CTM reads cluster size from the snapshot-backed
/// `MembershipView` and converges occupancy against a durable slot set sized to
/// `ClusterConfigValue.coreCount`. Deficit → EMPTY slots filled; surplus → highest-index reapable
/// slots removed (Option B: CTM-provisioned safety filter retained — MANUAL/UNKNOWN occupants are
/// never auto-terminated).
class ClusterTopologyManagerSnapshotDrivenDeficitTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();
    private static final NodeId PEER_ORPHAN = nodeId("node-orphan").unwrap();

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
                                                            clusterStore::lifecycle,
                                                            clusterStore::slots,
                                                            clusterStore::apply,
                                                            new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                            LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                 clusterStore::lifecycle,
                                                                                                 System::currentTimeMillis),
                                                            () -> AetherValue.ClusterPhase.NORMAL);
    }

    /// §4 provisioning FALLBACK: a slot with NO connected candidate (here the 4-node observer
    /// knows only SELF/A/B/C while configured=5) is filled by provisioning. Universal fill (§3
    /// step 1) binds the 4 connected members; the 5th slot has no spare connected node → provision.
    @Test
    void reconcile_provisionsIntoEmptySlots_whenNoConnectedCandidate() throws InterruptedException {
        var fallbackCtm = createCtmWithFourNodeObserver(() -> true);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        fallbackCtm.activate();
        fallbackCtm.onNodeReady(PEER_A);
        awaitProvision(1);
        assertThat(lifecycleManager.provisionCount.get())
                .as("the 5th slot has no connected candidate → provisioning fallback fills it")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void reconcile_doesNotProvision_whenSnapshotReportsConverged() throws InterruptedException {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 5);
        ctm.activate();
        ctm.onNodeReady(PEER_A);
        Thread.sleep(100L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("all 5 slots HEALTHY — no provisioning")
                .isZero();
    }

    /// LAYER 3 (anti-flood quorum gate): below committed-healthy quorum the CTM must NOT provision
    /// replacement nodes — it defers to SelfDrainCoordinator to dissolve the minority partition.
    /// `inQuorum=() -> false` simulates a minority-side leader; a slot with no connected candidate
    /// that would otherwise provision must result in ZERO provisions.
    @Test
    void reconcile_doesNotProvision_whenBelowQuorum() throws InterruptedException {
        var belowQuorumCtm = createCtmWithFourNodeObserver(() -> false);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        belowQuorumCtm.activate();
        belowQuorumCtm.onNodeReady(PEER_A);
        Thread.sleep(200L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("below quorum — provisioning deferred to SelfDrainCoordinator, no replacement nodes")
                .isZero();
    }

    /// LAYER 3 (gate-open preserves existing behavior): with `inQuorum=() -> true` the same
    /// no-candidate slot provisions normally — the gate is a pure suppressor, not a behavior change
    /// above quorum.
    @Test
    void reconcile_provisionsIntoEmptySlots_whenInQuorum() throws InterruptedException {
        var inQuorumCtm = createCtmWithFourNodeObserver(() -> true);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        inQuorumCtm.activate();
        inQuorumCtm.onNodeReady(PEER_A);
        awaitProvision(1);
        assertThat(lifecycleManager.provisionCount.get())
                .as("in quorum — provisioning fallback proceeds for the no-candidate slot")
                .isGreaterThanOrEqualTo(1);
    }

    /// §3 universal slot-fill excludes terminal nodes: on first formation the leader binds the
    /// CONNECTED, non-terminal core members into the empty slots. A peer whose committed lifecycle
    /// is already STOPPED must NOT be bound (binding would fire a SlotClaimed for a dead peer); its
    /// slot is filled by another connected member or provisioned. The enduring invariant: the
    /// STOPPED occupant is NEVER bound, and the live connected members ARE bound.
    @Test
    void activate_universalFillSkipsStoppedOccupant_neverBindsDeadNode() throws InterruptedException {
        clusterStore.seed(5);
        // 5 occupants present in the ON_DUTY snapshot, but PEER_D is already STOPPED in lifecycle KV.
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {clusterStore.installOnDuty(id, epoch++);}
        clusterStore.installStopped(PEER_D);
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5,
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        ctm.activate();
        Thread.sleep(200L);
        var boundToDead = clusterStore.slots()
                                      .values()
                                      .stream()
                                      .filter(slot -> slot.assignedNodeId().map(PEER_D::equals).or(false))
                                      .count();
        assertThat(boundToDead)
                .as("the already-STOPPED occupant PEER_D is never bound by universal fill (§3)")
                .isZero();
        var connectedBound = clusterStore.slots()
                                         .values()
                                         .stream()
                                         .flatMap(slot -> slot.assignedNodeId().stream())
                                         .collect(java.util.stream.Collectors.toSet());
        assertThat(connectedBound)
                .as("the 4 live connected members are bound by universal fill")
                .contains(SELF, PEER_A, PEER_B, PEER_C);
    }

    private ClusterTopologyManager createCtmWithQuorum(java.util.function.BooleanSupplier inQuorum) {
        return createCtm(() -> AetherValue.ClusterPhase.NORMAL, inQuorum);
    }

    /// Builds a CTM whose observer knows only 4 connected core nodes (SELF, A, B, C) while
    /// `configured = 5`. The 5th slot therefore has NO connected candidate for §3 step-1 binding,
    /// so the provisioning FALLBACK (§4) is the only way to fill it — this is the setup that
    /// exercises provisioning under the universal-fill regime (a connected node always binds first).
    private ClusterTopologyManager createCtmWithFourNodeObserver(java.util.function.BooleanSupplier inQuorum) {
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C));
        var fourNodeObserver = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(fourNodeObserver,
                                                             lifecycleManager,
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshotSource,
                                                             clusterStore::current,
                                                             clusterStore::lifecycle,
                                                             clusterStore::slots,
                                                             clusterStore::apply,
                                                             new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                             LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                  clusterStore::lifecycle,
                                                                                                  System::currentTimeMillis),
                                                             () -> AetherValue.ClusterPhase.NORMAL,
                                                             inQuorum);
    }

    private ClusterTopologyManager createCtm(java.util.function.Supplier<AetherValue.ClusterPhase> phase,
                                             java.util.function.BooleanSupplier inQuorum) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                             lifecycleManager,
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshotSource,
                                                             clusterStore::current,
                                                             clusterStore::lifecycle,
                                                             clusterStore::slots,
                                                             clusterStore::apply,
                                                             new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                             LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                  clusterStore::lifecycle,
                                                                                                  System::currentTimeMillis),
                                                             phase,
                                                             inQuorum);
    }

    @Test
    void setDesiredSize_writesClusterConfigValueAtom_withIncrementedVersion() {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 5);
        ctm.activate();
        var before = clusterStore.currentVersion();
        var result = ctm.setDesiredSize(7).await();
        assertThat(result.isSuccess()).isTrue();
        var after = clusterStore.current().unwrap();
        assertThat(after.coreCount()).isEqualTo(7);
        assertThat(after.configVersion()).isEqualTo(before + 1);
    }

    @Test
    void setDesiredSize_belowQuorum_rejectedWithoutAtomWrite() {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 5);
        ctm.activate();
        var before = clusterStore.currentVersion();
        var result = ctm.setDesiredSize(2).await();
        assertThat(result.isFailure()).isTrue();
        assertThat(clusterStore.currentVersion()).isEqualTo(before);
    }

    @Test
    void reconcile_observesDeficit_onSubsequentSnapshotTrigger() throws InterruptedException {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 5);
        ctm.activate();
        ctm.onNodeReady(PEER_A);
        Thread.sleep(100L);
        assertThat(lifecycleManager.provisionCount.get()).isZero();
        // The reducer STOPs a dropped core; CTM then frees + refills its slot.
        clusterStore.installStopped(PEER_C);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_D), 5);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        awaitProvision(1);
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);
    }

    /// BUG 1 CONSISTENCY GUARD (pass-after; NOT a fail-before reproduction). `classifyOccupied`
    /// keys occupancy on the lifecycle KV (`lifecycleReader`, DECIDE plane), NOT on the SWIM /
    /// observer transport-health gate that the old `occupantHealthy()` carried. This is structurally
    /// inert today (a bound slot is never refilled and `settleConverged` ignores `countHealthy`), so
    /// it cannot fail-before through the public surface — the guard locks in the sovereign-FSM
    /// principle so a future refactor that makes occupancy observable cannot silently re-introduce
    /// the SWIM gate. An occupant ON_DUTY in lifecycle KV but with NO observer health entry (the
    /// freshly-joined / SWIM-lagged case) must keep the cluster Converged with zero re-provision.
    @Test
    void classifyOccupied_keysOnLifecycleNotSwimHealthGate_consistencyGuard() throws InterruptedException {
        clusterStore.seed(5);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 5);
        ctm.activate();
        Thread.sleep(100L);
        // SWIM/observer carries no HEALTHY hint for the occupants (the old occupantHealthy() gate
        // would mis-classify them FILLING), while the lifecycle KV keeps every occupant ON_DUTY.
        var provisionsAtConverge = lifecycleManager.provisionCount.get();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        Thread.sleep(100L);
        assertThat(ctm.reconcilerState())
                .as("lifecycle-keyed occupancy keeps a fully-bound cluster Converged")
                .isInstanceOf(NodeReconcilerState.Converged.class);
        assertThat(lifecycleManager.provisionCount.get())
                .as("no re-provision — occupancy classified on the DECIDE plane, not the SWIM gate")
                .isEqualTo(provisionsAtConverge);
    }

    /// PRESERVE-PATH regression GUARD (async/timing; Docker is the decisive gate). With a DEFERRED
    /// `commandApplier` (resolves writes on a LATER tick, recreating the stale-read window the real
    /// async Rabia KV has) and KV that ALREADY holds 5 bound slots (leader-change / preserve path,
    /// §2), activation must NOT clobber the existing bindings: `maintainSlotSetSize` finds no missing
    /// indices, so it issues no EMPTY seed, and the universal fill finds every connected member
    /// already bound, so it binds nothing new. All 5 bindings survive the stale-read window.
    @Test
    void activate_withDeferredCommandApplier_preservesExistingBindings_noClobber() throws InterruptedException {
        var deferred = new DeferredCommandApplier(clusterStore);
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();
        var deferredCtm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                        lifecycleManager,
                                                                        autoHeal,
                                                                        DeploymentMap.deploymentMap(),
                                                                        snapshotSource,
                                                                        clusterStore::current,
                                                                        clusterStore::lifecycle,
                                                                        clusterStore::slots,
                                                                        deferred::apply,
                                                                        new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                                        LegacyLifecycleWriterFixture.create(deferred::apply,
                                                                                                            clusterStore::lifecycle,
                                                                                                            System::currentTimeMillis),
                                                                        () -> AetherValue.ClusterPhase.NORMAL);
        clusterStore.seed(5);
        // KV ALREADY holds 5 slots bound to the 5 keepers (preserve path).
        var keepers = List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        for (var index = 0; index < keepers.size(); index++) {clusterStore.seedSlot(index, keepers.get(index));}
        var epoch = 0L;
        for (var id : keepers) {clusterStore.installOnDuty(id, epoch++);}
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5,
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        deferredCtm.activate();
        // Flush any queued writes — the chained activation reconcile runs against the committed map.
        deferred.flush();
        Thread.sleep(100L);
        var boundCount = clusterStore.slots()
                                     .values()
                                     .stream()
                                     .filter(slot -> slot.assignedNodeId().isPresent())
                                     .count();
        assertThat(boundCount)
                .as("all 5 pre-bound slots stay BOUND through the stale-read window — no EMPTY clobber")
                .isEqualTo(5L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("no surplus provisioning at a fully-occupied cluster")
                .isZero();
    }

    /// §2 first formation (KV empty): the leader creates the slot set and binds the present
    /// non-stopped occupants. This is the ONLY path that writes initial bindings.
    @Test
    void activate_firstFormation_createsAndBindsSlots_whenKvEmpty() throws InterruptedException {
        clusterStore.seed(5);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {clusterStore.installOnDuty(id, epoch++);}
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5,
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        ctm.activate();
        Thread.sleep(150L);
        var slots = clusterStore.slots();
        assertThat(slots).as("first formation creates exactly configured slots").hasSize(5);
        var boundOccupants = slots.values()
                                  .stream()
                                  .flatMap(slot -> slot.assignedNodeId().stream())
                                  .collect(java.util.stream.Collectors.toSet());
        assertThat(boundOccupants)
                .as("first formation binds the present non-stopped occupants")
                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
    }

    /// §2 leader change / re-activation (KV already has slots): the leader does NOT wipe and does NOT
    /// rebind. Existing `slot→node` bindings persist across activation even when the ON_DUTY snapshot
    /// presents a different membership (here a replacement candidate PEER_ORPHAN that the old
    /// wipe-and-reseed would have bound into a slot, orphaning a live original).
    @Test
    void activate_leaderChange_preservesExistingBindings_noWipeNoRebind() throws InterruptedException {
        clusterStore.seed(5);
        // KV already holds 5 slots bound to the 5 keepers (survived a prior leader's formation).
        var keepers = List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        for (var index = 0; index < keepers.size(); index++) {clusterStore.seedSlot(index, keepers.get(index));}
        var epoch = 0L;
        for (var id : keepers) {clusterStore.installOnDuty(id, epoch++);}
        // The new leader's snapshot lists a replacement candidate (PEER_ORPHAN) ahead by seniority —
        // the old reseed would rebind a slot to it. Create-once/preserve must ignore it.
        clusterStore.installOnDuty(PEER_ORPHAN, 0L);
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, PEER_ORPHAN),
                                            Set.of(PEER_ORPHAN, SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            6,
                                            5,
                                            Set.of(),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        ctm.activate();
        Thread.sleep(150L);
        var boundOccupants = clusterStore.slots()
                                         .values()
                                         .stream()
                                         .flatMap(slot -> slot.assignedNodeId().stream())
                                         .collect(java.util.stream.Collectors.toSet());
        assertThat(boundOccupants)
                .as("existing bindings persist across leader change — no rebind to the replacement candidate")
                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        assertThat(boundOccupants)
                .as("the replacement candidate is never bound into a slot (no wipe-and-rebind)")
                .doesNotContain(PEER_ORPHAN);
    }

    /// §6 scale-down: the leader removes the surplus slot ATOMS (index >= configured), unbinding
    /// their occupants, but does NOT terminate them — the now-unbound occupants self-drain in Phase
    /// 2 (§5). 5 slots bound, coreCount shrinks to 3 → slots 3-4 removed, ZERO terminations.
    @Test
    void scaleDown_removesSurplusSlotAtoms_doesNotTerminateOccupants() throws InterruptedException {
        clusterStore.seed(5);
        var keepers = List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        for (var index = 0; index < keepers.size(); index++) {clusterStore.seedSlot(index, keepers.get(index));}
        var epoch = 0L;
        for (var id : keepers) {clusterStore.installOnDuty(id, epoch++);}
        ctm.activate();
        Thread.sleep(100L);
        // Scale down to 3 → slots 3 and 4 are surplus.
        clusterStore.seed(3);
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            3,
                                            Set.of(),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        ctm.onClusterConfigChanged();
        Thread.sleep(200L);
        assertThat(clusterStore.slots().keySet())
                .as("surplus slot atoms [3,4] are removed on scale-down")
                .doesNotContain(ProvisioningSlotKey.provisioningSlotKey("3"),
                                ProvisioningSlotKey.provisioningSlotKey("4"));
        assertThat(clusterStore.slots())
                .as("the slot set shrinks to exactly configured=3")
                .hasSize(3);
        assertThat(lifecycleManager.terminateCount.get())
                .as("the leader does NOT terminate scale-down occupants — they self-drain via §5")
                .isZero();
    }

    // ---------------------------------------------------------------------------------------
    // slot-based-core-membership-redesign Phase 2b: universal slot-fill (§3) tests.
    // ---------------------------------------------------------------------------------------

    /// §3 formation: N connected unbound seeds + empty slots → universal fill (step 1) BINDS them
    /// from the connected-members view — NO provisioning, NO boot latency. All 5 become occupants.
    @Test
    void formation_universalFillBindsConnectedSeeds_noProvisioning() throws InterruptedException {
        clusterStore.seed(5);
        // The 5 configured nodes are all connected in the observer (added healthy at construction),
        // but NONE has a lifecycle entry yet (genuine formation: connected, not yet ON_DUTY).
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(),
                                            0,
                                            5,
                                            Set.of(),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        ctm.activate();
        Thread.sleep(200L);
        var boundOccupants = clusterStore.slots()
                                         .values()
                                         .stream()
                                         .flatMap(slot -> slot.assignedNodeId().stream())
                                         .collect(java.util.stream.Collectors.toSet());
        assertThat(boundOccupants)
                .as("universal fill binds the connected seeds from the connected-members view")
                .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        assertThat(lifecycleManager.provisionCount.get())
                .as("no provisioning — connected nodes claimed every slot (§3 step 1)")
                .isZero();
    }

    /// §3 late-join: an empty slot + a connected unbound node → BOUND (step 1), not provisioned.
    /// 4 slots are pre-bound to SELF/A/B/C; slot 4 is empty; PEER_D is connected and unbound →
    /// universal fill binds PEER_D into slot 4 with zero provisioning.
    @Test
    void lateJoin_bindsConnectedUnboundNode_notProvisioned() throws InterruptedException {
        clusterStore.seed(5);
        var preBound = List.of(SELF, PEER_A, PEER_B, PEER_C);
        for (var index = 0; index < preBound.size(); index++) {clusterStore.seedSlot(index, preBound.get(index));}
        // slot 4 created empty by maintainSlotSetSize; PEER_D is connected (observer) and unbound.
        var epoch = 0L;
        for (var id : preBound) {clusterStore.installOnDuty(id, epoch++);}
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                            4,
                                            5,
                                            Set.of(),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        ctm.activate();
        Thread.sleep(200L);
        var boundOccupants = clusterStore.slots()
                                         .values()
                                         .stream()
                                         .flatMap(slot -> slot.assignedNodeId().stream())
                                         .collect(java.util.stream.Collectors.toSet());
        assertThat(boundOccupants)
                .as("the connected unbound node claims the empty slot (no provisioning)")
                .contains(PEER_D);
        assertThat(lifecycleManager.provisionCount.get())
                .as("late join binds an existing connected node — never provisions")
                .isZero();
    }

    /// §3 formation suppression: phase != NORMAL (COLD_BOOT) + an empty slot with NO connected
    /// candidate → NO provisioning (provisioning is suppressed during formation until the
    /// formation-timeout). The 4-node observer leaves the 5th slot candidate-less; COLD_BOOT phase
    /// must keep provisioning suppressed.
    @Test
    void formationPhase_emptySlotNoCandidate_doesNotProvision() throws InterruptedException {
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C));
        var fourNodeObserver = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();
        var coldBootCtm = ClusterTopologyManager.clusterTopologyManager(fourNodeObserver,
                                                                        lifecycleManager,
                                                                        autoHeal,
                                                                        DeploymentMap.deploymentMap(),
                                                                        snapshotSource,
                                                                        clusterStore::current,
                                                                        clusterStore::lifecycle,
                                                                        clusterStore::slots,
                                                                        clusterStore::apply,
                                                                        new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                                        LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                            clusterStore::lifecycle,
                                                                                                            System::currentTimeMillis),
                                                                        () -> AetherValue.ClusterPhase.COLD_BOOT,
                                                                        () -> true);
        clusterStore.seed(5);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        coldBootCtm.activate();
        Thread.sleep(200L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("formation phase (COLD_BOOT) suppresses provisioning even with a candidate-less empty slot")
                .isZero();
    }

    private void publishOnDuty(Set<NodeId> onDuty, int coreCount) {
        publishOnDutyWithSource(onDuty, onDuty, coreCount);
    }

    private void publishOnDutyWithSource(Set<NodeId> onDuty, Set<NodeId> ctmProvisioned, int coreCount) {
        clusterStore.seed(coreCount);
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        snapshotSource.publish(new StubView(all, onDuty, onDuty.size(), coreCount, ctmProvisioned, Set.of()),
                               snapshotSource.term.get() + 1L);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {
            if (onDuty.contains(id)) {clusterStore.installOnDuty(id, epoch++);}
        }
    }

    private void awaitProvision(int atLeast) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.provisionCount.get() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {}

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

    /// Recreates the async-commit window of the real Rabia KV: `apply` records the write into a
    /// pending queue WITHOUT mutating the store and returns an unresolved Promise; `flush` commits
    /// the queued writes to the backing store and resolves the Promises. Between `apply` and
    /// `flush`, `slotReader.get()` returns the STALE pre-commit map — the window in which the old
    /// activation double-seed clobbered bindings.
    private static final class DeferredCommandApplier {
        private final RecordingClusterStore store;
        private final java.util.List<Runnable> pending = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        DeferredCommandApplier(RecordingClusterStore store) {
            this.store = store;
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            var promise = Promise.<List<Object>>promise();
            pending.add(() -> store.apply(commands).onResult(promise::resolve));

            return promise;
        }

        void flush() {
            var snapshot = List.copyOf(pending);
            pending.clear();
            snapshot.forEach(Runnable::run);
        }
    }

    private static final class RecordingClusterStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("", "", "1.0.0", coreCount, 3, 9, "test",
                                                           current.get().map(ClusterConfigValue::configVersion).or(0L) + 1L,
                                                           System.currentTimeMillis())));
        }

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                          "host-" + nodeId.id(),
                                                                          5000,
                                                                          Epoch.epoch(0L, epoch)));
        }

        void installStopped(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, "host-" + nodeId.id(), 5000));
        }

        /// Pre-seeds a durable slot already bound to `occupant` (simulates KV that survived a prior
        /// leader's first-formation seed — the input to the create-once/preserve activation path).
        void seedSlot(int index, NodeId occupant) {
            slotKv.put(ProvisioningSlotKey.provisioningSlotKey(Integer.toString(index)),
                       new ProvisioningSlotValue(1L, Long.MAX_VALUE, Option.some(occupant), 1L, Option.none()));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        long currentVersion() {
            return current.get().map(ClusterConfigValue::configVersion).or(0L);
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
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
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                current.set(Option.some(cv));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> remove) {
            if (remove.key() instanceof ProvisioningSlotKey psk) {slotKv.remove(psk);}
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        private final CopyOnWriteArraySet<NodeId> terminatedIds = new CopyOnWriteArraySet<>();

        Set<NodeId> terminatedNodeIds() {
            return Set.copyOf(terminatedIds);
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var count = provisionCount.incrementAndGet();
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
