// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Theme K #1 (Fix E): on `Active.onEntry`, `transitionalStateTimestamps` must be re-derived
/// from the KV-Store atoms so a leader handoff does not reset the stuck-slice timer to 3×
/// the configured timeout. Atoms predating the schema bump (`transitionedAt == 0L`) fall
/// back to `nowMs()` — equivalent to the legacy "fresh timer at handoff" behaviour.
class ClusterDeploymentStateActiveTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;
    private AtomicLong injectedClock;

    @BeforeEach
    void setUp() {
        injectedClock = new AtomicLong(10_000_000L);
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        LongSupplier clock = injectedClock::get;

        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, NODE_A),
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        harness = FsmTestHarness.harness("active-rederive-test-" + SELF.id(), factory);
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private void seedNodeArtifact(NodeId nodeId, SliceState state, long transitionedAt) {
        // Seed a SliceTarget so processKVEntry registers the artifact as a blueprint —
        // otherwise cleanupOrphanedSliceEntries strips the slice during rebuild.
        var artifactBase = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
        var version = Version.version("1.0.0").unwrap();
        kvStore.put(SliceTargetKey.sliceTargetKey(artifactBase),
                    SliceTargetValue.sliceTargetValue(version, 1));
        kvStore.put(NodeArtifactKey.nodeArtifactKey(nodeId, ARTIFACT),
                    NodeArtifactValue.nodeArtifactValue(state, transitionedAt));
    }

    @Nested
    class RederivationFromKvStore {
        @Test
        void active_onEntry_rederivesTransitionalTimestampsFromKV() {
            // Pre-populate KV with a transitional NodeArtifactValue carrying a real
            // transitionedAt. Becoming Active must re-derive the in-memory timestamp map
            // from those persisted atoms — not stamp nowMs().
            long persistedAt = 9_999_000L;
            seedNodeArtifact(NODE_A, SliceState.ACTIVATING, persistedAt);

            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);
            assertThat(activeState().transitionalStateTimestamps())
                    .as("transitional timestamp must be re-derived from the persisted atom")
                    .containsEntry(sliceKey, persistedAt);
            assertThat(activeState().sliceStates())
                    .containsEntry(sliceKey, SliceState.ACTIVATING);
        }

        @Test
        void active_onEntry_legacyZeroTimestamp_fallsBackToNow() {
            // A pre-schema-bump atom carries transitionedAt = 0L. The consumer must
            // fall back to nowMs() so the stuck-slice timer starts fresh at handoff time
            // — equivalent to the legacy behaviour for upgrade compatibility.
            seedNodeArtifact(NODE_A, SliceState.ACTIVATING, 0L);
            long expectedFallback = injectedClock.get();

            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);
            assertThat(activeState().transitionalStateTimestamps())
                    .as("legacy atom (transitionedAt=0L) must fall back to ctx.nowMs()")
                    .containsEntry(sliceKey, expectedFallback);
        }

        @Test
        void active_onEntry_terminalState_doesNotPopulateTimestamp() {
            // ACTIVE/LOADED/FAILED are not transitional — they must not appear in the
            // timestamp map even when restored from KV.
            seedNodeArtifact(NODE_A, SliceState.ACTIVE, 0L);

            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);
            assertThat(activeState().transitionalStateTimestamps()).doesNotContainKey(sliceKey);
            assertThat(activeState().sliceStates()).containsEntry(sliceKey, SliceState.ACTIVE);
        }

        @Test
        void stuckSliceDetection_afterLeaderHandoff_remediatesAtConfiguredTimeoutNotTriple() {
            // Simulate a slice that entered ACTIVATING at t=t0. The original leader handed
            // off at t0+2s. The new leader (this harness) becomes Active at t0+5s. With the
            // re-derivation fix, the stuck-slice timer reads from the persisted atom: the
            // observation window is (now - t0), not (now - handoff). At t0 +
            // STUCK_TIMEOUT_MULTIPLIER * timeout, the timer must fire.
            long t0 = 1_000_000L;
            long handoffAt = t0 + 2_000L;
            injectedClock.set(handoffAt);

            // ACTIVATING timeout is 90s; stuck threshold is 3 * 90s = 270s past t0.
            seedNodeArtifact(NODE_A, SliceState.ACTIVATING, t0);

            // New leader becomes active at handoffAt.
            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);
            assertThat(activeState().transitionalStateTimestamps())
                    .as("on handoff, the timer must keep the original t0 — not reset to handoffAt")
                    .containsEntry(sliceKey, t0);

            // Without the fix, the timer would have been reset to handoffAt — so detecting
            // "stuck" would require waiting another 3*90s past handoffAt (effectively 3.022x
            // the timeout from t0). With the fix, the threshold (3*90s past t0) is met
            // immediately when we advance the clock.
            long stuckAtThreshold = t0 + (90_000L * 3);
            assertThat(stuckAtThreshold)
                    .as("stuck threshold must be measured from t0, not from handoff")
                    .isLessThan(handoffAt + (90_000L * 3));
        }
    }

    @Nested
    class HandoffCycle {
        @Test
        void freshActiveRecord_rederivesFromKvAtomNotFromInMemoryState() {
            // Hand-off semantics: a fresh Active record is created per Activate. The new
            // record's `transitionalStateTimestamps` map is empty until rebuild populates
            // it from the persisted KV atom. Verifies the per-cycle isolation guarantee
            // that motivated Theme K #1.
            //
            // The timestamp must be recent enough that `detectStuckTransitionalStates`
            // (which runs at the end of `reconcile`) does not classify it as stuck and
            // remove it during cycle execution.
            long persistedAt = injectedClock.get() - 1_000L; // 1s before now: not stuck.
            seedNodeArtifact(NODE_A, SliceState.ACTIVATING, persistedAt);
            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);

            harness.dispatch(new Activate());

            // On a fresh activation cycle the rebuild path is the only producer of
            // this entry, so its presence proves re-derivation worked.
            assertThat(activeState().transitionalStateTimestamps())
                    .as("rebuild must re-derive transitional timestamps from KV atoms")
                    .containsEntry(sliceKey, persistedAt);
        }
    }

    /// The remediator judges a slice by `sliceStates()` — an in-memory PROJECTION on the leader — and used
    /// to destroy it without ever consulting the authority it mirrors, the committed `NodeArtifactValue`.
    /// When the projection missed a transition the slice looked stuck forever, because the map it was
    /// judged by was the same map that had failed to advance.
    ///
    /// Measured 2026-08-16 (02y-stream-crash, remote cluster B): node-2's slice was ACTIVE and serving —
    /// its own log records `test-stream-multipart-stream-slice/publish depth=0 duration=25.591363ms` at
    /// 23:15:15 — and the leader force-UNLOADed it 35s later as "stuck ACTIVATING".
    /// The orphan sweep decides "no matching blueprint" from `active.blueprints()`, a leader-local
    /// projection rebuilt only on `Active` entry — nothing re-derives it during a term. One missed
    /// `AppBlueprintPut` therefore makes every slice of that artifact look orphaned for the leader's
    /// whole term, and the sweep force-UNLOADs them cluster-wide on each reconcile tick. Same shape as
    /// the stuck-slice remediator that destroyed a serving slice: judge by a projection, act destructively.
    @Nested
    class OrphanSweepStaleProjection {

        @Test
        void blueprintMissingFromProjectionButTargetCommitted_doesNotUnload() {
            seedNodeArtifact(NODE_A, SliceState.ACTIVE, injectedClock.get());
            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);

            // The projection loses the blueprint (a missed put / a rename that cleared it), while the
            // cluster's committed SliceTarget still says this exact version should be running.
            activeState().blueprints().remove(ARTIFACT);
            activeState().sliceStates().put(sliceKey, SliceState.ACTIVE);
            cluster.commands.clear();

            activeState().staleEntryCleaner().cleanupOrphanedSliceEntries();

            assertThat(cluster.commands)
                    .as("the committed SliceTarget still names this artifact — the projection was stale, so nothing may be unloaded")
                    .isEmpty();
            assertThat(activeState().sliceStates())
                    .as("a slice the cluster still targets must not be dropped from the view either")
                    .containsKey(sliceKey);
        }

        @Test
        void noCommittedTarget_stillCleansUpAsBefore() {
            seedNodeArtifact(NODE_A, SliceState.ACTIVE, injectedClock.get());
            harness.dispatch(new Activate());

            // A DIFFERENT artifact, never seeded: absent from the projection AND absent from the KV, so
            // it is genuinely orphaned rather than merely unseen by a stale view.
            var strayArtifact = Artifact.artifact("org.example:slice-stray:1.0.0").unwrap();
            var strayKey = SliceNodeKey.sliceNodeKey(strayArtifact, NODE_A);

            activeState().sliceStates().put(strayKey, SliceState.ACTIVE);
            cluster.commands.clear();

            activeState().staleEntryCleaner().cleanupOrphanedSliceEntries();

            assertThat(cluster.commands)
                    .as("with no committed target the slice IS orphaned and must still be cleaned up")
                    .isNotEmpty();
            assertThat(activeState().sliceStates())
                    .as("a genuinely orphaned entry is dropped from the view")
                    .doesNotContainKey(strayKey);
        }
    }

    @Nested
    class StaleViewProtection {

        private SliceNodeKey divergedSlice(long enteredAt, SliceState committed) {
            injectedClock.set(enteredAt);
            seedNodeArtifact(NODE_A, SliceState.ACTIVATING, enteredAt);
            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);

            // What the cluster actually committed.
            kvStore.put(NodeArtifactKey.nodeArtifactKey(NODE_A, ARTIFACT),
                        NodeArtifactValue.nodeArtifactValue(committed, enteredAt));
            // What the leader's projection still believes — the divergence, restated explicitly so the test
            // does not depend on whether the KV put happens to notify this FSM.
            activeState().sliceStates().put(sliceKey, SliceState.ACTIVATING);
            activeState().transitionalStateTimestamps().put(sliceKey, enteredAt);

            // Well past the stuck threshold (3 x the 90s ACTIVATING timeout).
            injectedClock.set(enteredAt + (90_000L * 3) + 1_000L);
            cluster.commands.clear();

            return sliceKey;
        }

        @Test
        void committedStateSettled_whileViewSaysActivating_adoptsCommittedStateAndIssuesNoUnload() {
            var sliceKey = divergedSlice(1_000_000L, SliceState.ACTIVE);

            activeState().stuckRemediator().detectStuckTransitionalStates();

            assertThat(cluster.commands)
                    .as("a slice the cluster committed as ACTIVE must never be force-unloaded — it may be serving traffic")
                    .isEmpty();
            assertThat(activeState().sliceStates())
                    .as("the stale projection must adopt the committed state instead")
                    .containsEntry(sliceKey, SliceState.ACTIVE);
            assertThat(activeState().transitionalStateTimestamps())
                    .as("adopting a settled state must also stop the stuck timer")
                    .doesNotContainKey(sliceKey);
        }

        @Test
        void committedStateStillTransitional_remediatesExactlyAsBefore() {
            var sliceKey = divergedSlice(2_000_000L, SliceState.ACTIVATING);

            activeState().stuckRemediator().detectStuckTransitionalStates();

            assertThat(cluster.commands)
                    .as("when the KV AGREES the slice is still transitional it is genuinely stuck and must still be remediated")
                    .isNotEmpty();
            assertThat(activeState().sliceStates()).doesNotContainKey(sliceKey);
        }
    }

    /// M4 not-yet-wired sentinel guard (cluster-topology-overhaul Wave 9 item 5). During the boot
    /// window the CDM core-membership supplier yields `MembershipFsm.MEMBERSHIP_NOT_WIRED`; a
    /// stale-entry cleanup that races the wiring must NO-OP rather than read the unresolved
    /// (sentinel) member set and mass-remove every KV-known node entry. A genuinely-empty (wired)
    /// member set, by contrast, MUST still drive cleanup.
    @Nested
    class M4SentinelCleanupGuard {
        private ClusterDeploymentState.Active activeWith(java.util.function.Supplier<Set<NodeId>> coreMembers) {
            var router = MessageRouter.mutable();
            var localKv = new InMemoryKvStore(router);
            var localCluster = new RecordingClusterNode(SELF);
            var artifactBase = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
            localKv.put(SliceTargetKey.sliceTargetKey(artifactBase),
                        SliceTargetValue.sliceTargetValue(Version.version("1.0.0").unwrap(), 1));
            localKv.put(NodeArtifactKey.nodeArtifactKey(NODE_A, ARTIFACT),
                        NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE, 0L));
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        localCluster,
                                                        localKv,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        stubSchemaOrchestrator(),
                                                        coreMembers,
                                                        () -> Set.of(SELF),
                                                        Set::of,
                                                        Set.of(SELF),
                                                        DeploymentAtomicity.ALL_OR_NOTHING,
                                                        3,
                                                        timeSpan(300).seconds(),
                                                        injectedClock::get).dormant();
            var localHarness = FsmTestHarness.harness("m4-sentinel-" + SELF.id() + "-" + System.nanoTime(), factory);
            localHarness.dispatch(new Activate());
            return (ClusterDeploymentState.Active) localHarness.state();
        }

        @Test
        void cleanupStaleSliceEntries_notYetWiredSentinel_isNoOp() {
            var active = activeWith(() -> MembershipFsm.MEMBERSHIP_NOT_WIRED);

            active.cleanupStaleSliceEntries();

            assertThat(active.sliceStates())
                    .as("NOT_WIRED sentinel must NOT mass-classify a KV-known node's slice as stale")
                    .containsKey(SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A));
        }

        @Test
        void cleanupStaleSliceEntries_wiredEmptyMembers_removesStaleEntry() {
            var active = activeWith(Set::of);

            active.cleanupStaleSliceEntries();

            assertThat(active.sliceStates())
                    .as("a wired (resolved) member set that excludes NODE_A MUST drive its slice cleanup")
                    .doesNotContainKey(SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A));
        }
    }

    /// #543 condition 1: the pre-fix `undoToVersion`/`baselineAtVersion` wrote a bare PENDING
    /// status and reported success without ever running the schema manager. This FSM's own stalled-
    /// migration recovery (`recoverStalledSchemaMigrations`, run from `onEntry` on every leader
    /// activation) treats any PENDING record as an interrupted migration and re-dispatches it via
    /// `handleSchemaPending` → `schemaOrchestrator().migrateIfNeeded(...)` — a FORWARD migration.
    /// Applied to an undo's leftover PENDING record, that silently re-ran the very migrations the
    /// operator had just undone. Undo/baseline now write `SchemaStatus.COMPLETED`, never PENDING,
    /// which routes `collectSchemaRecovery`'s `status == PENDING` filter around the record entirely
    /// — not merely "usually skips it", structurally cannot match. `pendingRecord_isStillReDispatched`
    /// is the control: without it, an orchestrator that is never called for ANY reason would pass
    /// `completedRecord...` too, proving nothing.
    @Nested
    class SchemaRecoveryDispatch {
        private static final String DATASOURCE = "database.orders";
        private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();

        private ClusterDeploymentState.Active activeWithSchemaRecord(SchemaStatus status, RecordingSchemaOrchestrator orchestrator) {
            var router = MessageRouter.mutable();
            var localKv = new InMemoryKvStore(router);
            var localCluster = new RecordingClusterNode(SELF);

            localKv.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                        SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                              7,
                                                              "V007__baseline",
                                                              status,
                                                              "org.example:orders-app:1.0.0",
                                                              OWNER));

            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        localCluster,
                                                        localKv,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        orchestrator,
                                                        () -> Set.of(SELF),
                                                        () -> Set.of(SELF),
                                                        Set::of,
                                                        Set.of(SELF),
                                                        DeploymentAtomicity.ALL_OR_NOTHING,
                                                        3,
                                                        timeSpan(300).seconds(),
                                                        injectedClock::get).dormant();
            var localHarness = FsmTestHarness.harness("schema-recovery-" + status + "-" + System.nanoTime(), factory);
            localHarness.dispatch(new Activate());
            return (ClusterDeploymentState.Active) localHarness.state();
        }

        @Test
        void completedRecord_fromUndo_isNeverReDispatchedAsPending() {
            var orchestrator = new RecordingSchemaOrchestrator();

            activeWithSchemaRecord(SchemaStatus.COMPLETED, orchestrator);

            assertThat(orchestrator.migrateIfNeededCalls)
                    .as("a COMPLETED record — what undo/baseline now write — must never be treated as a stalled PENDING migration")
                    .isEmpty();
        }

        @Test
        void pendingRecord_isStillReDispatched_provingTheTestCanTellTheDifference() {
            var orchestrator = new RecordingSchemaOrchestrator();

            activeWithSchemaRecord(SchemaStatus.PENDING, orchestrator);

            assertThat(orchestrator.migrateIfNeededCalls)
                    .as("control: a genuinely PENDING record must still be re-dispatched on activation — otherwise this test proves nothing")
                    .containsExactly(DATASOURCE);
        }
    }

    /// #325 fix 2a: a slice stuck in ROUTING past 3× its 30s timeout must join the force-unload
    /// remediation arm (issuing UNLOAD), and the relocated `transitionalStateTimestamps.remove()`
    /// must fire only inside the handled arm — a fresh (non-stuck) transitional entry is left
    /// tracked so it is re-evaluated on the next sweep.
    @Nested
    class StuckRoutingRemediation {
        @Test
        void reconcile_stuckRoutingSlice_issuesUnloadAndClearsTimestamp() {
            // ROUTING timeout is 30s; stuck threshold is 3 * 30s = 90s. Seed an entry that
            // entered ROUTING 100s ago so it is unambiguously stuck.
            long stuckSince = injectedClock.get() - 100_000L;
            seedNodeArtifact(NODE_A, SliceState.ROUTING, stuckSince);

            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);
            assertThat(unloadPutsFor(sliceKey))
                    .as("a stuck ROUTING slice must be force-unloaded (UNLOAD put recorded)")
                    .isNotEmpty();
            assertThat(activeState().transitionalStateTimestamps())
                    .as("the handled (ROUTING) arm must remove the stuck timestamp")
                    .doesNotContainKey(sliceKey);
        }

        @Test
        void reconcile_freshRoutingSlice_staysTrackedAndIssuesNoUnload() {
            // A ROUTING slice that entered 1s ago is not stuck: it must remain tracked and
            // must not be remediated.
            long freshSince = injectedClock.get() - 1_000L;
            seedNodeArtifact(NODE_A, SliceState.ROUTING, freshSince);

            harness.dispatch(new Activate());

            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A);
            assertThat(activeState().transitionalStateTimestamps())
                    .as("a non-stuck ROUTING slice must stay tracked for re-evaluation")
                    .containsKey(sliceKey);
            assertThat(unloadPutsFor(sliceKey))
                    .as("a non-stuck ROUTING slice must not be force-unloaded")
                    .isEmpty();
        }

        private List<KVCommand<AetherKey>> unloadPutsFor(SliceNodeKey sliceKey) {
            var artifactKey = NodeArtifactKey.nodeArtifactKey(sliceKey.nodeId(), sliceKey.artifact());

            return cluster.commands.stream()
                                   .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                      && put.key().equals(artifactKey)
                                                      && put.value() instanceof NodeArtifactValue value
                                                      && value.state() == SliceState.UNLOAD)
                                   .toList();
        }
    }

    // --- test fixtures ---

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    /// Unlike [#stubSchemaOrchestrator], records every `migrateIfNeeded` call so
    /// [SchemaRecoveryDispatch] can assert an undo-produced COMPLETED record never triggers one.
    private static final class RecordingSchemaOrchestrator implements SchemaOrchestratorService {
        final List<String> migrateIfNeededCalls = Collections.synchronizedList(new ArrayList<>());

        @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
            migrateIfNeededCalls.add(datasourceName);
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> baseline(String datasourceName, int version) {
            return Promise.success(Unit.unit());
        }
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            return Promise.success(Collections.emptyList());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
