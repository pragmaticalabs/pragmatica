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

    @Test
    void reconcile_provisionsIntoEmptySlots_whenSnapshotReportsDeficit() throws InterruptedException {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        ctm.activate();
        ctm.onNodeReady(PEER_A);
        awaitProvision(1);
        assertThat(lifecycleManager.provisionCount.get())
                .as("one EMPTY slot filled for the 4-of-5 cluster")
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
    /// `inQuorum=() -> false` simulates a minority-side leader; a clear 4-of-5 deficit that would
    /// otherwise provision must result in ZERO provisions.
    @Test
    void reconcile_doesNotProvision_whenBelowQuorum() throws InterruptedException {
        var belowQuorumCtm = createCtmWithQuorum(() -> false);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        belowQuorumCtm.activate();
        belowQuorumCtm.onNodeReady(PEER_A);
        Thread.sleep(200L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("below quorum — provisioning deferred to SelfDrainCoordinator, no replacement nodes")
                .isZero();
    }

    /// LAYER 3 (gate-open preserves existing behavior): with `inQuorum=() -> true` the same deficit
    /// provisions normally — the gate is a pure suppressor, not a behavior change above quorum.
    @Test
    void reconcile_provisionsIntoEmptySlots_whenInQuorum() throws InterruptedException {
        var inQuorumCtm = createCtmWithQuorum(() -> true);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), 5);
        inQuorumCtm.activate();
        inQuorumCtm.onNodeReady(PEER_A);
        awaitProvision(1);
        assertThat(lifecycleManager.provisionCount.get())
                .as("in quorum — normal deficit provisioning proceeds")
                .isGreaterThanOrEqualTo(1);
    }

    /// LAYER 2 (reseed skips STOPPED occupants): on leader activation the reseed binds occupants
    /// from the ON_DUTY snapshot, but a peer whose committed lifecycle is already STOPPED (the
    /// SENSE plane lagging the DECIDE plane) must NOT be re-bound — re-binding fires a SlotClaimed
    /// for a dead peer. Its slot must be left EMPTY for normal convergence to refill.
    @Test
    void activate_reseedSkipsStoppedOccupant_leavesSlotEmptyForRefill() throws InterruptedException {
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
        Thread.sleep(150L);
        var boundToDead = clusterStore.slots()
                                      .values()
                                      .stream()
                                      .filter(slot -> slot.assignedNodeId().map(PEER_D::equals).or(false))
                                      .count();
        assertThat(boundToDead)
                .as("no slot is re-bound to the already-STOPPED occupant PEER_D")
                .isZero();
        var boundCount = clusterStore.slots()
                                     .values()
                                     .stream()
                                     .filter(slot -> slot.assignedNodeId().isPresent())
                                     .count();
        assertThat(boundCount)
                .as("the 4 live occupants are bound; the dead occupant's slot is left EMPTY")
                .isEqualTo(4L);
    }

    private ClusterTopologyManager createCtmWithQuorum(java.util.function.BooleanSupplier inQuorum) {
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
                                                             () -> AetherValue.ClusterPhase.NORMAL,
                                                             inQuorum);
    }

    @Test
    void reconcile_terminatesSurplus_whenSnapshotReportsOverCapacity() throws InterruptedException {
        // 5 ON_DUTY occupants bound to slots 0-4, but coreCount shrinks to 3 → slots 3-4 removed.
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), 3);
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        awaitTerminate(1);
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
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

    /// Option B safety invariant: surplus slots whose occupants are MANUAL-source are NEVER
    /// auto-terminated — CTM must not kill an operator-seeded node on scale-down.
    @Test
    void reconcile_terminatesOnlyCtmProvisionedSurplus_whenSurplusIsManual() throws InterruptedException {
        // coreCount 3 → slots 3-4 surplus, but their occupants (PEER_C, PEER_D) are MANUAL.
        publishOnDutyWithSource(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), Set.of(), 3);
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        Thread.sleep(100L);
        assertThat(lifecycleManager.terminateCount.get())
                .as("MANUAL-source surplus occupants are NOT auto-terminated")
                .isZero();
    }

    @Test
    void reconcile_terminatesCtmProvisionedSurplus_whenCandidatesAreCtm() throws InterruptedException {
        // coreCount 3 → slots 3-4 surplus; their occupants (PEER_C, PEER_D) are CTM-provisioned.
        publishOnDutyWithSource(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), Set.of(PEER_C, PEER_D), 3);
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        awaitTerminate(1);
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(lifecycleManager.terminatedNodeIds()).isSubsetOf(Set.of(PEER_C, PEER_D));
    }

    /// Option B safety invariant: an empty CTM-provisioned set (legacy / UNKNOWN projection) means
    /// no surplus occupant is reapable — selection refuses to terminate anything (conservative).
    @Test
    void reconcile_doesNotTerminate_whenAllCandidatesUnknownProvisioningSource() throws InterruptedException {
        publishOnDutyWithSource(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), Set.of(), 3);
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        Thread.sleep(100L);
        assertThat(lifecycleManager.terminateCount.get()).isZero();
    }

    /// §5.4 highest-index removal: scale-down 5→4 removes slot 4. Its occupant is the youngest
    /// bound core (highest seniority epoch → highest index), reaped because CTM-provisioned.
    @Test
    void reconcile_reapsHighestIndexSlot_onScaleDownByOne() throws InterruptedException {
        publishOnDutyWithSource(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), Set.of(PEER_C, PEER_D), 4);
        ctm.activate();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        awaitTerminate(1);
        assertThat(lifecycleManager.terminateCount.get()).isEqualTo(1);
        // Slot 4 holds the youngest occupant (PEER_D, highest epoch) — it is reaped.
        assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_D);
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

    /// BUG 2 regression GUARD (activation double-seed clobber — async/timing; Docker is the
    /// decisive gate). With a DEFERRED `commandApplier` (resolves the reseed write on a LATER tick,
    /// recreating the stale-read window the real async Rabia KV has), activation with 5 ON_DUTY
    /// occupants must leave all 5 slots BOUND. Old code ran `maintainSlotSetSize` against the
    /// pre-commit (empty) map → re-seeded indices 0-4 EMPTY → clobbered the bindings (fails-before).
    /// New: the activation reconcile is chained onto the reseed COMMIT → bindings survive.
    @Test
    void activate_withDeferredCommandApplier_keepsBindings_noClobber() throws InterruptedException {
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
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {clusterStore.installOnDuty(id, epoch++);}
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5,
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of()),
                               snapshotSource.term.get() + 1L);
        deferredCtm.activate();
        // The reseed write is queued but not yet committed (stale-read window open). Now flush it —
        // the chained activation reconcile runs against the committed map.
        deferred.flush();
        Thread.sleep(100L);
        var boundCount = clusterStore.slots()
                                     .values()
                                     .stream()
                                     .filter(slot -> slot.assignedNodeId().isPresent())
                                     .count();
        assertThat(boundCount)
                .as("all 5 reseeded slots stay BOUND through the stale-read window — no EMPTY clobber")
                .isEqualTo(5L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("no surplus provisioning at a fully-occupied cluster")
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

    private void awaitTerminate(int atLeast) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.terminateCount.get() < atLeast && System.currentTimeMillis() < deadline) {
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
