package org.pragmatica.aether.worker;

import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.config.WorkerConfig.SwimSettings;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.aether.worker.network.WorkerDHTNetwork;
import org.pragmatica.aether.worker.bootstrap.SnapshotRequest;
import org.pragmatica.aether.worker.bootstrap.SnapshotResponse;
import org.pragmatica.aether.worker.bootstrap.WorkerBootstrap;
import org.pragmatica.aether.worker.governor.DecisionRelay;
import org.pragmatica.aether.worker.governor.GovernorCleanup;
import org.pragmatica.aether.worker.governor.GovernorElection;
import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.aether.worker.governor.GovernorReconciliation;
import org.pragmatica.aether.worker.governor.GovernorState;
import org.pragmatica.aether.worker.group.GroupMembershipTracker;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.aether.worker.network.DHTRelayMessage;
import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.NettySwimTransport;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimProtocol;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
        var workerBootstrap = WorkerBootstrap.workerBootstrap(nodeId, workerNetwork, passiveNode.kvStore());
        var swimConfig = toSwimConfig(config.swimSettings());
        var governorMesh = GovernorMesh.governorMesh(workerNetwork);
        var communityMembers = new ConcurrentHashMap<String, List<NodeId>>();
        var groupMembershipTracker = GroupMembershipTracker.groupMembershipTracker(nodeId,
                                                                                   config.groupName(),
                                                                                   config.maxGroupSize());
        var dhtComponents = DHTComponents.dhtComponents(nodeId,
                                                        workerNetwork,
                                                        governorMesh,
                                                        communityMembers,
                                                        serializer,
                                                        () -> groupMembershipTracker.myGroup()
                                                                                    .communityId());
        var aetherMaps = dhtComponents.aetherMaps();
        var governorCleanup = GovernorCleanup.governorCleanup(aetherMaps);
        var sliceStore = createSliceStore();
        var workerDeploymentManager = WorkerDeploymentManager.workerDeploymentManager(nodeId,
                                                                                      sliceStore,
                                                                                      aetherMaps,
                                                                                      List.of(),
                                                                                      () -> groupMembershipTracker.myGroup()
                                                                                                                  .communityId());
        var kvNotificationRouter = createKvNotificationRouter(workerDeploymentManager,
                                                              dhtComponents,
                                                              governorMesh,
                                                              communityMembers);
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
                                       aetherMaps,
                                       governorCleanup,
                                       groupMembershipTracker,
                                       workerDeploymentManager,
                                       kvNotificationRouter,
                                       dhtComponents,
                                       governorMesh,
                                       communityMembers);
    }

    private static SliceStore createSliceStore() {
        var sliceRegistry = SliceRegistry.sliceRegistry();
        var sliceActionConfig = SliceActionConfig.sliceActionConfig();
        var sharedLibraryLoader = new SharedLibraryClassLoader(Thread.currentThread()
                                                                     .getContextClassLoader());
        return SliceStore.sliceStore(sliceRegistry,
                                     sliceActionConfig.repositories(),
                                     sharedLibraryLoader,
                                     noOpSliceInvokerFacade(),
                                     sliceActionConfig);
    }

    Cause NO_INVOKER = Causes.cause("Worker node does not support inter-slice invocation in Phase 1");

    @SuppressWarnings("JBCT-RET-01")
    private static SliceInvokerFacade noOpSliceInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<org.pragmatica.aether.slice.MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                                              String methodName,
                                                                                              org.pragmatica.lang.type.TypeToken<T> requestType,
                                                                                              org.pragmatica.lang.type.TypeToken<R> responseType) {
                return NO_INVOKER.result();
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KVNotificationRouter<AetherKey, AetherValue> createKvNotificationRouter(WorkerDeploymentManager wdm,
                                                                                           DHTComponents dhtComponents,
                                                                                           GovernorMesh governorMesh,
                                                                                           Map<String, List<NodeId>> communityMembers) {
        return KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                   .onPut(WorkerSliceDirectiveKey.class,
                                          notification -> wdm.onDirectivePut((WorkerSliceDirectiveValue) notification.cause()
                                                                                                                    .value()))
                                   .onRemove(WorkerSliceDirectiveKey.class,
                                             notification -> wdm.onDirectiveRemove(notification.cause()
                                                                                               .key()
                                                                                               .artifact()))
                                   .onPut(GovernorAnnouncementKey.class,
                                          notification -> onGovernorAnnouncementPut(notification,
                                                                                    dhtComponents,
                                                                                    governorMesh,
                                                                                    communityMembers))
                                   .onRemove(GovernorAnnouncementKey.class,
                                             notification -> onGovernorAnnouncementRemove(notification,
                                                                                          dhtComponents,
                                                                                          governorMesh,
                                                                                          communityMembers))
                                   .build();
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    private static void onGovernorAnnouncementPut(Object notification,
                                                  DHTComponents dhtComponents,
                                                  GovernorMesh governorMesh,
                                                  Map<String, List<NodeId>> communityMembers) {
        var valuePut = (org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut<GovernorAnnouncementKey, GovernorAnnouncementValue>) notification;
        var announcement = valuePut.cause()
                                   .value();
        var communityId = valuePut.cause()
                                  .key()
                                  .communityId();
        governorMesh.registerGovernor(communityId, announcement.governorId(), announcement.tcpAddress());
        var members = announcement.members();
        communityMembers.put(communityId, members);
        members.forEach(memberId -> dhtComponents.dhtNode()
                                                 .ring()
                                                 .addNode(memberId));
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    private static void onGovernorAnnouncementRemove(Object notification,
                                                     DHTComponents dhtComponents,
                                                     GovernorMesh governorMesh,
                                                     Map<String, List<NodeId>> communityMembers) {
        var valueRemove = (org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove<GovernorAnnouncementKey, GovernorAnnouncementValue>) notification;
        var communityId = valueRemove.cause()
                                     .key()
                                     .communityId();
        governorMesh.unregisterGovernor(communityId);
        Option.option(communityMembers.remove(communityId))
              .onPresent(removed -> removed.forEach(memberId -> dhtComponents.dhtNode()
                                                                             .ring()
                                                                             .removeNode(memberId)));
    }

    /// Components for distributed DHT operation within the worker community.
    record DHTComponents(DHTNode dhtNode,
                         DistributedDHTClient dhtClient,
                         WorkerDHTNetwork workerDhtNetwork,
                         AetherMaps aetherMaps) {
        static DHTComponents dhtComponents(NodeId nodeId,
                                           WorkerNetwork workerNetwork,
                                           GovernorMesh governorMesh,
                                           Map<String, List<NodeId>> communityMembers,
                                           Serializer serializer,
                                           Supplier<String> selfCommunityId) {
            var storage = MemoryStorageEngine.memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(nodeId);
            var dhtNode = DHTNode.dhtNode(nodeId, storage, ring, DHTConfig.DEFAULT);
            var workerDhtNetwork = WorkerDHTNetwork.workerDHTNetwork(workerNetwork,
                                                                     governorMesh,
                                                                     communityMembers,
                                                                     serializer,
                                                                     selfCommunityId);
            var dhtClient = DistributedDHTClient.distributedDHTClient(dhtNode, workerDhtNetwork, DHTConfig.DEFAULT);
            var aetherMaps = AetherMaps.aetherMaps(dhtClient);
            return new DHTComponents(dhtNode, dhtClient, workerDhtNetwork, aetherMaps);
        }
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
    private final AetherMaps aetherMaps;
    private final GovernorCleanup governorCleanup;
    private final GroupMembershipTracker groupMembershipTracker;
    private final WorkerDeploymentManager workerDeploymentManager;
    private final KVNotificationRouter<AetherKey, AetherValue> kvNotificationRouter;
    private final WorkerNode.DHTComponents dhtComponents;
    private final GovernorMesh governorMesh;
    private final Map<String, List<NodeId>> communityMembers;
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
                        AetherMaps aetherMaps,
                        GovernorCleanup governorCleanup,
                        GroupMembershipTracker groupMembershipTracker,
                        WorkerDeploymentManager workerDeploymentManager,
                        KVNotificationRouter<AetherKey, AetherValue> kvNotificationRouter,
                        WorkerNode.DHTComponents dhtComponents,
                        GovernorMesh governorMesh,
                        Map<String, List<NodeId>> communityMembers) {
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
        this.governorCleanup = governorCleanup;
        this.groupMembershipTracker = groupMembershipTracker;
        this.workerDeploymentManager = workerDeploymentManager;
        this.kvNotificationRouter = kvNotificationRouter;
        this.dhtComponents = dhtComponents;
        this.governorMesh = governorMesh;
        this.communityMembers = communityMembers;
    }

    @Override
    public NodeId self() {
        return nodeId;
    }

    @Override
    public GovernorState governorState() {
        return GovernorElection.evaluateElection(nodeId, groupScopedMembership(), currentGovernor.get());
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
        wireDhtSubscriptions();
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
        groupMembershipTracker.updateMember(member);
        dhtComponents.dhtNode()
                     .ring()
                     .addNode(member.nodeId());
        reEvaluateGovernor();
        notifyDeploymentManagerOfMembershipChange();
        reAnnounceIfGovernor();
    }

    @Override
    public void onMemberSuspect(SwimMember member) {
        LOG.warn("Worker member suspect: {}",
                 member.nodeId()
                       .id());
        groupMembershipTracker.updateMember(member);
        reEvaluateGovernor();
    }

    @Override
    public void onMemberFaulty(SwimMember member) {
        LOG.warn("Worker member faulty: {}",
                 member.nodeId()
                       .id());
        workerNetwork.removePeer(member.nodeId());
        groupMembershipTracker.updateMember(member);
        dhtComponents.dhtNode()
                     .ring()
                     .removeNode(member.nodeId());
        reEvaluateGovernor();
        notifyDeploymentManagerOfMembershipChange();
        if (isGovernor()) {
            governorCleanup.cleanupDeadNode(member.nodeId());
        }
        reAnnounceIfGovernor();
    }

    @Override
    public void onMemberLeft(NodeId leftNodeId) {
        LOG.info("Worker member left: {}", leftNodeId.id());
        workerNetwork.removePeer(leftNodeId);
        groupMembershipTracker.removeMember(leftNodeId);
        dhtComponents.dhtNode()
                     .ring()
                     .removeNode(leftNodeId);
        reEvaluateGovernor();
        notifyDeploymentManagerOfMembershipChange();
        if (isGovernor()) {
            governorCleanup.cleanupDeadNode(leftNodeId);
        }
        reAnnounceIfGovernor();
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
        allEntries.addAll(kvNotificationRouter.asRouteEntries());
        // DHT request routes — handle incoming DHT operations from peer workers
        var dhtNode = dhtComponents.dhtNode();
        var dhtClient = dhtComponents.dhtClient();
        var dhtNetwork = dhtComponents.workerDhtNetwork();
        allEntries.add(route(DHTMessage.GetRequest.class,
                             (DHTMessage.GetRequest request) -> dhtNode.handleGetRequest(request,
                                                                                         response -> dhtNetwork.send(request.sender(),
                                                                                                                     response))));
        allEntries.add(route(DHTMessage.PutRequest.class,
                             (DHTMessage.PutRequest request) -> dhtNode.handlePutRequest(request,
                                                                                         response -> dhtNetwork.send(request.sender(),
                                                                                                                     response))));
        allEntries.add(route(DHTMessage.RemoveRequest.class,
                             (DHTMessage.RemoveRequest request) -> dhtNode.handleRemoveRequest(request,
                                                                                               response -> dhtNetwork.send(request.sender(),
                                                                                                                           response))));
        allEntries.add(route(DHTMessage.ExistsRequest.class,
                             (DHTMessage.ExistsRequest request) -> dhtNode.handleExistsRequest(request,
                                                                                               response -> dhtNetwork.send(request.sender(),
                                                                                                                           response))));
        // DHT response routes — complete quorum-based operations
        allEntries.add(route(DHTMessage.GetResponse.class, dhtClient::onGetResponse));
        allEntries.add(route(DHTMessage.PutResponse.class, dhtClient::onPutResponse));
        allEntries.add(route(DHTMessage.RemoveResponse.class, dhtClient::onRemoveResponse));
        allEntries.add(route(DHTMessage.ExistsResponse.class, dhtClient::onExistsResponse));
        RabiaNode.buildAndWireRouter(passiveNode.delegateRouter(), allEntries);
    }

    private void onDecision(Decision<?> decision) {
        var governor = currentGovernor.get();
        if (governor.map(nodeId::equals)
                    .or(false)) {
            var groupMembers = groupMembershipTracker.myGroupMembers();
            var followers = workerNetwork.connectedPeers()
                                         .stream()
                                         .filter(groupMembers::contains)
                                         .toList();
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
        if (message instanceof DHTRelayMessage relay) {
            workerNetwork.sendRaw(relay.actualTarget(), relay.serializedPayload());
            return;
        }
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

    private void notifyDeploymentManagerOfMembershipChange() {
        workerDeploymentManager.onMembershipChange(groupMembershipTracker.myGroupMembers());
    }

    private void reEvaluateGovernor() {
        var state = GovernorElection.evaluateElection(nodeId, groupScopedMembership(), currentGovernor.get());
        var newGovernor = switch (state) {
            case GovernorState.Governor g -> some(g.self());
            case GovernorState.Follower f -> some(f.governorId());
        };
        var previous = currentGovernor.getAndSet(newGovernor);
        mutationForwarder.updateGovernor(newGovernor);
        if (!previous.equals(newGovernor)) {
            logGovernorChange(state);
            triggerReconciliationIfNewGovernor(state, previous);
            triggerBootstrapIfNeeded(state);
            announceGovernorChange(state);
        }
    }

    private void triggerReconciliationIfNewGovernor(GovernorState state, Option<NodeId> previous) {
        if (state instanceof GovernorState.Governor && !previous.map(nodeId::equals)
                                                                .or(false)) {
            var aliveNodes = collectAliveNodeIds();
            GovernorReconciliation.reconcile(aliveNodes, governorCleanup);
            // Delayed reconciliation: subscription events may populate the cleanup index after election
            scheduleDelayedReconciliation();
        }
    }

    private void scheduleDelayedReconciliation() {
        Promise.<Unit> promise()
               .timeout(timeSpan(5).seconds())
               .onFailure(_ -> runDelayedReconciliation());
    }

    private void runDelayedReconciliation() {
        var aliveNodes = collectAliveNodeIds();
        GovernorReconciliation.reconcile(aliveNodes, governorCleanup);
    }

    private void triggerBootstrapIfNeeded(GovernorState state) {
        if (workerBootstrap.isBootstrapped()) {
            return;
        }
        switch (state) {
            case GovernorState.Governor _ -> markGovernorBootstrapped();
            case GovernorState.Follower f -> requestFollowerBootstrap(f.governorId());
        }
    }

    private void markGovernorBootstrapped() {
        workerBootstrap.markBootstrapped();
        LOG.info("Governor {} bootstrapped from core Decision stream", nodeId.id());
    }

    private void requestFollowerBootstrap(NodeId governorId) {
        workerBootstrap.requestSnapshot(some(governorId));
        LOG.info("Follower {} requesting bootstrap from governor {}", nodeId.id(), governorId.id());
    }

    private Set<NodeId> collectAliveNodeIds() {
        var alive = new HashSet<>(groupMembershipTracker.myGroupMembers());
        alive.add(nodeId);
        return Set.copyOf(alive);
    }

    private List<SwimMember> groupScopedMembership() {
        var groupMembers = groupMembershipTracker.myGroupMembers();
        return groupMembershipTracker.membershipSnapshot()
                                     .stream()
                                     .filter(m -> groupMembers.contains(m.nodeId()))
                                     .toList();
    }

    @SuppressWarnings("JBCT-RET-01")
    private void announceGovernorChange(GovernorState state) {
        if (state instanceof GovernorState.Governor) {
            var communityId = groupMembershipTracker.myGroup()
                                                    .communityId();
            var key = GovernorAnnouncementKey.forCommunity(communityId);
            var members = buildMemberList();
            var tcpAddress = "0.0.0.0:" + (config.swimPort() + 100);
            var value = GovernorAnnouncementValue.governorAnnouncementValue(nodeId, members, tcpAddress);
            LOG.info("Announcing governor for community '{}': {} with {} members",
                     communityId,
                     nodeId.id(),
                     members.size());
            var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
            mutationForwarder.forward(WorkerMutation.workerMutation(nodeId, "governor-" + communityId, command));
        }
    }

    private void reAnnounceIfGovernor() {
        if (isGovernor()) {
            announceGovernorChange(new GovernorState.Governor(nodeId));
        }
    }

    private List<NodeId> buildMemberList() {
        var members = new ArrayList<>(groupMembershipTracker.myGroupMembers());
        members.add(nodeId);
        return List.copyOf(members);
    }

    @SuppressWarnings("JBCT-RET-01") // MapSubscription callbacks are void
    private void wireDhtSubscriptions() {
        aetherMaps.endpoints()
                  .subscribe(new MapSubscription<>() {
            @Override
            public void onPut(EndpointKey key, EndpointValue value) {
                                 governorCleanup.trackEndpoint(value.nodeId(),
                                                               key);
                             }

            @Override
            public void onRemove(EndpointKey key) {}
        });
        aetherMaps.sliceNodes()
                  .subscribe(new MapSubscription<>() {
            @Override
            public void onPut(SliceNodeKey key, SliceNodeValue value) {
                                 governorCleanup.trackSliceNode(key.nodeId(),
                                                                key);
                             }

            @Override
            public void onRemove(SliceNodeKey key) {
                                 governorCleanup.untrackSliceNode(key.nodeId(),
                                                                  key);
                             }
        });
        aetherMaps.httpRoutes()
                  .subscribe(new MapSubscription<>() {
            @Override
            public void onPut(HttpNodeRouteKey key, HttpNodeRouteValue value) {
                                 governorCleanup.trackHttpRoute(key.nodeId(),
                                                                key);
                             }

            @Override
            public void onRemove(HttpNodeRouteKey key) {
                                 governorCleanup.untrackHttpRoute(key.nodeId(),
                                                                  key);
                             }
        });
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
