// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManagerTest.TestClusterNode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery.ReplayRank;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/// Exit (z), the Active-CDM arm (KVStore dispatcher design 3.7, "Replay emission order"): an **Active**
/// `ClusterDeploymentManager` fed a rolling-update replay burst by a **real** dispatched `KVStore` with the aether rank
/// issues no UNLOAD of the old version (D0 PAIR-1: VersionRouting before SliceTarget, `ClusterDeploymentState`
/// `handleSliceTargetChange`) and no ACTIVATE ahead of the slice's dependencies (PAIR-2: AppBlueprint before NodeArtifact,
/// `tryActivateIfDependenciesReady`), and a snapshot that drops the blueprint during a rolling update does not
/// deallocate it before the routing is known (row 6, `handleAppBlueprintRemoval`).
///
/// The chain under test is the store's own: `restoreSnapshot`, `replayNotifications`, the rank-major emission on the
/// dispatcher thread, the notification router, the manager's `on*Put`/`on*Remove`, the FSM, and the commands the Active
/// state writes. Nothing between the store and the manager is stubbed except the cluster (`TestClusterNode` records what
/// the leader would submit).
///
/// Each arm has a control run under an **inverted** rank over the same burst, asserting the UNLOAD and the early
/// ACTIVATE really happen, so a green arm cannot come from a burst that never reached the inversion. Mutations: the
/// aether rank changed so VersionRouting follows SliceTarget (UNLOAD arm red) / AppBlueprint follows NodeArtifact (ACTIVATE
/// arm red) / the flat rank (hash order, controls show whether it reaches an inversion) / `KVStore.rankMajor` emitting
/// every removal before any put (the row-6 arm red).
class ClusterDeploymentManagerReplayBurstTest {
    private static final ArtifactBase BASE_A = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
    private static final Artifact A_OLD = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final Artifact A_NEW = Artifact.artifact("org.example:slice-a:2.0.0").unwrap();
    private static final Artifact B_OLD = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
    private static final BlueprintId BLUEPRINT_ID = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();
    private static final VersionRoutingKey ROUTING_KEY = VersionRoutingKey.versionRoutingKey(BASE_A);
    private static final AppBlueprintKey BLUEPRINT_KEY = new AppBlueprintKey(BLUEPRINT_ID);
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE_A);

    private final NodeId self = NodeId.randomNodeId();
    private final NodeArtifactKey oldOnSelf = NodeArtifactKey.nodeArtifactKey(self, A_OLD);

    /// Installs whatever map the test sets, so a replay needs no codec.
    static final class InstallingDeserializer implements Deserializer {
        volatile Map<StructuredKey, Object> next = Map.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            return (T) next;
        }
    }

    /// A real dispatched store, an Active manager fed from it, and the recording cluster the manager writes to.
    final class Burst implements AutoCloseable {
        final TestClusterNode cluster = new TestClusterNode(self);
        final InstallingDeserializer deserializer = new InstallingDeserializer();
        final CopyOnWriteArrayList<Object> delivered = new CopyOnWriteArrayList<>();
        final KVStore<AetherKey, AetherValue> store;
        final ClusterDeploymentManager manager;

        Burst(ReplayRank rank) {
            var router = MessageRouter.mutable();

            store = new KVStore<AetherKey, AetherValue>(router,
                                                        new Serializer() {
                                                            @Override
                                                            public <T> void write(ByteBuf buffer, T value) {}
                                                        },
                                                        deserializer,
                                                        NotificationDelivery.dispatched("replay-burst",
                                                                                        rank,
                                                                                        DispatcherFailureSink.loggingOnly()));
            manager = ClusterDeploymentManager.clusterDeploymentManager(self,
                                                                        cluster,
                                                                        store,
                                                                        router,
                                                                        List.of(self),
                                                                        cluster.topologyManager(),
                                                                        ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT,
                                                                        0,
                                                                        ClusterDeploymentManager.DEFAULT_RECONCILE_INTERVAL,
                                                                        ClusterDeploymentManagerTest.NO_OP_SCHEMA,
                                                                        () -> Set.of(self),
                                                                        () -> Set.of(self),
                                                                        Set::of);
            router.addRoute(ValuePut.class, this::deliverPut);
            router.addRoute(ValueRemove.class, this::deliverRemove);
        }

        /// #2584 P0: Active is entered only on a committed LeaderKey naming this node, so the leader record is committed
        /// (restored and replayed) first, then the manager activated, and only then the burst is installed.
        Burst active() {
            install(Map.of());
            manager.activate().await();
            assertThat(manager.isActive()).as("control: the manager is Active before the burst").isTrue();

            return this;
        }

        /// Installs `snapshot` (plus the leader record) as a restored snapshot and replays it; returns once the burst is
        /// delivered, so every handler has run.
        Burst install(Map<AetherKey, AetherValue> snapshot) {
            var all = new HashMap<StructuredKey, Object>(snapshot);

            all.put(new LeaderKey(), new LeaderValue(self, 1));
            deserializer.next = all;
            store.restoreSnapshot(new byte[0]);
            store.replayNotifications();

            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void deliverPut(ValuePut put) {
            delivered.add(put.cause().key());
            switch (put.cause().key()) {
                case VersionRoutingKey _ -> manager.onVersionRoutingPut(put);
                case AppBlueprintKey _ -> manager.onAppBlueprintPut(put);
                case SliceTargetKey _ -> manager.onSliceTargetPut(put);
                case NodeArtifactKey _ -> manager.onNodeArtifactPut(put);
                default -> {}
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void deliverRemove(ValueRemove remove) {
            delivered.add(remove.cause().key());
            if (remove.cause().key() instanceof AppBlueprintKey) {
                manager.onAppBlueprintRemove(remove);
            }
        }

        /// The artifacts the Active manager wrote a NodeArtifact `state` command for.
        List<Artifact> written(SliceState state) {
            return cluster.appliedCommands.stream()
                          .filter(KVCommand.Put.class::isInstance)
                          .map(KVCommand.Put.class::cast)
                          .filter(put -> put.key() instanceof NodeArtifactKey && isState(put.value(), state))
                          .map(put -> ((NodeArtifactKey) put.key()).artifact())
                          .toList();
        }

        private static boolean isState(Object value, SliceState state) {
            return value instanceof NodeArtifactValue nodeArtifact && nodeArtifact.state() == state;
        }

        @Override
        public void close() {
            manager.deactivate().await();
            store.close();
        }
    }

    // === The rank under test, and the control: both rolling-update pairs inverted ===

    /// NodeArtifact, then AppBlueprint, then SliceTarget, then VersionRouting: every D0 pair backwards, and the order in
    /// which an Active manager UNLOADs the old version (blueprint registered, routing unknown at the SliceTarget) and
    /// activates a slice before its dependencies are known (NodeArtifact before the blueprint).
    static int invertedRank(StructuredKey key) {
        return switch (key) {
            case NodeArtifactKey _ -> 0;
            case AppBlueprintKey _ -> 1;
            case SliceTargetKey _ -> 2;
            case VersionRoutingKey _ -> 3;
            default -> 0;
        };
    }

    // === The rolling-update burst: old version A (needs B) loaded, routing 1 -> 2 pending, target already at 2 ===

    private ExpandedBlueprint blueprint() {
        var b = ResolvedSlice.resolvedSlice(B_OLD, 1, 1, true, Set.of()).unwrap();
        var a = ResolvedSlice.resolvedSlice(A_OLD, 1, 1, false, Set.of(B_OLD)).unwrap();

        return ExpandedBlueprint.expandedBlueprint(BLUEPRINT_ID, List.of(b, a));
    }

    private Map<AetherKey, AetherValue> rollingUpdateSnapshot() {
        return Map.of(ROUTING_KEY,
                      VersionRoutingValue.versionRoutingValue(A_OLD.version(), A_NEW.version()),
                      BLUEPRINT_KEY,
                      new AppBlueprintValue(blueprint()),
                      TARGET_KEY,
                      SliceTargetValue.sliceTargetValue(A_NEW.version(), 1),
                      oldOnSelf,
                      NodeArtifactValue.nodeArtifactValue(SliceState.LOADED));
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void rollingUpdateBurst_neverUnloadsTheOldVersionNorActivatesAheadOfDependencies() {
        try (var burst = new Burst(AetherNode::replayRank).active()) {
            burst.install(rollingUpdateSnapshot());

            assertThat(burst.delivered).as("control: the whole burst reached the Active manager, in aether rank order")
                                       .containsSubsequence(ROUTING_KEY, BLUEPRINT_KEY, TARGET_KEY, oldOnSelf);
            assertThat(burst.written(SliceState.UNLOAD)).as("no UNLOAD of the old version").isEmpty();
            assertThat(burst.written(SliceState.ACTIVATE)).as("no ACTIVATE ahead of the dependency B").isEmpty();
        }
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void rollingUpdateBurstUnderTheInvertedRank_unloadsAndActivatesEarly_provingTheProbeReachesBothInversions() {
        try (var burst = new Burst(ClusterDeploymentManagerReplayBurstTest::invertedRank).active()) {
            burst.install(rollingUpdateSnapshot());

            assertThat(burst.delivered).as("control: the inverted order was delivered")
                                       .containsSubsequence(oldOnSelf, BLUEPRINT_KEY, TARGET_KEY, ROUTING_KEY);
            assertThat(burst.written(SliceState.UNLOAD)).as("the old version is UNLOADed mid rolling update")
                                                        .contains(A_OLD);
            assertThat(burst.written(SliceState.ACTIVATE)).as("A is ACTIVATEd with B not yet active").contains(A_OLD);
        }
    }

    // === Row 6: a snapshot install that drops the blueprint while the routing arrives ===

    private Map<AetherKey, AetherValue> beforeSnapshot() {
        return Map.of(BLUEPRINT_KEY,
                      new AppBlueprintValue(blueprint()),
                      oldOnSelf,
                      NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
    }

    private Map<AetherKey, AetherValue> blueprintDroppedDuringRollingUpdateSnapshot() {
        return Map.of(ROUTING_KEY,
                      VersionRoutingValue.versionRoutingValue(A_OLD.version(), A_NEW.version()),
                      TARGET_KEY,
                      SliceTargetValue.sliceTargetValue(A_NEW.version(), 1),
                      oldOnSelf,
                      NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void rollingUpdateBurstThatDropsTheBlueprint_neverDeallocatesBeforeTheRoutingIsKnown() {
        try (var burst = new Burst(AetherNode::replayRank).active()) {
            burst.install(beforeSnapshot());
            burst.cluster.appliedCommands.clear();
            burst.delivered.clear();

            burst.install(blueprintDroppedDuringRollingUpdateSnapshot());

            assertThat(burst.delivered).as("control: the blueprint removal and the routing put both reached the manager")
                                       .contains(BLUEPRINT_KEY, ROUTING_KEY);
            assertThat(burst.delivered.indexOf(ROUTING_KEY)).as("the routing precedes the blueprint removal")
                                                            .isLessThan(burst.delivered.indexOf(BLUEPRINT_KEY));
            assertThat(burst.written(SliceState.UNLOAD)).as("the old version stays loaded").isEmpty();
        }
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void rollingUpdateBurstThatDropsTheBlueprintUnderTheInvertedRank_deallocates_provingTheProbeReachesTheRemoval() {
        try (var burst = new Burst(ClusterDeploymentManagerReplayBurstTest::invertedRank).active()) {
            burst.install(beforeSnapshot());
            burst.cluster.appliedCommands.clear();
            burst.delivered.clear();

            burst.install(blueprintDroppedDuringRollingUpdateSnapshot());

            assertThat(burst.delivered.indexOf(BLUEPRINT_KEY)).as("control: the removal precedes the routing")
                                                              .isLessThan(burst.delivered.indexOf(ROUTING_KEY));
            assertThat(burst.written(SliceState.UNLOAD)).as("the old version is deallocated").contains(A_OLD);
        }
    }
}
