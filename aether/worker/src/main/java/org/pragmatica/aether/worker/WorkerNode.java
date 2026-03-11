package org.pragmatica.aether.worker;

import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.LocalDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.aether.worker.bootstrap.SnapshotRequest;
import org.pragmatica.aether.worker.bootstrap.SnapshotResponse;
import org.pragmatica.aether.worker.bootstrap.WorkerBootstrap;
import org.pragmatica.aether.worker.governor.DecisionRelay;
import org.pragmatica.aether.worker.governor.GovernorElection;
import org.pragmatica.aether.worker.governor.GovernorState;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimProtocol;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.messaging.MessageRouter.Entry.route;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// Worker node: a passive cluster member that executes slices without
/// participating in Rabia consensus.
///
/// Connects to core nodes via PassiveNode to receive committed Decisions.
/// Uses SWIM for intra-worker failure detection and governor election.
/// The elected governor relays Decisions to group followers and forwards
/// mutations to the core cluster.
public interface WorkerNode {
    /// This node's identity.
    NodeId self();

    /// Start the worker node: connect to core cluster, start SWIM, elect governor.
    Promise<Unit> start();

    /// Stop the worker node gracefully.
    Promise<Unit> stop();

    /// Current governor election state.
    GovernorState governorState();

    /// Whether this node is the elected governor.
    boolean isGovernor();

    /// DHT-backed replicated maps for endpoint, slice-node, and HTTP route data.
    AetherMaps aetherMaps();

    /// Create a worker node from configuration.
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "JBCT-STY-05", "unchecked", "rawtypes"})
    static Result<WorkerNode> workerNode(WorkerConfig config) {
        var nodeId = NodeId.randomNodeId();
        var codecPair = createCodecs();
        return buildNode(config, nodeId, codecPair.serializer(), codecPair.deserializer());
    }

    /// Create a worker node with an explicit NodeId.
    @SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "JBCT-STY-05", "unchecked", "rawtypes"})
    static Result<WorkerNode> workerNode(WorkerConfig config, NodeId nodeId) {
        var codecPair = createCodecs();
        return buildNode(config, nodeId, codecPair.serializer(), codecPair.deserializer());
    }

    private static CodecPair createCodecs() {
        var codecs = WorkerCodecs.workerCodecs(FrameworkCodecs.frameworkCodecs());
        return CodecPair.codecPair(codecs, codecs);
    }

    private static Result<WorkerNode> buildNode(WorkerConfig config,
                                                NodeId nodeId,
                                                Serializer serializer,
                                                Deserializer deserializer) {
        var coreNodes = parseCoreNodes(config, nodeId);
        var topologyConfig = createTopologyConfig(nodeId, coreNodes);
        var workerNetwork = WorkerNetwork.workerNetwork(serializer, deserializer);
        return PassiveNode.<AetherKey, AetherValue> passiveNode(topologyConfig, serializer, deserializer)
                          .map(passiveNode -> assembleNode(config,
                                                           nodeId,
                                                           passiveNode,
                                                           workerNetwork,
                                                           serializer,
                                                           deserializer));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorkerNode assembleNode(WorkerConfig config,
                                           NodeId nodeId,
                                           PassiveNode<AetherKey, AetherValue> passiveNode,
                                           WorkerNetwork workerNetwork,
                                           Serializer serializer,
                                           Deserializer deserializer) {
        var decisionRelay = DecisionRelay.decisionRelay(nodeId, workerNetwork);
        var mutationForwarder = MutationForwarder.mutationForwarder(nodeId, workerNetwork, passiveNode);
        var workerBootstrap = WorkerBootstrap.workerBootstrap(nodeId, workerNetwork);
        var swimConfig = toSwimConfig(config.swimSettings());
        var aetherMaps = createAetherMaps(nodeId);
        return new AssembledWorkerNode(config,
                                       nodeId,
                                       passiveNode,
                                       workerNetwork,
                                       decisionRelay,
                                       mutationForwarder,
                                       workerBootstrap,
                                       swimConfig,
                                       serializer,
                                       deserializer,
                                       aetherMaps);
    }

    private static AetherMaps createAetherMaps(NodeId nodeId) {
        var storage = MemoryStorageEngine.memoryStorageEngine();
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(nodeId);
        var dhtNode = DHTNode.dhtNode(nodeId, storage, ring, DHTConfig.SINGLE_NODE);
        var dhtClient = LocalDHTClient.localDHTClient(dhtNode);
        return AetherMaps.aetherMaps(dhtClient);
    }

    private static List<NodeInfo> parseCoreNodes(WorkerConfig config, NodeId selfId) {
        var nodes = new ArrayList<NodeInfo>();
        var selfInfo = NodeInfo.nodeInfo(selfId,
                                         nodeAddress("localhost", config.clusterPort()).unwrap());
        nodes.add(selfInfo);
        for (int i = 0; i < config.coreNodes()
                                  .size(); i++) {
            parseCoreNodeAddress(config.coreNodes()
                                       .get(i),
                                 i).onPresent(nodes::add);
        }
        return List.copyOf(nodes);
    }

    private static Option<NodeInfo> parseCoreNodeAddress(String address, int index) {
        var parts = address.split(":");
        if (parts.length != 2) {
            return Option.empty();
        }
        var host = parts[0];
        var port = Integer.parseInt(parts[1]);
        var id = NodeId.nodeId("core-" + index)
                       .unwrap();
        return nodeAddress(host, port).map(addr -> NodeInfo.nodeInfo(id, addr))
                          .option();
    }

    private static TopologyConfig createTopologyConfig(NodeId self, List<NodeInfo> coreNodes) {
        return new TopologyConfig(self, coreNodes.size(), timeSpan(1).seconds(), timeSpan(10).seconds(), coreNodes);
    }

    private static SwimConfig toSwimConfig(SwimSettings settings) {
        return SwimConfig.swimConfig(Duration.ofMillis(settings.periodMs()),
                                     Duration.ofMillis(settings.probeTimeoutMs()),
                                     settings.indirectProbes(),
                                     Duration.ofMillis(settings.suspectTimeoutMs()),
                                     settings.maxPiggyback());
    }

    /// Internal holder for serializer/deserializer pair.
    record CodecPair(Serializer serializer, Deserializer deserializer) {
        static CodecPair codecPair(Serializer serializer, Deserializer deserializer) {
            return new CodecPair(serializer, deserializer);
        }
    }
}

/// Assembled worker node implementation.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "JBCT-STY-05"})
final class AssembledWorkerNode implements WorkerNode, SwimMembershipListener {
    private static final Logger LOG = LoggerFactory.getLogger(AssembledWorkerNode.class);

    private final WorkerConfig config;
    private final NodeId nodeId;
    private final PassiveNode<AetherKey, AetherValue> passiveNode;
    private final WorkerNetwork workerNetwork;
    private final DecisionRelay decisionRelay;
    private final MutationForwarder mutationForwarder;
    private final WorkerBootstrap workerBootstrap;
    private final SwimConfig swimConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final AtomicReference<Option<NodeId>> currentGovernor = new AtomicReference<>(Option.empty());
    private final CopyOnWriteArrayList<SwimMember> membershipSnapshot = new CopyOnWriteArrayList<>();
    private final AetherMaps aetherMaps;
    private volatile SwimProtocol swimProtocol;

    AssembledWorkerNode(WorkerConfig config,
                        NodeId nodeId,
                        PassiveNode<AetherKey, AetherValue> passiveNode,
                        WorkerNetwork workerNetwork,
                        DecisionRelay decisionRelay,
                        MutationForwarder mutationForwarder,
                        WorkerBootstrap workerBootstrap,
                        SwimConfig swimConfig,
                        Serializer serializer,
                        Deserializer deserializer,
                        AetherMaps aetherMaps) {
        this.config = config;
        this.nodeId = nodeId;
        this.passiveNode = passiveNode;
        this.workerNetwork = workerNetwork;
        this.decisionRelay = decisionRelay;
        this.mutationForwarder = mutationForwarder;
        this.workerBootstrap = workerBootstrap;
        this.swimConfig = swimConfig;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.aetherMaps = aetherMaps;
    }

    @Override
    public NodeId self() {
        return nodeId;
    }

    @Override
    public GovernorState governorState() {
        return GovernorElection.evaluateElection(nodeId, List.copyOf(membershipSnapshot), currentGovernor.get());
    }

    @Override
    public boolean isGovernor() {
        return currentGovernor.get()
                              .map(nodeId::equals)
                              .or(false);
    }

    @Override
    public AetherMaps aetherMaps() {
        return aetherMaps;
    }

    @Override
    public Promise<Unit> start() {
        wireRoutes();
        return passiveNode.start()
                          .flatMap(_ -> startWorkerNetwork())
                          .flatMap(_ -> startSwim())
                          .onSuccess(_ -> LOG.info("Worker node {} started",
                                                   nodeId.id()));
    }

    @Override
    public Promise<Unit> stop() {
        LOG.info("Stopping worker node {}", nodeId.id());
        stopSwim();
        return workerNetwork.stop()
                            .flatMap(_ -> passiveNode.stop())
                            .onSuccess(_ -> LOG.info("Worker node {} stopped",
                                                     nodeId.id()));
    }

    // -- SwimMembershipListener --
    @Override
    public void onMemberJoined(SwimMember member) {
        LOG.info("Worker member joined: {}",
                 member.nodeId()
                       .id());
        workerNetwork.registerPeer(member.nodeId(), member.address());
        updateMembership(member);
        reEvaluateGovernor();
    }

    @Override
    public void onMemberSuspect(SwimMember member) {
        LOG.warn("Worker member suspect: {}",
                 member.nodeId()
                       .id());
        updateMembership(member);
        reEvaluateGovernor();
    }

    @Override
    public void onMemberFaulty(SwimMember member) {
        LOG.warn("Worker member faulty: {}",
                 member.nodeId()
                       .id());
        workerNetwork.removePeer(member.nodeId());
        updateMembership(member);
        reEvaluateGovernor();
    }

    @Override
    public void onMemberLeft(NodeId leftNodeId) {
        LOG.info("Worker member left: {}", leftNodeId.id());
        workerNetwork.removePeer(leftNodeId);
        membershipSnapshot.removeIf(m -> m.nodeId()
                                          .equals(leftNodeId));
        reEvaluateGovernor();
    }

    // -- Internal wiring --
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireRoutes() {
        Entry decisionRoute = route(Decision.class, (Decision decision) -> onDecision(decision));
        Entry mutationRoute = route(WorkerMutation.class,
                                    (WorkerMutation mutation) -> mutationForwarder.onMutationFromFollower(mutation));
        Entry snapshotReqRoute = route(SnapshotRequest.class, (SnapshotRequest req) -> handleSnapshotRequest(req));
        Entry snapshotRespRoute = route(SnapshotResponse.class,
                                        (SnapshotResponse resp) -> workerBootstrap.onSnapshotReceived(resp));
        var allEntries = new ArrayList<>(passiveNode.routeEntries());
        allEntries.add(decisionRoute);
        allEntries.add(mutationRoute);
        allEntries.add(snapshotReqRoute);
        allEntries.add(snapshotRespRoute);
        RabiaNode.buildAndWireRouter(passiveNode.delegateRouter(), allEntries);
    }

    private void onDecision(Decision<?> decision) {
        var governor = currentGovernor.get();
        if (governor.map(nodeId::equals)
                    .or(false)) {
            var followers = workerNetwork.connectedPeers();
            decisionRelay.onDecisionFromCore(decision, followers);
        } else {
            decisionRelay.onDecisionFromGovernor(decision);
        }
    }

    @SuppressWarnings({"rawtypes", "JBCT-RET-01"})
    private void handleSnapshotRequest(SnapshotRequest request) {
        var kvState = serializer.encode(passiveNode.kvStore());
        workerBootstrap.onSnapshotRequest(request, kvState, decisionRelay.lastSequence());
    }

    private Promise<Unit> startWorkerNetwork() {
        return workerNetwork.start(config.swimPort() + 100, this::onWorkerMessage);
    }

    @SuppressWarnings("unchecked")
    private void onWorkerMessage(Object message) {
        if (message instanceof Message msg) {
            passiveNode.delegateRouter()
                       .route(msg);
        }
    }

    private Promise<Unit> startSwim() {
        var selfAddress = new InetSocketAddress("0.0.0.0", config.swimPort());
        return NettySwimTransport.nettySwimTransport(serializer, deserializer)
                                 .flatMap(transport -> SwimProtocol.swimProtocol(swimConfig,
                                                                                 transport,
                                                                                 this,
                                                                                 nodeId,
                                                                                 selfAddress))
                                 .flatMap(SwimProtocol::start)
                                 .map(this::storeSwimProtocol)
                                 .async()
                                 .mapToUnit();
    }

    private SwimProtocol storeSwimProtocol(SwimProtocol protocol) {
        swimProtocol = protocol;
        return protocol;
    }

    private void stopSwim() {
        var protocol = swimProtocol;
        if (protocol != null) {
            protocol.stop();
            swimProtocol = null;
        }
    }

    private void updateMembership(SwimMember member) {
        membershipSnapshot.removeIf(m -> m.nodeId()
                                          .equals(member.nodeId()));
        if (member.state() != MemberState.FAULTY) {
            membershipSnapshot.add(member);
        }
    }

    private void reEvaluateGovernor() {
        var state = GovernorElection.evaluateElection(nodeId, List.copyOf(membershipSnapshot), currentGovernor.get());
        var newGovernor = switch (state) {
            case GovernorState.Governor g -> some(g.self());
            case GovernorState.Follower f -> some(f.governorId());
        };
        var previous = currentGovernor.getAndSet(newGovernor);
        mutationForwarder.updateGovernor(newGovernor);
        if (!previous.equals(newGovernor)) {
            logGovernorChange(state);
        }
    }

    private void logGovernorChange(GovernorState state) {
        switch (state) {
            case GovernorState.Governor g -> LOG.info("This node {} elected as governor",
                                                      g.self()
                                                       .id());
            case GovernorState.Follower f -> LOG.info("Governor changed to {}",
                                                      f.governorId()
                                                       .id());
        }
    }
}
