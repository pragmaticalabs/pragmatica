// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metadata;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.*;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1516 end to end: a real `RabiaEngine`, `KVStore` and `WorkerMetadataServer`/`Client`, wired as `AetherNode` wires
/// them (ValuePut/ValueRemove to `server.put/remove`, `onStateRestored` to `server.onStateRestored`, requests gated on
/// `AetherNode.runtimeReady`: active, or observing while not an installed voter). A non-voter observer serves a worker,
/// re-syncs while its participation record is unwritable (the restore is installed silently), loses quorum, applies a
/// Decision while Paused and so reaches `Observing` through `advancePhaseAsObserver`. The restore must have been announced
/// by then: `WorkerMetadataServer.onStateRestored` invalidates its index, so the worker's next poll reads the restored
/// store and not the projection built before the restore.
class WorkerMetadataStaleViewAfterRefusedRestoreTest {
    static final NodeId NODE_1 = nodeId("node-1").unwrap();
    static final NodeId NODE_2 = nodeId("node-2").unwrap();
    static final NodeId NODE_3 = nodeId("node-3").unwrap();
    static final NodeId NODE_4 = nodeId("node-4").unwrap();
    static final NodeId WORKER = new NodeId("worker");
    static final WorkerMetadataMessage.DhtReplication REPLICATION = new WorkerMetadataMessage.DhtReplication(3, 2, 1, 1, 1, -1, 0, 0, true);
    static final WorkerMetadataLimits LIMITS = new WorkerMetadataLimits(64, 8192, 65536, 1048576, 8, 32,
                                                                         TimeSpan.timeSpan(30).seconds(), TimeSpan.timeSpan(0).millis());
    final org.pragmatica.serialization.SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    final AetherKey.ConfigKey configKey = new AetherKey.ConfigKey("test", Option.none());

    static final class Marker implements ParticipationMarker {
        final AtomicBoolean refuse = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        public Participation resolve() { return Participation.NEVER_PARTICIPATED; }
        public Result<Unit> recordParticipation() {
            attempts.incrementAndGet();

            return refuse.get() ? org.pragmatica.lang.utils.Causes.cause("marker unwritable").result() : Result.success(Unit.unit());
        }
    }

    KVStore<AetherKey, AetherValue> source(String value, long revision) {
        var src = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), codec, codec);
        List<KVCommand<AetherKey>> commands = List.of(
            new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(WORKER), AetherValue.ActivationDirectiveValue.worker("ours", "")),
            new KVCommand.Put<>(configKey, new AetherValue.ConfigValue("test", value, revision)));
        src.processCommitted(src.createBatch(commands), revision);
        return src;
    }

    @Test
    void refusedObserver_pausedThenDecision_servesTheRestoredProjection() throws Exception {
        var router = MessageRouter.mutable();
        var core = new KVStore<AetherKey, AetherValue>(router, codec, codec);
        var worker = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), codec, codec);
        var requests = new ArrayDeque<ProtocolMessage>();
        var responses = new ArrayDeque<ProtocolMessage>();
        var errors = new CopyOnWriteArrayList<String>();
        var server = new WorkerMetadataServer(NODE_1, core, codec, (_, m) -> responses.add(m), WORKER::equals, () -> Set.of(NODE_1),
                                              _ -> List.of(), LIMITS, (_, r) -> errors.add(r), () -> 0L, () -> Result.success(REPLICATION));
        var client = new WorkerMetadataClient(WORKER, worker, codec, () -> Set.of(NODE_1), NODE_1::equals, (_, m) -> requests.add(m),
                                              _ -> Result.success(Unit.unit()), _ -> {}, () -> {}, errors::add, LIMITS, _ -> {});
        router.addRoute(KVStoreNotification.ValuePut.class,
                        (KVStoreNotification.ValuePut<AetherKey, AetherValue> p) -> server.put(p.cause().key(), p.cause().value()));
        router.addRoute(KVStoreNotification.ValueRemove.class,
                        (KVStoreNotification.ValueRemove<AetherKey, AetherValue> r) -> server.remove(r.cause().key()));
        var marker = new Marker();
        var network = new Net();
        var engine = new RabiaEngine<KVCommand<AetherKey>>(new Topo(NODE_1, 3), network, core,
                                                          ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(50).millis(), Option.some(marker)),
                                                          ConsensusMetrics.noop(), true, RabiaPersistence.inMemory(), timeSpan(50).millis());
        try {
            engine.onStateRestored(server::onStateRestored);
            engine.clusterState(ClusterStateNotification.active());
            engine.authorizeObservation();
            assertThat(await(() -> network.sawSyncRequest())).isTrue();
            deliver(engine, source("initial", 5), 5);
            assertThat(await(engine::isObserving)).as("phase A: first restore, observing").isTrue();
            // phase A: the worker's first successful poll builds the server's index (server.initialized becomes true)
            assertThat(await(() -> pollOnce(engine, server, client, requests, responses, worker))).as("phase A: the worker read the initial projection").isTrue();
            assertThat(worker.getTyped(configKey, AetherValue.ConfigValue.class).unwrap().value()).isEqualTo("initial");

            // phase B: resync while the participation record is unwritable
            marker.refuse.set(true);
            engine.clusterState(ClusterStateNotification.active());
            assertThat(await(() -> !engine.isObserving() && !engine.isActive() && !engine.isPaused())).as("phase B: back in Syncing").isTrue();
            deliver(engine, source("restored", 10), 10);
            assertThat(await(() -> core.getTyped(configKey, AetherValue.ConfigValue.class).fold(() -> false, v -> "restored".equals(v.value())))).as("store holds the restored state").isTrue();
            assertThat(await(() -> marker.attempts.get() >= 1)).as("activation was attempted and refused").isTrue();
            assertThat(engine.isObserving()).as("refused: not serving").isFalse();
            assertThat(servingGate(engine)).as("the production gate keeps the worker out while refused").isFalse();

            // phase C: quorum loss, a Decision is applied while paused
            engine.clusterState(ClusterStateNotification.passive());
            assertThat(await(engine::isPaused)).isTrue();
            for (long p = 9; p <= 13; p++) {
                engine.processDecision(new Decision<KVCommand<AetherKey>>(NODE_2, Phase.phase(p), StateValue.V0, Batch.<KVCommand<AetherKey>>emptyBatch()));
            }
            assertThat(await(engine::isObserving)).as("PRECONDITION: the Decision moved the observer to Observing").isTrue();
            assertThat(servingGate(engine)).as("PRECONDITION: the production gate is open for a non-voter observer").isTrue();

            // phase D: the worker polls the serving observer
            assertThat(await(() -> {
                pollOnce(engine, server, client, requests, responses, worker);
                return "restored".equals(worker.getTyped(configKey, AetherValue.ConfigValue.class).fold(() -> "", AetherValue.ConfigValue::value));
            })).as("the projection served after reaching Observing is the restored store, not the one built before the restore")
               .isTrue();
        } finally {
            engine.stop().await();
        }
    }

    /// One worker poll: tick, then deliver the request/response exchange; true once the worker holds the config entry.
    boolean pollOnce(RabiaEngine<?> engine, WorkerMetadataServer server, WorkerMetadataClient client, ArrayDeque<ProtocolMessage> requests,
                     ArrayDeque<ProtocolMessage> responses, KVStore<AetherKey, AetherValue> worker) {
        client.tick();
        pump(engine, server, client, requests, responses);

        return worker.getTyped(configKey, AetherValue.ConfigValue.class).isPresent();
    }

    /// `AetherNode.runtimeReady` for a core engine: active, or observing while not an installed voter.
    static boolean servingGate(RabiaEngine<?> engine) {
        return engine.isActive() || (engine.isObserving() && !installedVoters(engine).contains(NODE_1));
    }

    static Set<NodeId> installedVoters(RabiaEngine<?> engine) {
        return engine.voterConfiguration().map(configuration -> Set.copyOf(configuration.members())).or(Set.of());
    }

    void pump(RabiaEngine<?> engine, WorkerMetadataServer server, WorkerMetadataClient client, ArrayDeque<ProtocolMessage> requests, ArrayDeque<ProtocolMessage> responses) {
        for (var step = 0; step < 1000 && (!requests.isEmpty() || !responses.isEmpty()); step++) {
            if (!requests.isEmpty()) {
                var gate = servingGate(engine);
                switch (requests.remove()) {
                    case WorkerMetadataMessage.ManifestRequest r -> { if (gate) server.onManifestRequest(r); }
                    case WorkerMetadataMessage.ChunkRequest r -> { if (gate) server.onChunkRequest(r); }
                    default -> {}
                }
            }
            if (!responses.isEmpty()) {
                switch (responses.remove()) {
                    case WorkerMetadataMessage.Manifest m -> client.onManifest(m);
                    case WorkerMetadataMessage.Chunk c -> client.onChunk(c);
                    default -> {}
                }
            }
        }
    }

    void deliver(RabiaEngine<KVCommand<AetherKey>> engine, KVStore<AetherKey, AetherValue> src, long phase) {
        var bytes = src.makeSnapshot().unwrap();
        for (var sender : List.of(NODE_2, NODE_3)) {
            engine.processSyncResponse(new SyncResponse<>(sender, SavedState.savedState(bytes, Phase.phase(phase), List.of()), ResponderState.LIVE));
        }
    }

    static boolean await(BooleanSupplier c) {
        var d = System.nanoTime() + MILLISECONDS.toNanos(5000);
        while (System.nanoTime() < d) { if (c.getAsBoolean()) return true; Thread.onSpinWait(); }
        return c.getAsBoolean();
    }

    static final class Net implements ClusterNetwork {
        final List<ProtocolMessage> messages = new CopyOnWriteArrayList<>();
        boolean sawSyncRequest() { return messages.stream().anyMatch(RabiaProtocolMessage.Asynchronous.SyncRequest.class::isInstance); }
        public <M extends ProtocolMessage> Unit broadcast(M m) { messages.add(m); return Unit.unit(); }
        public void connect(NetworkServiceMessage.ConnectNode c) {}
        public void disconnect(NetworkServiceMessage.DisconnectNode d) {}
        public void listNodes(NetworkServiceMessage.ListConnectedNodes l) {}
        public void handleSend(NetworkServiceMessage.Send s) {}
        public void handleBroadcast(NetworkServiceMessage.Broadcast b) {}
        public <M extends ProtocolMessage> Unit send(NodeId n, M m) { messages.add(m); return Unit.unit(); }
        public Promise<Unit> start() { return Promise.success(Unit.unit()); }
        public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
        public int connectedNodeCount() { return 0; }
        public Set<NodeId> connectedPeers() { return Set.of(); }
        public Option<Server> server() { return Option.none(); }
    }

    static final class Topo implements TopologyManager {
        final NodeInfo self; final int size;
        Topo(NodeId id, int size) { self = NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 5000).unwrap()); this.size = size; }
        public boolean isConsensusMember(NodeId id) { return topology().contains(id); }
        public NodeInfo self() { return self; }
        public Option<NodeInfo> get(NodeId id) { return Option.option(NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 5000).unwrap())); }
        public int clusterSize() { return size; }
        public Option<NodeId> reverseLookup(SocketAddress a) { return Option.empty(); }
        public Promise<Unit> start() { return Promise.success(Unit.unit()); }
        public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
        public TimeSpan pingInterval() { return timeSpan(1).seconds(); }
        public TimeSpan helloTimeout() { return timeSpan(5).seconds(); }
        public Option<NodeState> getState(NodeId id) { return Option.empty(); }
        public List<NodeId> topology() { return List.of(NODE_2, NODE_3, NODE_4); }
    }
}
