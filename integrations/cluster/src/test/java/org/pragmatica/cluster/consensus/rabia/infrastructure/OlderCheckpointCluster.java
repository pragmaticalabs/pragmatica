// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia.infrastructure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

import org.pragmatica.cluster.metrics.MetricsCodecs;
import org.pragmatica.cluster.net.local.LocalNetwork;
import org.pragmatica.cluster.net.local.LocalNetwork.FaultInjector;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KvstoreCodecs;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.rabia.RabiaCodecs;
import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TcpCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// A real three-node Rabia cluster (real engines, real `KVStore`, in-process `LocalNetwork`) that can be stopped and
/// restarted from an OLDER checkpoint, which is the crash scenario the worker-replica-feed design's anchor exists for.
///
/// `RabiaEngine` persists its state only on pause, stop, restore and reconfigure (`saveState`), never on commit, so a node that
/// crashes after a pause restarts from the pause-time checkpoint and the slots after it are gone from its history. Restarting
/// every node from such a checkpoint and committing different values reuses slots with different content under the SAME cluster
/// (there is no incarnation key in this harness, as in a crash restart where none is written).
///
/// Usage: start a generation, commit, [pauseAndCheckpoint] at the chosen moment, resume, commit more, [close] (the crash), then
/// start a second generation seeded with the checkpoints and commit different values. Every node's store records what it applied as
/// `(slot, batchId, commands)`, so the histories of the two generations can be compared slot by slot.
public final class OlderCheckpointCluster {
    public static final int SIZE = 3;
    private static final long BOUND_MILLIS = 15_000;

    /// One applied batch as a core recorded it: the slot, the content-derived batch id, the commands.
    public record Applied(long slot, String batchId, List<KVCommand<StringKey>> commands) {}

    /// A `KVStore` that records every committed batch. Recording at `processCommitted` is the position the real ring appends at.
    public static final class RecordingStore extends KVStore<StringKey, String> {
        private final List<Applied> applied = new CopyOnWriteArrayList<>();

        /// One cluster identity across both generations: a constant tag, so a restart is a restart under the SAME incarnation (the scenario the
        /// replica anchor exists for). The replica ring records and serves under it.
        private static final org.pragmatica.cluster.state.kvstore.IncarnationSource ONE_IDENTITY = new org.pragmatica.cluster.state.kvstore.IncarnationSource() {
            @Override
            public org.pragmatica.cluster.state.kvstore.IncarnationTag read(java.util.function.Function<StructuredKey, Object> lookup) {
                return org.pragmatica.cluster.state.kvstore.IncarnationTag.incarnationTag(1, "one-identity");
            }

            @Override
            public boolean isIncarnationKey(StructuredKey key) {
                return false;
            }
        };

        RecordingStore(MessageRouter router, SliceCodec codec) {
            super(router, codec, codec, org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter.includeAll(), ONE_IDENTITY, org.pragmatica.cluster.state.kvstore.CommitRing.DEFAULT_BUDGET_BYTES);
        }

        @Override
        public <R> List<R> processCommitted(Batch<KVCommand<StringKey>> batch, long nextSlot) {
            var results = super.<R> processCommitted(batch, nextSlot);

            // Recorded after the store (revision, ring) has the batch: the cluster's commit() waits on this list, and callers then read the ring.
            applied.add(new Applied(nextSlot - 1, batch.id().toString(), batch.commands()));

            return results;
        }

        public List<Applied> applied() {
            return List.copyOf(applied);
        }
    }

    /// One node of a generation.
    public record Node(NodeId id,
                       RabiaEngine<KVCommand<StringKey>> engine,
                       RecordingStore store,
                       MessageRouter.MutableRouter router,
                       SeededPersistence persistence,
                       List<String> bootFutureHistoryEvents) {}

    /// Persistence that starts from a given saved state (the older checkpoint) and keeps whatever the engine saves afterwards.
    public static final class SeededPersistence implements RabiaPersistence<KVCommand<StringKey>> {
        private final AtomicReference<Option<SavedState<KVCommand<StringKey>>>> state;

        SeededPersistence(Option<SavedState<KVCommand<StringKey>>> seed) {
            state = new AtomicReference<>(seed);
        }

        @Override
        public Result<Unit> save(StateMachine<KVCommand<StringKey>> machine,
                                 Phase lastCommittedPhase,
                                 Collection<Batch<KVCommand<StringKey>>> pending) {
            return machine.makeSnapshot()
                          .map(snapshot -> SavedState.savedState(snapshot, lastCommittedPhase, List.copyOf(pending)))
                          .onSuccess(saved -> state.set(Option.some(saved)))
                          .mapToUnit();
        }

        @Override
        public Result<Unit> save(StateMachine<KVCommand<StringKey>> machine,
                                 Phase nextSlot,
                                 Collection<Batch<KVCommand<StringKey>>> pending,
                                 VoterConfiguration configuration) {
            return machine.makeSnapshot()
                          .map(snapshot -> new SavedState<>(snapshot, nextSlot, List.copyOf(pending), Option.some(configuration)))
                          .onSuccess(saved -> state.set(Option.some(saved)))
                          .mapToUnit();
        }

        @Override
        public Option<SavedState<KVCommand<StringKey>>> load() {
            return state.get();
        }
    }

    private final LocalNetwork network;
    private final Map<NodeId, Node> nodes = new LinkedHashMap<>();
    private final Map<NodeId, MessageRouter.MutableRouter> routers = new LinkedHashMap<>();

    private OlderCheckpointCluster(Map<NodeId, Option<SavedState<KVCommand<StringKey>>>> seeds) {
        network = new LocalNetwork(topology(NodeInfo.nodeInfo(NodeId.randomNodeId(), nodeAddress("localhost", 8090).unwrap())),
                                   routers,
                                   new FaultInjector());
        for (var i = 1; i <= SIZE; i++) {
            var id = NodeId.nodeId("node-" + i).unwrap();

            addNode(id, seeds.getOrDefault(id, Option.none()));
        }
        network.start();
    }

    /// A first generation: empty nodes.
    public static OlderCheckpointCluster fresh() {
        return new OlderCheckpointCluster(Map.of());
    }

    /// A restarted generation: every node starts from the given checkpoint (its own persisted state at the crash).
    public static OlderCheckpointCluster restartedFrom(Map<NodeId, SavedState<KVCommand<StringKey>>> checkpoints) {
        var seeds = new LinkedHashMap<NodeId, Option<SavedState<KVCommand<StringKey>>>>();

        checkpoints.forEach((id, checkpoint) -> seeds.put(id, Option.some(checkpoint)));

        return new OlderCheckpointCluster(seeds);
    }

    /// All three nodes are core members. The stock `TestTopologyManager` reports only itself as a core node, which makes every engine a
    /// one-voter electorate that decides alone; a cluster that is to agree needs the electorate to be the three of them.
    private static TopologyManager topology(NodeInfo self) {
        var ids = java.util.stream.IntStream.rangeClosed(1, SIZE).mapToObj(index -> NodeId.nodeId("node-" + index).unwrap()).toList();

        return new TopologyManager() {
            @Override
            public NodeInfo self() {
                return self;
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, nodeAddress("localhost", 8090).unwrap()));
            }

            @Override
            public int clusterSize() {
                return SIZE;
            }

            @Override
            public Option<NodeId> reverseLookup(java.net.SocketAddress socketAddress) {
                return Option.none();
            }

            @Override
            public org.pragmatica.lang.Promise<Unit> start() {
                return org.pragmatica.lang.Promise.unitPromise();
            }

            @Override
            public org.pragmatica.lang.Promise<Unit> stop() {
                return org.pragmatica.lang.Promise.unitPromise();
            }

            @Override
            public org.pragmatica.lang.io.TimeSpan pingInterval() {
                return timeSpan(1).seconds();
            }

            @Override
            public org.pragmatica.lang.io.TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.some(NodeState.discovered(self, java.time.Instant.now()));
            }

            @Override
            public List<NodeId> topology() {
                return ids;
            }
        };
    }

    private void addNode(NodeId id, Option<SavedState<KVCommand<StringKey>>> seed) {
        var router = MessageRouter.mutable();
        var codec = codec();
        var store = new RecordingStore(router, codec);
        var persistence = new SeededPersistence(seed);
        var engine = new RabiaEngine<>(topology(NodeInfo.nodeInfo(id, nodeAddress("localhost", 8090).unwrap())),
                                       network,
                                       store,
                                       ProtocolConfig.testConfig(),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence);

        // The one detector the design names (section 8): the boot future-history check reports through this listener.
        var bootFutureHistoryEvents = new CopyOnWriteArrayList<String>();

        engine.onBootFutureHistory((persisted, cluster) -> bootFutureHistoryEvents.add("persisted " + persisted + " > cluster " + cluster));
        router.addRoute(ClusterStateNotification.class, engine::clusterState);
        routers.put(id, router);
        nodes.put(id, new Node(id, engine, store, router, persistence, bootFutureHistoryEvents));
        network.addNode(id, handler(engine));
    }

    @SuppressWarnings("unchecked")
    private static Consumer<RabiaProtocolMessage> handler(RabiaEngine<KVCommand<StringKey>> engine) {
        return message -> {
            switch (message) {
                case RabiaProtocolMessage.Synchronous.Propose<?> propose -> engine.processPropose((RabiaProtocolMessage.Synchronous.Propose<KVCommand<StringKey>>) propose);
                case RabiaProtocolMessage.Synchronous.VoteRound1 vote -> engine.processVoteRound1(vote);
                case RabiaProtocolMessage.Synchronous.VoteRound2 vote -> engine.processVoteRound2(vote);
                case RabiaProtocolMessage.Synchronous.Decision<?> decision -> engine.processDecision((RabiaProtocolMessage.Synchronous.Decision<KVCommand<StringKey>>) decision);
                case RabiaProtocolMessage.Synchronous.SyncResponse<?> response -> engine.processSyncResponse((RabiaProtocolMessage.Synchronous.SyncResponse<KVCommand<StringKey>>) response);
                case RabiaProtocolMessage.Asynchronous.SyncRequest request -> engine.handleSyncRequest(request);
                case RabiaProtocolMessage.Asynchronous.SyncRejected rejected -> engine.handleSyncRejected(rejected);
                case RabiaProtocolMessage.Asynchronous.RoundRequest request -> engine.handleRoundRequest(request);
                case RabiaProtocolMessage.Asynchronous.ReconfigurationRequest request -> engine.reconfigurationRequest(request);
                case RabiaProtocolMessage.Asynchronous.GenesisAnnouncement announcement -> engine.genesisAnnouncement(announcement);
                case RabiaProtocolMessage.Asynchronous.NewBatch<?> newBatch -> engine.handleNewBatch((RabiaProtocolMessage.Asynchronous.NewBatch<KVCommand<StringKey>>) newBatch);
            }
        };
    }

    private static SliceCodec codec() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();

        codecs.addAll(ConsensusCodecs.CODECS);
        codecs.addAll(RabiaCodecs.CODECS);
        codecs.addAll(NetCodecs.CODECS);
        codecs.addAll(TcpCodecs.CODECS);
        codecs.addAll(KvstoreCodecs.CODECS);
        codecs.addAll(MetricsCodecs.CODECS);
        codecs.addAll(InfrastructureCodecs.CODECS);

        return SliceCodec.sliceCodec(frameworkCodecs(), codecs);
    }

    public Node node(int index) {
        return nodes.get(NodeId.nodeId("node-" + index).unwrap());
    }

    public Collection<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    /// Starts every engine and waits until all are active.
    public OlderCheckpointCluster awaitActive() {
        nodes.values().forEach(node -> node.engine().start());
        awaitCondition("every node is active", () -> nodes.values().stream().allMatch(node -> node.engine().isActive()));

        return this;
    }

    /// Commits one `Put` through `node-1` and waits until every node has applied it.
    public OlderCheckpointCluster commit(String key, String value) {
        var before = node(1).store().applied().size();

        node(1).engine()
               .apply(List.of(new KVCommand.Put<>(new StringKey(key), value)))
               .await(timeSpan(10).seconds())
               .onFailure(cause -> {
                   throw new AssertionError("commit failed: " + cause);
               });
        awaitCondition(() -> "every node applied the batch; applied counts " + appliedCounts(),
                       () -> nodes.values().stream().allMatch(node -> node.store().applied().size() > before));

        return this;
    }

    private String appliedCounts() {
        return nodes.values().stream().map(node -> node.id().id() + "=" + node.store().applied().size() + (node.engine().isActive() ? " active" : " inactive")).toList().toString();
    }

    private boolean checkpointsDescribeTheFrontier() {
        return nodes.values().stream().allMatch(node -> node.persistence().load().map(saved -> saved.lastCommittedPhase().value() == frontier(node)).or(false));
    }

    private static long frontier(Node node) {
        var applied = node.store().applied();

        return applied.isEmpty() ? 0 : applied.getLast().slot() + 1;
    }

    private String frontiers() {
        return nodes.values().stream().map(node -> node.id().id() + " applied-frontier=" + frontier(node) + " saved=" + node.persistence().load().map(saved -> saved.lastCommittedPhase().value()).or(-1L)).toList().toString();
    }

    /// Pauses every node (quorum loss: each engine saves its state), reads back each node's checkpoint, and resumes.
    /// The checkpoints are the states a crash at this moment would restart from.
    public Map<NodeId, SavedState<KVCommand<StringKey>>> pauseAndCheckpoint() {
        routers.values().forEach(router -> router.route(ClusterStateNotification.passive()));
        awaitCondition("every node paused", () -> nodes.values().stream().allMatch(node -> node.engine().isPaused()));
        // The engine flips to Paused BEFORE it persists, so paused is not "saved": wait for the checkpoint to describe the applied frontier.
        awaitCondition(() -> "every node persisted its pause-time checkpoint; frontiers " + frontiers(), this::checkpointsDescribeTheFrontier);
        var checkpoints = new LinkedHashMap<NodeId, SavedState<KVCommand<StringKey>>>();

        nodes.forEach((id, node) -> checkpoints.put(id, node.persistence().load().unwrap()));
        routers.values().forEach(router -> router.route(ClusterStateNotification.active()));
        awaitCondition("every node resumed", () -> nodes.values().stream().allMatch(node -> node.engine().isActive()));

        return checkpoints;
    }

    /// The crash: the engines stop. What they persist on the way out is irrelevant, the checkpoints were taken earlier.
    public void close() {
        nodes.values().forEach(node -> node.engine().stop().await(timeSpan(10).seconds()));
    }

    public static void awaitCondition(String what, BooleanSupplier condition) {
        awaitCondition(() -> what, condition);
    }

    public static void awaitCondition(java.util.function.Supplier<String> what, BooleanSupplier condition) {
        var deadline = System.nanoTime() + BOUND_MILLIS * 1_000_000L;

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting: " + what.get());
            }
            LockSupport.parkNanos(5_000_000L);
        }
    }
}
