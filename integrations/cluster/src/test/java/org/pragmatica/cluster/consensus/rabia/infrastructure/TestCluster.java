package org.pragmatica.consensus.rabia.infrastructure;

import org.pragmatica.cluster.metrics.MetricsCodecs;
import org.pragmatica.cluster.net.local.LocalNetwork;
import org.pragmatica.cluster.net.local.LocalNetwork.FaultInjector;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KvstoreCodecs;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.rabia.RabiaCodecs;
import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TcpCodecs;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;
import static org.pragmatica.consensus.NodeId.randomNodeId;
import static org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous;
import static org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// Holds a small Rabia cluster wired over a single LocalNetwork.
public class TestCluster {
    @Codec
    public record StringKey(String key) implements StructuredKey {
        public static StringKey key(String key) {
            return new StringKey(key);
        }
    }

    private final LocalNetwork network;
    private final List<NodeId> ids = new ArrayList<>();
    private final Map<NodeId, RabiaEngine<KVCommand<StringKey>>> engines = new LinkedHashMap<>();
    private final Map<NodeId, KVStore<StringKey, String>> stores = new LinkedHashMap<>();
    private final Map<NodeId, MessageRouter.MutableRouter> routers = new LinkedHashMap<>();
    private final Map<NodeId, Consumer<RabiaProtocolMessage>> handlers = new LinkedHashMap<>();
    /// Per node, every Propose / VoteRound1 / VoteRound2 / Decision the network delivered to it, in delivery order:
    /// the raw material of a stale-phase redelivery (see [#deliver]) and the record of repair replies.
    private final Map<NodeId, ConcurrentLinkedQueue<RabiaProtocolMessage>> delivered = new ConcurrentHashMap<>();
    /// Per node, how many times each key was PUT into its store: a non-idempotent view of apply (a batch applied
    /// twice counts a key twice, where the stored value alone cannot tell).
    private final Map<NodeId, Map<StringKey, AtomicInteger>> putCounts = new ConcurrentHashMap<>();
    private final SliceCodec codec = buildTestCodec();
    private final int size;

    private static SliceCodec buildTestCodec() {
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

    public TestCluster(int size) {
        this.size = size;
        var topologyManager = new TestTopologyManager(size, NodeInfo.nodeInfo(randomNodeId(), nodeAddress("localhost", 8090).unwrap()));
        network = new LocalNetwork(topologyManager, routers, new FaultInjector());

        // create nodes
        for (int i = 1; i <= size; i++) {
            var id = NodeId.nodeId("node-" + i).unwrap();
            ids.add(id);
            addNewNode(id);
        }
        network.start();
    }

    public Map<NodeId, RabiaEngine<KVCommand<StringKey>>> engines() {
        return engines;
    }

    public Map<NodeId, KVStore<StringKey, String>> stores() {
        return stores;
    }

    public Map<NodeId, MessageRouter.MutableRouter> routers() {
        return routers;
    }

    public NodeId getFirst() {
        return ids.getFirst();
    }

    public List<NodeId> ids() {
        return ids;
    }

    public LocalNetwork network() {
        return network;
    }

    public void disconnect(NodeId id) {
        network.disconnectNode(id);
    }

    /// Puts a disconnected node back on the network with its existing engine and store.
    public void reconnect(NodeId id) {
        network.addNode(id, handlers.get(id));
    }

    /// Everything the network delivered to `id` so far that carries a phase (proposals, both vote rounds, decisions).
    public List<RabiaProtocolMessage> deliveredTo(NodeId id) {
        return List.copyOf(delivered.get(id));
    }

    /// Hands `message` straight to `id`'s engine, bypassing the network and its fault injection.
    public void deliver(NodeId id, RabiaProtocolMessage message) {
        handlers.get(id).accept(message);
    }

    /// How many ValuePut notifications `id`'s store has emitted for `key`.
    public int putCount(NodeId id, StringKey key) {
        return Option.option(putCounts.get(id).get(key)).map(AtomicInteger::get).or(0);
    }

    public boolean hasPendingNotifications(NodeId id) {
        return stores.get(id).hasPendingNotifications();
    }

    public void addNewNode(NodeId id) {
        var router = MessageRouter.mutable();
        var store = new KVStore<StringKey, String>(router, codec, codec);
        var topologyManager = new TestTopologyManager(size, NodeInfo.nodeInfo(id, nodeAddress("localhost", 8090).unwrap()));
        var engine = new RabiaEngine<>(topologyManager, network, store, ProtocolConfig.testConfig());

        router.addRoute(KVStoreLocalIO.Request.Find.class, store::find);
        router.addRoute(ClusterStateNotification.class, engine::clusterState);

        var stateChangePrinter = new StateChangePrinter(id);
        router.addRoute(KVStoreNotification.ValuePut.class, stateChangePrinter::accept);
        putCounts.put(id, new ConcurrentHashMap<>());
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<?, ?> put) -> countPut(id, put));

        // Register router BEFORE adding node to network to ensure ClusterStateNotification
        // is received by all nodes when quorum is reached
        stores.put(id, store);
        engines.put(id, engine);
        routers.put(id, router);

        var handler = recording(id, createHandler(engine));

        handlers.put(id, handler);
        delivered.put(id, new ConcurrentLinkedQueue<>());
        network.addNode(id, handler);
    }

    private void countPut(NodeId id, KVStoreNotification.ValuePut<?, ?> put) {
        putCounts.get(id).computeIfAbsent((StringKey) put.cause().key(), _ -> new AtomicInteger()).incrementAndGet();
    }

    private Consumer<RabiaProtocolMessage> recording(NodeId id, Consumer<RabiaProtocolMessage> next) {
        return message -> recordAndDeliver(id, message, next);
    }

    private void recordAndDeliver(NodeId id, RabiaProtocolMessage message, Consumer<RabiaProtocolMessage> next) {
        if (message instanceof Synchronous.Propose<?> || message instanceof Synchronous.VoteRound1 || message instanceof Synchronous.VoteRound2 || message instanceof Synchronous.Decision<?>) {
            delivered.get(id).add(message);
        }
        next.accept(message);
    }

    @SuppressWarnings("unchecked")
    private static <C extends Command> Consumer<RabiaProtocolMessage> createHandler(RabiaEngine<C> engine) {
        return message -> {
            switch (message) {
                case Synchronous.Propose<?> propose -> engine.processPropose((Synchronous.Propose<C>) propose);
                case Synchronous.VoteRound1 voteRnd1 -> engine.processVoteRound1(voteRnd1);
                case Synchronous.VoteRound2 voteRnd2 -> engine.processVoteRound2(voteRnd2);
                case Synchronous.Decision<?> decision -> engine.processDecision((Synchronous.Decision<C>) decision);
                case Synchronous.SyncResponse<?> syncResponse ->
                        engine.processSyncResponse((Synchronous.SyncResponse<C>) syncResponse);
                case Asynchronous.SyncRequest syncRequest -> engine.handleSyncRequest(syncRequest);
                case Asynchronous.SyncRejected rejected -> engine.handleSyncRejected(rejected);
                case Asynchronous.RoundRequest request -> engine.handleRoundRequest(request);
                case Asynchronous.ReconfigurationRequest request -> engine.reconfigurationRequest(request);
                case Asynchronous.GenesisAnnouncement announcement -> engine.genesisAnnouncement(announcement);
                case Asynchronous.NewBatch<?> newBatch -> engine.handleNewBatch(newBatch);
                case Asynchronous.BatchCommitted committed -> engine.handleBatchCommitted(committed);
            }
        };
    }

    public void awaitNode(NodeId nodeId) {
        engines.get(nodeId)
               .start()
               .await(timeSpan(10).seconds())
               .onFailure(cause -> fail("Failed to start " + nodeId.id() + " " + cause));
    }

    public void awaitStart() {
        var promises = engines.values()
                              .stream()
                              .map(RabiaEngine::start)
                              .toList();

        Promise.allOf(promises)
               .await(timeSpan(60).seconds())
               .onFailureRun(() -> fail("Failed to start all nodes within 60 seconds"));
    }

    public void submitAndWait(NodeId nodeId, KVCommand<StringKey> command) {
        engines.get(nodeId)
               .apply(List.of(command))
               .await(timeSpan(10).seconds())
               .onFailure(cause -> fail("Failed to apply command: (a, 1): " + cause));
    }

    /// True when every node CURRENTLY CONNECTED to the network holds `v1` under `k1`; a disconnected node is not
    /// read, so a stale replica is invisible to this check until it is reconnected.
    public boolean allNodesHaveValue(StringKey k1, String v1) {
        return network.connectedNodes().stream()
                      .allMatch(id -> v1.equals(stores.get(id).snapshot().get(k1)));
    }
}
