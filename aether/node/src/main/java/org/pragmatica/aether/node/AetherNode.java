// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ClusterEventAggregatorConfig;
import org.pragmatica.aether.api.ClusterEventLogPublisher;
import org.pragmatica.aether.api.ClusterEventLogSweeper;
import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.RollbackManager;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.aether.node.lifecycle.NodeStateChanged;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.drain.SelfDrainConfig;
import org.pragmatica.aether.deployment.drain.SelfDrainCoordinator;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.deployment.membership.phase.ClusterPhaseView;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.deployment.schema.SchemaPolicy;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.deployment.delegation.TaskAssignmentCoordinator;
import org.pragmatica.aether.deployment.delegation.TaskGroupActivator;
import org.pragmatica.aether.deployment.membership.ReachabilityAggregator;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.delegation.TaskGroupAssignmentRegistry;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwardMessage;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.api.ObservabilityDepthRegistry;
import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ObservabilityInterceptor;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.deployment.generation.ClusterGenerationProjector;
import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.deployment.generation.GenerationSnapshotPublisher;
import org.pragmatica.aether.deployment.generation.KvBackedGenerationSnapshotSource;
import org.pragmatica.aether.deployment.generation.SwimHintsRegistry;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.metrics.PeriodicObservationConfig;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsScheduler;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.network.NetworkMetricsHandler;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.storage.DelegatedStorageAdapter;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.StreamPublisherFactory;
import org.pragmatica.aether.stream.StreamingCoordinator;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.GovernorFailoverHandler;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.StreamPartitionRecovery;
import org.pragmatica.aether.stream.replication.WatermarkTracker;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.ttm.AdaptiveDecisionTree;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.worker.bootstrap.WorkerBootstrap;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager;
import org.pragmatica.aether.worker.governor.DecisionRelay;
import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.aether.worker.group.GroupMembershipTracker;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.CommunityScalingRequest;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.BuildInfo;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.ClusterSyncMessage;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.net.quic.PeerConnectivityReporter;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.forward.ForwardApplyRequest;
import org.pragmatica.cluster.node.forward.ForwardApplyResponse;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
// NOTE: cluster-wide TransportObservation referenced via FQCN
// (`org.pragmatica.consensus.topology.TransportObservation`) to avoid the simple-name
// collision with `org.pragmatica.swim.TransportObservation` (legacy SWIM-local hint type)
// that is imported below.
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicDisconnectListener;
import org.pragmatica.consensus.net.quic.QuicPeerStateListener;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.swim.AesGcmGossipEncryptor;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.RotatingGossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.TransportObservation;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface AetherNode extends ManageableNode {
    Logger LOG = LoggerFactory.getLogger(AetherNode.class);

    String VERSION = BuildInfo.version();

    NodeId self();
    Promise<Unit> start();
    Promise<Unit> stop();
    KVStore<AetherKey, AetherValue> kvStore();
    SliceStore sliceStore();
    ClusterSyncCollector metricsCollector();
    DeploymentMetricsCollector deploymentMetricsCollector();
    ControlLoop controlLoop();
    SliceInvoker sliceInvoker();
    InvocationHandler invocationHandler();
    BlueprintService blueprintService();
    MavenProtocolHandler mavenProtocolHandler();
    ArtifactStore artifactStore();
    TopologyManager topologyManager();
    InvocationMetricsCollector invocationMetrics();
    ClusterController controller();
    DeploymentManager deploymentManager();
    AbTestManager abTestManager();
    EndpointRegistry endpointRegistry();
    AlertManager alertManager();
    ObservabilityDepthRegistry observabilityDepthRegistry();
    InvocationTraceStore traceStore();
    LogLevelRegistry logLevelRegistry();
    Option<DynamicConfigManager> dynamicConfigManager();
    AppHttpServer appHttpServer();
    HttpRouteRegistry httpRouteRegistry();
    TTMManager ttmManager();
    RollbackManager rollbackManager();
    ComprehensiveSnapshotCollector snapshotCollector();
    ArtifactMetricsCollector artifactMetricsCollector();
    DeploymentMap deploymentMap();
    ClusterEventAggregator eventAggregator();
    BackupService backupService();
    StreamPartitionManager streamPartitionManager();
    StreamReadRouter streamReadRouter();
    ConsumerGroupCoordinator consumerGroupCoordinator();
    ConsumerGroupRegistry consumerGroupRegistry();
    TaskAssignmentCoordinator taskAssignmentCoordinator();
    Map<String, StorageFactory.StorageSetup> storageSetups();
    Option<CertificateRenewalScheduler> certRenewalScheduler();
    int connectedNodeCount();
    Map<String, Number> transportMetrics();
    Set<NodeId> connectedPeerIds();
    boolean isLeader();
    boolean isReady();
    Option<NodeId> leader();
    <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands);
    int managementPort();
    long uptimeSeconds();
    List<NodeId> initialTopology();
    TopologyConfig topologyConfig();
    @Contract void route(Message message);

    static Result<AetherNode> aetherNode(AetherNodeConfig config) {
        return aetherNode(config, () -> Runtime.getRuntime().halt(2));
    }

    /// Overload for single-JVM hosting (Forge / Ember). The `jvmExit` hook is invoked by
    /// the node's `SelfDrainCoordinator` when the drain phase completes — production passes
    /// `Runtime.getRuntime().halt(2)`, in-JVM hosts pass a per-node callback that stops the
    /// node gracefully and removes it from the host's registry without taking down the JVM.
    static Result<AetherNode> aetherNode(AetherNodeConfig config, Runnable jvmExit) {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var nodeCodec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        return aetherNode(config, delegateRouter, nodeCodec, jvmExit);
    }

    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec) {
        return aetherNode(config, delegateRouter, nodeCodec, () -> Runtime.getRuntime().halt(2));
    }

    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec,
                                         Runnable jvmExit) {
        return config.validate().flatMap(_ -> createNode(config, delegateRouter, nodeCodec, jvmExit));
    }

    private static Result<AetherNode> createNode(AetherNodeConfig config,
                                                 MessageRouter.DelegateRouter delegateRouter,
                                                 SliceCodec nodeCodec,
                                                 Runnable jvmExit) {
        Serializer serializer = nodeCodec;
        Deserializer deserializer = nodeCodec;
        var kvStore = new KVStore<AetherKey, AetherValue>(delegateRouter, serializer, deserializer);
        var dhtStorage = MemoryStorageEngine.memoryStorageEngine();
        var dhtRing = ConsistentHashRing.<NodeId>consistentHashRing();
        dhtRing.addNode(config.self());
        config.topology().coreNodes()
                       .forEach(peer -> dhtRing.addNode(peer.id()));
        var dhtNode = DHTNode.dhtNode(config.self(), dhtStorage, dhtRing, config.artifactRepo());
        var sliceRegistry = SliceRegistry.sliceRegistry();
        var deferredInvoker = DeferredSliceInvokerFacade.deferredSliceInvokerFacade();
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(),
                                               config.topology(),
                                               config.activationGated(),
                                               config.clusterFormation());
        var rabiaMetricsCollector = RabiaMetricsCollector.rabiaMetricsCollector();
        var networkMetricsHandler = NetworkMetricsHandler.networkMetricsHandler();
        var persistence = resolvePersistence(config);
        var leaderTerm = new AtomicLong(0L);
        Supplier<Long> rabiaTermSupplier = leaderTerm::get;
        Predicate<NodeId> isDecommissioned = nodeId -> kvStore.get(AetherKey.NodeLifecycleKey.nodeLifecycleKey(nodeId)).filter(v -> v instanceof AetherValue.NodeLifecycleValue)
                                                                  .map(v -> (AetherValue.NodeLifecycleValue) v)
                                                                  .map(v -> v.state() == AetherValue.NodeLifecycleState.DECOMMISSIONED)
                                                                  .or(false);
        Supplier<Option<NodeId>> currentLeaderFromKvSupplier = () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                      LeaderValue.class)
        .map(LeaderValue::leader);
        var hlcClock = HlcClock.hlcClock(config.self().id()).unwrap();
        var snapshotSource = KvBackedGenerationSnapshotSource.kvBackedGenerationSnapshotSource(kvStore);
        return RabiaNode.rabiaNode(nodeConfig,
                                   delegateRouter,
                                   kvStore,
                                   serializer,
                                   deserializer,
                                   rabiaMetricsCollector,
                                   true,
                                   persistence,
                                   config.quicTls(),
                                   rabiaTermSupplier,
                                   isDecommissioned,
                                   currentLeaderFromKvSupplier,
                                   snapshotSource,
                                   hlcClock::now)
        .flatMap(clusterNode -> assembleNode(config,
                                             delegateRouter,
                                             kvStore,
                                             sliceRegistry,
                                             deferredInvoker,
                                             clusterNode,
                                             rabiaMetricsCollector,
                                             networkMetricsHandler,
                                             serializer,
                                             deserializer,
                                             nodeCodec,
                                             dhtNode,
                                             leaderTerm,
                                             hlcClock,
                                             snapshotSource,
                                             jvmExit));
    }

    private static RabiaPersistence<KVCommand<AetherKey>> resolvePersistence(AetherNodeConfig config) {
        return config.backupConfig().filter(b -> !b.path().isBlank())
                                  .map(AetherNode::createGitBackedPersistence)
                                  .or(RabiaPersistence::inMemory);
    }

    private static RabiaPersistence<KVCommand<AetherKey>> createGitBackedPersistence(BackupConfig backup) {
        var backupDir = Path.of(backup.path());
        var remote = Option.option(backup.remote()).filter(s -> !s.isBlank());
        LoggerFactory.getLogger(AetherNode.class).info("Consensus persistence: git-backed at {}", backupDir);
        return RabiaPersistence.gitBacked(backupDir, remote, AetherNode::snapshotToBase64, AetherNode::base64ToSnapshot);
    }

    private static Result<String> snapshotToBase64(byte[] snapshot) {
        return Result.success(Base64.getEncoder().encodeToString(snapshot));
    }

    private static Result<byte[]> base64ToSnapshot(String encoded) {
        return Result.lift(Causes::fromThrowable,
                           () -> Base64.getDecoder().decode(encoded.trim()));
    }

    long DEFAULT_STREAM_RETENTION_MS = 24 * 60 * 60 * 1000L;

    long DEFAULT_STREAM_MEMORY_BYTES = 16 * 1024 * 1024L;

    private static StorageInstance createStreamStorage(Option<DHTClient> dhtClient) {
        var memoryTier = MemoryTier.memoryTier(DEFAULT_STREAM_MEMORY_BYTES);
        return dhtClient.map(client -> DhtStorageTier.dhtStorageTier(client, "stream-segments")).map(dht -> StorageInstance.storageInstance("streams",
                                                                                                                                            List.of(memoryTier,
                                                                                                                                                    dht)))
                            .or(StorageInstance.storageInstance("streams",
                                                                List.of(memoryTier)));
    }

    private static Result<AetherNode> assembleNode(AetherNodeConfig config,
                                                   MessageRouter.DelegateRouter delegateRouter,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   SliceRegistry sliceRegistry,
                                                   DeferredSliceInvokerFacade deferredInvoker,
                                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                   RabiaMetricsCollector rabiaMetricsCollector,
                                                   NetworkMetricsHandler networkMetricsHandler,
                                                   Serializer serializer,
                                                   Deserializer deserializer,
                                                   SliceCodec nodeCodec,
                                                   DHTNode dhtNode,
                                                   AtomicLong leaderTerm,
                                                   HlcClock hlcClock,
                                                   GenerationSnapshotSource snapshotSource,
                                                   Runnable jvmExit) {
        // Concrete adapter (not a lambda) so we can override `sendOutcome` and forward
        // it to the QUIC transport's tracked-write API. The default DHTNetwork impl
        // just calls `send` and reports Sent — but the DHT quorum collectors need the
        // real synchronous refusal signal to fail-fast against unreachable replicas
        // (see aether/docs/specs/dht-resilience-spec.md Layer 1/3).
        DHTNetwork dhtNetwork = new DHTNetwork() {
            @Override
            public void send(NodeId target, org.pragmatica.consensus.ProtocolMessage msg) {
                var _ = clusterNode.network().send(target, msg);
            }

            @Override
            public org.pragmatica.lang.Promise<org.pragmatica.consensus.net.WriteOutcome> sendOutcome(NodeId target, org.pragmatica.consensus.ProtocolMessage msg) {
                return clusterNode.network().sendOutcome(target, msg);
            }

            @Override
            public java.util.Set<NodeId> livePeers() {
                // Includes self and currently-connected peers. EVICTED peers are not
                // included — they're locally-believed-unreachable, so the DHT routes
                // around them until they reconcile back to CONNECTED via the SWIM /
                // reconciler path. The ring still holds them as owners, but quorum is
                // computed against the live intersection.
                var live = new java.util.HashSet<>(clusterNode.network().connectedPeers());
                live.add(config.self());
                return live;
            }
        };
        var dhtClient = DistributedDHTClient.distributedDHTClient(dhtNode, dhtNetwork, config.artifactRepo());
        var aetherMaps = AetherMaps.aetherMaps(dhtClient.scoped(DHTConfig.FULL));
        var cacheDhtClient = dhtClient.scoped(config.cache());
        var dhtClientOption = Option.<DHTClient>some(dhtClient);
        var storageSetups = StorageFactory.createAll(config.storageConfig(),
                                                     config.self().id(),
                                                     dhtClientOption);
        var artifactStorage = Option.option(storageSetups.get("artifacts")).map(StorageFactory.StorageSetup::instance)
                                           .or(StorageFactory.defaultArtifactStorage(dhtClientOption));
        var artifactStore = ArtifactStore.artifactStore(dhtClient, artifactStorage);
        var repositoryFactory = RepositoryFactory.repositoryFactory(artifactStore);
        var repositories = repositoryFactory.createAll(config.sliceConfig());
        var sharedLibraryLoader = createSharedLibraryLoader(config);
        var resourceProviderSetup = createResourceProviderFacade(config);
        var sliceStore = SliceStore.sliceStore(sliceRegistry,
                                               repositories,
                                               sharedLibraryLoader,
                                               deferredInvoker,
                                               resourceProviderSetup.facade(),
                                               config.sliceAction());
        var dhtRebalancer = DHTRebalancer.dhtRebalancer(dhtNode, dhtNetwork, config.artifactRepo());
        var dhtTopologyListener = DHTTopologyListener.dhtTopologyListener(dhtNode, dhtRebalancer);
        var dhtAntiEntropy = DHTAntiEntropy.dhtAntiEntropy(dhtNode, dhtNetwork, config.artifactRepo());
        var switchableCluster = SwitchableClusterNode.switchableClusterNode(clusterNode);
        var corePeerIds = config.topology().coreNodes()
                                         .stream()
                                         .map(NodeInfo::id)
                                         .filter(id -> !id.equals(config.self()))
                                         .collect(Collectors.toSet());
        var forwardingClusterNode = ForwardingClusterNode.forwardingClusterNode(clusterNode,
                                                                                clusterNode.network(),
                                                                                corePeerIds);
        record aetherNode(AetherNodeConfig config,
                          MessageRouter.DelegateRouter router,
                          KVStore<AetherKey, AetherValue> kvStore,
                          SliceRegistry sliceRegistry,
                          SliceStore sliceStore,
                          RabiaNode<KVCommand<AetherKey>> clusterNode,
                          SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                          NodeDeploymentManager nodeDeploymentManager,
                          ClusterDeploymentManager clusterDeploymentManager,
                          EndpointRegistry endpointRegistry,
                          HttpRouteRegistry httpRouteRegistry,
                          ClusterSyncCollector metricsCollector,
                          ClusterSyncScheduler metricsScheduler,
                          DeploymentMetricsCollector deploymentMetricsCollector,
                          DeploymentMetricsScheduler deploymentMetricsScheduler,
                          ControlLoop controlLoop,
                          SliceInvoker sliceInvoker,
                          InvocationHandler invocationHandler,
                          BlueprintService blueprintService,
                          MavenProtocolHandler mavenProtocolHandler,
                          ArtifactStore artifactStore,
                          InvocationMetricsCollector invocationMetrics,
                          DecisionTreeController controller,
                          DeploymentManager deploymentManager,
                          AbTestManager abTestManager,
                          AlertManager alertManager,
                          ObservabilityDepthRegistry observabilityDepthRegistry,
                          InvocationTraceStore traceStore,
                          LogLevelRegistry logLevelRegistry,
                          Option<DynamicConfigManager> dynamicConfigManager,
                          AppHttpServer appHttpServer,
                          TTMManager ttmManager,
                          RollbackManager rollbackManager,
                          ScheduledTaskManager scheduledTaskManager,
                          ComprehensiveSnapshotCollector snapshotCollector,
                          ArtifactMetricsCollector artifactMetricsCollector,
                          DeploymentMap deploymentMap,
                          ClusterEventAggregator eventAggregator,
                          BackupService backupService,
                          StreamPartitionManager streamPartitionManager,
                          StreamReadRouter streamReadRouter,
                          ConsumerGroupCoordinator consumerGroupCoordinator,
                          ConsumerGroupRegistry consumerGroupRegistry,
                          TaskAssignmentCoordinator taskAssignmentCoordinator,
                          TaskGroupAssignmentRegistry taskGroupAssignmentRegistry,
                          Map<String, StorageFactory.StorageSetup> storageSetups,
                          ClusterTopologyManager clusterTopologyManagerInstance,
                          EventLoopMetricsCollector eventLoopMetricsCollector,
                          CoreSwimHealthDetector swimHealthDetector,
                          Supplier<Option<ClusterGenerationSnapshot>> generationSnapshotSupplier,
                          Runnable refreshGenerationSnapshot,
                          Option<ManagementServer> managementServer,
                          Option<DiscoveryProvider> discoveryProvider,
                          Option<CertificateRenewalScheduler> certRenewalScheduler,
                          HealthSignalSink healthSignalSink,
                          LifecycleWriter lifecycleWriter,
                          org.pragmatica.aether.deployment.drain.InFlightRequestTracker inFlightRequestTracker,
                          org.pragmatica.aether.deployment.drain.DrainCoordinator drainCoordinator,
                          NodeLifecycle nodeLifecycle,
                          MembershipFsm membershipFsm,
                          HlcClock hlcClock,
                          Supplier<AetherValue.ClusterPhase> clusterPhaseSupplier,
                          long startTimeMs) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNode.class);

            @Override public NodeId self() {
                return config.self();
            }

            @Override public TopologyManager topologyManager() {
                return clusterNode.topologyManager();
            }

            @Override public Option<ClusterGenerationSnapshot> currentGenerationSnapshot() {
                return generationSnapshotSupplier.get();
            }

            @Override public void requestGenerationSnapshotRefresh() {
                refreshGenerationSnapshot.run();
            }

            @Override public Promise<Unit> start() {
                log.info("Starting Aether node {}", self());
                snapshotCollector.start();
                SliceRuntime.setSliceInvoker(sliceInvoker);
                certRenewalScheduler.onPresent(CertificateRenewalScheduler::start);
                return managementServer.map(ManagementServer::start).or(Promise.unitPromise())
                                           .flatMap(_ -> appHttpServer.start())
                                           .flatMap(_ -> startClusterAsync())
                                           .onSuccess(_ -> log.info("Aether node {} started, cluster forming...",
                                                                    self()));
            }

            @Override public Promise<Unit> stop() {
                log.info("Stopping Aether node {}", self());
                router.route(QuorumStateNotification.disappeared());
                router.quiesce();
                controlLoop.stop();
                metricsScheduler.stop();
                deploymentMetricsScheduler.stop();
                ttmManager.stop();
                scheduledTaskManager.stop();
                snapshotCollector.stop();
                SliceRuntime.clear();
                streamPartitionManager.close();
                certRenewalScheduler.onPresent(CertificateRenewalScheduler::stop);
                swimHealthDetector.stop();
                discoveryProvider.onPresent(this::deregisterFromDiscovery);
                return managementServer.map(ManagementServer::stop).or(Promise.unitPromise())
                                           .flatMap(_ -> appHttpServer.stop())
                                           .flatMap(_ -> sliceInvoker.stop())
                                           .flatMap(_ -> clusterNode.stop())
                                           .onSuccess(_ -> log.info("Aether node {} stopped",
                                                                    self()));
            }

            private Promise<Unit> startClusterAsync() {
                return clusterNode.start().onSuccess(_ -> {
                                                         log.info("Aether node {} cluster formation complete",
                                                                  self());
                                                         clusterNode.network().server()
                                                                            .onPresent(server -> {
                                                                                           eventLoopMetricsCollector.register(server.bossGroup());
                                                                                           eventLoopMetricsCollector.register(server.workerGroup());
                                                                                           log.info("Registered EventLoopGroups for metrics collection");
                                                                                       });
                                                         clusterNode.leaderManager().triggerElection();
                                                         discoveryProvider.onPresent(this::registerWithDiscovery);
                                                         applyNodeIdTag();
                                                     })
                                        .onSuccess(_ -> printStartupBanner())
                                        .onFailure(cause -> log.error("Cluster formation failed: {}",
                                                                      cause.message()));
            }

            private void registerWithDiscovery(DiscoveryProvider dp) {
                Option.from(config.topology().coreNodes()
                                           .stream()
                                           .filter(n -> n.id().equals(self()))
                                           .findFirst()).map(n -> new PeerInfo(n.address().host(),
                                                                               n.address().port(),
                                                                               Map.of("role",
                                                                                      "core",
                                                                                      "nodeId",
                                                                                      self().id())))
                           .onPresent(peerInfo -> dp.registerSelf(peerInfo).await()
                                                                 .onSuccess(_ -> log.info("Registered self with discovery provider"))
                                                                 .onFailure(cause -> log.warn("Failed to register with discovery: {}",
                                                                                              cause.message())));
            }

            private void applyNodeIdTag() {
                config.environment().flatMap(EnvironmentIntegration::compute)
                                  .onPresent(this::tagSelfInstance);
            }

            private void tagSelfInstance(ComputeProvider provider) {
                provider.listInstances().onSuccess(instances -> tagMatchingInstance(provider, instances))
                                      .onFailure(cause -> log.warn("Failed to list instances for self-tagging: {}",
                                                                   cause.message()));
            }

            private void tagMatchingInstance(ComputeProvider provider, List<InstanceInfo> instances) {
                findSelfInstance(instances).onPresent(instance -> provider.applyTags(instance.id(),
                                                                                     Map.of("aether-node-id",
                                                                                            self().id())).onSuccess(_ -> log.info("Applied aether-node-id tag to instance {}",
                                                                                                                                  instance.id()
                                                                                                                                             .value()))
                                                                                    .onFailure(cause -> log.warn("Failed to apply aether-node-id tag: {}",
                                                                                                                 cause.message())));
            }

            private Option<InstanceInfo> findSelfInstance(List<InstanceInfo> instances) {
                return selfAddress().flatMap(selfIp -> Option.from(instances.stream().filter(i -> i.addresses()
                                                                                                             .contains(selfIp))
                                                                                   .findFirst()));
            }

            private Option<String> selfAddress() {
                return Option.from(config.topology().coreNodes()
                                                  .stream()
                                                  .filter(n -> n.id().equals(self()))
                                                  .map(n -> n.address().host())
                                                  .findFirst());
            }

            private void deregisterFromDiscovery(DiscoveryProvider dp) {
                dp.stopWatching().await()
                               .onFailure(cause -> log.warn("Failed to stop discovery watching: {}",
                                                            cause.message()));
                dp.deregisterSelf().await()
                                 .onFailure(cause -> log.warn("Failed to deregister from discovery: {}",
                                                              cause.message()));
            }

            private void printStartupBanner() {
                var nodeId = self().id();
                var clusterPort = Option.from(config.topology().coreNodes()
                                                             .stream()
                                                             .filter(n -> n.id().equals(self()))
                                                             .findFirst()).map(n -> n.address().port())
                                             .or(0);
                var mgmtPort = config.managementPort();
                var appHttpPort = config.appHttp().enabled()
                                 ? config.appHttp().port()
                                 : 0;
                var peerCount = config.topology().coreNodes()
                                               .size();
                var ttmEnabled = ttmManager.isEnabled();
                var tlsEnabled = config.tls().isPresent();
                log.info("{}", "+-----------------------------------------------------------------+");
                log.info("{}", "|                     AETHER NODE v" + VERSION + "                       |");
                log.info("{}", "+-----------------------------------------------------------------+");
                log.info("|  Node ID:        {}", pad(nodeId, 46) + "|");
                log.info("|  Cluster Port:   {}", pad(String.valueOf(clusterPort), 46) + "|");
                if (mgmtPort > 0) {log.info("|  Management:     {}", pad("http://localhost:" + mgmtPort, 46) + "|");} else {log.info("|  Management:     {}",
                                                                                                                                     pad("disabled",
                                                                                                                                         46) + "|");}
                if (appHttpPort > 0) {log.info("|  App HTTP:       {}", pad("http://localhost:" + appHttpPort, 46) + "|");} else {log.info("|  App HTTP:       {}",
                                                                                                                                           pad("disabled",
                                                                                                                                               46) + "|");}
                log.info("|  Peers:          {}", pad(peerCount + " configured", 46) + "|");
                log.info("|  TTM:            {}", pad(ttmEnabled
                                                      ? "enabled"
                                                      : "disabled", 46) + "|");
                log.info("|  TLS:            {}", pad(tlsEnabled
                                                      ? "enabled"
                                                      : "disabled", 46) + "|");
                var discoveryEnabled = discoveryProvider.isPresent();
                log.info("|  Discovery:      {}", pad(discoveryEnabled
                                                      ? "enabled"
                                                      : "disabled", 46) + "|");
                log.info("{}", "+-----------------------------------------------------------------+");
                logCloudBootstrapHintIfNoStaticSeeds(peerCount);
            }

            private void logCloudBootstrapHintIfNoStaticSeeds(int peerCount) {
                if (peerCount > 0) {return;}
                log.info("No static seed peers configured — cluster requires operator bootstrap.");
                log.info("Run `aether cluster bootstrap <aether-cluster.toml>` after cloud nodes start.");
            }

            private static String pad(String value, int width) {
                if (value.length() >= width) {return value.substring(0, width);}
                return value + " ".repeat(width - value.length());
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return switchableCluster.apply(commands);
            }

            @Override public int connectedNodeCount() {
                return clusterNode.network().connectedNodeCount();
            }

            @Override public Map<String, Number> transportMetrics() {
                return clusterNode.network().transportMetrics();
            }

            @Override public Option<ClusterTopologyManager> clusterTopologyManager() {
                return Option.some(clusterTopologyManagerInstance);
            }

            @Override public Set<NodeId> connectedPeerIds() {
                // Use activePeers (CONNECTED + EVICTED) to match the quorum-counting
                // semantics inside QuicClusterNetwork.activeConnectedCount. Without this,
                // a brief EVICTED transition on any peer flickers the externally-visible
                // /api/cluster/topology.connectedPeerCount below quorum.
                return clusterNode.network().activePeers();
            }

            @Override public boolean isLeader() {
                return clusterNode.leaderManager().isLeader();
            }

            @Override public boolean isReady() {
                return clusterNode.isActive();
            }

            @Override public Option<NodeId> leader() {
                return clusterNode.leaderManager().leader();
            }

            @Override public int managementPort() {
                return config.managementPort();
            }

            @Override public int appHttpPort() {
                return config.appHttp().enabled()
                      ? config.appHttp().port()
                      : 0;
            }

            @Override public long uptimeSeconds() {
                return (System.currentTimeMillis() - startTimeMs) / 1000;
            }

            @Override public List<NodeId> initialTopology() {
                return config.topology().coreNodes()
                                      .stream()
                                      .map(NodeInfo::id)
                                      .toList();
            }

            @Override public TopologyConfig topologyConfig() {
                return config.topology();
            }

            @Override public void route(Message message) {
                router.route(message);
            }

            @Override
            public org.pragmatica.aether.deployment.membership.view.MembershipView membershipView() {
                // H.2 (spec §H): SWIM does not observe self — the local detector returns
                // only remote peers' health. Inject `self → HEALTHY` so the derived view
                // correctly reports the local node as ON_DUTY (assuming the node has
                // reached `NodeLifecycle.ACTIVE` and is serving requests; if the view is
                // queried during a self-shutdown window, KV operator overrides like
                // `DRAINING`/`DECOMMISSIONED` still take precedence per the view rules).
                //
                // RC1 Step 5: external accessor uses the strict factory so a minority-side
                // node reports empty `onDutyPeers()` instead of leaking local-SWIM-derived
                // claims that the majority has likely re-routed past. Quorum source is the
                // TopologyObserver's `quorumEstablished` AtomicBoolean — single truth.
                //
                // RC1 reachability-aggregator landing: the strict view also consults the
                // leader-broadcast cluster-canonical reachability snapshot to confirm
                // ON_DUTY status when local SWIM hasn't yet acked HEALTHY (closes the
                // per-reader-variance window without breaking the SWIM-faulty downgrade).
                // See aether/docs/specs/reachability-aggregator-spec.md Layer 5.
                return org.pragmatica.aether.deployment.membership.view.MembershipView.strict(
                        () -> {
                            var swim = swimHealthDetector.currentHealth()
                                                          .or(() -> org.pragmatica.swim.HealthSnapshot.healthSnapshot(java.util.Map.of()));
                            var merged = new java.util.HashMap<>(swim.peerHealth());
                            merged.putIfAbsent(config.self(), org.pragmatica.swim.SwimHealth.HEALTHY);
                            return org.pragmatica.lang.Option.some(org.pragmatica.swim.HealthSnapshot.healthSnapshot(merged));
                        },
                        consumer -> kvStore.forEach(AetherKey.NodeLifecycleKey.class,
                                                     AetherValue.NodeLifecycleValue.class,
                                                     consumer),
                        ((org.pragmatica.consensus.topology.TopologyObserver) clusterNode.topologyManager()).inQuorum(),
                        metricsCollector::bestSnapshot);
            }
        }
        var httpRoutePublisher = HttpRoutePublisher.httpRoutePublisher(config.self(), clusterNode);
        var invocationMetrics = InvocationMetricsCollector.invocationMetricsCollector();
        var logLevelRegistry = LogLevelRegistry.logLevelRegistry(clusterNode, kvStore);
        var depthRegistry = ObservabilityDepthRegistry.observabilityDepthRegistry(clusterNode,
                                                                                  kvStore,
                                                                                  config.observability());
        var traceStore = InvocationTraceStore.invocationTraceStore();
        var observabilityInterceptor = config.observability().depthThreshold() <0
                                      ? ObservabilityInterceptor.noOp()
                                      : createObservabilityInterceptor(config, traceStore, depthRegistry);
        var invocationHandler = InvocationHandler.invocationHandler(config.self(),
                                                                    clusterNode.network(),
                                                                    invocationMetrics,
                                                                    config.timeouts().invocation()
                                                                                   .timeout(),
                                                                    serializer,
                                                                    deserializer,
                                                                    httpRoutePublisher,
                                                                    observabilityInterceptor);
        var deploymentMetricsCollector = DeploymentMetricsCollector.deploymentMetricsCollector(config.self(),
                                                                                               clusterNode.network());
        var deploymentMetricsScheduler = DeploymentMetricsScheduler.deploymentMetricsScheduler(config.self(),
                                                                                               clusterNode.network(),
                                                                                               deploymentMetricsCollector);
        var initialTopology = config.topology().coreNodes()
                                             .stream()
                                             .map(NodeInfo::id)
                                             .toList();
        var schemaPolicy = SchemaPolicy.schemaPolicy();
        var schemaManager = AetherSchemaManager.aetherSchemaManager(schemaPolicy);
        var repository = compositeRepository(repositories);
        var connectionProvider = DatasourceConnectionProvider.datasourceConnectionProvider();
        var schemaOrchestrator = SchemaOrchestratorService.schemaOrchestratorService(clusterNode,
                                                                                     kvStore,
                                                                                     artifactStore,
                                                                                     repository,
                                                                                     schemaManager,
                                                                                     connectionProvider,
                                                                                     config.self(),
                                                                                     delegateRouter);
        var computeProvider = config.environment().flatMap(EnvironmentIntegration::compute);
        var lifecycleManager = NodeLifecycleManager.nodeLifecycleManager(computeProvider);
        var deploymentMap = DeploymentMap.deploymentMap();
        var healthSinkRef = new AtomicReference<HealthSignalSink>(HealthSignalSink.noop());
        HealthSignalSink stableHealthSink = signal -> healthSinkRef.get().emit(signal);
        var cdmSnapshotSupplierRef = new AtomicReference<Supplier<Option<ClusterGenerationSnapshot>>>(Option::none);
        Supplier<Option<ClusterGenerationSnapshot>> stableCdmSnapshotSupplier = () -> cdmSnapshotSupplierRef.get()
                                                                                                                .get();
        var clusterDeploymentManager = ClusterDeploymentManager.clusterDeploymentManager(config.self(),
                                                                                         clusterNode,
                                                                                         kvStore,
                                                                                         delegateRouter,
                                                                                         initialTopology,
                                                                                         clusterNode.topologyManager(),
                                                                                         config.atomicity(),
                                                                                         config.topology().coreMax(),
                                                                                         config.timeouts().deployment()
                                                                                                        .reconciliationInterval(),
                                                                                         schemaOrchestrator,
                                                                                         stableHealthSink,
                                                                                         stableCdmSnapshotSupplier);
        var loadBalancerManager = config.environment().flatMap(EnvironmentIntegration::loadBalancer)
                                                    .map(provider -> LoadBalancerManager.loadBalancerManager(config.self(),
                                                                                                             kvStore,
                                                                                                             clusterNode.topologyManager(),
                                                                                                             provider,
                                                                                                             config.appHttp()
                                                                                                                           .port()));
        var taskGroupActivator = TaskGroupActivator.taskGroupActivator(config.self(), clusterNode);
        var taskAssignmentCoordinator = TaskAssignmentCoordinator.taskAssignmentCoordinator(config.self(),
                                                                                            clusterNode,
                                                                                            kvStore,
                                                                                            clusterNode.topologyManager());
        var discoveryProvider = config.environment().flatMap(EnvironmentIntegration::discovery);
        var endpointRegistry = EndpointRegistry.endpointRegistry();
        var topicSubscriptionRegistry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        var scheduledTaskRegistry = ScheduledTaskRegistry.scheduledTaskRegistry();
        var scheduledTaskStateRegistry = ScheduledTaskStateRegistry.scheduledTaskStateRegistry();
        var httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();
        var metricsCollector = ClusterSyncCollector.clusterSyncCollector(config.self(),
                                                                         clusterNode.network(),
                                                                         config.timeouts().observability()
                                                                                        .metricsSlidingWindow()
                                                                                        .millis());
        metricsCollector.setInvocationMetricsProvider(invocationMetrics);
        metricsCollector.recordCustom("mgmt.port", config.managementPort());
        var peerObservationStore = org.pragmatica.aether.metrics.observation.PeerObservationStore.peerObservationStore();
        // Step 1: periodic-observation config (5s emission cadence, 15s aggregator
        // TTL, cap floor 64). The store's cap supplier is wired by ClusterSyncContext
        // and computes `max(capFloor, peers × 4)` per push — sized to handle one
        // full emission cycle (N-1 observations per node per period) plus per-peer
        // transition burst.
        var periodicConfig = PeriodicObservationConfig.defaultConfig();
        BooleanSupplier isLeaderSupplier = clusterNode.leaderManager()::isLeader;
        // Decision-plane peer set: KV-known nodes in non-terminal lifecycle states
        // (everything except DECOMMISSIONED), plus self. The aggregator MUST iterate
        // this set — not the QUIC-driven `topologyManager().topology()` — otherwise a
        // killed peer drops out of the self-fold the moment QUIC fires REMOVE, no
        // fresh UNREACHABLE observations are produced, prior entries age out in 15s,
        // and the dead peer becomes structurally invisible to the UNREACHABLE-quorum
        // gate (see reachability-aggregator-spec.md "Periodic Observation Mode").
        // Decision plane (KV) = "what to track"; observation plane (QUIC) =
        // "what state". Keeping them on separate sources is the architectural
        // invariant.
        Supplier<Set<NodeId>> kvTrackedPeersSupplier = () -> {
            var peers = new java.util.HashSet<NodeId>();
            peers.add(config.self());
            kvStore.forEach(AetherKey.NodeLifecycleKey.class,
                            AetherValue.NodeLifecycleValue.class,
                            (key, value) -> {
                                if (value.state() != AetherValue.NodeLifecycleState.DECOMMISSIONED) {
                                    peers.add(key.nodeId());
                                }
                            });
            return Set.copyOf(peers);
        };
        // ReachabilityAggregator: leader-side TTL+quorum aggregator producing the
        // cluster-canonical reachability snapshot broadcast in ClusterSyncPing.
        // Quorum threshold N = KV-canonical non-terminal peer count (stable across
        // chaos kills — DECOMMISSIONED is the only state that decrements N). TTL=15s
        // — 3× the 5s periodic emission cadence, ensures observations remain live
        // across 2-3 emission cycles before expiry. See
        // aether/docs/specs/reachability-aggregator-spec.md "Periodic Observation
        // Mode" subsection.
        var reachabilityAggregator = ReachabilityAggregator.reachabilityAggregator(
            config.self(),
            () -> kvTrackedPeersSupplier.get().size(),
            () -> clusterNode.network().connectedPeers(),
            kvTrackedPeersSupplier,
            System::currentTimeMillis,
            15_000L);
        // Wire the leader-side aggregator into metricsCollector so MembershipView's
        // bestSnapshot() reads the leader's OWN aggregator output (since the leader
        // doesn't receive pings, its lastReachabilitySnapshot() is forever none).
        // Gated on leader role: on followers the aggregator is fed by no pongs (only
        // leaders/spokesmen ingest), so returning its self-fold-only snapshot would
        // mislead the consumer — fall back to the cached received snapshot instead.
        metricsCollector.setLocalSnapshotSupplier(() -> isLeaderSupplier.getAsBoolean()
                                                        ? reachabilityAggregator.snapshot()
                                                        : Option.none());
        metricsCollector.setPongSignalFan(ClusterSyncPongSignalFan.clusterSyncPongSignalFan(stableHealthSink,
                                                                                            clusterNode.leaderManager()));
        Supplier<Long> rabiaTermSupplier = leaderTerm::get;
        Supplier<Epoch> leaderEpochSupplier = () -> Epoch.epoch(leaderTerm.get(), 0L);
        var projectorEarly = ClusterGenerationProjector.clusterGenerationProjector();
        var generationChangedSink = buildGenerationChangedSink(delegateRouter);
        Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier = () -> kvStore.getTyped(AetherKey.GenerationSnapshotKey.SINGLETON,
                                                                                              AetherValue.GenerationSnapshotValue.class)
        .map(AetherValue.GenerationSnapshotValue::snapshot);
        cdmSnapshotSupplierRef.set(snapshotSupplier);
        var metricsScheduler = ClusterSyncScheduler.clusterSyncScheduler(config.self(),
                                                                         clusterNode.network(),
                                                                         metricsCollector,
                                                                         config.timeouts().cluster()
                                                                                        .pingInterval(),
                                                                         rabiaTermSupplier,
                                                                         stableHealthSink,
                                                                         ClusterSyncScheduler.DEFAULT_PING_TIMEOUT_THRESHOLD,
                                                                         leaderEpochSupplier,
                                                                         peerObservationStore,
                                                                         reachabilityAggregator::snapshot,
                                                                         periodicConfig);
        // Step 1 (Periodic Observation Mode): cap supplier honored externally so
        // the buffer absorbs one full emission cycle (N-1 observations per 5s tick)
        // plus per-peer transition burst. Floor of 64 from periodicConfig accommodates
        // small clusters; topology × 4 scales with peer count. Set AFTER the scheduler
        // is constructed because ClusterSyncContext's own constructor pre-wires the
        // store's cap supplier to its internal `bufferCap()` formula — this call wins
        // and gives AetherNode the canonical sizing surface.
        peerObservationStore.setCapSupplier(() -> Math.max(periodicConfig.capFloor().getAsInt(),
                                                            clusterNode.topologyManager().topology().size() * 4));
        metricsCollector.addPongListener(pong -> metricsScheduler.onPongReceived(pong.sender()));
        // Leader/spokesman-gated aggregator ingest. Tier-1 pongs (from core members)
        // arrive when this node is the cluster leader; Tier-2 pongs (from governors)
        // arrive when this node is an active spokesman. Both feed the same
        // ReachabilityAggregator. On the not-leader→leader edge, reset and seed
        // from the cached snapshot received from the prior leader. See
        // reachability-aggregator-spec.md Layers 3-4 + 6.
        // SpokesmanPingLoop is constructed later in the wiring; forward-reference
        // via a settable holder, set after construction.
        var wasLeaderRef = new java.util.concurrent.atomic.AtomicBoolean(false);
        var spokesmanActiveRef = new java.util.concurrent.atomic.AtomicReference<BooleanSupplier>(() -> false);
        metricsCollector.addPongListener(pong -> {
            var nowLeader = isLeaderSupplier.getAsBoolean();
            var nowSpokesman = spokesmanActiveRef.get().getAsBoolean();
            var prev = wasLeaderRef.getAndSet(nowLeader);
            if (nowLeader && !prev) {
                reachabilityAggregator.reset();
                metricsCollector.lastReachabilitySnapshot().onPresent(reachabilityAggregator::seedFromCache);
            }
            if (!nowLeader && !nowSpokesman) {return;}
            reachabilityAggregator.ingest(pong.sender(), pong.peerConnectivity(), pong.peerHealth());
        });
        metricsCollector.setPeerObservationBuffer(peerObservationStore);
        Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader = () -> kvStore.get(AetherKey.ClusterConfigKey.CURRENT).filter(v -> v instanceof AetherValue.ClusterConfigValue)
                                                                                                .map(v -> (AetherValue.ClusterConfigValue) v);
        java.util.function.Function<NodeId, Option<AetherValue.NodeLifecycleValue>> lifecycleReader = nodeId -> kvStore.get(AetherKey.NodeLifecycleKey.nodeLifecycleKey(nodeId)).filter(v -> v instanceof AetherValue.NodeLifecycleValue)
                                                                                                                           .map(v -> (AetherValue.NodeLifecycleValue) v);
        java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier = commands -> clusterNode.apply(commands);
        var leaderAwareSnapshotSource = snapshotSource;
        // Drain infrastructure: tracker is shared with NodeLifecycleRoutes (/api/node/inflight).
        // ConsensusDrainCoordinator is constructed below once ctmLifecycleWriter exists.
        var inFlightTrackerForDrain = InFlightRequestTracker.inFlightRequestTracker();
        // Topology-observation refactor Step 5: node-side self-drain coordinator. Watches three
        // independent triggers (periodic 1Hz connectivity check, QuorumStateNotification.DISAPPEARED,
        // Rabia paused) and on the first one to fire kicks off an uninterruptible drain that
        // gates the in-flight tracker, awaits ≤ inflightGrace, then invokes `jvmExit`.
        // membership-architecture-spec.md §16.1 (S19/S20). No KV/consensus dependency — a
        // partition victim cannot use either anyway.
        //
        // `jvmExit` is threaded in from the factory: production is `Runtime.getRuntime().halt(2)`,
        // single-JVM hosts (Forge/Ember) pass a per-node callback that stops the node gracefully
        // and removes it from the host's registry without taking down the whole test JVM.
        //
        // T3.1: SELF_DRAIN_INITIATED cluster event surface. The `ClusterEventLogPublisher` is
        // constructed downstream (after rabiaTermSupplier and clusterCommandApplier are wired)
        // so we forward-declare it via an AtomicReference resolved lazily inside the publisher
        // lambda. The lambda is invoked only at the ACTIVE→DRAINING transition, by which time
        // the ref is always populated. NOT leader-gated: the draining node itself is the only
        // authoritative source for "I'm self-draining" — a partition victim cannot rely on the
        // leader to surface this on its behalf.
        var eventLogPublisherForDrainRef = new AtomicReference<ClusterEventLogPublisher>();
        org.pragmatica.aether.deployment.drain.SelfDrainEventPublisher selfDrainEventSink = (type, severity, message, details) -> {
            var publisher = eventLogPublisherForDrainRef.get();
            if (publisher == null) {return;}
            publisher.publish(type, severity, message, details);
        };
        var selfDrainCoordinator = SelfDrainCoordinator.selfDrainCoordinator(
                config.self(),
                () -> clusterNode.network().connectedPeers(),
                () -> clusterNode.topologyManager().topology().size(),
                inFlightTrackerForDrain,
                SelfDrainConfig.selfDrainConfig(),
                jvmExit,
                selfDrainEventSink);
        SharedScheduler.scheduleAtFixedRate(
                selfDrainCoordinator::onConnectivityChange,
                TimeSpan.timeSpan(1).seconds(),
                TimeSpan.timeSpan(1).seconds());
        java.util.function.Supplier<java.util.Map<AetherKey.ProvisioningSlotKey, AetherValue.ProvisioningSlotValue>> slotReader = () -> {
            var collected = new java.util.LinkedHashMap<AetherKey.ProvisioningSlotKey, AetherValue.ProvisioningSlotValue>();
            kvStore.forEach(AetherKey.ProvisioningSlotKey.class, AetherValue.ProvisioningSlotValue.class, collected::put);
            return collected;
        };
        Supplier<Option<AetherValue.ClusterPhase>> clusterPhaseReader = () -> kvStore.get(AetherKey.ClusterPhaseKey.SINGLETON).filter(v -> v instanceof AetherValue.ClusterPhaseValue)
                                                                                         .map(v -> ((AetherValue.ClusterPhaseValue) v).phase());
        Supplier<Integer> onDutyCountSupplier = () -> {
            var counter = new java.util.concurrent.atomic.AtomicInteger();
            kvStore.forEach(AetherKey.NodeLifecycleKey.class,
                            AetherValue.NodeLifecycleValue.class,
                            (_, value) -> {
                                if (value.state() == AetherValue.NodeLifecycleState.ON_DUTY) {counter.incrementAndGet();}
                            });
            return counter.get();
        };
        Supplier<Option<NodeId>> healthLeaderSupplier = () -> clusterNode.leaderManager().leader();
        // E.6 / E.8 (spec §7): ClusterPhase derived view is the single source of truth for
        // cluster phase. The KV value is consulted as a cache hint to track the
        // ever-reached-NORMAL bit across leader takeovers — see ClusterPhaseView javadoc.
        // H.2b (spec §H): ClusterPhaseView reads through MembershipView so SWIM-derived
        // ON_DUTY peers (no KV entry) count toward quorum. The SWIM detector is constructed
        // downstream of this declaration — use a forward AtomicReference and lazy-resolve at
        // each compute() call. Until the detector lands the view falls back to KV-only.
        var swimDetectorRefForPhase = new AtomicReference<CoreSwimHealthDetector>();
        // RC1 Step 5: ClusterPhase consumes the strict view so a minority-side partition
        // computes COLD_BOOT/RECOVERING (zero on-duty peers under the quorum threshold)
        // instead of falsely claiming NORMAL from local-SWIM observations. Same quorum
        // source as `aetherNode.membershipView()`.
        var phaseInQuorum = ((org.pragmatica.consensus.topology.TopologyObserver) clusterNode.topologyManager()).inQuorum();
        ClusterPhaseView.MembershipViewReader phaseMembershipReader = () -> MembershipView.strict(
                () -> {
                    // H.2 (spec §H): mirror `aetherNode.membershipView()` self-injection — SWIM
                    // doesn't observe self, but the phase calculation must count this node
                    // toward quorum.
                    var swimOpt = Option.option(swimDetectorRefForPhase.get())
                                         .flatMap(CoreSwimHealthDetector::currentHealth);
                    var swim = swimOpt.or(() -> org.pragmatica.swim.HealthSnapshot.healthSnapshot(java.util.Map.of()));
                    var merged = new java.util.HashMap<>(swim.peerHealth());
                    merged.putIfAbsent(config.self(), org.pragmatica.swim.SwimHealth.HEALTHY);
                    return Option.some(org.pragmatica.swim.HealthSnapshot.healthSnapshot(merged));
                },
                consumer -> kvStore.forEach(AetherKey.NodeLifecycleKey.class,
                                             AetherValue.NodeLifecycleValue.class,
                                             consumer),
                phaseInQuorum);
        var clusterPhaseView = ClusterPhaseView.clusterPhaseView(config.topology().coreNodes().size(),
                                                                  org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds(),
                                                                  org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds(),
                                                                  phaseMembershipReader,
                                                                  clusterPhaseReader,
                                                                  () -> healthLeaderSupplier.get().isPresent());
        Supplier<AetherValue.ClusterPhase> effectivePhaseSupplier = () -> clusterPhaseView.compute(System.currentTimeMillis());
        // Direct lifecycle writes for transitions not owned by the FSM operator-event path
        // (requestActivate, requestFailedDrain) and for CTM-initiated drain/decommission
        // (which the FSM does not yet own — those still bypass FSM-driven InvokeDrain).
        var ctmLifecycleWriter = LifecycleWriter.directLifecycleWriter(lifecycleReader::apply, clusterCommandApplier);
        org.pragmatica.lang.Functions.Fn1<Promise<Integer>, NodeId> inFlightProbe = targetNodeId ->
                targetNodeId.equals(config.self().id())
                        ? Promise.success(inFlightTrackerForDrain.count())
                        : Promise.success(0);
        var drainCoordinator = org.pragmatica.aether.deployment.drain.ConsensusDrainCoordinator
                .consensusDrainCoordinator(ctmLifecycleWriter, lifecycleReader::apply, inFlightProbe);
        // MembershipFsm wiring (spec §9 — post-E.8 always active). Constructed AFTER
        // drainCoordinator so the FSM can route InvokeDrain effects through the real coordinator.
        // Step 4: also wired with `reachabilityAggregator::snapshot` so the reducer can apply
        // the aggregator-quorum gate at the two ON_DUTY decommission cells (S04/S05/S13/S17).
        var membershipFsm = buildMembershipFsm(config.self(),
                                                kvStore,
                                                clusterCommandApplier,
                                                drainCoordinator,
                                                isLeaderSupplier,
                                                hlcClock,
                                                reachabilityAggregator);
        membershipFsm.start();
        // Topology-observation refactor Step 3: wire the leader-side aggregator's snapshot
        // stream into the FSM. The aggregator invokes this listener synchronously after each
        // non-empty `snapshot()` build (driven by the periodic ping-build path). MembershipFsm
        // re-leader-gates internally and translates the snapshot into per-peer
        // TransportReachable / TransportUnreachable events. Registered AFTER both the
        // aggregator and FSM are constructed (and the FSM started) so the wiring is complete
        // before the first snapshot can fire.
        reachabilityAggregator.addSnapshotListener(membershipFsm::onTransportSnapshot);
        var clusterTopologyManager = ClusterTopologyManager.clusterTopologyManager((org.pragmatica.consensus.topology.TopologyObserver) clusterNode.topologyManager(),
                                                                                   lifecycleManager,
                                                                                   config.autoHeal(),
                                                                                   deploymentMap,
                                                                                   leaderAwareSnapshotSource,
                                                                                   clusterConfigReader,
                                                                                   lifecycleReader,
                                                                                   slotReader,
                                                                                   clusterCommandApplier,
                                                                                   drainCoordinator,
                                                                                   ctmLifecycleWriter,
                                                                                   effectivePhaseSupplier);
        // Post-E.8 phase-change publisher. ClusterPhaseView computes the phase on each call;
        // CTM needs the edge-triggered `onClusterPhaseChanged` callback to reset the
        // provisioning circuit + stability marker on COLD_BOOT → NORMAL. Poll the derived
        // view periodically and dispatch on change.
        schedulePhaseChangeWatcher(effectivePhaseSupplier, clusterTopologyManager);
        var controller = DecisionTreeController.decisionTreeController(config.controllerConfig());
        var blueprintService = BlueprintService.blueprintService(clusterNode, kvStore, repository, artifactStore);
        var mavenProtocolHandler = MavenProtocolHandler.mavenProtocolHandler(artifactStore);
        var deploymentManager = DeploymentManager.deploymentManager(clusterNode, kvStore);
        var alertManager = AlertManager.alertManager(clusterNode, kvStore);
        var dynamicConfigManager = resourceProviderSetup.dynamicProvider()
                                                                        .map(dp -> DynamicConfigManager.dynamicConfigManager(clusterNode,
                                                                                                                             kvStore,
                                                                                                                             dp,
                                                                                                                             config.self()));
        var minuteAggregator = MinuteAggregator.minuteAggregator();
        var gcMetricsCollector = GCMetricsCollector.gcMetricsCollector();
        var eventLoopMetricsCollector = EventLoopMetricsCollector.eventLoopMetricsCollector(config.timeouts().observability()
                                                                                                           .eventLoopProbe()
                                                                                                           .millis());
        var snapshotCollector = ComprehensiveSnapshotCollector.comprehensiveSnapshotCollector(gcMetricsCollector,
                                                                                              eventLoopMetricsCollector,
                                                                                              networkMetricsHandler,
                                                                                              rabiaMetricsCollector,
                                                                                              invocationMetrics,
                                                                                              minuteAggregator);
        var artifactMetricsCollector = ArtifactMetricsCollector.artifactMetricsCollector(artifactStore);
        // RC1 Step 1 — cluster-scoped replicated event log wiring.
        //
        // `eventLogPublisher` writes each producer-emitted event into the replicated KV
        // `(ClusterEventLogKey, ClusterEventValue)` family. Rabia commit order is the
        // canonical total order. `eventLogSweeper` GCs old events on the leader, gated on
        // `TopologyObserver.inQuorum()` so a minority-side leader cannot delete events the
        // majority retains.
        var eventLogPublisher = ClusterEventLogPublisher.clusterEventLogPublisher(config.self(),
                                                                                    hlcClock,
                                                                                    rabiaTermSupplier::get,
                                                                                    clusterCommandApplier);
        // T3.1: surface the publisher to the SelfDrainCoordinator's lazy-resolving lambda
        // forward-declared upstream. By this point the publisher is fully wired; the
        // coordinator's lambda will resolve the ref on the first ACTIVE→DRAINING transition.
        eventLogPublisherForDrainRef.set(eventLogPublisher);
        var eventAggregator = ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                                            clusterTopologyManager.observer()::clusterSize,
                                                                            eventLogPublisher,
                                                                            isLeaderSupplier);
        // OB1 (investigator round 2): operator-driven inject endpoints must replicate via the
        // cluster-scoped event log so peer nodes return injected items on cross-node reads.
        // Bind publisher + cluster-event reader to both inject surfaces (AlertManager,
        // InvocationTraceStore). Local node-local maps remain authoritative for the originator;
        // peers UNION via the projected `ALERT_INJECTED` / `TRACE_INJECTED` events, dedup by id.
        alertManager.bindEventLogPublisher(eventLogPublisher::publish);
        alertManager.bindClusterEventsSource(eventAggregator::events);
        traceStore.bindEventLogPublisher(eventLogPublisher::publish);
        traceStore.bindClusterEventsSource(() -> projectClusterTraceInjections(eventAggregator.events()));
        var eventLogSweeper = ClusterEventLogSweeper.clusterEventLogSweeper(kvStore::snapshot,
                                                                              isLeaderSupplier,
                                                                              ((org.pragmatica.consensus.topology.TopologyObserver) clusterNode.topologyManager()).inQuorum(),
                                                                              rabiaTermSupplier::get,
                                                                              clusterCommandApplier);
        eventLogSweeper.start();
        var ttmManager = TTMManager.ttmManager(config.ttm(),
                                               minuteAggregator,
                                               controller::configuration)
        .or(TTMManager.noOp(config.ttm()));
        ClusterController effectiveController = ttmManager.isEnabled()
                                               ? AdaptiveDecisionTree.adaptiveDecisionTree(controller, ttmManager)
                                               : controller;
        var controlLoop = ControlLoop.controlLoop(config.self(),
                                                  effectiveController,
                                                  metricsCollector,
                                                  Option.some(invocationMetrics),
                                                  clusterNode,
                                                  kvStore,
                                                  config.controllerConfig().scalingConfig()
                                                                                           .evaluationInterval(),
                                                  config.controllerConfig(),
                                                  delegateRouter::route);
        var rollbackManager = config.rollback().enabled()
                             ? RollbackManager.rollbackManager(config.self(),
                                                               config.rollback(),
                                                               clusterNode,
                                                               kvStore,
                                                               clusterNode.leaderManager())
                             : RollbackManager.disabled();
        var abTestManager = AbTestManager.abTestManager(clusterNode, kvStore, invocationMetrics);
        var sliceInvoker = SliceInvoker.sliceInvoker(config.self(),
                                                     clusterNode.network(),
                                                     endpointRegistry,
                                                     invocationHandler,
                                                     serializer,
                                                     deserializer,
                                                     config.timeouts().invocation()
                                                                    .invokerTimeout()
                                                                    .millis(),
                                                     config.timeouts().observability()
                                                                    .invocationCleanup()
                                                                    .millis(),
                                                     deploymentManager,
                                                     observabilityInterceptor);
        deferredInvoker.setDelegate(sliceInvoker);
        var scheduledTaskManager = ScheduledTaskManager.scheduledTaskManager(scheduledTaskRegistry,
                                                                             sliceInvoker,
                                                                             config.self(),
                                                                             command -> clusterNode.apply(List.of(command)),
                                                                             clusterNode.leaderManager());
        resourceProviderSetup.spiProvider()
                                         .onPresent(spi -> registerRuntimeExtensions(spi,
                                                                                     topicSubscriptionRegistry,
                                                                                     sliceInvoker,
                                                                                     cacheDhtClient));
        var selfAddress = findSelfAddress(config);
        var nodeDeploymentManager = NodeDeploymentManager.nodeDeploymentManagerFromSnapshot(config.self(),
                                                                                            selfAddress,
                                                                                            delegateRouter,
                                                                                            sliceStore,
                                                                                            clusterNode,
                                                                                            kvStore,
                                                                                            invocationHandler,
                                                                                            config.sliceAction(),
                                                                                            nodeCodec,
                                                                                            Option.some(httpRoutePublisher),
                                                                                            Option.some(sliceInvoker),
                                                                                            config.timeouts().deployment()
                                                                                                           .activationChain(),
                                                                                            config.timeouts().deployment()
                                                                                                           .transitionRetryDelay(),
                                                                                            snapshotSupplier);
        var serverBossGroup = clusterNode.network().server()
                                                 .map(org.pragmatica.net.tcp.Server::bossGroup);
        var serverWorkerGroup = clusterNode.network().server()
                                                   .map(org.pragmatica.net.tcp.Server::workerGroup);
        var taskGroupAssignmentRegistry = TaskGroupAssignmentRegistry.taskGroupAssignmentRegistry(kvStore);
        var appHttpServer = AppHttpServer.appHttpServer(config.appHttp(),
                                                        config.timeouts().forwarding(),
                                                        config.self(),
                                                        httpRouteRegistry,
                                                        Option.some(httpRoutePublisher),
                                                        Option.some(clusterNode.network()),
                                                        Option.some(serializer),
                                                        Option.some(deserializer),
                                                        config.tls(),
                                                        Option.some(invocationMetrics),
                                                        serverBossGroup,
                                                        serverWorkerGroup,
                                                        Option.some(deploymentManager),
                                                        Option.empty(),
                                                        Option.some(taskGroupAssignmentRegistry::ownerFor));
        taskGroupActivator.register(metricsScheduler);
        taskGroupActivator.register(deploymentMetricsScheduler);
        taskGroupActivator.register(controlLoop);
        taskGroupActivator.register(ttmManager);
        taskGroupActivator.register(rollbackManager);
        taskGroupActivator.register(deploymentManager);
        taskGroupActivator.register(abTestManager);
        taskGroupActivator.register(clusterDeploymentManager);
        loadBalancerManager.onPresent(taskGroupActivator::register);
        taskGroupActivator.register(DelegatedStorageAdapter.noOp());
        var consumerGroupRegistry = ConsumerGroupRegistry.consumerGroupRegistry();
        var consumerGroupCoordinator = ConsumerGroupCoordinator.consumerGroupCoordinator(clusterNode);
        var managementServerRef = new java.util.concurrent.atomic.AtomicReference<Option<ManagementServer>>(Option.empty());
        var aetherEntries = collectRouteEntries(kvStore,
                                                nodeDeploymentManager,
                                                clusterDeploymentManager,
                                                endpointRegistry,
                                                topicSubscriptionRegistry,
                                                scheduledTaskRegistry,
                                                scheduledTaskStateRegistry,
                                                scheduledTaskManager,
                                                httpRouteRegistry,
                                                metricsCollector,
                                                metricsScheduler,
                                                deploymentMetricsCollector,
                                                deploymentMetricsScheduler,
                                                controlLoop,
                                                sliceInvoker,
                                                invocationHandler,
                                                alertManager,
                                                depthRegistry,
                                                logLevelRegistry,
                                                dynamicConfigManager,
                                                ttmManager,
                                                rabiaMetricsCollector,
                                                deploymentManager,
                                                abTestManager,
                                                rollbackManager,
                                                artifactMetricsCollector,
                                                deploymentMap,
                                                eventAggregator,
                                                clusterNode.leaderManager(),
                                                appHttpServer,
                                                loadBalancerManager,
                                                (TopologyObserver) clusterNode.topologyManager(),
                                                clusterTopologyManager,
                                                taskGroupActivator,
                                                taskAssignmentCoordinator,
                                                taskGroupAssignmentRegistry,
                                                consumerGroupCoordinator,
                                                consumerGroupRegistry,
                                                membershipFsm,
                                                selfDrainCoordinator,
                                                managementServerRef);
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetRequest.class,
                                                    request -> dhtNode.handleGetRequest(request,
                                                                                        response -> dhtNetwork.send(request.sender(),
                                                                                                                    response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutRequest.class,
                                                    request -> dhtNode.handlePutRequest(request,
                                                                                        response -> handleRemotePutResponse(dhtNetwork,
                                                                                                                            aetherMaps,
                                                                                                                            request,
                                                                                                                            response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveRequest.class,
                                                    request -> dhtNode.handleRemoveRequest(request,
                                                                                           response -> handleRemoteRemoveResponse(dhtNetwork,
                                                                                                                                  aetherMaps,
                                                                                                                                  request,
                                                                                                                                  response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsRequest.class,
                                                    request -> dhtNode.handleExistsRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetResponse.class, dhtClient::onGetResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutResponse.class, dhtClient::onPutResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveResponse.class, dhtClient::onRemoveResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.ExistsResponse.class, dhtClient::onExistsResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestRequest.class,
                                                    request -> dhtNode.handleDigestRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
                                                                                                                       response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.DigestResponse.class, dhtAntiEntropy::onDigestResponse));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataRequest.class,
                                                    request -> dhtNode.handleMigrationDataRequest(request,
                                                                                                  response -> dhtNetwork.send(request.sender(),
                                                                                                                              response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataResponse.class,
                                                    dhtAntiEntropy::onMigrationDataResponse));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                                    dhtTopologyListener::onNodeJoined));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                                    dhtTopologyListener::onNodeRemoved));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                                    dhtTopologyListener::onNodeDecommissioned));
        // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
        aetherEntries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                                    dhtTopologyListener::onSelfShutdown));
        // PeerDisconnected → DHT-ring prune is intentionally NOT wired. The naive variant
        // (commit `d3e54717e`, reverted here) pruned aggressively on every transient QUIC
        // drop, which triggered a rebalance storm under sustained write pressure (16-chunk
        // 1MB artifact fan-out): each chunk replication targeting a freshly-pruned peer ran
        // through a re-replication pass that saturated QUIC, which in turn caused
        // `writeIfWritable` watermark drops, which manifest as the 1MB-push hang we tried
        // to fix. Consensus-driven `MembershipDecision.NodeRemoved` (wired via
        // `dhtTopologyListener::onNodeRemoved` above) IS the correct ring-prune trigger:
        // it fires only after the cluster has agreed the node is gone, not on every QUIC
        // flap. Future work: bound transport-level pruning by an observation window
        // (e.g., 30s of unbroken PeerDisconnected with no intervening ConnectionEstablished)
        // before pruning, so genuinely-dead peers eventually leave the ring without the
        // every-flap storm.
        @SuppressWarnings({"unchecked", "rawtypes"}) MessageRouter.Entry forwardRequestRoute = MessageRouter.Entry.route(ForwardApplyRequest.class,
                                                                                                                         (ForwardApplyRequest request) -> handleForwardApplyRequest(request,
                                                                                                                                                                                    clusterNode));
        aetherEntries.add(forwardRequestRoute);
        @SuppressWarnings({"unchecked", "rawtypes"}) MessageRouter.Entry forwardResponseRoute = MessageRouter.Entry.route(ForwardApplyResponse.class,
                                                                                                                          forwardingClusterNode::onForwardApplyResponse);
        aetherEntries.add(forwardResponseRoute);
        var growthLog = LoggerFactory.getLogger(AetherNode.class);
        var selfId = config.self();
        var rotatingEncryptor = createGossipEncryptor(config);
        var gossipKeyRotationHandler = GossipKeyRotationHandler.gossipKeyRotationHandler(rotatingEncryptor);
        var activationKvRouter = KVNotificationRouter.<AetherKey, AetherValue>builder(AetherKey.class)
                                                     .onPut(AetherKey.ActivationDirectiveKey.class,
                                                            (ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put) -> handleActivationDirective(put,
                                                                                                                                                                                selfId,
                                                                                                                                                                                clusterNode,
                                                                                                                                                                                switchableCluster,
                                                                                                                                                                                forwardingClusterNode,
                                                                                                                                                                                config,
                                                                                                                                                                                delegateRouter,
                                                                                                                                                                                kvStore,
                                                                                                                                                                                sliceStore,
                                                                                                                                                                                sliceInvoker,
                                                                                                                                                                                growthLog))
                                                     .onPut(AetherKey.GossipKeyRotationKey.class,
                                                            gossipKeyRotationHandler::onGossipKeyRotationPut)
                                                     .build();
        var allEntries = new ArrayList<>(clusterNode.routeEntries());
        allEntries.addAll(aetherEntries);
        allEntries.addAll(activationKvRouter.asRouteEntries());
        var swimTimeouts = config.timeouts().swim();
        var swimConfig = SwimConfig.fromTimeouts(swimTimeouts.period(),
                                                 swimTimeouts.probeTimeout(),
                                                 swimTimeouts.suspectTimeout())
                                   .withSwimPortOffset(CoreSwimHealthDetector.SWIM_PORT_OFFSET);
        // Phase-aware SWIM cold-boot suppression (D.3, 2026-05-11). SWIM suppresses
        // FAULTY-for-never-HEALTHY peers ONLY while the cluster is in `COLD_BOOT`
        // (initial formation, never reached quorum). In `NORMAL` and `RECOVERING`,
        // FAULTY edges always emit — the `RECOVERING` branch is the critical fix
        // for compose-restart: peers were Healthy in the prior NORMAL period and a
        // post-restart kill must produce `FaultyObserved` (drives DECOMMISSIONED
        // write + NODE_LEFT / NODE_FAILED downstream event).
        // E.6 / E.8 (spec §7.2): SWIM phase-suppression gate routes through `ClusterPhaseView`
        // (the single source of truth post-E.8).
        BooleanSupplier swimIsBootingSupplier = () -> effectivePhaseSupplier.get() == AetherValue.ClusterPhase.COLD_BOOT;
        // Leader-faulty evictor (2026-05-09): bridges SWIM-FAULTY → QUIC disconnect when
        // the FAULTY peer IS the current cluster leader. Breaks the consensus.apply
        // broadcast stall on cloud Container kill-leader (post-Step-3 architecture
        // otherwise depends on consensus to evict, but consensus is what's stuck).
        // Narrow trigger preserves Step 3's removal of the N+1 fan-out cascade for
        // general FAULTY peers. See SwimHealthContext.faultyLeaderEvictor field doc.
        java.util.function.Consumer<NodeId> faultyLeaderEvictor = peer ->
            clusterNode.network().disconnect(
                new org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode(peer));
        var swimHealthDetector = CoreSwimHealthDetector.coreSwimHealthDetector(delegateRouter,
                                                                               config.topology(),
                                                                               serializer,
                                                                               deserializer,
                                                                               stableHealthSink,
                                                                               leaderEpochSupplier,
                                                                               isLeaderSupplier,
                                                                               peerObservationStore,
                                                                               swimConfig,
                                                                               swimIsBootingSupplier,
                                                                               faultyLeaderEvictor);
        swimDetectorRefForPhase.set(swimHealthDetector);
        // RC1 (S01 fix) — wire the SWIM-backed liveness check for owner-broadcast eviction
        // hints. Followers REFUSE to act on the owner's `ClusterSyncPing.evictionHints` for
        // peers SWIM observes as HEALTHY; the owner's hint is a SUGGESTION, not authority.
        // SWIM HEALTHY requires actual probe-ack within `suspectTimeout`, so this gate is
        // robust against owner partial-network errors. See `ClusterSyncCollector.processEvictionHints`.
        metricsCollector.setPeerLocallyAlive(nodeId -> swimHealthDetector.healthOf(nodeId) == SwimHealth.HEALTHY);
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> swimHealthDetector.onLeaderChanged(change.leaderId())));
        var announceTopology = config.topology();
        var selfNodeInfo = announceTopology.coreNodes().stream()
                                          .filter(n -> n.id().equals(announceTopology.self()))
                                          .findFirst().orElse(null);
        var swimSeeds = announceTopology.coreNodes().stream()
                                        .filter(n -> !n.id().equals(announceTopology.self()))
                                        .map(n -> InetSocketAddress.createUnresolved(n.address().host(),
                                                                                      n.address().port() + CoreSwimHealthDetector.SWIM_PORT_OFFSET))
                                        .toList();
        var quorumThreshold = announceTopology.coreNodes().size() / 2 + 1;
        Runnable announceJoinTrigger = selfNodeInfo == null ? () -> {} : () ->
            swimHealthDetector.announceJoin(selfNodeInfo, swimConfig.clusterName(), System.currentTimeMillis(),
                                            swimSeeds, () -> clusterNode.network().connectedNodeCount() + 1 >= quorumThreshold);
        allEntries.add(MessageRouter.Entry.route(QuorumStateNotification.class,
                                                 notification -> startSwimOnQuorum(notification,
                                                                                   swimHealthDetector,
                                                                                   clusterNode.network(),
                                                                                   rotatingEncryptor,
                                                                                   announceJoinTrigger)));
        swimHealthDetector.addObservationListener(membershipFsm::onSwimObservation);
        membershipFsm.setSwimHealthGate(nodeId -> swimHealthDetector.healthOf(nodeId) == SwimHealth.HEALTHY);
        // SwimProtocol → router wire-up: SWIM-detected FAULTY peers are forwarded to the
        // cluster-wide `TransportObservation` stream so subscribers (LeaderManager,
        // ClusterFsmRouter, etc.) reach all `TransportObservation.PeerObservedFaulty` edges.
        swimHealthDetector.addTransportObservationEmitter(delegateRouter::route);
        // RC1-9 audit Step 4: ClusterEventAggregator no longer subscribes to SWIM
        // observations directly. NODE_FAILED / NODE_LEFT events are emitted only via
        // `onNodeLifecyclePut` (the leader FSM writing DECOMMISSIONED to
        // KV-Store with prior-state context). The SWIM-witnessed duplicate emit was
        // amplifying the membership-tracker cascade audit identified.
        // RC1-9 audit Step 3: the SWIM-FAULTY-to-disconnect short-circuit lambda is
        // gone. QUIC eviction now flows from `MembershipDecision.NodeRemoved`
        // (published by `TopologyObserver.publishMembershipDeltas` after the leader's
        // `MembershipFsm` writes `DECOMMISSIONED` and the snapshot re-projects).
        // The membership-delta-driven path is a single canonical edge instead of N+1
        // fan-out across every survivor's local SWIM listener; eviction trades sub-ms
        // local-SWIM latency for a Rabia round-trip + projection (~200-500ms cloud RTT).
        var clusterNetworkRef = clusterNode.network();
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                                 (MembershipDecision.NodeRemoved removed) ->
                                                     clusterNetworkRef.disconnect(
                                                         new org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode(removed.nodeId()))));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                                 (MembershipDecision.NodeDecommissioned decommissioned) ->
                                                     clusterNetworkRef.departurePermanent(decommissioned.nodeId())));
        swimHealthDetector.addObservationListener(obs -> {
            switch (obs) {
                case SwimObservation.JoinAnnounced j -> clusterNetworkRef.connect(j.nodeInfo());
                case SwimObservation.FaultyObserved f -> clusterNetworkRef.disconnect(
                    new org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode(f.peer()));
                case SwimObservation.DepartedObserved d -> clusterNetworkRef.departurePermanent(d.peer());
                default -> {}
            }
        });
        clusterNetworkRef.setSwimHealthGate(nodeId -> {
            var h = swimHealthDetector.healthOf(nodeId);
            return h != SwimHealth.FAULTY && h != SwimHealth.UNKNOWN;
        });
        var topologyForSwim = clusterNode.topologyManager();
        // P1 fix: prefer the transport-supplied `NodeInfo` (QUIC/Netty Hello handshake)
        // over the static-topology lookup. CTM-replaced or topology-forgotten peers are
        // absent from `topologyForSwim`, so the legacy NodeId-only path produced a SWIM
        // `PeerConnected(id, none())` that the FSM's `resolveSwimAddress` could not
        // resolve — leaving the peer permanently UNKNOWN and rejected by the gate.
        allEntries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                                 connection -> connection.nodeInfo()
                                                                          .orElse(() -> topologyForSwim.get(connection.nodeId()))
                                                                          .onPresent(swimHealthDetector::onNodeConnected)
                                                                          .onEmpty(() -> swimHealthDetector.onNodeConnected(connection.nodeId()))));
        Supplier<Integer> initialCoreSizeSupplier = () -> config.topology().coreNodes()
                                                                         .size();
        var publisherExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                                                                                           var thread = new Thread(runnable,
                                                                                                                   "generation-snapshot-publisher");
                                                                                           thread.setDaemon(true);
                                                                                           return thread;
                                                                                       });
        var publisherRef = new AtomicReference<GenerationSnapshotPublisher>();
        var swimHints = SwimHintsRegistry.swimHintsRegistry(java.time.Duration.ofMillis(config.autoHeal().swimHintsTtl()
                                                                                                       .millis()),
                                                            () -> Option.option(publisherRef.get())
                                                                               .onPresent(GenerationSnapshotPublisher::markDirty));
        peerObservationStore.subscribeHealth(swimHints::onPeerHealth);
        var generationSnapshotPublisher = GenerationSnapshotPublisher.generationSnapshotPublisher(isLeaderSupplier,
                                                                                                  rabiaTermSupplier,
                                                                                                  hlcClock,
                                                                                                  projectorEarly,
                                                                                                  swimHints,
                                                                                                  kvStore::snapshot,
                                                                                                  kvStore,
                                                                                                  clusterNode,
                                                                                                  publisherExecutor);
        publisherRef.set(generationSnapshotPublisher);
        var bootstrapModule = BootstrapModule.bootstrapModule(isLeaderSupplier,
                                                              rabiaTermSupplier,
                                                              () -> isLeaderSupplier.getAsBoolean()
                                                                   ? Option.some(leaderTerm.get())
                                                                   : Option.<Long>none(),
                                                              hlcClock,
                                                              projectorEarly,
                                                              kvStore::snapshot,
                                                              config::self,
                                                              initialCoreSizeSupplier,
                                                              clusterNode);
        var decommissionedAtomGc = org.pragmatica.aether.deployment.generation.DecommissionedAtomGc.decommissionedAtomGc(clusterNode,
                                                                                                                         kvStore::snapshot,
                                                                                                                         isLeaderSupplier,
                                                                                                                         config.autoHeal());
        decommissionedAtomGc.start();
        var publisherTickExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                                                                                                        var thread = new Thread(runnable,
                                                                                                                                "generation-publisher-tick");
                                                                                                        thread.setDaemon(true);
                                                                                                        return thread;
                                                                                                    });
        publisherTickExecutor.scheduleAtFixedRate(generationSnapshotPublisher::markDirty,
                                                  1,
                                                  1,
                                                  java.util.concurrent.TimeUnit.SECONDS);
        attachQuicDisconnectListener(clusterNode.network(), stableHealthSink, leaderEpochSupplier);
        attachQuicConnectivityReporter(clusterNode.network(),
                                       isLeaderSupplier,
                                       peerObservationStore,
                                       leaderEpochSupplier,
                                       reachabilityAggregator);
        attachQuicPeerStateListener(clusterNode.network(), swimHealthDetector);
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> onLeaderChangeForPublisher(change,
                                                                                      leaderTerm,
                                                                                      generationSnapshotPublisher,
                                                                                      bootstrapModule)));
        // RC1 Step 2: snapshot-then-tail wiring. GSP and BootstrapModule already
        // consume the current KV snapshot via their `kvSnapshotSupplier` at the time
        // of `projectFromKv` / `projectFromCommittedAtoms`. The routes attached below
        // provide the "tail" — every MembershipDecision variant routed by TopologyObserver
        // (single canonical emitter) signals dirty + bootstrap retry, replacing the
        // retired dual-channel `onPut(NodeLifecycleKey)` listener.
        wireMembershipDecisionTail(allEntries, generationSnapshotPublisher::onMembershipDecision);
        wireMembershipDecisionTail(allEntries, bootstrapModule::onMembershipDecision);
        var healthKvRouter = KVNotificationRouter.<AetherKey, AetherValue>builder(AetherKey.class)
                                                 .onPut(AetherKey.GovernorAnnouncementKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.GovernorAnnouncementKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.SpokesmanKey.class,
                                                        _ -> {
                                                            generationSnapshotPublisher.markDirty();
                                                            bootstrapModule.retryIfNeeded();
                                                        })
                                                 .onRemove(AetherKey.SpokesmanKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 // RC1 Step 2: NodeLifecycleKey put listener retired — the equivalent
                                                 // dirty signal arrives via the MembershipDecision route attached
                                                 // below (TopologyObserver's lifecycle-projection walker emits one
                                                 // decision per lifecycle transition, with snapshot-then-tail
                                                 // semantics for GSP + BootstrapModule).
                                                 .onRemove(AetherKey.NodeLifecycleKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.ClusterConfigKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.ClusterConfigKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.ClusterConfigKey.class,
                                                        (KVStoreNotification.ValuePut<AetherKey.ClusterConfigKey, AetherValue.ClusterConfigValue>_) -> clusterTopologyManager.onClusterConfigChanged())
                                                 .onPut(AetherKey.VersionRoutingKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.VersionRoutingKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.NodeArtifactKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.NodeArtifactKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.SliceTargetKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.SliceTargetKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.SliceNodeKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.SliceNodeKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.AppBlueprintKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.AppBlueprintKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.BlueprintResourcesKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.BlueprintResourcesKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .onPut(AetherKey.DhtPartitionOwnershipKey.class,
                                                        _ -> generationSnapshotPublisher.markDirty())
                                                 .onRemove(AetherKey.DhtPartitionOwnershipKey.class,
                                                           _ -> generationSnapshotPublisher.markDirty())
                                                 .build();
        allEntries.addAll(healthKvRouter.asRouteEntries());
        Supplier<Option<ClusterGenerationSnapshot>> spokesmanSnapshotSupplier = snapshotSupplier;
        var spokesmanPingLoop = org.pragmatica.aether.worker.metrics.SpokesmanPingLoop.spokesmanPingLoop(config.self(),
                                                                                                         clusterNode.network(),
                                                                                                         config.timeouts().cluster()
                                                                                                                        .pingInterval(),
                                                                                                         rabiaTermSupplier,
                                                                                                         metricsCollector::allMetrics,
                                                                                                         communityId -> lookupGovernor(kvStore,
                                                                                                                                       communityId),
                                                                                                         org.pragmatica.aether.worker.metrics.SpokesmanPingLoop.SpokesmanStatusWriter.fromCluster(clusterNode),
                                                                                                         reachabilityAggregator::snapshot);
        spokesmanPingLoop.start();
        // Wire the spokesman-active flag into the reachability-aggregator's
        // ingest gate so Tier-2 governor pongs feed the same aggregator. See
        // reachability-aggregator-spec.md Layer 6.
        spokesmanActiveRef.set(spokesmanPingLoop::isActive);
        metricsCollector.setCommunityReportSupplier(spokesmanPingLoop::currentReports);
        var spokesmanKvRouter = KVNotificationRouter.<AetherKey, AetherValue>builder(AetherKey.class)
                                                    .onPut(AetherKey.SpokesmanKey.class,
                                                           spokesmanPingLoop::onSpokesmanPut)
                                                    .onRemove(AetherKey.SpokesmanKey.class,
                                                              spokesmanPingLoop::onSpokesmanRemove)
                                                    .build();
        allEntries.addAll(spokesmanKvRouter.asRouteEntries());
        metricsCollector.addPongListener(spokesmanPingLoop::onClusterSyncPong);
        var streamMaxMemoryBytes = resolveStreamMaxMemoryBytes();
        var streamPartitionManager = StreamPartitionManager.streamPartitionManager(streamMaxMemoryBytes, clusterNode);
        var streamConfigKvRouter = KVNotificationRouter.<AetherKey, AetherValue>builder(AetherKey.class)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              streamPartitionManager::onStreamConfigPut)
                                                       .onRemove(AetherKey.StreamConfigKey.class,
                                                                 streamPartitionManager::onStreamConfigRemove)
                                                       .build();
        allEntries.addAll(streamConfigKvRouter.asRouteEntries());
        var streamSegmentIndex = new SegmentIndex();
        var streamWatermarkTracker = WatermarkTracker.watermarkTracker();
        var streamStorage = createStreamStorage(dhtClientOption);
        var streamSegmentReader = SegmentReader.segmentReader(streamStorage, streamSegmentIndex);
        var streamRetentionEnforcer = RetentionEnforcer.retentionEnforcer(streamStorage,
                                                                          streamSegmentIndex,
                                                                          DEFAULT_STREAM_RETENTION_MS);
        var streamReplicaRegistry = ReplicaRegistry.replicaRegistry();
        var streamFailoverHandler = GovernorFailoverHandler.governorFailoverHandler(streamReplicaRegistry,
                                                                                    StreamPartitionRecovery.NOOP);
        var streamingCoordinator = StreamingCoordinator.streamingCoordinator(streamFailoverHandler,
                                                                             streamRetentionEnforcer,
                                                                             streamPartitionManager,
                                                                             streamWatermarkTracker,
                                                                             streamSegmentIndex,
                                                                             streamSegmentReader);
        taskGroupActivator.register(streamingCoordinator);
        var streamForwardTransport = createStreamForwardTransport(clusterNode.network());
        var streamingConfig = config.streaming();
        var streamReadForwardMetrics = StreamReadForwardMetrics.inMemory();
        var streamForwardClient = StreamForwardClient.streamForwardClient(config.self(),
                                                                          streamForwardTransport,
                                                                          streamingConfig.publishForwardTimeout(),
                                                                          streamingConfig.readForwardTimeout(),
                                                                          streamReadForwardMetrics);
        var streamForwardHandler = StreamForwardHandler.streamForwardHandler(config.self(),
                                                                             streamPartitionManager,
                                                                             streamForwardTransport,
                                                                             streamingConfig.maxReadResponseBytes(),
                                                                             streamReadForwardMetrics);
        var streamReadRouter = StreamReadRouter.streamReadRouter(streamPartitionManager,
                                                                 Option.some(streamReplicaRegistry),
                                                                 Option.some(streamForwardClient),
                                                                 config.self(),
                                                                 streamReadForwardMetrics);
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.PublishForward.class,
                                                 streamForwardHandler::onPublishForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.PublishForwardResponse.class,
                                                 streamForwardClient::onPublishForwardResponse));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForward.class,
                                                 streamForwardHandler::onReadForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForwardResponse.class,
                                                 streamForwardClient::onReadForwardResponse));
        registerStreamForwardExtensions(resourceProviderSetup,
                                        streamForwardClient,
                                        taskGroupAssignmentRegistry,
                                        streamPartitionManager,
                                        serializer,
                                        deserializer);
        var certRenewalScheduler = createCertRenewalScheduler(config,
                                                              clusterNode,
                                                              appHttpServer,
                                                              managementServerRef::get);
        var startTimeMs = System.currentTimeMillis();
        var nodeLifecycle = NodeLifecycle.nodeLifecycle();
        var node = new aetherNode(config,
                                  delegateRouter,
                                  kvStore,
                                  sliceRegistry,
                                  sliceStore,
                                  clusterNode,
                                  switchableCluster,
                                  nodeDeploymentManager,
                                  clusterDeploymentManager,
                                  endpointRegistry,
                                  httpRouteRegistry,
                                  metricsCollector,
                                  metricsScheduler,
                                  deploymentMetricsCollector,
                                  deploymentMetricsScheduler,
                                  controlLoop,
                                  sliceInvoker,
                                  invocationHandler,
                                  blueprintService,
                                  mavenProtocolHandler,
                                  artifactStore,
                                  invocationMetrics,
                                  controller,
                                  deploymentManager,
                                  abTestManager,
                                  alertManager,
                                  depthRegistry,
                                  traceStore,
                                  logLevelRegistry,
                                  dynamicConfigManager,
                                  appHttpServer,
                                  ttmManager,
                                  rollbackManager,
                                  scheduledTaskManager,
                                  snapshotCollector,
                                  artifactMetricsCollector,
                                  deploymentMap,
                                  eventAggregator,
                                  BackupService.disabled(),
                                  streamPartitionManager,
                                  streamReadRouter,
                                  consumerGroupCoordinator,
                                  consumerGroupRegistry,
                                  taskAssignmentCoordinator,
                                  taskGroupAssignmentRegistry,
                                  storageSetups,
                                  clusterTopologyManager,
                                  eventLoopMetricsCollector,
                                  swimHealthDetector,
                                  spokesmanSnapshotSupplier,
                                  generationSnapshotPublisher::markDirty,
                                  Option.empty(),
                                  discoveryProvider,
                                  certRenewalScheduler,
                                  stableHealthSink,
                                  ctmLifecycleWriter,
                                  inFlightTrackerForDrain,
                                  drainCoordinator,
                                  nodeLifecycle,
                                  membershipFsm,
                                  hlcClock,
                                  effectivePhaseSupplier,
                                  startTimeMs);
        nodeDeploymentManager.setShutdownCallback(node::stop);
        nodeDeploymentManager.setSelfReadySignal(nodeLifecycle::signalReady);
        // Self-bootstrap (Bootstrap-correction 2026-05-12): SWIM does not observe self, so the
        // leader's FSM never receives `SwimHealthy(self)` via the normal gossip path. When this
        // node's local lifecycle reaches ACTIVE, synthesize that observation into our own FSM.
        // The leader-write gate ensures only the leader's enqueue produces a Put(L=ON_DUTY) for
        // self; followers drop the synthetic observation harmlessly. Spec §6 step 7.
        nodeLifecycle.addStateListener(change -> bootstrapSelfOnDutyOnActive(change,
                                                                              membershipFsm,
                                                                              config.self()));
        nodeLifecycle.subsystemsReady();
        return RabiaNode.buildAndWireRouter(delegateRouter, allEntries)
                                           .map(_ -> {
                                                    if (config.managementPort() > 0) {
                                                        var mgmtSecurityEnabled = config.appHttp().securityEnabled();
                                                        var configValidator = mgmtSecurityEnabled
                                                                             ? SecurityValidator.apiKeyValidator(config.appHttp()
                                                                                                                               .apiKeys())
                                                                             : SecurityValidator.noOpValidator();
                                                        var mgmtSecurityValidator = SecurityValidator.kvStoreAwareValidator(configValidator,
                                                                                                                            () -> node.kvStore());
                                                        var managementServer = ManagementServer.managementServer(config.managementPort(),
                                                                                                                 () -> node,
                                                                                                                 alertManager,
                                                                                                                 depthRegistry,
                                                                                                                 traceStore,
                                                                                                                 logLevelRegistry,
                                                                                                                 dynamicConfigManager,
                                                                                                                 scheduledTaskRegistry,
                                                                                                                 scheduledTaskManager,
                                                                                                                 sliceInvoker,
                                                                                                                 scheduledTaskStateRegistry,
                                                                                                                 config.tls(),
                                                                                                                 mgmtSecurityValidator,
                                                                                                                 mgmtSecurityEnabled,
                                                                                                                 serverBossGroup,
                                                                                                                 serverWorkerGroup,
                                                                                                                 config.managementHttpProtocol(),
                                                                                                                 config.timeouts()
                                                                                                                                .forwarding(),
                                                                                                                 Option.some(clusterNode.network()),
                                                                                                                 Option.some(serializer),
                                                                                                                 Option.some(deserializer));
                                                        managementServerRef.set(Option.some(managementServer));
                                                        return new aetherNode(config,
                                                                              delegateRouter,
                                                                              kvStore,
                                                                              sliceRegistry,
                                                                              sliceStore,
                                                                              clusterNode,
                                                                              switchableCluster,
                                                                              nodeDeploymentManager,
                                                                              clusterDeploymentManager,
                                                                              endpointRegistry,
                                                                              httpRouteRegistry,
                                                                              metricsCollector,
                                                                              metricsScheduler,
                                                                              deploymentMetricsCollector,
                                                                              deploymentMetricsScheduler,
                                                                              controlLoop,
                                                                              sliceInvoker,
                                                                              invocationHandler,
                                                                              blueprintService,
                                                                              mavenProtocolHandler,
                                                                              artifactStore,
                                                                              invocationMetrics,
                                                                              controller,
                                                                              deploymentManager,
                                                                              abTestManager,
                                                                              alertManager,
                                                                              depthRegistry,
                                                                              traceStore,
                                                                              logLevelRegistry,
                                                                              dynamicConfigManager,
                                                                              appHttpServer,
                                                                              ttmManager,
                                                                              rollbackManager,
                                                                              scheduledTaskManager,
                                                                              snapshotCollector,
                                                                              artifactMetricsCollector,
                                                                              deploymentMap,
                                                                              eventAggregator,
                                                                              BackupService.disabled(),
                                                                              streamPartitionManager,
                                                                              streamReadRouter,
                                                                              consumerGroupCoordinator,
                                                                              consumerGroupRegistry,
                                                                              taskAssignmentCoordinator,
                                                                              taskGroupAssignmentRegistry,
                                                                              storageSetups,
                                                                              clusterTopologyManager,
                                                                              eventLoopMetricsCollector,
                                                                              swimHealthDetector,
                                                                              spokesmanSnapshotSupplier,
                                                                              generationSnapshotPublisher::markDirty,
                                                                              Option.some(managementServer),
                                                                              discoveryProvider,
                                                                              certRenewalScheduler,
                                                                              stableHealthSink,
                                                                              ctmLifecycleWriter,
                                                                              inFlightTrackerForDrain,
                                                                              drainCoordinator,
                                                                              nodeLifecycle,
                                                                              membershipFsm,
                                                                              hlcClock,
                                                                              effectivePhaseSupplier,
                                                                              startTimeMs);
                                                    }
                                                    return node;
                                                });
    }

    /// Build the membership FSM (spec §9 — post-E.8 always active). The FSM:
    /// - reads `NodeLifecycleKey` + `ProvisioningSlotKey` on `start()` to reconstruct
    ///   per-peer state from KV;
    /// - routes operator-initiated drain/decommission events to consensus via
    ///   `commandApplier` and invokes `drainCoordinator` for the drain protocol;
    /// - routes SWIM observations through the leader-gated reducer (`isLeaderSupplier`).
    ///
    /// **Topology-observation refactor Step 4.** The aggregator snapshot supplier is passed
    /// to the FSM so the reducer can apply the aggregator-quorum gate at the two ON_DUTY
    /// decommission cells (`SwimFaulty`, `TransportUnreachable`). Cold-start fallback: when
    /// `reachabilityAggregator.snapshot()` returns `Option.none()`, the gate is permissive
    /// (pre-Step-4 behavior).
    private static MembershipFsm buildMembershipFsm(NodeId self,
                                                     KVStore<AetherKey, AetherValue> kvStore,
                                                     java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                     org.pragmatica.aether.deployment.drain.DrainCoordinator drainCoordinator,
                                                     BooleanSupplier isLeaderSupplier,
                                                     HlcClock hlcClock,
                                                     org.pragmatica.aether.deployment.membership.ReachabilityAggregator reachabilityAggregator) {
        var fsmConfig = MembershipFsmConfig.defaultMembershipFsmConfig();
        MembershipFsm.LifecycleSnapshotReader lifecycleSnapshot = consumer -> kvStore.forEach(AetherKey.NodeLifecycleKey.class,
                                                                                                AetherValue.NodeLifecycleValue.class,
                                                                                                consumer);
        MembershipFsm.SlotSnapshotReader slotSnapshot = consumer -> kvStore.forEach(AetherKey.ProvisioningSlotKey.class,
                                                                                      AetherValue.ProvisioningSlotValue.class,
                                                                                      consumer);
        MembershipFsm.TimerScheduler scheduler = org.pragmatica.lang.utils.SharedScheduler::schedule;
        return MembershipFsm.membershipFsm(self,
                                            fsmConfig,
                                            lifecycleSnapshot,
                                            slotSnapshot,
                                            commandApplier,
                                            drainCoordinator,
                                            scheduler,
                                            isLeaderSupplier,
                                            hlcClock,
                                            reachabilityAggregator::snapshot);
    }

    /// Self-bootstrap (Bootstrap-correction 2026-05-12; spec §6 step 7). SWIM does not observe
    /// self, so the leader's FSM never receives `SwimHealthy(self)` via the normal gossip path.
    /// When this node's `NodeLifecycle` transitions to ACTIVE, we synthesize a `HealthyObserved`
    /// for self into the local `MembershipFsm`. The reducer cell `(UNTRACKED, SwimHealthy) →
    /// ON_DUTY` (Change 1 of this correction) then drives the leader to write the self
    /// `Put(L=ON_DUTY)`. On followers the leader-write gate inside `MembershipFsm` drops the
    /// synthetic observation (single-writer invariant) — which is correct, because the leader
    /// writes the lifecycle atom for every peer including itself.
    @Contract private static void bootstrapSelfOnDutyOnActive(NodeStateChanged change,
                                                                MembershipFsm membershipFsm,
                                                                NodeId self) {
        if (change.current() != NodeState.ACTIVE) {
            return;
        }
        membershipFsm.onSwimObservation(new SwimObservation.HealthyObserved(self, 0L));
    }

    org.pragmatica.lang.io.TimeSpan PHASE_WATCH_INTERVAL = org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds();

    /// Post-E.8 phase-change publisher. Polls `phaseSupplier` at `PHASE_WATCH_INTERVAL` and
    /// dispatches `ctm.onClusterPhaseChanged(newPhase)` on every observed transition.
    /// Replaces the legacy `AetherNode.schedulePhaseChangeWatcher` wiring.
    @Contract private static void schedulePhaseChangeWatcher(Supplier<AetherValue.ClusterPhase> phaseSupplier,
                                                              ClusterTopologyManager ctm) {
        var lastPhase = new java.util.concurrent.atomic.AtomicReference<>(phaseSupplier.get());
        org.pragmatica.lang.utils.SharedScheduler.scheduleAtFixedRate(() -> publishPhaseChange(phaseSupplier, ctm, lastPhase),
                                                                       PHASE_WATCH_INTERVAL,
                                                                       PHASE_WATCH_INTERVAL);
    }

    @Contract private static void publishPhaseChange(Supplier<AetherValue.ClusterPhase> phaseSupplier,
                                                      ClusterTopologyManager ctm,
                                                      java.util.concurrent.atomic.AtomicReference<AetherValue.ClusterPhase> lastPhase) {
        var current = phaseSupplier.get();
        var previous = lastPhase.getAndSet(current);
        if (previous != current) {
            ctm.onClusterPhaseChanged(current);
        }
    }

    /// Topology-observation refactor Step 5: bridge `QuorumStateNotification.DISAPPEARED` into
    /// the local `SelfDrainCoordinator`. Both `onQuorumDisappeared` and `onRabiaPaused` are
    /// invoked because Rabia's `EngineState.Paused` fires on the same DISAPPEARED signal and the
    /// coordinator surface keeps the two trigger paths distinct for forward compatibility with
    /// a future Rabia-direct paused listener.
    @Contract private static void routeQuorumDisappearedToSelfDrain(QuorumStateNotification notification,
                                                                     SelfDrainCoordinator selfDrainCoordinator) {
        if (notification.state() != QuorumStateNotification.State.DISAPPEARED) {return;}
        selfDrainCoordinator.onQuorumDisappeared();
        selfDrainCoordinator.onRabiaPaused();
    }

    @SuppressWarnings("unchecked") private static void notifyCtmOnDuty(ValuePut<AetherKey.NodeLifecycleKey, AetherValue> put,
                                                                       ClusterTopologyManager ctm) {
        if (put.cause().value() instanceof AetherValue.NodeLifecycleValue lifecycleValue && lifecycleValue.state() == AetherValue.NodeLifecycleState.ON_DUTY) {ctm.onNodeReady(put.cause().key()
                                                                                                                                                                                        .nodeId());}
    }

    private static NodeAddress findSelfAddress(AetherNodeConfig config) {
        return config.topology().coreNodes()
                              .stream()
                              .filter(info -> info.id().equals(config.self()))
                              .map(NodeInfo::address)
                              .findFirst()
                              .orElse(new NodeAddress("", 0));
    }

    private static AetherValue.ProvisioningSource detectProvisioningSource() {
        var raw = Option.option(System.getenv("AETHER_PROVISIONED_BY")).filter(v -> !v.isBlank())
                               .map(String::trim)
                               .map(String::toLowerCase);
        return raw.map(AetherNode::provisioningSourceFrom).or(AetherValue.ProvisioningSource.MANUAL);
    }

    private static AetherValue.ProvisioningSource provisioningSourceFrom(String raw) {
        return switch (raw){
            case "ctm" -> AetherValue.ProvisioningSource.CTM;
            case "manual" -> AetherValue.ProvisioningSource.MANUAL;
            default -> AetherValue.ProvisioningSource.UNKNOWN;
        };
    }

    /// RC1 Step 2: register a `MembershipDecision` subscriber against every concrete variant
    /// individually because `MessageRouter` dispatches by exact class match. Subscribers that
    /// want "every membership decision" must enumerate all six variants — this helper centralises
    /// the enumeration so adding a new variant in the future is a single-site change.
    @Contract
    private static void wireMembershipDecisionTail(List<MessageRouter.Entry<?>> allEntries,
                                                   Consumer<MembershipDecision> subscriber) {
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class, subscriber::accept));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class, subscriber::accept));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class, subscriber::accept));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoining.class, subscriber::accept));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDraining.class, subscriber::accept));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeFailedDrain.class, subscriber::accept));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeShuttingDown.class, subscriber::accept));
    }

    private static void attachQuicDisconnectListener(ClusterNetwork network,
                                                     HealthSignalSink sink,
                                                     Supplier<Epoch> epochSupplier) {
        if (network instanceof QuicClusterNetwork quicNetwork) {
            QuicDisconnectListener listener = nodeId -> sink.emit(new HealthSignal.QuicDisconnect(nodeId,
                                                                                                  epochSupplier.get()));
            quicNetwork.setDisconnectListener(listener);
        }
    }

    private static void attachQuicPeerStateListener(ClusterNetwork network,
                                                     CoreSwimHealthDetector swimDetector) {
        LOG.debug("attachQuicPeerStateListener: network class={}",
                  network == null
                  ? "null"
                  : network.getClass().getName());
        if (! (network instanceof QuicClusterNetwork quicNetwork)) {
            LOG.warn("attachQuicPeerStateListener: network is NOT QuicClusterNetwork — skipping listener attachment");
            return;
        }
        var listener = new QuicPeerStateListener() {
            @Override@Contract public void onPeerJoined(NodeId nodeId) {
                LOG.debug("QuicPeerState: onPeerJoined({}) — recordTransportHint(reachable)", nodeId);
                swimDetector.recordTransportHint(new TransportObservation.PeerReachable(nodeId));
            }

            @Override@Contract public void onPeerReconnected(NodeId nodeId) {
                LOG.debug("QuicPeerState: onPeerReconnected({}) — recordTransportHint(reachable)", nodeId);
                swimDetector.recordTransportHint(new TransportObservation.PeerReachable(nodeId));
            }

            @Override@Contract public void onPeerLeft(NodeId nodeId) {
                LOG.debug("QuicPeerState: onPeerLeft({}) — recordTransportHint(unreachable)", nodeId);
                swimDetector.recordTransportHint(new TransportObservation.PeerUnreachable(nodeId,
                                                                                          QuicTransportCause.PEER_LEFT));
            }
        };
        quicNetwork.setPeerStateListener(listener);
        quicNetwork.connectedPeers()
                                  .forEach(peer -> {
                                               LOG.debug("QuicPeerState: catch-up recordTransportHint(reachable) for already-connected peer {}",
                                                         peer);
                                               swimDetector.recordTransportHint(new TransportObservation.PeerReachable(peer));
                                           });
    }

    enum QuicTransportCause implements Cause {
        PEER_LEFT("QUIC peer connection closed");
        private final String message;
        QuicTransportCause(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    /// Installs a single `PeerConnectivityReporter` on EVERY node (leader + followers).
    /// On EVERY transition observation:
    ///   * Push into local `PeerObservationBuffer` so the next outbound `ClusterSyncPong`
    ///     carries the observation (follower→leader relay path; benign on the leader, the
    ///     leader does not pong itself).
    ///   * If this node IS leader AT REPORT TIME (runtime check, not install-time), ALSO
    ///     ingest directly into the local `ReachabilityAggregator`, bypassing the 5s
    ///     self-fold tick and skipping the follower→leader pong roundtrip. Leadership
    ///     flips during a node's lifetime; the runtime check makes a freshly-elected
    ///     leader's QUIC drops feed the aggregator without re-installing the reporter.
    /// Step 4 of the topology-observation refactor — fixes the ~16s UNREACHABLE
    /// confirmation latency when an ON_DUTY peer is killed (was: leader learns only via
    /// the next self-fold tick or via follower pong relay; now: synchronous one-hop).
    /// See `aether/docs/specs/reachability-aggregator-spec.md`.
    private static void attachQuicConnectivityReporter(ClusterNetwork network,
                                                       BooleanSupplier isLeaderSupplier,
                                                       PeerObservationBuffer buffer,
                                                       Supplier<Epoch> epochSupplier,
                                                       ReachabilityAggregator reachabilityAggregator) {
        if (! (network instanceof QuicClusterNetwork quicNetwork)) {return;}
        PeerConnectivityReporter reporter = new PeerConnectivityReporter() {
            @Override public void onPeerDisconnected(NodeId peerId, long term, long counter) {
                var now = System.currentTimeMillis();
                buffer.pushConnectivity(new PeerConnectivityObservation(peerId,
                                                                        ConnectivityState.DISCONNECTED,
                                                                        term,
                                                                        counter,
                                                                        now));
                if (isLeaderSupplier.getAsBoolean()) {
                    reachabilityAggregator.ingestSelfTransition(peerId, ReachabilityKind.UNREACHABLE, now);
                }
            }

            @Override public void onPeerConnected(NodeId peerId, long term, long counter) {
                var now = System.currentTimeMillis();
                buffer.pushConnectivity(new PeerConnectivityObservation(peerId,
                                                                        ConnectivityState.CONNECTED,
                                                                        term,
                                                                        counter,
                                                                        now));
                if (isLeaderSupplier.getAsBoolean()) {
                    reachabilityAggregator.ingestSelfTransition(peerId, ReachabilityKind.REACHABLE, now);
                }
            }
        };
        QuicClusterNetwork.ObservedEpochSupplier epochAdapter = new QuicClusterNetwork.ObservedEpochSupplier() {
            @Override public long term() {
                return epochSupplier.get().rabiaTerm();
            }

            @Override public long counter() {
                return epochSupplier.get().localCounter();
            }
        };
        quicNetwork.setFollowerObservationWiring(isLeaderSupplier, reporter, epochAdapter);
    }

    private static Option<NodeId> lookupGovernor(KVStore<AetherKey, AetherValue> kvStore, String communityId) {
        return kvStore.get(AetherKey.GovernorAnnouncementKey.forCommunity(communityId)).filter(v -> v instanceof AetherValue.GovernorAnnouncementValue)
                          .map(v -> ((AetherValue.GovernorAnnouncementValue) v).governorId());
    }

    private static GenerationChangedSink buildGenerationChangedSink(MessageRouter router) {
        return notice -> router.route(OperationalEvent.GenerationChanged.generationChanged(notice.oldEpoch().toString(),
                                                                                           notice.newEpoch().toString(),
                                                                                           notice.reason().name()));
    }

    private static void onLeaderChangeForPublisher(LeaderNotification.LeaderChange change,
                                                   AtomicLong leaderTerm,
                                                   GenerationSnapshotPublisher publisher,
                                                   BootstrapModule bootstrap) {
        if (change.localNodeIsLeader()) {
            leaderTerm.incrementAndGet();
            bootstrap.onLeaderGained();
            publisher.onLeaderGained();
        } else {
            bootstrap.onLeaderLost();
            publisher.onLeaderLost();
        }
    }

    @SuppressWarnings("JBCT-RET-01") private static void toggleCtmOnLeaderChange(LeaderNotification.LeaderChange change,
                                                                                 ClusterTopologyManager ctm) {
        if (change.localNodeIsLeader()) {ctm.activate();} else {ctm.deactivate();}
    }

    private static void startSwimOnQuorum(QuorumStateNotification notification,
                                          CoreSwimHealthDetector swimHealthDetector,
                                          ClusterNetwork network,
                                          RotatingGossipEncryptor encryptor,
                                          Runnable announceJoinTrigger) {
        if (notification.state() == QuorumStateNotification.State.ESTABLISHED) {
            var workerGroup = network.server().map(org.pragmatica.net.tcp.Server::workerGroup);
            swimHealthDetector.start(workerGroup, encryptor);
            announceJoinTrigger.run();
        }
    }

    @SuppressWarnings({"JBCT-RET-01"}) private static void handleActivationDirective(ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put,
                                                                                     NodeId selfId,
                                                                                     RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                     SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                                                                     ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                                                                     AetherNodeConfig config,
                                                                                     MessageRouter.DelegateRouter delegateRouter,
                                                                                     KVStore<AetherKey, AetherValue> kvStore,
                                                                                     SliceStore sliceStore,
                                                                                     SliceInvoker sliceInvoker,
                                                                                     Logger growthLog) {
        if (!put.cause().key()
                      .nodeId()
                      .equals(selfId)) {return;}
        var role = put.cause().value()
                            .role();
        if (AetherValue.ActivationDirectiveValue.CORE.equals(role)) {
            growthLog.info("Received core activation directive from CDM");
            clusterNode.authorizeActivation();
        } else if (AetherValue.ActivationDirectiveValue.WORKER.equals(role)) {
            growthLog.info("Received worker activation directive from CDM");
            activateWorkerMode(selfId,
                               clusterNode,
                               switchableCluster,
                               forwardingClusterNode,
                               config,
                               delegateRouter,
                               kvStore,
                               sliceStore,
                               sliceInvoker,
                               growthLog);
        }
    }

    @SuppressWarnings({"JBCT-RET-01"}) private static void activateWorkerMode(NodeId selfId,
                                                                              RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                              SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                                                              ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                                                              AetherNodeConfig config,
                                                                              MessageRouter.DelegateRouter delegateRouter,
                                                                              KVStore<AetherKey, AetherValue> kvStore,
                                                                              SliceStore sliceStore,
                                                                              SliceInvoker sliceInvoker,
                                                                              Logger log) {
        clusterNode.authorizeObservation();
        switchableCluster.switchTo(forwardingClusterNode);
        log.info("Worker {} switched to forwarding mode", selfId.id());
        var decisionRelay = DecisionRelay.decisionRelay(selfId, delegateRouter);
        var mutationForwarder = MutationForwarder.mutationForwarder(selfId, delegateRouter);
        var workerBootstrap = WorkerBootstrap.workerBootstrap(selfId, delegateRouter, kvStore);
        var governorMesh = GovernorMesh.governorMesh(delegateRouter);
        var groupMembershipTracker = GroupMembershipTracker.groupMembershipTracker(selfId,
                                                                                   config.workerConfig().map(WorkerConfig::groupName)
                                                                                                      .or(WorkerConfig.DEFAULT_GROUP_NAME),
                                                                                   config.workerConfig().map(WorkerConfig::maxGroupSize)
                                                                                                      .or(WorkerConfig.DEFAULT_MAX_GROUP_SIZE));
        var workerDeploymentManager = WorkerDeploymentManager.workerDeploymentManager(selfId,
                                                                                      sliceStore,
                                                                                      mutationForwarder,
                                                                                      List.of(),
                                                                                      () -> groupMembershipTracker.myGroup()
                                                                                                                          .communityId());
        var workerHlc = HlcClock.hlcClock(selfId.id()).unwrap();
        var workerTcpAddress = resolveSelfTcpAddress(config);
        var governorAnnouncer = org.pragmatica.aether.worker.governor.GovernorAnnouncer.governorAnnouncer(selfId,
                                                                                                          clusterNode,
                                                                                                          workerHlc,
                                                                                                          () -> groupMembershipTracker.myGroup()
                                                                                                                                              .communityId(),
                                                                                                          () -> workerTcpAddress,
                                                                                                          () -> Epoch.ZERO);
        governorAnnouncer.start();
        log.info("Worker {} subsystems created, ready for SWIM-based community formation", selfId.id());
    }

    private static String resolveSelfTcpAddress(AetherNodeConfig config) {
        return config.topology().coreNodes()
                              .stream()
                              .filter(info -> info.id().equals(config.self()))
                              .map(info -> info.address().host() + ":" + info.address().port())
                              .findFirst()
                              .orElse("");
    }

    @SuppressWarnings({"unchecked", "rawtypes", "JBCT-RET-01"}) private static void handleForwardApplyRequest(ForwardApplyRequest request,
                                                                                                              RabiaNode<KVCommand<AetherKey>> clusterNode) {
        clusterNode.apply((List) request.commands()).onSuccess(results -> sendSuccessResponse(clusterNode,
                                                                                              request,
                                                                                              (List) results))
                         .onFailure(cause -> sendFailureResponse(clusterNode,
                                                                 request,
                                                                 (Cause) cause));
    }

    @SuppressWarnings({"rawtypes"}) private static void sendSuccessResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                            ForwardApplyRequest request,
                                                                            List<?> results) {
        var response = new ForwardApplyResponse<>(clusterNode.self(), request.correlationId(), results, Option.empty());
        clusterNode.network().send(request.sender(), response);
    }

    @SuppressWarnings({"rawtypes"}) private static void sendFailureResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                            ForwardApplyRequest request,
                                                                            Cause cause) {
        var response = new ForwardApplyResponse<>(clusterNode.self(),
                                                  request.correlationId(),
                                                  List.of(),
                                                  Option.some(cause.message()));
        clusterNode.network().send(request.sender(), response);
    }

    @SuppressWarnings("JBCT-RET-01") private static RotatingGossipEncryptor createGossipEncryptor(AetherNodeConfig config) {
        var initial = config.certificateProvider().flatMap(provider -> buildDualKeyEncryptor(provider))
                                                .or(GossipEncryptor.none());
        return RotatingGossipEncryptor.rotatingGossipEncryptor(initial);
    }

    @SuppressWarnings("JBCT-RET-01") private static Option<GossipEncryptor> buildDualKeyEncryptor(org.pragmatica.net.tcp.security.CertificateProvider provider) {
        return provider.currentGossipKey().option()
                                        .flatMap(current -> buildEncryptorFromKeys(current,
                                                                                   provider.previousGossipKey()));
    }

    @SuppressWarnings("JBCT-RET-01") private static Option<GossipEncryptor> buildEncryptorFromKeys(org.pragmatica.net.tcp.security.GossipKey current,
                                                                                                   Option<org.pragmatica.net.tcp.security.GossipKey> previous) {
        return previous.flatMap(prev -> buildDualKeyAesEncryptor(current, prev))
                               .orElse(() -> buildSingleKeyAesEncryptor(current));
    }

    private static Option<GossipEncryptor> buildDualKeyAesEncryptor(org.pragmatica.net.tcp.security.GossipKey current,
                                                                    org.pragmatica.net.tcp.security.GossipKey prev) {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(current.key(),
                                                           current.keyId(),
                                                           prev.key(),
                                                           prev.keyId())
        .option();
    }

    private static Option<GossipEncryptor> buildSingleKeyAesEncryptor(org.pragmatica.net.tcp.security.GossipKey current) {
        return AesGcmGossipEncryptor.aesGcmGossipEncryptor(current.key(), current.keyId()).option();
    }

    @SuppressWarnings("JBCT-PAT-01") private static Option<CertificateRenewalScheduler> createCertRenewalScheduler(AetherNodeConfig config,
                                                                                                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                                                   AppHttpServer appHttpServer,
                                                                                                                   java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        return config.certificateProvider()
                                         .flatMap(provider -> buildCertRenewalScheduler(config,
                                                                                        provider,
                                                                                        clusterNode,
                                                                                        appHttpServer,
                                                                                        managementServerSupplier));
    }

    @SuppressWarnings("JBCT-PAT-01") private static Option<CertificateRenewalScheduler> buildCertRenewalScheduler(AetherNodeConfig config,
                                                                                                                  org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                                                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                                                  AppHttpServer appHttpServer,
                                                                                                                  java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        var nodeId = config.self().id();
        var hostname = resolveHostname(config);
        return provider.issueCertificate(nodeId, hostname).map(bundle -> createSchedulerFromBundle(provider,
                                                                                                   nodeId,
                                                                                                   hostname,
                                                                                                   bundle,
                                                                                                   clusterNode,
                                                                                                   appHttpServer,
                                                                                                   managementServerSupplier))
                                        .option();
    }

    private static CertificateRenewalScheduler createSchedulerFromBundle(org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                         String nodeId,
                                                                         String hostname,
                                                                         CertificateBundle bundle,
                                                                         RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                         AppHttpServer appHttpServer,
                                                                         java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        return CertificateRenewalScheduler.certificateRenewalScheduler(provider,
                                                                       nodeId,
                                                                       hostname,
                                                                       newBundle -> onCertificateRenewed(newBundle,
                                                                                                         clusterNode,
                                                                                                         appHttpServer,
                                                                                                         managementServerSupplier),
                                                                       bundle.notAfter());
    }

    @SuppressWarnings("JBCT-PAT-01") private static void onCertificateRenewed(CertificateBundle newBundle,
                                                                              RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                              AppHttpServer appHttpServer,
                                                                              java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        log.info("Certificate renewed, valid until {}", newBundle.notAfter());
        Result.all(QuicSslContextFactory.createServerFromBundle(newBundle, QuicTlsProvider.CLUSTER_PROTOCOL),
                   QuicSslContextFactory.createClientFromBundle(newBundle, QuicTlsProvider.CLUSTER_PROTOCOL)).id()
                  .onSuccess(tuple -> triggerCertRotation(clusterNode,
                                                          tuple.first(),
                                                          tuple.last(),
                                                          newBundle,
                                                          appHttpServer,
                                                          managementServerSupplier))
                  .onFailure(cause -> log.error("Failed to build SSL contexts from renewed certificate: {}",
                                                cause.message()));
    }

    @SuppressWarnings("JBCT-PAT-01") private static void triggerCertRotation(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                             io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                                                             io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                                                             CertificateBundle newBundle,
                                                                             AppHttpServer appHttpServer,
                                                                             java.util.function.Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        rotateQuicNetwork(clusterNode, serverSsl, clientSsl, log);
        managementServerSupplier.get().onPresent(mgmt -> rotateManagementServer(mgmt, newBundle, log));
        rotateAppHttpServer(appHttpServer, newBundle, log);
    }

    private static void rotateQuicNetwork(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                          io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                          io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                          Logger log) {
        if (clusterNode.network() instanceof QuicClusterNetwork quicNetwork) {quicNetwork.rotateCertificate(serverSsl,
                                                                                                            clientSsl).onSuccess(_ -> log.info("QUIC certificate rotation complete"))
                                                                                                           .onFailure(cause -> log.error("QUIC certificate rotation failed: {}",
                                                                                                                                         cause.message()));} else {log.warn("QUIC certificate rotation skipped: network is not QUIC-based");}
    }

    private static void rotateManagementServer(ManagementServer mgmt, CertificateBundle newBundle, Logger log) {
        mgmt.rotateCertificate(newBundle).onSuccess(_ -> log.info("Management server certificate rotation complete"))
                              .onFailure(cause -> log.error("Management server certificate rotation failed: {}",
                                                            cause.message()));
    }

    private static void rotateAppHttpServer(AppHttpServer appHttpServer, CertificateBundle newBundle, Logger log) {
        appHttpServer.rotateCertificate(newBundle).onSuccess(_ -> log.info("App HTTP server certificate rotation complete"))
                                       .onFailure(cause -> log.error("App HTTP server certificate rotation failed: {}",
                                                                     cause.message()));
    }

    private static long resolveStreamMaxMemoryBytes() {
        return Option.option(System.getenv("STREAM_MAX_MEMORY_BYTES")).filter(s -> !s.isBlank())
                            .flatMap(s -> Result.lift(() -> Long.parseLong(s)).option())
                            .or(128 * 1024 * 1024L);
    }

    private static String resolveHostname(AetherNodeConfig config) {
        return config.topology().coreNodes()
                              .stream()
                              .filter(n -> n.id().equals(config.self()))
                              .findFirst()
                              .map(n -> n.address().host())
                              .orElse("localhost");
    }

    @SuppressWarnings("JBCT-RET-01") private static void handleRemotePutResponse(DHTNetwork dhtNetwork,
                                                                                 AetherMaps aetherMaps,
                                                                                 DHTMessage.PutRequest request,
                                                                                 DHTMessage.PutResponse response) {
        dhtNetwork.send(request.sender(), response);
        if (response.success() && !response.superseded()) {aetherMaps.dispatchRemotePut(request.key(), request.value());}
    }

    @SuppressWarnings("JBCT-RET-01") private static void handleRemoteRemoveResponse(DHTNetwork dhtNetwork,
                                                                                    AetherMaps aetherMaps,
                                                                                    DHTMessage.RemoveRequest request,
                                                                                    DHTMessage.RemoveResponse response) {
        dhtNetwork.send(request.sender(), response);
        if (response.found()) {aetherMaps.dispatchRemoteRemove(request.key());}
    }

    private static List<MessageRouter.Entry<?>> collectRouteEntries(KVStore<AetherKey, AetherValue> kvStore,
                                                                    NodeDeploymentManager nodeDeploymentManager,
                                                                    ClusterDeploymentManager clusterDeploymentManager,
                                                                    EndpointRegistry endpointRegistry,
                                                                    TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                                    ScheduledTaskRegistry scheduledTaskRegistry,
                                                                    ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                                                                    ScheduledTaskManager scheduledTaskManager,
                                                                    HttpRouteRegistry httpRouteRegistry,
                                                                    ClusterSyncCollector metricsCollector,
                                                                    ClusterSyncScheduler metricsScheduler,
                                                                    DeploymentMetricsCollector deploymentMetricsCollector,
                                                                    DeploymentMetricsScheduler deploymentMetricsScheduler,
                                                                    ControlLoop controlLoop,
                                                                    SliceInvoker sliceInvoker,
                                                                    InvocationHandler invocationHandler,
                                                                    AlertManager alertManager,
                                                                    ObservabilityDepthRegistry depthRegistry,
                                                                    LogLevelRegistry logLevelRegistry,
                                                                    Option<DynamicConfigManager> dynamicConfigManager,
                                                                    TTMManager ttmManager,
                                                                    RabiaMetricsCollector rabiaMetricsCollector,
                                                                    DeploymentManager deploymentManager,
                                                                    AbTestManager abTestManager,
                                                                    RollbackManager rollbackManager,
                                                                    ArtifactMetricsCollector artifactMetricsCollector,
                                                                    DeploymentMap deploymentMap,
                                                                    ClusterEventAggregator eventAggregator,
                                                                    LeaderManager leaderManager,
                                                                    AppHttpServer appHttpServer,
                                                                    Option<LoadBalancerManager> loadBalancerManager,
                                                                    TopologyObserver topologyManager,
                                                                    ClusterTopologyManager clusterTopologyManager,
                                                                    TaskGroupActivator taskGroupActivator,
                                                                    TaskAssignmentCoordinator taskAssignmentCoordinator,
                                                                    TaskGroupAssignmentRegistry taskGroupAssignmentRegistry,
                                                                    ConsumerGroupCoordinator consumerGroupCoordinator,
                                                                    ConsumerGroupRegistry consumerGroupRegistry,
                                                                    MembershipFsm membershipFsm,
                                                                    SelfDrainCoordinator selfDrainCoordinator,
                                                                    java.util.concurrent.atomic.AtomicReference<Option<ManagementServer>> managementServerRef) {
        var entries = new ArrayList<MessageRouter.Entry<?>>();
        var kvRouterBuilder = KVNotificationRouter.<AetherKey, AetherValue>builder(AetherKey.class)
                                                  .onPut(AetherKey.AppBlueprintKey.class,
                                                         clusterDeploymentManager::onAppBlueprintPut)
                                                  .onPut(AetherKey.SliceTargetKey.class,
                                                         clusterDeploymentManager::onSliceTargetPut)
                                                  .onPut(AetherKey.VersionRoutingKey.class,
                                                         clusterDeploymentManager::onVersionRoutingPut)
                                                  .onRemove(AetherKey.AppBlueprintKey.class,
                                                            clusterDeploymentManager::onAppBlueprintRemove)
                                                  .onRemove(AetherKey.SliceTargetKey.class,
                                                            clusterDeploymentManager::onSliceTargetRemove)
                                                  .onRemove(AetherKey.VersionRoutingKey.class,
                                                            clusterDeploymentManager::onVersionRoutingRemove)
                                                  .onPut(AetherKey.SliceTargetKey.class, controlLoop::onSliceTargetPut)
                                                  .onRemove(AetherKey.SliceTargetKey.class,
                                                            controlLoop::onSliceTargetRemove)
                                                  .onPut(AetherKey.AlertThresholdKey.class,
                                                         alertManager::onAlertThresholdPut)
                                                  .onRemove(AetherKey.AlertThresholdKey.class,
                                                            alertManager::onAlertThresholdRemove)
                                                  .onPut(AetherKey.ObservabilityDepthKey.class,
                                                         depthRegistry::onDepthPut)
                                                  .onRemove(AetherKey.ObservabilityDepthKey.class,
                                                            depthRegistry::onDepthRemove)
                                                  .onPut(AetherKey.LogLevelKey.class, logLevelRegistry::onLogLevelPut)
                                                  .onRemove(AetherKey.LogLevelKey.class,
                                                            logLevelRegistry::onLogLevelRemove)
                                                  .onPut(AetherKey.SliceTargetKey.class,
                                                         rollbackManager::onSliceTargetPut)
                                                  .onPut(AetherKey.PreviousVersionKey.class,
                                                         rollbackManager::onPreviousVersionPut)
                                                  .onPut(AetherKey.TopicSubscriptionKey.class,
                                                         topicSubscriptionRegistry::onSubscriptionPut)
                                                  .onRemove(AetherKey.TopicSubscriptionKey.class,
                                                            topicSubscriptionRegistry::onSubscriptionRemove)
                                                  .onPut(AetherKey.ScheduledTaskKey.class,
                                                         scheduledTaskRegistry::onScheduledTaskPut)
                                                  .onRemove(AetherKey.ScheduledTaskKey.class,
                                                            scheduledTaskRegistry::onScheduledTaskRemove)
                                                  .onPut(AetherKey.ScheduledTaskStateKey.class,
                                                         scheduledTaskStateRegistry::onStatePut)
                                                  .onRemove(AetherKey.ScheduledTaskStateKey.class,
                                                            scheduledTaskStateRegistry::onStateRemove)
                                                  // RC1 Step 2: NodeDeploymentManager + ClusterDeploymentManager no longer
                                                  // subscribe to NodeLifecycleKey directly — they consume
                                                  // MembershipDecision via the routes added near the
                                                  // generationSnapshotPublisher wiring above. ClusterEventAggregator
                                                  // (Step 1 scope) and MembershipFsm remain as KV-put consumers.
                                                  .onRemove(AetherKey.NodeLifecycleKey.class,
                                                            nodeDeploymentManager::onNodeLifecycleRemove)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         eventAggregator::onNodeLifecyclePut)
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         put -> notifyCtmOnDuty(put, clusterTopologyManager))
                                                  .onPut(AetherKey.NodeLifecycleKey.class,
                                                         membershipFsm::onNodeLifecyclePut)
                                                  .onRemove(AetherKey.NodeLifecycleKey.class,
                                                            membershipFsm::onNodeLifecycleRemove)
                                                  .onPut(AetherKey.ProvisioningSlotKey.class,
                                                         membershipFsm::onProvisioningSlotPut)
                                                  .onRemove(AetherKey.ProvisioningSlotKey.class,
                                                            membershipFsm::onProvisioningSlotRemove)
                                                  .onPut(AetherKey.ActivationDirectiveKey.class,
                                                         clusterDeploymentManager::onActivationDirectivePut)
                                                  .onRemove(AetherKey.ActivationDirectiveKey.class,
                                                            clusterDeploymentManager::onActivationDirectiveRemove)
                                                  .onPut(AetherKey.SchemaVersionKey.class,
                                                         clusterDeploymentManager::onSchemaVersionPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         nodeDeploymentManager::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         clusterDeploymentManager::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         endpointRegistry::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         artifactMetricsCollector.deploymentTracker()::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         deploymentMap::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class, controlLoop::onNodeArtifactPut)
                                                  .onPut(AetherKey.NodeArtifactKey.class,
                                                         eventAggregator::onNodeArtifactPut)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            nodeDeploymentManager::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            clusterDeploymentManager::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            endpointRegistry::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            artifactMetricsCollector.deploymentTracker()::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            deploymentMap::onNodeArtifactRemove)
                                                  .onRemove(AetherKey.NodeArtifactKey.class,
                                                            controlLoop::onNodeArtifactRemove)
                                                  .onPut(AetherKey.NodeRoutesKey.class,
                                                         httpRouteRegistry::onNodeRoutesPut)
                                                  .onPut(AetherKey.NodeRoutesKey.class, appHttpServer::onNodeRoutesPut)
                                                  .onRemove(AetherKey.NodeRoutesKey.class,
                                                            httpRouteRegistry::onNodeRoutesRemove)
                                                  .onRemove(AetherKey.NodeRoutesKey.class,
                                                            appHttpServer::onNodeRoutesRemove);
        // RC1 Step 1 — materialised view subscriber. Every Rabia-committed `ClusterEventLogKey`
        // put — whether it arrives via fresh consensus or cold-boot snapshot replay — flows
        // through `onClusterEventLogPut` and into the local RingBuffer projection that
        // `/api/events` reads. The `isReplay` flag inside the aggregator suppresses downstream
        // sink fan-out during the snapshot-replay window.
        kvRouterBuilder.onPut(AetherKey.ClusterEventLogKey.class, eventAggregator::onClusterEventLogPut);
        loadBalancerManager.onPresent(lbm -> kvRouterBuilder.onPut(AetherKey.NodeRoutesKey.class, lbm::onNodeRoutesPut)
                                                                  .onRemove(AetherKey.NodeRoutesKey.class,
                                                                            lbm::onNodeRoutesRemove));
        dynamicConfigManager.onPresent(dcm -> kvRouterBuilder.onPut(AetherKey.ConfigKey.class, dcm::onConfigPut).onRemove(AetherKey.ConfigKey.class,
                                                                                                                          dcm::onConfigRemove)
                                                                   .onPut(AetherKey.BlueprintResourcesKey.class,
                                                                          dcm::onBlueprintResourcesPut));
        kvRouterBuilder.onPut(AetherKey.TaskAssignmentKey.class, taskGroupActivator::onTaskAssignmentPut);
        kvRouterBuilder.onRemove(AetherKey.TaskAssignmentKey.class, taskGroupActivator::onTaskAssignmentRemove);
        kvRouterBuilder.onPut(AetherKey.TaskAssignmentKey.class, taskGroupAssignmentRegistry::onTaskAssignmentPut);
        kvRouterBuilder.onRemove(AetherKey.TaskAssignmentKey.class, taskGroupAssignmentRegistry::onTaskAssignmentRemove);
        kvRouterBuilder.onPut(AetherKey.ConsumerGroupKey.class, consumerGroupRegistry::onConsumerGroupPut);
        kvRouterBuilder.onRemove(AetherKey.ConsumerGroupKey.class, consumerGroupRegistry::onConsumerGroupRemove);
        entries.addAll(kvRouterBuilder.build().asRouteEntries());
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, nodeDeploymentManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, controlLoop::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, metricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class,
                                              deploymentMetricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, scheduledTaskManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, appHttpServer::onQuorumStateChange));
        // Topology-observation refactor Step 5: self-drain is wired to both branches of the
        // QuorumStateNotification stream. `DISAPPEARED` triggers the immediate hard drain path
        // (`onQuorumDisappeared`); Rabia's `Paused` state fires on the same DISAPPEARED signal,
        // so `onRabiaPaused` is invoked from the same handler to keep the surface symmetric
        // with the spec §16.1 trigger list (a future Rabia-direct paused listener can route to
        // `onRabiaPaused` without changing the FSM).
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class,
                                              notification -> routeQuorumDisappearedToSelfDrain(notification, selfDrainCoordinator)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              taskAssignmentCoordinator::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              consumerGroupCoordinator::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleCtmOnLeaderChange(change, clusterTopologyManager)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> rabiaMetricsCollector.updateRole(change.localNodeIsLeader(),
                                                                                         change.leaderId()
                                                                                                        .map(NodeId::id))));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              scheduledTaskManager::onLeaderChange));
        // Self-bootstrap second trigger (Bootstrap-correction 2026-05-12; spec §6.2 step 7).
        // The NodeLifecycle.ACTIVE listener (see bootstrapSelfOnDutyOnActive) covers the race
        // where subsystem readiness completes AFTER leader election. This LeaderChange route
        // covers the inverse race — leader election completes AFTER subsystem readiness — by
        // re-injecting the synthetic SwimHealthy(self) once this node becomes leader. The
        // reducer's (ON_DUTY, SwimHealthy) → nop rule keeps both triggers idempotent.
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              membershipFsm::onLeaderChange));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              rollbackManager::onAllInstancesFailed));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              clusterDeploymentManager::onMembershipDecision));
        // RC1 Step 2: route the new lifecycle-projection variants into CDM so the
        // dropped `onNodeLifecyclePut` listener's work (drain eviction, etc.) is
        // covered through the single canonical MembershipDecision channel.
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoining.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDraining.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeFailedDrain.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeShuttingDown.class,
                                              clusterDeploymentManager::onMembershipDecision));
        // RC1 Step 2: NodeDeploymentManager consumes MembershipDecision.NodeShuttingDown
        // to trigger self-shutdown after its `onNodeLifecyclePut` listener was retired.
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeShuttingDown.class,
                                              nodeDeploymentManager::onMembershipDecision));
        // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                              clusterDeploymentManager::onSelfShutdown));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              clusterTopologyManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              clusterTopologyManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              clusterTopologyManager::onMembershipDecision));
        // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                              clusterTopologyManager::onSelfShutdown));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              taskAssignmentCoordinator::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              taskAssignmentCoordinator::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              taskAssignmentCoordinator::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              metricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              metricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              metricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class, controlLoop::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              controlLoop::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              controlLoop::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              metricsCollector::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              metricsCollector::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(ClusterSyncMessage.ClusterSyncPing.class,
                                              metricsCollector::onClusterSyncPing));
        entries.add(MessageRouter.Entry.route(ClusterSyncMessage.ClusterSyncPong.class,
                                              metricsCollector::onClusterSyncPong));
        entries.add(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPing.class,
                                              deploymentMetricsCollector::onDeploymentMetricsPing));
        entries.add(MessageRouter.Entry.route(DeploymentMetricsMessage.DeploymentMetricsPong.class,
                                              deploymentMetricsCollector::onDeploymentMetricsPong));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              deploymentMetricsCollector::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              deploymentMetricsCollector::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              deploymentMetricsCollector::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentStarted.class,
                                              deploymentMetricsCollector::onDeploymentStarted));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.StateTransition.class,
                                              deploymentMetricsCollector::onStateTransition));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentCompleted.class,
                                              deploymentMetricsCollector::onDeploymentCompleted));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class,
                                              deploymentMetricsCollector::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              deploymentMetricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              deploymentMetricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              deploymentMetricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class, appHttpServer::onNodeRemoved));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class, appHttpServer::onNodeDecommissioned));
        // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class, appHttpServer::onSelfShutdown));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              msg -> httpRouteRegistry.evictNode(msg.nodeId())));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              msg -> httpRouteRegistry.evictNode(msg.nodeId())));
        // NODE_JOINED user-facing event reflects transport-level visibility (PeerJoined)
        // rather than the consensus-level membership decision. Required for CTM-provisioned
        // replacements that re-occupy the same node-id slot — no MembershipDecision delta
        // fires for those, but the fresh QUIC handshake produces a TransportObservation.
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.PeerJoined.class,
                                              eventAggregator::onPeerJoined));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, eventAggregator::onLeaderChange));
        entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, eventAggregator::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class, abTestManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              eventAggregator::onSliceFailure));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledUp.class, eventAggregator::onScaledUp));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledDown.class, eventAggregator::onScaledDown));
        entries.add(MessageRouter.Entry.route(ClusterDeploymentManager.ReconciliationAdjustment.class,
                                              eventAggregator::onReconciliationAdjustment));
        entries.add(MessageRouter.Entry.route(CommunityScalingRequest.class, controlLoop::onCommunityScalingRequest));
        entries.add(MessageRouter.Entry.route(CommunityMetricsSnapshot.class, controlLoop::onCommunityMetricsSnapshot));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                              eventAggregator::onConnectionEstablished));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionFailed.class,
                                              eventAggregator::onConnectionFailed));
        entries.add(MessageRouter.Entry.route(OperationalEvent.AccessDenied.class, eventAggregator::onAccessDenied));
        entries.add(MessageRouter.Entry.route(OperationalEvent.NodeLifecycleChanged.class,
                                              eventAggregator::onNodeLifecycleChanged));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ConfigChanged.class, eventAggregator::onConfigChanged));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BackupCreated.class, eventAggregator::onBackupCreated));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BackupRestored.class, eventAggregator::onBackupRestored));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BlueprintDeployed.class,
                                              eventAggregator::onBlueprintDeployed));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BlueprintDeleted.class,
                                              eventAggregator::onBlueprintDeleted));
        entries.add(MessageRouter.Entry.route(OperationalEvent.GenerationChanged.class,
                                              eventAggregator::onGenerationChanged));
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeRequest.class, invocationHandler::onInvokeRequest));
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeResponse.class, sliceInvoker::onInvokeResponse));
        entries.add(MessageRouter.Entry.route(org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest.class,
                                              request -> demuxHttpForwardRequest(request,
                                                                                 appHttpServer,
                                                                                 managementServerRef.get())));
        entries.add(MessageRouter.Entry.route(org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse.class,
                                              response -> demuxHttpForwardResponse(response,
                                                                                   appHttpServer,
                                                                                   managementServerRef.get())));
        entries.add(MessageRouter.Entry.route(KVStoreLocalIO.Request.Find.class, kvStore::find));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                              notification -> handleLeaderCommit(notification, leaderManager)));
        loadBalancerManager.onPresent(lbm -> {
                                          entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                                                                lbm::onMembershipDecision));
                                          entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                                                                lbm::onMembershipDecision));
                                          entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                                                                lbm::onMembershipDecision));
                                          // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
                                          entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                                                                lbm::onSelfShutdown));
                                      });
        return entries;
    }

    @SuppressWarnings("JBCT-PAT-01") private static void demuxHttpForwardRequest(HttpForwardMessage.HttpForwardRequest request,
                                                                                 AppHttpServer appHttpServer,
                                                                                 Option<ManagementServer> managementServer) {
        if (request.pipeline() == HttpForwardMessage.Pipeline.MANAGEMENT) {managementServer.onPresent(ms -> ms.onHttpForwardRequest(request));} else {appHttpServer.onHttpForwardRequest(request);}
    }

    @SuppressWarnings("JBCT-PAT-01") private static void demuxHttpForwardResponse(HttpForwardMessage.HttpForwardResponse response,
                                                                                  AppHttpServer appHttpServer,
                                                                                  Option<ManagementServer> managementServer) {
        if (response.pipeline() == HttpForwardMessage.Pipeline.MANAGEMENT) {managementServer.onPresent(ms -> ms.onHttpForwardResponse(response));} else {appHttpServer.onHttpForwardResponse(response);}
    }

    private static void handleLeaderCommit(KVStoreNotification.ValuePut<?, ?> notification,
                                           LeaderManager leaderManager) {
        if (notification.cause().key() instanceof LeaderKey) {
            var value = (LeaderValue) notification.cause().value();
            leaderManager.onLeaderCommitted(value.leader());
        }
    }

    private static ObservabilityInterceptor createObservabilityInterceptor(AetherNodeConfig config,
                                                                           InvocationTraceStore traceStore,
                                                                           ObservabilityDepthRegistry depthRegistry) {
        var sampler = AdaptiveSampler.adaptiveSampler(config.observability().targetTracesPerSec());
        return ObservabilityInterceptor.observabilityInterceptor(sampler,
                                                                 traceStore,
                                                                 config.self().id(),
                                                                 (artifact, method) -> depthRegistry.getConfig(artifact,
                                                                                                               method)
        .depthThreshold());
    }

    private static SharedLibraryClassLoader createSharedLibraryLoader(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        return config.sliceAction().frameworkJarsPath()
                                 .fold(() -> {
                                           log.debug("No framework JARs path configured, using Application ClassLoader as parent");
                                           return new SharedLibraryClassLoader(AetherNode.class.getClassLoader());
                                       },
                                       path -> FrameworkClassLoader.fromDirectory(path).onFailure(cause -> log.warn("Failed to create FrameworkClassLoader from {}: {}. " + "Falling back to Application ClassLoader.",
                                                                                                                    path,
                                                                                                                    cause.message()))
                                                                                 .map(loader -> {
                                                                                          log.info("Using FrameworkClassLoader with {} JARs as parent",
                                                                                                   loader.getLoadedJars()
                                                                                                                       .size());
                                                                                          return new SharedLibraryClassLoader(loader);
                                                                                      })
                                                                                 .or(new SharedLibraryClassLoader(AetherNode.class.getClassLoader())));
    }

    record ResourceProviderSetup(ResourceProviderFacade facade,
                                 Option<DynamicConfigurationProvider> dynamicProvider,
                                 Option<SpiResourceProvider> spiProvider){}

    private static ResourceProviderSetup createResourceProviderFacade(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);
        return config.configProvider()
                                    .fold(() -> {
                                              log.debug("No configuration provider configured, resource provisioning disabled");
                                              return new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                               Option.empty(),
                                                                               Option.empty());
                                          },
                                          configProvider -> {
                                              log.info("Creating ConfigService and ResourceProvider from configuration provider");
                                              var resolvedProvider = config.environment().flatMap(EnvironmentIntegration::secrets)
                                                                                       .fold(() -> Result.success(configProvider),
                                                                                             sp -> ConfigurationProvider.withSecretResolution(configProvider,
                                                                                                                                              sp::resolveSecret));
                                              return resolvedProvider.fold(cause -> {
                                                                               log.error("Failed to resolve secrets in configuration: {}",
                                                                                         cause.message());
                                                                               return new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                                                                Option.empty(),
                                                                                                                Option.empty());
                                                                           },
                                                                           provider -> {
                                                                               var dynamicProvider = DynamicConfigurationProvider.dynamicConfigurationProvider(provider);
                                                                               var configService = ProviderBasedConfigService.providerBasedConfigService(dynamicProvider);
                                                                               ConfigService.setInstance(configService);
                                                                               var resourceProvider = SpiResourceProvider.spiResourceProvider();
                                                                               ResourceProvider.setInstance(resourceProvider);
                                                                               log.info("ConfigService and ResourceProvider initialized with dynamic overlay");
                                                                               return new ResourceProviderSetup(new ResourceProviderFacade() {
            @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                                                                                                                    return resourceProvider.provide(resourceType,
                                                                                                                                                    configSection);
                                                                                                                }

            @Override public <T> Promise<T> provide(Class<T> resourceType,
                                                    String configSection,
                                                    ProvisioningContext context) {
                                                                                                                    return resourceProvider.provide(resourceType,
                                                                                                                                                    configSection,
                                                                                                                                                    context);
                                                                                                                }
        },
                                                                                                                Option.some(dynamicProvider),
                                                                                                                Option.some(resourceProvider));
                                                                           });
                                          });
    }

    private static ResourceProviderFacade noOpResourceProviderFacade() {
        return new ResourceProviderFacade() {
            private static final Cause NOT_CONFIGURED = Causes.cause("Resource provisioning not configured. Use AetherNodeConfig.withConfigProvider() to enable.");

            @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return NOT_CONFIGURED.promise();
            }

            @Override public <T> Promise<T> provide(Class<T> resourceType,
                                                    String configSection,
                                                    ProvisioningContext context) {
                return NOT_CONFIGURED.promise();
            }
        };
    }

    private static Repository compositeRepository(List<Repository> repositories) {
        if (repositories.isEmpty()) {return artifact -> Causes.cause("No repositories configured").promise();}
        return repositories.getFirst();
    }

    private static void registerRuntimeExtensions(SpiResourceProvider spi,
                                                  TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                  SliceInvoker sliceInvoker,
                                                  DHTClient cacheDhtClient) {
        spi.registerExtension(TopicSubscriptionRegistry.class, topicSubscriptionRegistry);
        spi.registerExtension(SliceInvoker.class, sliceInvoker);
        spi.registerExtension(DHTClient.class, cacheDhtClient);
    }

    private static StreamForwardTransport createStreamForwardTransport(ClusterNetwork network) {
        return network::send;
    }

    private static void registerStreamForwardExtensions(ResourceProviderSetup resourceProviderSetup,
                                                        StreamForwardClient forwardClient,
                                                        TaskGroupAssignmentRegistry registry,
                                                        StreamPartitionManager streamPartitionManager,
                                                        Serializer serializer,
                                                        Deserializer deserializer) {
        resourceProviderSetup.spiProvider()
                                         .onPresent(spi -> registerForwardExtensionsOnSpi(spi,
                                                                                          forwardClient,
                                                                                          registry,
                                                                                          streamPartitionManager,
                                                                                          serializer,
                                                                                          deserializer));
    }

    private static void registerForwardExtensionsOnSpi(SpiResourceProvider spi,
                                                       StreamForwardClient forwardClient,
                                                       TaskGroupAssignmentRegistry registry,
                                                       StreamPartitionManager streamPartitionManager,
                                                       Serializer serializer,
                                                       Deserializer deserializer) {
        spi.registerExtension(StreamForwardClient.class, forwardClient);
        spi.registerExtension(StreamPartitionManager.class, streamPartitionManager);
        spi.registerExtension(Serializer.class, serializer);
        spi.registerExtension(Deserializer.class, deserializer);
        spi.registerExtension(StreamPublisherFactory.GovernorResolver.class,
                              new StreamPublisherFactory.GovernorResolver(() -> registry.ownerFor(TaskGroup.STREAMING)
                                                                                                 .option()));
    }

    /// Project the aggregator's materialised `ClusterEvent` view onto
    /// `InvocationTraceStore.ClusterTraceEvent` records — filters by `TRACE_INJECTED` and
    /// extracts the metadata stamped by `InvocationTraceStore.publishInjectionToClusterLog`.
    /// Lives here (not in aether-invoke) because `ClusterEvent` is owned by aether/node.
    private static List<InvocationTraceStore.ClusterTraceEvent> projectClusterTraceInjections(List<ClusterEvent> events) {
        var list = new java.util.ArrayList<InvocationTraceStore.ClusterTraceEvent>();
        for (var event : events) {
            if (event.type() != org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue.EventType.TRACE_INJECTED) {continue;}
            var details = event.details();
            var requestId = details.get("requestId");
            if (requestId == null) {continue;}
            var operation = details.getOrDefault("operation", "");
            var durationMs = parseLongDetail(details.get("durationMs"), 0L);
            var depth = (int) parseLongDetail(details.get("depth"), 0L);
            var timestamp = parseLongDetail(details.get("timestamp"), 0L);
            var nodeId = details.getOrDefault("originNodeId", "");
            list.add(new InvocationTraceStore.ClusterTraceEvent(requestId, operation, durationMs, depth, timestamp, nodeId));
        }
        return list;
    }

    private static long parseLongDetail(String raw, long defaultValue) {
        if (raw == null) {return defaultValue;}
        return org.pragmatica.lang.parse.Number.parseLong(raw).or(defaultValue);
    }
}
