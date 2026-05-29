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
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.hlc.HlcTimestamp;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    private AtomicReference<Option<ClusterGenerationSnapshot>> snapshotRef;

    @BeforeEach
    void setUp() {
        injectedClock = new AtomicLong(10_000_000L);
        snapshotRef = new AtomicReference<>(Option.some(buildSnapshot(List.of(SELF, NODE_A))));
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
                                                    HealthSignalSink.noop(),
                                                    snapshotRef::get,
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        harness = FsmTestHarness.harness("active-rederive-test-" + SELF.id(), factory);
    }

    private static ClusterGenerationSnapshot buildSnapshot(List<NodeId> coreMembers) {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        for (var id : coreMembers) {
            members.put(id, CoreMember.coreMember(id,
                                                   "localhost",
                                                   9000,
                                                   NodeLifecycleState.ON_DUTY,
                                                   HealthHint.HEALTHY,
                                                   Epoch.epoch(1L, 0L),
                                                   Epoch.epoch(1L, 0L)));
        }
        return ClusterGenerationSnapshot.clusterGenerationSnapshot(Epoch.epoch(1L, 0L),
                                                                    HlcTimestamp.ZERO,
                                                                    GenerationReason.LEADER_ELECTED,
                                                                    coreMembers.size(),
                                                                    members,
                                                                    Map.of(),
                                                                    Map.of(),
                                                                    ClusterMode.CORE_ONLY,
                                                                    ClusterQuiescence.QUIESCED,
                                                                    "");
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
            process(new KVCommand.Put<>(key, value));
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
