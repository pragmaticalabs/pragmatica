// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.replica.ReplicaApplier;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// A falsely-dead worker catches up (#3028, design 4): the leader removed the worker's `NodeArtifactKey`s while it was cut off, and the worker
/// re-syncs through `ReplicaApplier.install` (or an incremental `accept`). A real `NodeDeploymentManager` over a real worker `KVStore`
/// (the production codec and replica filter), fed by a real core `KVStore` snapshot; the slice store, cluster node and route publisher are doubles.
///
/// Probe gap d, settled: `ReplicaFilter` (aether/slice, line 72) INCLUDES `NodeArtifactKey`, `SliceTargetKey` and the other slice keys, so the
/// snapshot a worker installs carries its own keys and a sweep over the committed set would not see "everything absent".
///
/// "Written" means a `Put` of a `NodeArtifactKey` of this node after the removal (D2: a swept slice must not draw a FAILED write that resurrects the
/// key). The design names `SliceNodeKey`; the deployment manager here writes `NodeArtifactKey`, and the check covers both key types.
///
/// Arms B, C and F are RED at the base (gaps 1, 2 and 1-off-the-burst): each is pinned by an ENABLED tripwire asserting today's behaviour, with the
/// real assertion `@Disabled` beside it; the C3 production work enables the real assertion and deletes the tripwire.
class FalseDeathWorkerCatchUpTest {
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId CORE = NodeId.nodeId("core-1").unwrap();
    private static final Artifact GONE = Artifact.artifact("org.example:gone:1.0.0").unwrap();
    private static final Artifact KEPT = Artifact.artifact("org.example:kept:1.0.0").unwrap();

    private final SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private KVStore<AetherKey, AetherValue> core;
    private KVStore<AetherKey, AetherValue> worker;
    private ReplicaApplier<AetherKey, AetherValue> applier;
    private NodeDeploymentManager manager;
    private TestSliceStore sliceStore;
    private TestClusterNode clusterNode;
    private HttpRoutePublisher publisher;
    private IncarnationTag tag;
    private long slot;
    private long subSeq;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void workerAtQuorum() {
        var router = MessageRouter.mutable();

        core = newStore(MessageRouter.mutable());
        worker = newStore(router);
        applier = ReplicaApplier.replicaApplier(worker, 1 << 20);
        sliceStore = new TestSliceStore();
        clusterNode = new TestClusterNode();
        publisher = Mockito.mock(HttpRoutePublisher.class, FalseDeathWorkerCatchUpTest::answerPromisesAndOptions);
        manager = NodeDeploymentManager.nodeDeploymentManager(SELF,
                                                              new NodeAddress("", 0),
                                                              router,
                                                              sliceStore,
                                                              clusterNode,
                                                              worker,
                                                              Mockito.mock(InvocationHandler.class, FalseDeathWorkerCatchUpTest::answerPromisesAndOptions),
                                                              SliceActionConfig.sliceActionConfig(),
                                                              SliceCodec.sliceCodec(List.of()),
                                                              Option.some(publisher),
                                                              Option.none());
        KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                            .onPut(NodeArtifactKey.class, manager::onNodeArtifactPut)
                            .onRemove(NodeArtifactKey.class, manager::onNodeArtifactRemove)
                            .build()
                            .asRouteEntries()
                            .forEach(entry -> entry.entries()
                                                   .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last())));
        manager.onQuorumStateChange(ClusterStateNotification.active());
        commit(new KVCommand.Put<AetherKey, AetherValue>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                         ClusterIncarnationValue.genesis("lineage", "inc-1")));
    }

    private KVStore<AetherKey, AetherValue> newStore(MessageRouter router) {
        return new KVStore<>(router, codec, codec, ReplicaFilter.replicaFilter(), ReplicaIncarnationSource.replicaIncarnationSource(),
                             CommitRing.DEFAULT_BUDGET_BYTES);
    }

    private static Object answerPromisesAndOptions(org.mockito.invocation.InvocationOnMock call) throws Throwable {
        var type = call.getMethod().getReturnType();

        if (type == Promise.class) {
            return Promise.unitPromise();
        }
        if (type == Option.class) {
            return Option.none();
        }
        return Mockito.RETURNS_DEFAULTS.answer(call);
    }

    @SafeVarargs
    private void commit(KVCommand<AetherKey>... commands) {
        core.processCommitted(core.createBatch(List.of(commands)), slot + 1);
        core.onPhaseCompleted(slot + 1);
        slot++;
    }

    private void assign(Artifact artifact, SliceState state) {
        commit(new KVCommand.Put<AetherKey, AetherValue>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                                         SliceTargetValue.sliceTargetValue(artifact.version(), 1)),
               new KVCommand.Put<AetherKey, AetherValue>(NodeArtifactKey.nodeArtifactKey(SELF, artifact),
                                                         NodeArtifactValue.nodeArtifactValue(state)));
    }

    private void leaderRemoves(Artifact artifact) {
        commit(new KVCommand.Remove<AetherKey>(NodeArtifactKey.nodeArtifactKey(SELF, artifact)));
    }

    /// The worker re-syncs from a fresh core snapshot: subscribe first (buffer), install, go live.
    private void resync() {
        var snapshot = core.captureReplicaSnapshot(ReplicaFilter.replicaFilter()).unwrap();

        tag = snapshot.tag();
        subSeq = 0L;
        applier.beginBuffering();
        applier.install(snapshot.bytes(), snapshot.tag(), snapshot.revision(), snapshot.anchor()).unwrap();
        applier.beginLive();
    }

    /// One incremental live feed message carrying `effects`, with no replay burst behind it.
    @SafeVarargs
    private void feed(KVCommand<?>... effects) {
        var from = applier.position().applied();
        var entry = new ReplicaEntry(tag, from, from, "live-" + from, List.of(effects));

        subSeq++;
        assertThat(applier.accept(new ReplicaEntries(CORE, 1L, subSeq, List.of(entry), from + 1))).isEqualTo(ReplicaApplier.Step.APPLIED);
    }

    private void sliceActive(Artifact artifact) {
        sliceStore.markAsLoadedWithSlice(artifact);
        assign(artifact, SliceState.ACTIVE);
    }

    private int writesBefore() {
        return clusterNode.appliedCommands.size();
    }

    private List<String> nodeKeyPutsSince(int mark) {
        var writes = new ArrayList<String>();

        clusterNode.appliedCommands.stream()
                                   .skip(mark)
                                   .filter(command -> command instanceof KVCommand.Put<?, ?>)
                                   .map(command -> ((KVCommand.Put<?, ?>) command).key())
                                   .filter(key -> key instanceof NodeArtifactKey node && node.nodeId().equals(SELF)
                                                  || key instanceof AetherKey.SliceNodeKey slice && slice.nodeId().equals(SELF))
                                   .forEach(key -> writes.add(String.valueOf(key)));
        return writes;
    }

    private void assertUnloadedAndQuiet(Artifact artifact, int mark) {
        assertThat(sliceStore.loaded()).as("the slice the leader removed is no longer loaded on the worker").isEmpty();
        Mockito.verify(publisher, Mockito.atLeastOnce()).unpublishRoutes(artifact);
        assertThat(nodeKeyPutsSince(mark)).as("D2: the worker writes no key of its own for the removed slice").isEmpty();
    }

    // --- Arm A: ACTIVE, manager stays Active (the main path; the green control) ---

    @Test
    void armA_activeSlice_isUnloadedByTheReplayedRemove() {
        sliceActive(GONE);
        resync();
        var mark = writesBefore();

        leaderRemoves(GONE);
        resync();

        assertUnloadedAndQuiet(GONE, mark);
    }

    // --- Arm B: LOADED slice (gap 1) ---

    private void armBRun() {
        assign(GONE, SliceState.LOAD);
        resync();
        leaderRemoves(GONE);
        resync();
    }

    @Test
    void armB_tripwire_loadedSliceStaysLoadedToday() {
        armBRun();

        assertThat(sliceStore.loaded()).as("gap 1 is still open (a LOADED slice survives its replayed remove); when the root fix lands, "
                                           + "enable armB_loadedSlice_isUnloaded and delete this tripwire").extracting(SliceStore.LoadedSlice::artifact)
                                       .containsExactly(GONE);
    }

    @Test
    @Disabled("C3 root fix (design 4 rule 1): enable when shouldForceCleanup becomes 'loaded locally'")
    void armB_loadedSlice_isUnloaded() {
        var mark = writesBefore();

        armBRun();

        assertUnloadedAndQuiet(GONE, mark);
    }

    // --- Arm C: Dormant drops the replayed remove, then QuorumEstablished reactivates (gap 2) ---

    private int armCRun() {
        sliceActive(GONE);
        resync();
        manager.onQuorumStateChange(ClusterStateNotification.passive());
        var mark = writesBefore();

        leaderRemoves(GONE);
        resync();
        manager.onQuorumStateChange(ClusterStateNotification.active());
        return mark;
    }

    @Test
    void armC_tripwire_dormantDropsTheRemove_sliceStaysLoadedToday() {
        armCRun();

        assertThat(sliceStore.loaded()).as("gap 2 is still open (Dormant drops the replayed remove and reactivation keeps the slice); when the "
                                           + "sweep lands, enable armC_dormantDrop_isUnloaded and delete this tripwire")
                                       .extracting(SliceStore.LoadedSlice::artifact)
                                       .containsExactly(GONE);
    }

    @Test
    @Disabled("C3 sweep + reactivation filter (design 4 rules 2-3): enable when CatchUpSwept lands")
    void armC_dormantDrop_isUnloaded() {
        var mark = armCRun();

        assertUnloadedAndQuiet(GONE, mark);
    }

    // --- Arm D: one removed key, one still-assigned key (overreach pin) ---

    @Test
    void armD_onlyTheRemovedSlice_isUnloaded() {
        sliceActive(GONE);
        sliceActive(KEPT);
        resync();

        leaderRemoves(GONE);
        resync();

        assertThat(sliceStore.loaded()).extracting(SliceStore.LoadedSlice::artifact).containsExactly(KEPT);
        assertThat(sliceStore.unloadCalls).doesNotContain(KEPT);
        assertThat(sliceStore.deactivateCalls).doesNotContain(KEPT);
    }

    // --- Arm E: a put for the same key arrives right after the removal (the LOAD-waits-for-cleanup ordering pin) ---

    @Test
    void armE_putAfterTheRemove_endsLoadedAndNeverFailed() {
        sliceActive(GONE);
        resync();
        var mark = writesBefore();

        leaderRemoves(GONE);
        resync();
        feed(new KVCommand.Put<AetherKey, AetherValue>(NodeArtifactKey.nodeArtifactKey(SELF, GONE), NodeArtifactValue.nodeArtifactValue(SliceState.LOAD)));

        assertThat(sliceStore.loaded()).extracting(SliceStore.LoadedSlice::artifact).containsExactly(GONE);
        assertThat(clusterNode.appliedCommands.stream()
                                              .skip(mark)
                                              .filter(command -> command instanceof KVCommand.Put<?, ?> put
                                                                 && put.value() instanceof NodeArtifactValue value
                                                                 && value.state() == SliceState.FAILED)
                                              .toList()).as("the re-placed slice is never written FAILED").isEmpty();
    }

    // --- Arm F (D5): incremental accept of a remove for a LOADED slice, no burst ---

    private void armFRun() {
        assign(GONE, SliceState.LOAD);
        resync();
        feed(new KVCommand.Remove<AetherKey>(NodeArtifactKey.nodeArtifactKey(SELF, GONE)));
    }

    @Test
    void armF_tripwire_incrementalRemoveOfLoadedSlice_staysLoadedToday() {
        armFRun();

        assertThat(sliceStore.loaded()).as("gap 1 off the burst path is still open (a live remove of a LOADED slice leaves it loaded); when the "
                                           + "root fix lands, enable armF_incrementalRemove_isUnloaded and delete this tripwire")
                                       .extracting(SliceStore.LoadedSlice::artifact)
                                       .containsExactly(GONE);
    }

    @Test
    @Disabled("C3 root fix (design 4 rule 1, D5): enable when shouldForceCleanup becomes 'loaded locally'")
    void armF_incrementalRemove_isUnloaded() {
        var mark = writesBefore();

        armFRun();

        assertUnloadedAndQuiet(GONE, mark);
    }

    // --- Doubles ---

    static final class TestSliceStore implements SliceStore {
        final List<Artifact> unloadCalls = new CopyOnWriteArrayList<>();
        final List<Artifact> deactivateCalls = new CopyOnWriteArrayList<>();
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        record Loaded(Artifact artifact, Slice slice) implements LoadedSlice {}

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            var loaded = new Loaded(artifact, new IdleSlice());

            loadedSlices.add(loaded);
            return Promise.success(loaded);
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Promise.success(new Loaded(artifact, null));
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            deactivateCalls.add(artifact);
            return Promise.success(new Loaded(artifact, null));
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            unloadCalls.add(artifact);
            loadedSlices.removeIf(slice -> slice.artifact().equals(artifact));
            return Promise.unitPromise();
        }

        @Override
        public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        @Override
        public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.none();
        }

        void markAsLoadedWithSlice(Artifact artifact) {
            loadedSlices.add(new Loaded(artifact, new IdleSlice()));
        }
    }

    static final class IdleSlice implements Slice {
        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public List<org.pragmatica.aether.slice.SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();

        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            appliedCommands.addAll(commands);
            return Promise.success((List<R>) commands.stream().map(_ -> Unit.unit()).toList());
        }
    }
}
