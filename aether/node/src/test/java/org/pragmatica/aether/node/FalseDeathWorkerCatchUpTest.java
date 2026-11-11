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
import org.pragmatica.lang.utils.Causes;
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
/// Arms B and F (gap 1, on and off the burst path) were RED at the probe head 1d9732e247 and are GREEN since the root fix (design 4 rule 1).
/// Arm C (gap 2) was RED at 02c10270c0 (its tripwire held: the slice stayed loaded) and is GREEN since the `CatchUpSwept` sweep and the
/// reactivation filter (design 4 rules 2-3). The worker store tells the manager each delivered replay burst, as `AetherNode` wires it.
/// Arm G pins the post-burst raise itself (a loaded slice no remove ever reaches) and D7 (Dormant ignores the raise; `onEntry` sweeps).
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
        worker.onReplayEnd(manager::onReplayDelivered);
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

    /// The mark is taken after the slice's own load writes (LOADING, LOADED) and before the removal, as in arm A: D2 is about what the
    /// worker writes once the leader removed the slice.
    @Test
    void armB_loadedSlice_isUnloaded() {
        assign(GONE, SliceState.LOAD);
        resync();
        var mark = writesBefore();

        leaderRemoves(GONE);
        resync();

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

    // --- Arm E: a put for the same key arrives while the removal's cleanup is in flight (the LOAD-waits-for-cleanup ordering pin) ---

    /// The cleanup is held at its deactivation step, so the LOAD lands while the slice is still loaded (design 4 rule 4 iii). Without the wait the
    /// LOAD finds the slice "already loaded", the released cleanup then unloads it, and the assigned slice is left not running.
    @Test
    void armE_putDuringTheCleanup_endsLoadedAndNeverFailed() {
        sliceActive(GONE);
        resync();
        var mark = writesBefore();
        var deactivation = sliceStore.holdDeactivation();

        leaderRemoves(GONE);
        resync();
        feed(new KVCommand.Put<AetherKey, AetherValue>(NodeArtifactKey.nodeArtifactKey(SELF, GONE), NodeArtifactValue.nodeArtifactValue(SliceState.LOAD)));
        deactivation.succeed(new TestSliceStore.Loaded(GONE, null));

        assertThat(sliceStore.unloadCalls).as("one cleanup: the post-burst sweep joined the in-flight one").containsExactly(GONE);
        assertThat(sliceStore.loaded()).extracting(SliceStore.LoadedSlice::artifact).containsExactly(GONE);
        assertThat(failedPutsSince(mark)).as("the re-placed slice is never written FAILED").isEmpty();

        feed(new KVCommand.Remove<AetherKey>(NodeArtifactKey.nodeArtifactKey(SELF, GONE)));
        assertThat(sliceStore.loaded()).as("a settled cleanup leaves nothing in flight: a later removal cleans up again").isEmpty();
    }

    /// m12: a claimed-ACTIVE redeploy (KV convergence) of a slice whose cleanup is still in flight waits for the cleanup; it must end loaded, never
    /// unloaded by the late cleanup.
    @Test
    void armE_claimedActiveRedeployDuringTheCleanup_endsLoaded() {
        sliceActive(GONE);
        resync();
        var unload = sliceStore.holdUnload();

        leaderRemoves(GONE);
        resync();
        feed(new KVCommand.Put<AetherKey, AetherValue>(NodeArtifactKey.nodeArtifactKey(SELF, GONE), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE)));
        unload.succeed(Unit.unit());

        assertThat(sliceStore.unloadCalls).as("one cleanup, joined by the redeploy").containsExactly(GONE);
        assertThat(sliceStore.loaded()).as("the redeploy's load ran after the cleanup settled").extracting(SliceStore.LoadedSlice::artifact).containsExactly(GONE);
    }

    /// FER at the wait (`NodeDeploymentContext.cleanupSettled`): a cleanup that fails is logged at its site and the LOAD still runs.
    @Test
    void armE_failedCleanup_loadStillRunsAndNeverFailed() {
        sliceActive(GONE);
        resync();
        var mark = writesBefore();
        var deactivation = sliceStore.holdDeactivation();

        leaderRemoves(GONE);
        resync();
        feed(new KVCommand.Put<AetherKey, AetherValue>(NodeArtifactKey.nodeArtifactKey(SELF, GONE), NodeArtifactValue.nodeArtifactValue(SliceState.LOAD)));
        deactivation.fail(Causes.cause("deactivation refused"));

        assertThat(sliceStore.loaded()).extracting(SliceStore.LoadedSlice::artifact).containsExactly(GONE);
        assertThat(failedPutsSince(mark)).as("a failed cleanup does not fail the LOAD that waited for it").isEmpty();
    }

    private List<KVCommand<AetherKey>> failedPutsSince(int mark) {
        return clusterNode.appliedCommands.stream()
                                          .skip(mark)
                                          .filter(command -> command instanceof KVCommand.Put<?, ?> put
                                                             && put.value() instanceof NodeArtifactValue value
                                                             && value.state() == SliceState.FAILED)
                                          .toList();
    }

    // --- Arm F (D5): incremental accept of a remove for a LOADED slice, no burst ---

    /// The mark sits after the slice's own load writes and before the live remove, as in arm B.
    @Test
    void armF_incrementalRemove_isUnloaded() {
        assign(GONE, SliceState.LOAD);
        resync();
        var mark = writesBefore();

        feed(new KVCommand.Remove<AetherKey>(NodeArtifactKey.nodeArtifactKey(SELF, GONE)));

        assertUnloadedAndQuiet(GONE, mark);
    }

    // --- Arm G: a loaded slice with no entry at all, manager Active (the post-burst CatchUpSwept raise) ---

    /// No remove is ever delivered for the slice, so only the sweep raised after the replay burst (design 4 rule 2) can unload it.
    @Test
    void armG_loadedSliceWithoutEntry_isSweptAfterTheBurst() {
        resync();
        sliceStore.markAsLoadedWithSlice(GONE);
        var mark = writesBefore();

        resync();

        assertUnloadedAndQuiet(GONE, mark);
    }

    /// D7: Dormant ignores the post-burst raise; the slice stays loaded until quorum returns, and `Active.onEntry` sweeps it then.
    @Test
    void armG_dormantIgnoresTheRaise_onEntrySweeps() {
        resync();
        sliceStore.markAsLoadedWithSlice(GONE);
        manager.onQuorumStateChange(ClusterStateNotification.passive());
        var mark = writesBefore();

        resync();
        assertThat(sliceStore.loaded()).as("Dormant ignores CatchUpSwept").extracting(SliceStore.LoadedSlice::artifact).containsExactly(GONE);

        manager.onQuorumStateChange(ClusterStateNotification.active());
        assertUnloadedAndQuiet(GONE, mark);
    }

    // --- Doubles ---

    static final class TestSliceStore implements SliceStore {
        final List<Artifact> unloadCalls = new CopyOnWriteArrayList<>();
        final List<Artifact> deactivateCalls = new CopyOnWriteArrayList<>();
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        record Loaded(Artifact artifact, Slice slice) implements LoadedSlice {}

        private Option<Promise<LoadedSlice>> deactivationGate = Option.none();
        private Option<Promise<Unit>> unloadGate = Option.none();

        /// As the real store: a load of an artifact already loaded returns the loaded slice and loads nothing.
        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return Option.from(loadedSlices.stream()
                                           .filter(slice -> slice.artifact().equals(artifact))
                                           .findFirst())
                         .map(Promise::success)
                         .or(() -> freshLoad(artifact));
        }

        private Promise<LoadedSlice> freshLoad(Artifact artifact) {
            var loaded = new Loaded(artifact, new IdleSlice());

            loadedSlices.add(loaded);
            return Promise.success(loaded);
        }

        /// The next deactivations wait for the returned promise; the test resolves it to release them.
        Promise<LoadedSlice> holdDeactivation() {
            var gate = Promise.<LoadedSlice>promise();

            deactivationGate = Option.some(gate);
            return gate;
        }

        private Unit finalizeUnload(Artifact artifact) {
            loadedSlices.removeIf(slice -> slice.artifact().equals(artifact));
            return Unit.unit();
        }

        /// The next unload takes the slice out of `loaded()` at once, and finalizes (drops whatever of that artifact is loaded then) only when the
        /// returned promise is resolved: a cleanup still in flight after the slice already reads as not loaded.
        Promise<Unit> holdUnload() {
            var gate = Promise.<Unit>promise();

            unloadGate = Option.some(gate);
            return gate;
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Promise.success(new Loaded(artifact, null));
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            deactivateCalls.add(artifact);
            return deactivationGate.or(() -> Promise.success(new Loaded(artifact, null)));
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            unloadCalls.add(artifact);
            loadedSlices.removeIf(slice -> slice.artifact().equals(artifact));
            return unloadGate.or(Promise::unitPromise)
                             .map(_ -> finalizeUnload(artifact));
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
