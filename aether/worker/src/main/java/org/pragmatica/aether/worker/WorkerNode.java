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
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
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
import org.pragmatica.aether.worker.heartbeat.FollowerHeartbeat;
import org.pragmatica.aether.worker.heartbeat.FollowerHealthTracker;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshotRequest;
import org.pragmatica.aether.worker.metrics.WorkerMetricsAggregator;
import org.pragmatica.aether.worker.metrics.WorkerMetricsPing;
import org.pragmatica.aether.worker.metrics.WorkerMetricsPong;
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
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
        return PassiveNode.<AetherKey, AetherValue> passiveNode(topologyConfig, serializer, deserializer)
                          .map(passiveNode -> assembleNode(config,
                                                           nodeId,
                                                           passiveNode,
                                                           serializer,
                                                           deserializer));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorkerNode assembleNode(WorkerConfig config,
                                           NodeId nodeId,
                                           PassiveNode<AetherKey, AetherValue> passiveNode,
                                           Serializer serializer,
                                           Deserializer deserializer) {
        var delegateRouter = passiveNode.delegateRouter();
        var decisionRelay = DecisionRelay.decisionRelay(nodeId, delegateRouter);
        var mutationForwarder = MutationForwarder.mutationForwarder(nodeId, passiveNode);
        var workerBootstrap = WorkerBootstrap.workerBootstrap(nodeId, delegateRouter, passiveNode.kvStore());
        var swimConfig = toSwimConfig(config.swimSettings());
        var governorMesh = GovernorMesh.governorMesh(delegateRouter);
        var communityMembers = new ConcurrentHashMap<String, List<NodeId>>();
        var groupMembershipTracker = GroupMembershipTracker.groupMembershipTracker(nodeId,
                                                                                   config.groupName(),
                                                                                   config.maxGroupSize());
        var dhtComponents = DHTComponents.dhtComponents(nodeId,
                                                        delegateRouter,
                                                        passiveNode,
                                                        governorMesh,
                                                        communityMembers,
                                                        serializer,
                                                        () -> groupMembershipTracker.myGroup()
                                                                                    .communityId());
        var aetherMaps = dhtComponents.aetherMaps();
        var governorCleanup = GovernorCleanup.governorCleanup(mutationForwarder);
        var sliceStore = createSliceStore();
        var workerDeploymentManager = WorkerDeploymentManager.workerDeploymentManager(nodeId,
                                                                                      sliceStore,
                                                                                      mutationForwarder,
                                                                                      List.of(),
                                                                                      () -> groupMembershipTracker.myGroup()
                                                                                                                  .communityId());
        var kvNotificationRouter = createKvNotificationRouter(workerDeploymentManager,
                                                              dhtComponents,
                                                              governorMesh,
                                                              communityMembers,
                                                              governorCleanup);
        return new AssembledWorkerNode(config,
                                       nodeId,
                                       passiveNode,
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
                                                                                           Map<String, List<NodeId>> communityMembers,
                                                                                           GovernorCleanup governorCleanup) {
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
                                   .onPut(NodeArtifactKey.class,
                                          notification -> onNodeArtifactPut(notification, governorCleanup))
                                   .onRemove(NodeArtifactKey.class,
                                             notification -> onNodeArtifactRemove(notification, governorCleanup))
                                   .onPut(NodeRoutesKey.class,
                                          notification -> onNodeRoutesPut(notification, governorCleanup))
                                   .onRemove(NodeRoutesKey.class,
                                             notification -> onNodeRoutesRemove(notification, governorCleanup))
                                   .build();
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    private static void onNodeArtifactPut(Object notification, GovernorCleanup governorCleanup) {
        var valuePut = (KVStoreNotification.ValuePut<NodeArtifactKey, NodeArtifactValue>) notification;
        governorCleanup.trackNodeArtifact(valuePut.cause()
                                                  .key()
                                                  .nodeId(),
                                          valuePut.cause()
                                                  .key());
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    private static void onNodeArtifactRemove(Object notification, GovernorCleanup governorCleanup) {
        var valueRemove = (KVStoreNotification.ValueRemove<NodeArtifactKey, NodeArtifactValue>) notification;
        governorCleanup.untrackNodeArtifact(valueRemove.cause()
                                                       .key()
                                                       .nodeId(),
                                            valueRemove.cause()
                                                       .key());
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    private static void onNodeRoutesPut(Object notification, GovernorCleanup governorCleanup) {
        var valuePut = (KVStoreNotification.ValuePut<NodeRoutesKey, NodeRoutesValue>) notification;
        governorCleanup.trackNodeRoutes(valuePut.cause()
                                                .key()
                                                .nodeId(),
                                        valuePut.cause()
                                                .key());
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-01"})
    private static void onNodeRoutesRemove(Object notification, GovernorCleanup governorCleanup) {
        var valueRemove = (KVStoreNotification.ValueRemove<NodeRoutesKey, NodeRoutesValue>) notification;
        governorCleanup.untrackNodeRoutes(valueRemove.cause()
                                                     .key()
                                                     .nodeId(),
                                          valueRemove.cause()
                                                     .key());
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
                                           DelegateRouter delegateRouter,
                                           PassiveNode<?, ?> passiveNode,
                                           GovernorMesh governorMesh,
                                           Map<String, List<NodeId>> communityMembers,
                                           Serializer serializer,
                                           Supplier<String> selfCommunityId) {
            var storage = MemoryStorageEngine.memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(nodeId);
            var dhtNode = DHTNode.dhtNode(nodeId, storage, ring, DHTConfig.DEFAULT);
            var workerDhtNetwork = WorkerDHTNetwork.workerDHTNetwork(delegateRouter,
                                                                     () -> passiveNode.network()
                                                                                      .connectedPeers(),
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
    private final DecisionRelay decisionRelay;
    private final MutationForwarder mutationForwarder;
    private final WorkerBootstrap workerBootstrap;
    private final SwimConfig swimConfig;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final AtomicReference<Option<NodeId>> currentGovernor = new AtomicReference<>(Option.empty());
    private final AtomicLong membershipVersion = new AtomicLong(0);
    private final AetherMaps aetherMaps;
    private final GovernorCleanup governorCleanup;
    private final GroupMembershipTracker groupMembershipTracker;
    private final WorkerDeploymentManager workerDeploymentManager;
    private final KVNotificationRouter<AetherKey, AetherValue> kvNotificationRouter;
    private final WorkerNode.DHTComponents dhtComponents;
    private final GovernorMesh governorMesh;
    private final Map<String, List<NodeId>> communityMembers;
    private volatile SwimProtocol swimProtocol;
    private volatile FollowerHealthTracker followerHealthTracker;
    private volatile boolean heartbeatSenderActive;
    private volatile WorkerMetricsAggregator metricsAggregator;

    AssembledWorkerNode(WorkerConfig config,
                        NodeId nodeId,
                        PassiveNode<AetherKey, AetherValue> passiveNode,
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
        return passiveNode.start()
                          .flatMap(_ -> startSwim())
                          .onSuccess(_ -> LOG.info("Worker node {} started",
                                                   nodeId.id()));
    }

    @Override
    public Promise<Unit> stop() {
        LOG.info("Stopping worker node {}", nodeId.id());
        stopHeartbeatSender();
        stopHealthTracker();
        stopMetricsAggregator();
        stopSwim();
        return passiveNode.stop()
                          .onSuccess(_ -> LOG.info("Worker node {} stopped",
                                                   nodeId.id()));
    }

    // -- SwimMembershipListener --
    @Override
    public void onMemberJoined(SwimMember member) {
        LOG.info("Worker member joined: {}",
                 member.nodeId()
                       .id());
        registerPeerInTopology(member.nodeId(), member.address());
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
        passiveNode.delegateRouter()
                   .route(new NetworkServiceMessage.DisconnectNode(member.nodeId()));
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
        passiveNode.delegateRouter()
                   .route(new NetworkServiceMessage.DisconnectNode(leftNodeId));
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
        Entry heartbeatRoute = route(FollowerHeartbeat.class, (FollowerHeartbeat hb) -> handleFollowerHeartbeat(hb));
        Entry metricsPingRoute = route(WorkerMetricsPing.class, (WorkerMetricsPing ping) -> handleMetricsPing(ping));
        Entry metricsPongRoute = route(WorkerMetricsPong.class, (WorkerMetricsPong pong) -> handleMetricsPong(pong));
        Entry snapshotReqMetricsRoute = route(CommunityMetricsSnapshotRequest.class,
                                              (CommunityMetricsSnapshotRequest req) -> handleCommunitySnapshotRequest(req));
        Entry dhtRelayRoute = route(DHTRelayMessage.class, (DHTRelayMessage relay) -> handleDHTRelay(relay));
        var allEntries = new ArrayList<>(passiveNode.routeEntries());
        allEntries.add(decisionRoute);
        allEntries.add(mutationRoute);
        allEntries.add(snapshotReqRoute);
        allEntries.add(snapshotRespRoute);
        allEntries.add(heartbeatRoute);
        allEntries.add(metricsPingRoute);
        allEntries.add(metricsPongRoute);
        allEntries.add(snapshotReqMetricsRoute);
        allEntries.add(dhtRelayRoute);
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
            var followers = new ArrayList<>(passiveNode.network()
                                                       .connectedPeers());
            followers.retainAll(groupMembers);
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

    private void handleFollowerHeartbeat(FollowerHeartbeat heartbeat) {
        var tracker = followerHealthTracker;
        if (tracker != null && isGovernor()) {
            tracker.onHeartbeat(heartbeat);
        }
    }

    private void handleMetricsPing(WorkerMetricsPing ping) {
        if (!isGovernor()) {
            var pong = collectLocalMetrics();
            passiveNode.delegateRouter()
                       .route(new NetworkServiceMessage.Send(ping.sender(), pong));
        }
    }

    private void handleMetricsPong(WorkerMetricsPong pong) {
        var aggregator = metricsAggregator;
        if (aggregator != null && isGovernor()) {
            aggregator.onMetricsPong(pong);
        }
    }

    private void handleCommunitySnapshotRequest(CommunityMetricsSnapshotRequest request) {
        var aggregator = metricsAggregator;
        if (aggregator != null && isGovernor()) {
            aggregator.onSnapshotRequest(request);
        }
    }

    private void handleDHTRelay(DHTRelayMessage relay) {
        if (!relay.actualTarget().equals(nodeId)) {
            passiveNode.delegateRouter()
                       .route(new NetworkServiceMessage.Send(relay.actualTarget(), relay));
            return;
        }
        Object dhtMessage = deserializer.decode(relay.serializedPayload());
        if (dhtMessage instanceof Message msg) {
            passiveNode.delegateRouter()
                       .route(msg);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private WorkerMetricsPong collectLocalMetrics() {
        var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        var memBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        var cpuLoad = osBean.getSystemLoadAverage() / Runtime.getRuntime()
                                                            .availableProcessors();
        if (cpuLoad < 0) {
            cpuLoad = 0.0;
        }
        var heapUsed = memBean.getHeapMemoryUsage()
                              .getUsed();
        var heapMax = memBean.getHeapMemoryUsage()
                             .getMax();
        var heapUsage = heapMax > 0
                        ? (double) heapUsed / heapMax
                        : 0.0;
        return WorkerMetricsPong.workerMetricsPong(nodeId, cpuLoad, heapUsage, 0L, 0.0, 0.0);
    }

    private void manageHeartbeatOnRoleChange(GovernorState state) {
        switch (state) {
            case GovernorState.Governor _ -> {
                stopHeartbeatSender();
                startHealthTracker();
                startMetricsAggregator();
            }
            case GovernorState.Follower f -> {
                stopHealthTracker();
                stopMetricsAggregator();
                startHeartbeatSender(f.governorId());
            }
        }
    }

    private void startHealthTracker() {
        followerHealthTracker = FollowerHealthTracker.followerHealthTracker();
        LOG.debug("Started follower health tracker on governor {}", nodeId.id());
    }

    private void stopHealthTracker() {
        var tracker = followerHealthTracker;
        if (tracker != null) {
            tracker.clear();
            followerHealthTracker = null;
        }
    }

    private void startMetricsAggregator() {
        var aggregator = WorkerMetricsAggregator.workerMetricsAggregator(nodeId,
                                                                         passiveNode.delegateRouter(),
                                                                         passiveNode,
                                                                         () -> groupMembershipTracker.myGroup()
                                                                                                     .communityId(),
                                                                         this::connectedGroupFollowers,
                                                                         config.metricsAggregationIntervalMs());
        metricsAggregator = aggregator;
        aggregator.start();
        LOG.debug("Started metrics aggregator on governor {}", nodeId.id());
    }

    private void stopMetricsAggregator() {
        var aggregator = metricsAggregator;
        if (aggregator != null) {
            aggregator.stop();
            metricsAggregator = null;
        }
    }

    private List<NodeId> connectedGroupFollowers() {
        var groupMembers = groupMembershipTracker.myGroupMembers();
        return passiveNode.network()
                          .connectedPeers()
                          .stream()
                          .filter(groupMembers::contains)
                          .toList();
    }

    private void startHeartbeatSender(NodeId governorId) {
        heartbeatSenderActive = true;
        scheduleNextHeartbeat(governorId);
        LOG.debug("Started heartbeat sender to governor {}", governorId.id());
    }

    private void stopHeartbeatSender() {
        heartbeatSenderActive = false;
    }

    private void scheduleNextHeartbeat(NodeId governorId) {
        if (!heartbeatSenderActive) {
            return;
        }
        Promise.<Unit> promise()
               .timeout(timeSpan(config.heartbeatIntervalMs()).millis())
               .onFailure(_ -> sendHeartbeatAndReschedule(governorId));
    }

    private void sendHeartbeatAndReschedule(NodeId governorId) {
        if (!heartbeatSenderActive) {
            return;
        }
        var heartbeat = FollowerHeartbeat.followerHeartbeat(nodeId, decisionRelay.lastSequence());
        passiveNode.delegateRouter()
                   .route(new NetworkServiceMessage.Send(governorId, heartbeat));
        scheduleNextHeartbeat(governorId);
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

    private void registerPeerInTopology(NodeId peerId, InetSocketAddress address) {
        nodeAddress(address).onSuccess(addr -> addAndConnectPeer(peerId, addr));
    }

    private void addAndConnectPeer(NodeId peerId, org.pragmatica.net.tcp.NodeAddress addr) {
        passiveNode.delegateRouter()
                   .route(new TopologyManagementMessage.AddNode(NodeInfo.nodeInfo(peerId, addr, NodeRole.PASSIVE)));
        passiveNode.delegateRouter()
                   .route(new NetworkServiceMessage.ConnectNode(peerId));
    }

    private void reEvaluateGovernor() {
        var state = GovernorElection.evaluateElection(nodeId, groupScopedMembership(), currentGovernor.get());
        var newGovernor = switch (state) {
            case GovernorState.Governor g -> some(g.self());
            case GovernorState.Follower f -> some(f.governorId());
        };
        var previous = currentGovernor.getAndSet(newGovernor);
        membershipVersion.incrementAndGet();
        mutationForwarder.updateGovernor(newGovernor);
        if (!previous.equals(newGovernor)) {
            logGovernorChange(state);
            manageHeartbeatOnRoleChange(state);
            triggerReconciliationIfNewGovernor(state, previous);
            triggerBootstrapIfNeeded(state);
            announceGovernorChange(state);
        }
    }

    private void triggerReconciliationIfNewGovernor(GovernorState state, Option<NodeId> previous) {
        if (state instanceof GovernorState.Governor && !previous.map(nodeId::equals)
                                                                .or(false)) {
            var aliveNodes = collectAliveNodeIds();
            GovernorReconciliation.reconcile(aliveNodes, governorCleanup, dhtComponents.dhtNode());
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
        GovernorReconciliation.reconcile(aliveNodes, governorCleanup, dhtComponents.dhtNode());
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
        scheduleBootstrapTimeout(governorId);
        LOG.info("Follower {} requesting bootstrap from governor {}", nodeId.id(), governorId.id());
    }

    private void scheduleBootstrapTimeout(NodeId governorId) {
        Promise.<Unit> promise()
               .timeout(timeSpan(30).seconds())
               .onFailure(_ -> handleBootstrapTimeout(governorId));
    }

    private void handleBootstrapTimeout(NodeId governorId) {
        if (workerBootstrap.isBootstrapped()) {
            return;
        }
        if (workerBootstrap.incrementRetry() > 1) {
            LOG.warn("Bootstrap timed out after retry — marking {} as bootstrapped (will sync via Decision stream)",
                     nodeId.id());
            workerBootstrap.markBootstrapped();
            return;
        }
        LOG.warn("Bootstrap timed out for {} — retrying snapshot from governor {}", nodeId.id(), governorId.id());
        workerBootstrap.requestSnapshot(some(governorId));
        scheduleBootstrapTimeout(governorId);
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
            var tcpAddress = resolveAdvertiseAddress();
            var value = GovernorAnnouncementValue.governorAnnouncementValue(nodeId, members, tcpAddress);
            LOG.info("Announcing governor for community '{}': {} with {} members (version {})",
                     communityId,
                     nodeId.id(),
                     members.size(),
                     membershipVersion.get());
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

    @SuppressWarnings({"JBCT-STY-05", "JBCT-RET-01"})
    private String resolveAdvertiseAddress() {
        var configuredAddress = config.advertiseAddress();
        if (configuredAddress != null && !configuredAddress.isBlank()) {
            return configuredAddress;
        }
        var port = config.swimPort() + 100;
        try{
            return InetAddress.getLocalHost()
                              .getHostAddress() + ":" + port;
        } catch (UnknownHostException e) {
            LOG.warn("Could not detect local host address, using localhost:{}", port);
            return "localhost:" + port;
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
