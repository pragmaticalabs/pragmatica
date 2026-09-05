// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.net.InetSocketAddress;

import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.api.routes.RetentionRoutes;
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.backup.BackupService;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.config.NamedConfigProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.RollbackManager;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaProjector;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.deployment.membership.fsm.PeerTarget;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumCoConfirmation;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.aether.deployment.membership.phase.ClusterPhaseView;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.deployment.schema.SchemaPolicy;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.dht.AetherMaps;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.HighWaterOwnerEpochGate;
import org.pragmatica.aether.dht.KvCommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.KvOwnerEpochSource;
import org.pragmatica.aether.dht.PartitionOwnerEpochGate;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.AccessibilityFilter;
import org.pragmatica.aether.http.forward.HttpForwardMessage;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.SliceScopedResourceProvider;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage;
import org.pragmatica.aether.node.entityforward.EntityForwardService;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.resource.entity.EntityTimerDriver;
import org.pragmatica.aether.resource.entity.EntityOwnerForward;
import org.pragmatica.aether.resource.entity.EntityForwardRegistry;
import org.pragmatica.aether.resource.entity.EntityKeyspaceRegistrar;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.resource.entity.EntityLinearizableBarrier;
import org.pragmatica.aether.api.ObservabilityBaseline;
import org.pragmatica.aether.api.ObservabilityConfigRegistry;
import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.InvocationMessage;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.ComprehensiveSnapshotCollector;
import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.deployment.generation.PresenceGenerationSnapshotSource;
import org.pragmatica.aether.deployment.generation.SwimHintsRegistry;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.metrics.PeriodicObservationConfig;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.metrics.consensus.RabiaMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsCollector;
import org.pragmatica.aether.metrics.deployment.DeploymentMetricsScheduler;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetricsCollector;
import org.pragmatica.aether.metrics.gc.GCMetricsCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.repository.RepositoryFactory;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.storage.DelegatedStorageAdapter;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.KvStreamOwnerEpochSource;
import org.pragmatica.aether.stream.KvCommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.LinearizableBarrier;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.aether.node.stream.TopicGroupDeclarationSource;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamWriteRouter;
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
import org.pragmatica.aether.stream.replication.ForwardCatchupTransport;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.topic.DlqEnvelope;
import org.pragmatica.aether.stream.topic.DlqStreamSink;
import org.pragmatica.aether.stream.topic.RoutingDeadLetterSink;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.aether.stream.replication.ReplicaWatermarkProbe;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.aether.stream.replication.SelfWatermark;
import org.pragmatica.aether.stream.replication.StreamPartitionRecovery;
import org.pragmatica.aether.stream.replication.WatermarkTracker;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.ttm.AdaptiveDecisionTree;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.worker.bootstrap.WorkerBootstrap;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager;
import org.pragmatica.aether.worker.governor.CommunityMembershipFilter;
import org.pragmatica.aether.worker.governor.DecisionRelay;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.aether.worker.isolation.CoreAbsenceDetector;
import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.aether.worker.group.GroupMembershipTracker;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.SpokesmanPingLoop;
import org.pragmatica.aether.worker.metrics.WorkerMetricsAggregator;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.BuildInfo;
import org.pragmatica.aether.config.ReadLinearizationMode;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.ClusterSyncMessage;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.consensus.net.quic.PeerConnectivityReporter;
import org.pragmatica.consensus.net.quic.PeerTransitionRecord;
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
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicPeerStateListener;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Lazy;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.swim.RotatingGossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.TransportObservation;
import org.pragmatica.swim.HealthSnapshot;
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

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface AetherNode extends ManageableNode {
    Logger LOG = LoggerFactory.getLogger(AetherNode.class);
    /// Read-window upper bound for `events()` on the `system:cluster-events:1.0.0` stream
    /// (B5b — replicated partition transport). The stream's actual retention `maxCount` is now
    /// config-overridable (see [#resolveClusterEventsMaxCount]); this constant is the read window the
    /// aggregator requests and must stay >= that maxCount so a single fetch covers the full retained
    /// window (the B5a/#239 fix). The default retention maxCount equals this value.
    long CLUSTER_EVENTS_MAX_RETAINED = org.pragmatica.aether.api.ClusterEventAggregator.MAX_RETAINED_EVENTS;
    /// #336 — system property carrying the absolute path of the config file this node loaded
    /// (published by [org.pragmatica.aether.Main] where it resolves `--config=`). The CTM
    /// placeholder-resolution path reads it via [#parseOwnResolvedConfig] to source the leader's own
    /// resolved overlay. Unset on the forge / test paths, which then degrade to none() gracefully.
    String CONFIG_PATH_PROPERTY = "aether.config.path";
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
    /// The node's deferred/armed recurring work (#644) — the observation seam the never-started
    /// contract test reads (a created-but-unstarted node must hold zero armed tasks). Implemented by
    /// the node record's component accessor.
    PeriodicTasks periodicTasks();
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
    StreamNamespacesService streamNamespacesService();
    Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver();
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

    @Contract
    void route(Message message);

    /// Test-only fault injection: silently drop all cluster transport traffic to/from this node
    /// without closing connections (peers keep seeing the link as connected). Reproduces a hard
    /// process kill with idle-timeout disabled. See [ClusterNetwork#blackhole(boolean)].
    @Contract
    default void blackhole(boolean enabled) {}

    static Result<AetherNode> aetherNode(AetherNodeConfig config) {
        return aetherNode(config,
                          () -> Runtime.getRuntime().halt(2));
    }

    /// Overload for single-JVM hosting (Forge / Ember). The `jvmExit` hook is invoked by
    /// the node's `DrainProcedure` (membership v2 spec §8.2) when the drain phase completes
    /// — production passes `Runtime.getRuntime().halt(2)`, in-JVM hosts pass a per-node
    /// callback that stops the node gracefully and removes it from the host's registry
    /// without taking down the JVM.
    static Result<AetherNode> aetherNode(AetherNodeConfig config, Runnable jvmExit) {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var nodeCodec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

        return aetherNode(config, delegateRouter, nodeCodec, jvmExit);
    }

    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec) {
        return aetherNode(config,
                          delegateRouter,
                          nodeCodec,
                          () -> Runtime.getRuntime().halt(2));
    }

    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec,
                                         Runnable jvmExit) {
        return config.validate()
                     .flatMap(_ -> createNode(config, delegateRouter, nodeCodec, jvmExit));
    }

    private static Result<AetherNode> createNode(AetherNodeConfig config,
                                                 MessageRouter.DelegateRouter delegateRouter,
                                                 SliceCodec nodeCodec,
                                                 Runnable jvmExit) {
        // #253 owner condition 5: boot REFUSES an encrypted tier whose key cannot be resolved.
        // Resolved BEFORE any other node component so a bad `[storage.encryption]` config (or a
        // missing SecretsProvider) fails boot immediately rather than after partial construction.
        var keyringResolution = resolveStorageEncryptionKeyring(config);

        if (keyringResolution.isFailure()) {
            return keyringResolution.map(ignored -> null);
        }

        var storageKeyring = keyringResolution.fold(_ -> Option.<EncryptionKeyring> empty(), keyring -> keyring);
        Serializer serializer = nodeCodec;
        Deserializer deserializer = nodeCodec;
        var kvStore = new KVStore<AetherKey, AetherValue>(delegateRouter, serializer, deserializer);
        // #345 1c: the per-ownership-domain epoch high-water (data-plane half of the ownership
        // fence) must exist BEFORE the DHT storage engine, because the engine's owner-epoch gate is
        // backed by it. The SAME instance is threaded into assembleNode for its KV-router
        // notification wiring and the ManageableNode accessor — one high-water per node.
        var ownershipEpochHighWater = OwnershipEpochHighWater.ownershipEpochHighWater(kvStore);
        var dhtStorage = MemoryStorageEngine.memoryStorageEngine(HighWaterOwnerEpochGate.highWaterOwnerEpochGate(ownershipEpochHighWater,
                                                                                                                 BootstrapModule.CORE_PARTITION_ID));
        var dhtRing = ConsistentHashRing.<NodeId> consistentHashRing();

        dhtRing.addNode(config.self());
        config.topology().coreNodes().forEach(peer -> dhtRing.addNode(peer.id()));
        var dhtNode = DHTNode.dhtNode(config.self(), dhtStorage, dhtRing, config.artifactRepo());
        var sliceRegistry = SliceRegistry.sliceRegistry();
        var deferredInvoker = DeferredSliceInvokerFacade.deferredSliceInvokerFacade();
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(),
                                               config.topology(),
                                               config.activationGated(),
                                               config.clusterFormation());
        var rabiaMetricsCollector = RabiaMetricsCollector.rabiaMetricsCollector();
        var persistence = resolvePersistence(config);
        var leaderTerm = new AtomicLong(0L);
        Supplier<Long> rabiaTermSupplier = leaderTerm::get;
        // Membership v2: decommission is NTT/membership-driven, not a KV atom. A decommissioned
        // node's process is gone; a restart is a fresh NTT-gated join, so there is no stale KV
        // STOPPED record to refuse rejoin against.
        Predicate<NodeId> isDecommissioned = _ -> false;
        Supplier<Option<NodeId>> currentLeaderFromKvSupplier = () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                      LeaderValue.class)
                                                                            .map(LeaderValue::leader);
        // Leader-acting lease (zero-leader-wedge recovery edge): the leader-election FSM, while in
        // Led(X) where X != self, re-elects when X's leader-stamped ClusterSync ping goes silent
        // for the silence threshold. The freshness signal is the follower's observed leader-ping
        // freshness, read from the ClusterSyncCollector via a deferred holder — the collector is
        // built later (in assembleNode) than the leaderManager (in RabiaNode here), so the
        // predicate dereferences the holder at lease-tick time and degrades to "always fresh"
        // (lease-neutral) while the holder is still null. `hasAuthoritativeReadiness()` returns
        // true on the leader (self-tenure is FSM-exempt anyway) and, on a follower, true iff the
        // believed leader's ping is within the cache TTL — exactly the lease signal.
        var metricsCollectorRef = new AtomicReference<ClusterSyncCollector>();
        Predicate<NodeId> leaderPingFreshFn = _ -> Option.option(metricsCollectorRef.get())
                                                         .map(ClusterSyncCollector::hasAuthoritativeReadiness)
                                                         .or(true);
        var hlcClock = HlcClock.hlcClock(config.self());
        // Membership/placement split (spec §4.1–4.2, step 2), Wave-4 update: membership-delta
        // emission lives in the MembershipDeltaProjector (fed by the MembershipFsm delta edge,
        // wired in assembleNode); TopologyObserver's quorum eval reads the LOCAL FSM projection, not
        // the committed GenerationSnapshot — so NodeRemoved/NodeJoined fire decoupled from
        // consensus commits.
        // memberSupplier returns Set.of() until the FSM exists → PresenceGenerationSnapshotSource
        // yields none() → TopologyObserver falls back to its legacy BOOTING quorum path
        // (CRITICAL for cold-start formation). desiredCoreSize is supplied from
        // ClusterConfigKey.CURRENT (fallback: topology core count) — the FSM doesn't carry it.
        // ctmProvisioned + observedRabiaTerm delegate to the FSM-backed snapshotSource / leader term.
        // Membership-FSM unification (Wave D): deferred holder for the authoritative MembershipFsm.
        // The FSM is constructed later in createNode (after NTT), but several membership CONSUMERS are
        // wired before that point (the DHT livePeers() adapter, the forward-routing accessibility
        // filter, the NTT-reconcile quorum-count propagation). They deref this holder at request time
        // and degrade to their pre-FSM source while it is still null — populated at FSM construction.
        var membershipFsmRef = new AtomicReference<MembershipFsm>();
        // #110 + Wave 2 / W1 (cluster-topology-overhaul spec): generation-snapshot membership
        // source feeding PresenceGenerationSnapshotSource's BOOTING→NORMAL quorum latch + member
        // projection (PresenceMembershipView.coreMemberIds()/healthyOnDutyCount() →
        // TopologyObserver.haveQuorum). Sourced from the authoritative FSM's CORE-SCOPED counting
        // projection (MEMBER + SUSPECT, role=worker excluded; unknown role counts as core) — a
        // worker must never inflate the quorum numerator (1 core + 2 workers is NOT quorum 2 of a
        // 3-core config; Rabia has no voter majority). Role filtering happens HERE, on the aether
        // side of the supplier seam: integrations/consensus cannot name aether roles, so the view
        // and TopologyObserver stay role-agnostic consumers of an already-scoped set. SUSPECT is
        // included and the latch stays edge-driven, as before. The `.or(Set.of())` guards the
        // pre-FSM-published boot window (lazy supplier; the FSM holder is populated before any
        // snapshot is taken).
        // #557 / #558: the quorum numerator reads the OBSERVED-REACHABILITY projection, not the
        // counting one. `coreCountedMembers` includes every member the boot seed promoted from the
        // CONFIGURED topology at wiring time (MembershipFsm.seed, below), so feeding it here made
        // boot-time quorum a statement about configuration rather than reachability: all cores
        // counted before a packet moved, the latch below flipped on its first call, TopologyObserver
        // went BOOTING→NORMAL and declared quorum with zero connections, and RabiaEngine broadcast
        // its SyncRequest into an empty network. It also made TopologyObserver's BOOTING
        // connectivity fallback dead code in production — that path runs only while this source
        // yields none(), which the seed made false before the observer had started.
        // `coreObservedMembers` narrows to members with latched first-hand reachability evidence
        // (QUIC handshake or SWIM ALIVE) plus self, restoring the none()-until-converged intent.
        // Placement / heal-deficit / role-assignment consumers keep reading coreCountedMembers.
        var presenceMemberSupplier = presenceMemberSupplier(membershipFsmRef::get, config.self());
        IntSupplier presenceCoreSizeSupplier = () -> kvStore.get(AetherKey.ClusterConfigKey.CURRENT)
                                                            .filter(v -> v instanceof AetherValue.ClusterConfigValue)
                                                            .map(v -> ((AetherValue.ClusterConfigValue) v).coreCount())
                                                            .or(() -> config.topology()
                                                                            .coreNodes()
                                                                            .size());
        // #114: ctmProvisioned = members whose FSM descriptor source label is "ctm" (CTM
        // auto-provisioned, as opposed to seed/manual). Read DIRECTLY from the FSM. The prior wiring
        // read it from snapshotSource, which itself consumes this supplier to build its membership
        // view — so the first call recursed infinitely (StackOverflowError at boot, no leader elected).
        Supplier<Set<NodeId>> ctmProvisionedSupplier = () -> Option.option(membershipFsmRef.get())
                                                                   .map(AetherNode::ctmProvisionedFromFsm)
                                                                   .or(Set.of());
        var snapshotSource = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(presenceMemberSupplier,
                                                                                               presenceCoreSizeSupplier,
                                                                                               ctmProvisionedSupplier,
                                                                                               rabiaTermSupplier);
        // Membership v2 — `syncHoldRegistry` is consulted by the leader reconciler to skip nodes
        // that are legitimately syncing KV state. The KVSyncResponse signal no longer drives a
        // readiness candidate; the v2 control-heartbeat carries node-reported readiness instead.
        var syncHoldRegistry = org.pragmatica.cluster.node.rabia.SyncHoldRegistry.syncHoldRegistry();
        var syncHoldConfig = org.pragmatica.cluster.node.rabia.SyncHoldConfig.defaults();
        Runnable onSyncResponseReceived = () -> {};

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
                                   leaderPingFreshFn,
                                   snapshotSource,
                                   hlcClock::now,
                                   syncHoldRegistry,
                                   syncHoldConfig,
                                   onSyncResponseReceived)
                        .flatMap(clusterNode -> assembleNode(config,
                                                             delegateRouter,
                                                             kvStore,
                                                             sliceRegistry,
                                                             deferredInvoker,
                                                             clusterNode,
                                                             rabiaMetricsCollector,
                                                             serializer,
                                                             deserializer,
                                                             nodeCodec,
                                                             dhtNode,
                                                             ownershipEpochHighWater,
                                                             leaderTerm,
                                                             hlcClock,
                                                             snapshotSource,
                                                             membershipFsmRef,
                                                             metricsCollectorRef,
                                                             syncHoldRegistry,
                                                             jvmExit,
                                                             storageKeyring));
    }

    /// #253 — resolves the boot-time storage-encryption keyring before any storage tier exists. No
    /// `[storage.encryption]` block at all is the common, unencrypted case and resolves to
    /// `Option.empty()` without touching `SecretsProvider`. A `[storage.encryption]` block with no
    /// `SecretsProvider` wired (`config.environment()` empty, or its `secrets()` empty) can never
    /// resolve any key, so it fails closed here instead of silently booting unencrypted.
    private static Result<Option<EncryptionKeyring>> resolveStorageEncryptionKeyring(AetherNodeConfig config) {
        return config.storageEncryption()
                     .fold(() -> Result.success(Option.empty()),
                           encryptionConfig -> config.environment()
                                                     .flatMap(EnvironmentIntegration::secrets)
                                                     .fold(() -> Result.<Option<EncryptionKeyring>> failure(new NoSecretsProviderForStorageEncryption()),
                                                           provider -> StorageEncryption.resolveKeyring(provider,
                                                                                                        encryptionConfig).map(Option::some)));
    }

    /// `[storage.encryption]` is configured but no `SecretsProvider` is wired -- every configured
    /// key reference would be unresolvable, so boot fails rather than silently starting unencrypted.
    record NoSecretsProviderForStorageEncryption() implements Cause {
        @Override
        public String message() {
            return "storage.encryption is configured but no SecretsProvider is available "
                 + "(environment integration is absent or does not provide secrets)";
        }
    }

    private static RabiaPersistence<KVCommand<AetherKey>> resolvePersistence(AetherNodeConfig config) {
        return config.backupConfig()
                     .filter(b -> !b.path()
                                    .isBlank())
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

    /// NTT reconcile fan-out (membership v2). Fired once per stable presence-sensor membership
    /// transition AND (Wave 7) once per FSM death edge — both gated upstream so an already-handled
    /// re-fire is a no-op. Propagates the FSM member count to the quorum-loss detector and nudges
    /// the leader reconciler.
    @Contract
    private static void onNttReconcile(AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                       AtomicReference<MembershipFsm> membershipFsmRef,
                                       AtomicReference<LeaderReconciler> leaderReconcilerRef,
                                       NodeId self) {
        Option.option(membershipFsmRef.get()).onPresent(fsm -> propagateMemberCount(quorumLossDetectorRef, fsm, self));
        Option.option(leaderReconcilerRef.get()).onPresent(LeaderReconciler::onTopologyUnhealthy);
    }

    /// Feed the quorum-loss detector the current member count. Wave 2 / W1 of the
    /// cluster-topology-overhaul spec (adopting membership-fsm-unification §6): the numerator is
    /// the FSM's STRICT CORE count — state exactly MEMBER, role=core; SUSPECT excluded, workers
    /// excluded. A worker must never hold the
    /// quorum-loss detector above threshold (1 core + 2 workers is NOT a quorum of a 3-core
    /// config — Rabia has no voter majority). The strict MEMBER-only numerator is safe against
    /// premature self-drain on a transient SUSPECT because the detector only fires after the
    /// `quorumLossDrainThreshold` window elapses below threshold — a flap that recovers within
    /// the window cancels the pending intent. Wave 7 (parallel-view collapse): the legacy
    /// pre-FSM fallback to the sampler's role-blind `currentMemberCount` is GONE — the FSM is
    /// published before the sampler's first 1s tick can fire this path, so the propagation is
    /// simply skipped in the (unreachable in practice) pre-FSM window; the next reconcile
    /// trigger after FSM publication propagates.
    ///
    /// #557: the count is [`MembershipFsm#strictCoreObservedMemberCount`], i.e. the strict core
    /// count narrowed to OBSERVED reachability. The plain strict count is seed-derived — every
    /// configured core is MEMBER from wiring time — which armed the detector's
    /// arm-after-first-quorum latch on configuration alone, before the cluster had formed. That
    /// latch is specified as "has this cluster ever been quorate"; only the observed count means it.
    @Contract
    private static void propagateMemberCount(AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                             MembershipFsm membershipFsm,
                                             NodeId self) {
        var memberCount = membershipFsm.strictCoreObservedMemberCount(self);

        Option.option(quorumLossDetectorRef.get()).onPresent(detector -> detector.onMemberCountChanged(memberCount));
    }

    /// True iff this transition crosses the exact-`Member` boundary — i.e. the strict core member
    /// count ([`MembershipFsm#strictCoreMemberCount`], the quorum-loss numerator) changes. Feeding the
    /// detector on this edge (NOT only on the 15s presence down-hysteresis / DEAD edge) is what lets a
    /// node recognize quorum loss promptly when several cores enter SUSPECT at once — the design's own
    /// safety argument (propagateMemberCount javadoc) already assumes this feed; the splitTimeout window
    /// + refutation-cancel + Fix-C co-confirmation guard against a transient SUSPECT flap.
    private static boolean crossesMemberBoundary(MembershipTransitionRecord record) {
        return "Member".equals(record.fromState()) != "Member".equals(record.toState());
    }

    /// Build the Fix C co-confirmation snapshot at the detector's firing instant. The counted
    /// denominator is the FSM's role-scoped MEMBER+SUSPECT set ([`MembershipFsm#coreCountedMembers`],
    /// includes self); the STUCK set is the counted members ABSENT from the strict MEMBER-only count
    /// ([`MembershipFsm#strictCoreMembers`]) — promotions that have not completed. The stuck set is
    /// PARTITIONED per-member by RAW SwimProtocol health (HEALTHY or SUSPECTED → alive) using the
    /// same non-FSM signal Fix A uses; the per-member sufficiency predicate counts only the alive
    /// stuck members toward effective quorum, so a genuinely partitioned member (FAULTY / UNKNOWN —
    /// no probe-acks reach this node) contributes 0 and the genuine self-fence proceeds, while a
    /// single dead member no longer vetoes suppression for the reachable rest.
    private static QuorumCoConfirmation buildQuorumCoConfirmation(MembershipFsm membershipFsm,
                                                                  CoreSwimHealthDetector swimHealthDetector) {
        var counted = membershipFsm.coreCountedMembers();
        var strict = membershipFsm.strictCoreMembers();
        var stuck = counted.stream().filter(id -> !strict.contains(id)).toList();
        var swimAliveStuck = stuck.stream().filter(id -> swimAliveForCoConfirmation(swimHealthDetector, id)).toList();
        var swimDeadStuck = stuck.stream().filter(id -> !swimAliveForCoConfirmation(swimHealthDetector, id)).toList();

        return QuorumCoConfirmation.quorumCoConfirmation(strict.size(), counted.size(), swimAliveStuck, swimDeadStuck);
    }

    /// Raw-SWIM aliveness for the Fix C stuck-member discrimination: a stuck (SUSPECT) member counts
    /// as alive when the SWIM protocol layer reports HEALTHY or SUSPECTED — distinguishing a wedged
    /// promotion (reachable, refuting) from a real partition (FAULTY / UNKNOWN).
    private static boolean swimAliveForCoConfirmation(CoreSwimHealthDetector swimHealthDetector, NodeId id) {
        var health = swimHealthDetector.healthOf(id);

        return health == SwimHealth.HEALTHY || health == SwimHealth.SUSPECTED;
    }

    /// FSM death-edge fan-out (Wave 7, cluster-topology-overhaul): invoked once per fresh edge into
    /// DEAD via [`MembershipFsm#onConfirmedDeparture`]. Drops the dead peer's QUIC link promptly
    /// (`departurePermanent`, idempotent) and runs the same heal/quorum nudge the deleted
    /// FSM→PresenceSampler eviction used to trigger through the sampler's reconcile callback —
    /// behaviour parity with the sampler removed from the authority loop.
    @Contract
    private static void onMembershipDeath(NodeId departed,
                                          Consumer<NodeId> dropDeadPeerLink,
                                          AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                          AtomicReference<MembershipFsm> membershipFsmRef,
                                          AtomicReference<LeaderReconciler> leaderReconcilerRef,
                                          NodeId self) {
        dropDeadPeerLink.accept(departed);
        onNttReconcile(quorumLossDetectorRef, membershipFsmRef, leaderReconcilerRef, self);
    }

    /// Operator/controller drain sink (M10, cluster-topology-overhaul Wave 7): every drain command
    /// is BOTH enqueued into the `DrainCommandRegistry` (the leader's cluster-sync ping carries it;
    /// the target self-drains via its `DrainProcedure`, unchanged) AND routed through the
    /// authoritative `MembershipFsm` as a `DrainRequested` event — the target transitions to
    /// DEPARTING (stops counting toward effective, H2 timeout terminalizes it if it goes silent,
    /// higher-incarnation `SwimHealthy` recovers it mid-drain) instead of the drain bypassing the
    /// FSM entirely. The FSM holder is deref'd lazily: the registry-backed sinks are wired before
    /// the FSM exists; pre-FSM the registry path alone applies (boot window only).
    @Contract
    private static void requestDrainThroughFsm(DrainCommandRegistry drainCommandRegistry,
                                               AtomicReference<MembershipFsm> membershipFsmRef,
                                               NodeId target) {
        drainCommandRegistry.requestDrain(target);
        Option.option(membershipFsmRef.get()).onPresent(fsm -> fsm.onDrainRequested(target));
    }

    long DEFAULT_STREAM_RETENTION_MS = 24 * 60 * 60 * 1000L;
    /// A4 catch-up: number of events pulled per forward-read page during partition backfill.
    int STREAM_CATCHUP_BATCH_SIZE = 256;
    /// A4 periodic backfill re-drive interval. `reconcilePartition` fires `onBecameReplica → backfill`
    /// ONCE per reconcile edge (no built-in retry), so a cold-start replica that observed NO_SOURCE on
    /// its first attempt would never re-attempt and stay SYNCING forever. This periodic re-drive
    /// re-invokes backfill for every partition where self is a registered, not-yet-CAUGHT_UP replica, so
    /// the owner-immediate / bounded-wait cold-start promotion is actually reached and non-owners
    /// re-attempt once the owner becomes CAUGHT_UP. Kept well under the backfill source-wait bound (20s)
    /// so several attempts occur within one wait window; CAUGHT_UP partitions are skipped (no-op).
    TimeSpan STREAM_BACKFILL_REDRIVE_INTERVAL = TimeSpan.timeSpan(5).seconds();
    /// Cadence for the metadata snapshot driver (A3b). Drives `SnapshotManager.maybeSnapshot()` across
    /// every storage setup; the manager self-gates on its mutation-count / time-interval trigger, so
    /// this tick only needs to be frequent enough to bound the post-trigger snapshot latency.
    TimeSpan METADATA_SNAPSHOT_INTERVAL = TimeSpan.timeSpan(10).seconds();
    /// Cadence for the WAL disk-reclamation driver (streaming-persistence W5). Periodically truncates
    /// every partition's write-ahead log up to its DURABLE last-sealed offset, dropping records already
    /// persisted in cold segments so the WAL does not grow unbounded. `truncate` is threshold-lazy
    /// (cheap when nothing new has sealed), so a modest 30s tick keeps the WAL bounded without contending
    /// with the publish fsync path.
    TimeSpan WAL_TRUNCATE_INTERVAL = TimeSpan.timeSpan(30).seconds();
    /// How long a forwarded entity command waits for its owner before failing (#596).
    /// Deliberately finite: the caller must learn the write did not land rather than hang, and
    /// the sender must NEVER fall back to applying locally — that is a second writer on the key.
    TimeSpan ENTITY_FORWARD_TIMEOUT = TimeSpan.timeSpan(30).seconds();

    /// Cadence for the NodeDeploymentManager activation level-heal. On a cold-start
    /// `restart_all_nodes` the single CAS-latched `ClusterStateNotification.ACTIVE` edge can be
    /// dropped while the message-router delegate is being rebuilt, leaving NDM stuck in `Dormant`
    /// (never runs `Active.onEntry` → self-ready → `subsystemsReady`) so the node reports `SYNCING`
    /// forever despite live consensus-active. A periodic tick gated on `clusterNode.isActive()`
    /// re-dispatches `QuorumEstablished` only while still Dormant; matches the 5s reconcile cadence
    /// and is a no-op on every healthy node thereafter.
    TimeSpan NDM_ACTIVATION_RECONCILE_INTERVAL = TimeSpan.timeSpan(5).seconds();

    /// Cadence for the #265 increment-5 reshuffle-lifecycle reconcile
    /// ({@link StreamPartitionManager#reconcileReshuffle}): frees reshuffle-concurrency slots for
    /// finished/lost partitions, evaluates release candidates (role-loss debounce → catch-up + owner gate →
    /// release), and drains the materialization queue. Matches the 5s reconcile cadence, so the
    /// {@code RELEASE_DEBOUNCE_TICKS = 2} flap-debounce window is ≈10s wall-clock (spec §5.4). A no-op tick is
    /// cheap (a sweep of the materialized partitions + empty queues) so a steady-state node pays almost nothing.
    TimeSpan STREAM_RESHUFFLE_RECONCILE_INTERVAL = TimeSpan.timeSpan(5).seconds();
    /// Declarative stream-consumer ownership poll (#488). No role-change callback is available to a
    /// third party, so partition ownership is polled; matching the reshuffle cadence keeps the
    /// handover window (during which the old and new owner may both deliver) at one tick.
    TimeSpan STREAM_CONSUMER_RECONCILE_INTERVAL = TimeSpan.timeSpan(5).seconds();
    /// Durable-entity ownership reconcile (#345 I1, narrow C). Level-triggered, not event-triggered: a
    /// keyspace is declared when a slice is DEPLOYED, which is not a membership change, so the
    /// stream-side ownership pass (driven off `ReplicaSetController` reconciles) would not fire for it
    /// and the arcs would stay unowned — leaving every entity write refused — until some unrelated
    /// topology change happened to come along. Each tick makes this node's committed per-node keyspace
    /// records equal its locally-declared set (asserting declared keyspaces, pruning retracted or
    /// no-longer-hosted ones) and, on the leader only, mints the arcs' ownership records over each
    /// keyspace's registered hosts; both halves are idempotent, so a steady-state tick commits nothing.
    /// Matched to the reshuffle cadence so an entity arc's owner converges in the same window a stream
    /// partition's does.
    TimeSpan ENTITY_OWNERSHIP_RECONCILE_INTERVAL = TimeSpan.timeSpan(5).seconds();
    /// How often each entity partition folds to a durable checkpoint (#345 I3).
    ///
    /// This is a safety bound, not a tuning knob. A new owner recovers from the checkpoint plus whatever
    /// of the log its own ring still holds, so the interval must stay comfortably INSIDE ring retention —
    /// if the checkpoint falls further behind than the ring reaches back, the records in between are on no
    /// reachable node and the fold refuses rather than serving state missing committed writes. Thirty
    /// seconds against a ring measured in minutes leaves a wide margin; raising it narrows the margin
    /// toward that refusal.
    TimeSpan ENTITY_CHECKPOINT_INTERVAL = TimeSpan.timeSpan(30).seconds();
    /// The entity-timer tick, and therefore the punctuality contract itself: a timer fires at the first
    /// tick at or after its due instant, so this value IS the worst-case lateness a scheduled timer can
    /// see, plus however long its partition goes without a live owner.
    ///
    /// **A constant, deliberately, not a config knob.** #351 promises no punctuality anywhere, so this
    /// choice CREATES the de-facto contract rather than implementing one — and a knob nobody reads would
    /// leave that contract undefined per deployment. It becomes configurable when a real consumer needs a
    /// different value, not before.
    ///
    /// One second is chosen against the consumers this mechanism is for — reservation expiry, saga
    /// timeouts, and similar minutes-scale due times, where a second of lateness is invisible and the
    /// thirty above would be sloppy. **Sub-second punctuality is explicitly not this mechanism's job**: a
    /// deadline measured in milliseconds wants in-memory scheduling, which buys that precision by giving
    /// up durability across an owner change.
    TimeSpan ENTITY_TIMER_INTERVAL = TimeSpan.timeSpan(1).seconds();
    /// #634-4 periodic invariant check — the tri-floor retention invariant is evaluated on every
    /// `/api/storage/retention` read, but a violation nobody polls for must still reach an operator:
    /// the metrics-threshold alert path only runs while a dashboard client is connected. Five minutes
    /// matches the RetentionEnforcer cadence — the only mover that can newly violate between checks.
    TimeSpan RETENTION_INVARIANT_CHECK_INTERVAL = TimeSpan.timeSpan(5).minutes();

    /// Per-node, restart-stable data dir for the disk-backed stream storage. Derived as a sibling of
    /// the node's artifact disk path (the artifacts/content convention, `StorageConfig.diskPath()`)
    /// suffixed with the node id, so that (a) it lives under the same durable `${data.dir}` mount in
    /// production, (b) it is distinct per node — critical for a multi-node `EmberCluster` running in a
    /// single JVM, which would otherwise share one segment dir and collide on the `latest` snapshot
    /// link and content-addressed blocks — and (c) it is stable across a same-node restart because the
    /// node id is stable, so sealed segments are reclaimed on reboot.
    private static Path streamDataDir(AetherNodeConfig config) {
        var artifactsConfig = Option.option(config.storageConfig().get("artifacts")).or(StorageConfig.storageConfig());

        return Path.of(artifactsConfig.diskPath())
                   .resolveSibling("stream-segments")
                   .resolve(config.self().id());
    }

    /// The per-node stream WAL BASE directory (#634-3): the `streams` storage instance's explicit
    /// `wal_path` when set, else the derived pre-#634-3 sibling (`<streamDataDir>/wal`). The node-id
    /// suffix on the explicit path keeps co-located nodes (EmberCluster, containers sharing a mount)
    /// from ever sharing one WAL dir — the same invariant `streamDataDir` enforces, independent of
    /// operator input.
    private static Path streamWalBaseDir(AetherNodeConfig config) {
        var streamsConfig = Option.option(config.storageConfig().get("streams")).or(StorageConfig.storageConfig());

        return effectiveWalBase(streamsConfig, config.self(), streamDataDir(config).resolve("wal"));
    }

    /// The pure wal-path decision, package-visible so the SUFFIX invariant is pinned (review m10
    /// verdict: required): an explicit `wal_path` is ALWAYS suffixed with the node id — two co-located
    /// nodes sharing one WAL directory is corruption, and this is the one line keeping that
    /// independent of operator input. The derive branch passes the pre-#634-3 path through verbatim.
    static Path effectiveWalBase(StorageConfig streamsConfig, NodeId self, Path derivedWalDir) {
        return streamsConfig.hasExplicitWalPath()
               ? Path.of(streamsConfig.walPath()).resolve(self.id())
               : derivedWalDir;
    }

    /// The per-node stream WAL directory when it is writable.
    ///
    /// An UNWRITABLE dir is a BOOT ERROR by default (#634 item 2). The old behaviour — one startup WARN,
    /// then every publish acks with no fsync — silently converted "durable entity" into "in-memory
    /// entity": the ack path (`durablyLog`) degrades to `success(offset)` when the WAL is absent, and
    /// nothing downstream can tell. A node that cannot honour the durability its streams declare must
    /// say so at the moment an operator is looking at it, not in a log line nobody reads back.
    ///
    /// Explicitly best-effort deployments (Forge on a read-only mount, dev profiles) opt in with the
    /// `aether.allowNonDurableStreams=true` system property or `AETHER_ALLOW_NON_DURABLE_STREAMS=true`
    /// env — and keep exactly the previous degrade-with-WARN.
    private static Option<Path> resolveStreamWalDir(AetherNodeConfig config) {
        var walDir = streamWalBaseDir(config);

        return FileOps.createDirectories(walDir).fold(cause -> walDisabled(walDir, cause), _ -> Option.some(walDir));
    }

    /// #634 item 2 — the boot gate, exposed for [org.pragmatica.aether.Main]'s verification chain (the
    /// same `verify* -> abortBoot` idiom as the cluster-name and dev-mode gates). An unwritable WAL dir
    /// without the explicit non-durable opt-in REFUSES BOOT: the old behaviour — one WARN, then every
    /// publish acks with no fsync — silently converted "durable entity" into "in-memory entity".
    ///
    /// Enforced at the PRODUCTION entrypoint only, deliberately: a node constructed directly
    /// (Forge, tests, embedded harnesses) never passes through Main and keeps the degrade-with-WARN in
    /// [#resolveStreamWalDir] — those are exactly the explicitly-best-effort deployments the opt-in
    /// exists for.
    public static Result<Unit> verifyWalBootable(AetherNodeConfig config) {
        var walDir = streamWalBaseDir(config);

        return decideWalAvailability(walDir,
                                     FileOps.createDirectories(walDir).mapToUnit(),
                                     nonDurableStreamsAllowed()).mapToUnit();
    }

    /// The #492-class killer (#634 structural follow-up): every ROUTED `Message.Wired` type must have
    /// a codec at boot, or the node REFUSES to start — the alternative, lived twice, is a generated
    /// codec registry that exists but was never aggregated, so every message of that type silently
    /// vanishes at the transport (runs 2–5 burned on exactly this, with zero log lines until the
    /// encode path was made loud). `Message.Local` types are structurally exempt — the sealed
    /// hierarchy is the discriminator, so no exemption list can rot. The check accepts a
    /// supertype-fallback resolution (`hasCodecFor` semantics), so it proves "SOME codec resolves",
    /// not "this type's own generated codec is aggregated" — the realistic #492 shape (a whole
    /// unaggregated registry) is caught because the family's types vanish together, while a NEW
    /// subtype of an already-aggregated family would boot and encode through a registered supertype:
    /// a narrower residual, recorded here. Accumulates ALL missing types so
    /// one boot failure reports the whole set. Known limit, recorded deliberately: this covers types
    /// this node ROUTES (receives); a Wired type only ever SENT and never routed locally is not seen
    /// here — the transport-side loud encode failure is the net under that gap.
    static Result<Unit> verifyRoutedTypesEncodable(List<? extends MessageRouter.Entry<?>> entries, SliceCodec codec) {
        var missing = entries.stream()
                             .flatMap(AetherNode::routedClasses)
                             .distinct()
                             .filter(Message.Wired.class::isAssignableFrom)
                             .filter(type -> !codec.hasCodecFor(type))
                             .map(Class::getName)
                             .sorted()
                             .toList();

        return missing.isEmpty()
               ? Result.unitResult()
               : Causes.cause("routed wire type(s) have NO registered codec — messages of these types would"
                             + " silently vanish at the transport (the #492 class): " + String.join(", ", missing)
                             + ". A generated *CodecsNode registry probably exists but is not aggregated into"
                             + " NodeCodecs.").result();
    }

    /// Failed-boot-guard cleanup for single-JVM hosts: discard the periodic-task thunks this assembly
    /// deferred (#644 — nothing was scheduled yet at guard time, so a refused node leaves nothing
    /// ticking on the JVM-global scheduler; pre-#644 this raced to cancel timers already armed, the
    /// #499 zombie class), stop the started samplers, and cancel the two CONSTRUCTOR-armed cleanup
    /// ticks the deferral cannot reach (#644's outside-the-list finding): `SliceInvoker`'s
    /// stale-invocation sweep and `AdaptiveSampler`'s rate recalculation arm in their constructors,
    /// their stop hooks are reached on the NORMAL path by `stop()`, and before this line they leaked
    /// forever on exactly this failure path. (The third constructor-armed site, `RabiaEngine`'s
    /// phase cleanup, is reachable only through `clusterNode.stop()` — a never-started cluster stack
    /// teardown this guard deliberately does not attempt; recorded on #644.) Mirrors the head of
    /// `stop()`.
    @Contract
    private static void cancelArmedWork(PeriodicTasks periodicTasks,
                                        PresenceSampler presenceSampler,
                                        CoreAbsenceDetector coreAbsenceDetector,
                                        SliceInvoker sliceInvoker,
                                        ObservabilityBaseline observabilityBaseline) {
        periodicTasks.cancel();
        presenceSampler.stop();
        coreAbsenceDetector.stop();
        sliceInvoker.stop();
        observabilityBaseline.sampler().onPresent(AdaptiveSampler::stop);
    }

    /// Typed extraction (a raw `Entry` here erases `entries()` to a raw Stream and the tuple to
    /// `Object`): each route entry's registered message classes, widened to `Class<?>`.
    private static Stream<Class<?>> routedClasses(MessageRouter.Entry<?> entry) {
        return entry.entries()
                    .map(tuple -> (Class<?>) tuple.first());
    }

    /// The decision, separated from the probe and the abort so the gate is directly testable: the boot
    /// guard is the part most likely to be "simplified" into a silent-degrade regression.
    static Result<Option<Path>> decideWalAvailability(Path walDir, Result<Unit> probe, boolean allowNonDurable) {
        return probe.fold(cause -> allowNonDurable
                                   ? Result.success(walDisabled(walDir, cause))
                                   : walUnavailable(walDir, cause),
                          _ -> Result.success(Option.some(walDir)));
    }

    private static Result<Option<Path>> walUnavailable(Path walDir, Cause cause) {
        return Causes.cause("stream WAL directory " + walDir
                           + " is not writable (" + cause.message()
                           + ") — refusing to boot: streams on this node would ack writes with NO fsync,"
                           + " silently converting durable entities into in-memory ones. Fix the mount, or"
                           + " opt in to non-durable streams explicitly with -Daether.allowNonDurableStreams=true"
                           + " (env AETHER_ALLOW_NON_DURABLE_STREAMS=true) for best-effort dev/Forge profiles").result();
    }

    private static boolean nonDurableStreamsAllowed() {
        return Boolean.getBoolean("aether.allowNonDurableStreams") || "true".equalsIgnoreCase(System.getenv("AETHER_ALLOW_NON_DURABLE_STREAMS"));
    }

    private static Option<Path> walDisabled(Path walDir, Cause cause) {
        LOG.warn("Stream WAL disabled ({} not writable): {} — streaming runs non-crash-durable (in-memory tail)"
                + " [explicitly allowed by aether.allowNonDurableStreams]",
                 walDir,
                 cause.message());

        return Option.none();
    }

    /// Fold the `streams` StorageSetup into the (immutable) map produced by `StorageFactory.createAll`
    /// so that downstream consumers — storage-status routes today, the snapshot scheduler later —
    /// treat stream storage uniformly with artifacts/content.
    private static Map<String, StorageFactory.StorageSetup> withStreamSetup(Map<String, StorageFactory.StorageSetup> base,
                                                                            StorageFactory.StorageSetup streamSetup) {
        var merged = new HashMap<>(base);

        merged.put(streamSetup.name(), streamSetup);

        return Map.copyOf(merged);
    }

    /// Build a named daemon thread for a single-thread executor's `ThreadFactory`. Extracted so the
    /// executor factories pass a method-reference-friendly single-expression lambda instead of a
    /// multi-statement block.
    private static Thread daemonThread(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);

        thread.setDaemon(true);

        return thread;
    }

    /// Periodic backfill re-drive (A4): for every partition where self is a registered, not-yet-CAUGHT_UP
    /// replica — PLUS a CAUGHT_UP non-owner replica still stale against the HRW owner (#333 write-idle
    /// residual) — re-submit `backfill` onto the dedicated backfill executor. Idempotent and bounded —
    /// {@link PartitionBackfill#redriveCandidates} enumerates the registry (owner-aware, offset-quiesced),
    /// so once everything has genuinely caught up this is a no-op. Runs off the periodic scheduler tick;
    /// backfill itself stays on `backfillExecutor` (its dedicated thread), so no competing thread pool is
    /// introduced.
    @Contract
    private static void redriveIncompleteBackfills(PartitionBackfill backfill, Executor backfillExecutor) {
        backfill.redriveCandidates()
                .forEach(key -> backfillExecutor.execute(() -> backfill.backfill(key.streamName(),
                                                                                 key.partition())));
    }

    /// #265 increment 2 materialize-on-reconcile hook. Fired (on the backfill executor) behind the
    /// controller's `onBecameReplica` seam — which triggers the instant `self` joins a partition's replica
    /// set (owner-inclusive: the HRW owner is index 0 of the owner-first replica set). It MATERIALIZES the
    /// held partition's ring FIRST, then runs the existing backfill. This resolves the spec §5.4
    /// defer-then-materialize case: a config committed before placement was known is hydrated metadata-only,
    /// and the ring is built here the moment the role resolves to OWNER/REPLICA. Materialize is idempotent
    /// (a ring built at hydrate time is a no-op) and runs before backfill so the recovered events have a
    /// ring to land in; a materialize failure is logged and backfill still attempts (the owner-append
    /// safety valve is the backstop).
    @Contract
    private static void materializeThenBackfill(StreamPartitionManager manager,
                                                PartitionBackfill backfill,
                                                String streamName,
                                                int partition) {
        manager.materializePartition(streamName, partition)
               .onFailure(cause -> LOG.warn("Stream partition {}[{}] materialize-on-reconcile failed: {} — backfill will still attempt",
                                            streamName,
                                            partition,
                                            cause.message()));
        backfill.backfill(streamName, partition);
    }

    /// #445 single-sourced placement view: the backfill orchestrator ranks its owner/member view over the
    /// SAME reconciled snapshot [ReplicaSetController#roleFor] / [ReplicaSetController#ownerFor] and the
    /// registry reconcile use ([ReplicaSetController#reconciledMembers]) — NOT an independent LIVE
    /// `observer().coreNodes()` read. This removes the live-vs-reconciled split that let a node the owner
    /// registers as a replica self-classify NONE — never materializing its ring, bouncing every apply
    /// `PARTITION_NOT_LOCAL`, leaving RF≥2 structurally unreachable. Before the controller is bound / its
    /// first reconcile it falls back to the live topology observer, the SAME pre-reconcile fallback
    /// `roleFor` uses, so cold-start owner-immediate self-promotion is unaffected.
    private static List<NodeId> streamPlacementMembers(AtomicReference<ReplicaSetController> controllerRef,
                                                       ClusterTopologyManager topologyManager) {
        return Option.option(controllerRef.get())
                     .map(ReplicaSetController::reconciledMembers)
                     .or(() -> List.copyOf(topologyManager.observer().coreNodes()));
    }

    /// #265 increment 5: the live replica catch-up view for the release gate + slot completion, read from the
    /// [ReplicaRegistry]. `caughtUpReplicaCount` is the number of OTHER (non-self) replicas of the current
    /// registered replica set that have reached CAUGHT_UP (the release catch-up gate compares it against the
    /// effective, clamped RF); `selfCaughtUp` is whether THIS node has finished backfilling the partition (so
    /// its reshuffle slot may free). Self is excluded from the count so the "≥ RF caught-up remain AFTER I
    /// release" invariant holds regardless of whether the controller has already unregistered self.
    ///
    /// The OTHERS count goes through [ReplicaRegistry#freshPeersFor], which additionally requires each peer to
    /// be within the configured lag bound of the freshest peer watermark. `CAUGHT_UP` never downgrades, so a
    /// peer that stopped acking stays CAUGHT_UP forever; counting it raw let an owner release its ring
    /// believing enough replicas were caught up when they had frozen. That method is shared with
    /// `ForwardingReadRouter` on purpose — a guard applied at one reader and not the other is the same
    /// half-applied fix that left #590 live at the placement grain.
    ///
    /// `selfCaughtUp` stays on the RAW state: a node never acks itself, so its own descriptor keeps the
    /// `SYNCING` / `-1` seed (#593) and its `CAUGHT_UP` comes from backfill completion, not the ack path.
    /// Lag-checking a self row would report staleness on a healthy owner.
    private static StreamPartitionManager.ReplicaCatchupSource.CatchupView streamCatchupView(ReplicaRegistry registry,
                                                                                             NodeId self,
                                                                                             String stream,
                                                                                             int partition) {
        var replicas = registry.replicasFor(stream, partition);
        var caughtUpOthers = registry.freshPeersFor(stream, partition, self).size();
        var selfCaughtUp = replicas.stream()
                                   .anyMatch(descriptor -> descriptor.nodeId()
                                                                     .equals(self) && descriptor.state() == ReplicationState.CAUGHT_UP);

        return new StreamPartitionManager.ReplicaCatchupSource.CatchupView(caughtUpOthers, selfCaughtUp);
    }

    /// Partition ownership as the declarative stream-consumer manager sees it (#488/#535).
    ///
    /// `partitionCount` reads the replica catalog, which is hydrated from committed `StreamConfig`; an
    /// absent stream yields none() and the manager consumes nothing for it — the transient state before
    /// a stream's config reaches this node, not an error. `ownerOf` is the HRW owner test, the same pure
    /// placement function the partition manager itself gates materialization on. `liveMembers` is the
    /// controller's OWN reconciled view, deliberately the same one its ownership decisions are computed
    /// from, so the ownership half and the placement half of an assignment cannot disagree about which
    /// nodes are alive.
    private static StreamConsumerManager.PartitionOwnership streamConsumerOwnership(StreamPartitionManager manager,
                                                                                    ReplicaSetController controller) {
        record ownership(StreamPartitionManager manager, ReplicaSetController controller) implements StreamConsumerManager.PartitionOwnership {
            @Override
            public Option<Integer> partitionCount(String streamName) {
                return manager.replicaCatalog()
                              .streams()
                              .stream()
                              .filter(spec -> spec.name()
                                                  .equals(streamName))
                              .findFirst()
                              .map(spec -> Option.some(spec.partitions()))
                              .orElseGet(Option::none);
            }

            @Override
            public Option<NodeId> ownerOf(String streamName, int partition) {
                return controller.ownerFor(streamName, partition);
            }

            @Override
            public List<NodeId> liveMembers() {
                return controller.reconciledMembers();
            }
        }

        return new ownership(manager, controller);
    }

    /// Where `artifact` is deployed cluster-wide, from this node's locally mirrored deployment map
    /// (#535).
    ///
    /// `DeploymentMap` is fed by `NodeArtifactKey` KV notifications on EVERY node, so this answers the
    /// "where can this consumer run?" half of an assignment with no consensus round and no leader. The
    /// full per-node STATE is handed over rather than a pre-filtered node list because the manager needs
    /// to tell "ACTIVE nowhere" from "deployed but not ACTIVE yet" — only the first is a gap worth an
    /// alarm, and the second is what every normal deploy looks like for a few seconds.
    private static Map<NodeId, SliceState> sliceDeploymentStates(DeploymentMap deploymentMap, Artifact artifact) {
        return deploymentMap.byArtifact(artifact);
    }

    /// #265 increment 5: the committed-ownership release guard. Safe to release (on the ownership axis) when
    /// the committed `StreamPartitionOwnershipValue.owner` names a node OTHER than self, or no ownership record
    /// is committed yet. While the committed owner is still self, the node holds the ring until the 1d-iii
    /// reshuffle driver commits the ownership change — a deposed-but-still-committed owner must not release.
    private static boolean committedOwnerElsewhere(CommittedStreamOwnerSource source,
                                                   NodeId self,
                                                   String stream,
                                                   int partition) {
        return source.committedOwner(stream, partition)
                     .map(owner -> !owner.owner()
                                         .equals(self))
                     .or(true);
    }

    /// Whether a committed stream-partition owner is still a live cluster member (#568).
    ///
    /// Committed stream ownership has no relinquish path other than the owner releasing it, so a record
    /// whose holder has died outlives it indefinitely and wedges the partition — see the wiring comment on
    /// `streamCommittedOwnerSource`. Treating a dead holder's record as absent is what lets HRW ownership
    /// take effect, and it closes the class: any partition whose committed owner dies is otherwise exposed.
    ///
    /// [`MembershipFsm#countedMembers`] is the correct set on two counts. It is ROLE-BLIND — a stream owner
    /// may be a worker, so the core-scoped projection would wrongly evict worker-owned partitions. And it is
    /// MEMBER + SUSPECT — a transient SUSPECT keeps its ownership (ownership must not flap on a link
    /// wobble), while only a confirmed DEAD member, already removed from the counted set, releases it.
    ///
    /// **Empty membership means "cannot judge", not "nobody is alive".** During the boot window the FSM has
    /// no members; answering `false` there would reject every committed owner and reintroduce exactly the
    /// #491 F4 self-promote the gate exists to prevent. Absent evidence, the record stands.
    /// Package-private rather than private so the #568 semantics — including the boot-window guard — are
    /// directly testable; the boot guard is the part most likely to be "simplified" into a regression.
    static boolean committedOwnerStillAlive(MembershipFsm membershipFsm, NodeId owner) {
        var counted = membershipFsm.countedMembers();

        return counted.isEmpty() || counted.contains(owner);
    }

    private static void snapshotAllSetups(Map<String, StorageFactory.StorageSetup> storageSetups) {
        storageSetups.values().forEach(setup -> setup.snapshotManager()
                                                     .maybeSnapshot());
    }

    /// 1d-iii / #265 owner-reconciled BATCH driver: once per [ReplicaSetController] reconcile pass, the
    /// controller flushes the pass's reconciled `(stream, partition)` list here. The leader-only
    /// [StreamPartitionOwnershipWriter#writeOwnershipChanges] decides each pair — self-limiting to the
    /// leader (its `isLeaderSupplier` gate short-circuits BEFORE any committed-state read) and to
    /// genuinely-moved partitions (an unchanged owner's `decide` returns [Option#none]) — and returns the
    /// ownership-change Puts as ONE list. That list is applied through the consensus `ClusterNode` as a
    /// SINGLE batch, so a mass reshuffle (a membership change that shifts HRW ownership for many
    /// stream×partitions) commits ONE consensus batch per reconcile pass instead of N un-batched applies —
    /// the fan-out bound #265 promises. There is no extra leader-epoch guard around the apply (unlike the
    /// DHT bootstrap writer, whose apply runs in an async retry batch that can straddle a leader change):
    /// the decide-then-apply here is synchronous on the reconcile thread, the writer re-checks leadership
    /// immediately before deciding each pair, and consensus rejects a stale-leader apply. A failed apply
    /// is logged, never thrown — the next membership-change reconcile re-drives.
    @Contract
    private static void driveStreamOwnership(StreamPartitionOwnershipWriter writer,
                                             Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                             List<PartitionKey> reconciled) {
        // Entity arcs are excluded HERE, not in the controller's reconcile: their LOG replicas are
        // placed by the same reconcile as any stream's (wanted), but their OWNERSHIP has exactly one
        // authority — EntityOwnershipReconciler, which places over the keyspace's hosting set. Left
        // unfiltered, this driver would re-place entity arcs over the WHOLE member view on every
        // catalog/membership edge and fight that reconcile record-for-record, parking arcs on
        // non-hosting nodes (which refuse every write) for up to one entity tick each time.
        applyStreamOwnershipBatch(applier,
                                  writer.writeOwnershipChanges(EntityOwnershipReconciler.withoutEntityArcs(reconciled)));
    }

    /// Apply the reconcile pass's ownership-change Puts as ONE consensus batch. An empty batch (a
    /// follower, or a pass with no moved partitions) applies nothing and logs nothing — the empty apply
    /// is skipped rather than sent (an empty command batch would be rejected by consensus).
    @Contract
    private static void applyStreamOwnershipBatch(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                  List<KVCommand<AetherKey>> commands) {
        if (commands.isEmpty()) {
            return;
        }

        applier.apply(commands)
               .onFailure(cause -> LOG.warn("Stream ownership batch write for {} moved partition(s) failed: {} — re-driven on next reconcile",
                                            commands.size(),
                                            cause.message()));
    }

    /// #336 reachability-evidence: when an app/blueprint stream's committed `StreamConfig` lands via
    /// consensus (e.g. `replicas=2 / min-sync=2` at slice activation), place its replica set NOW instead
    /// of waiting for an unrelated membership/quorum edge that may never fire in a membership-stable
    /// cluster. Registered as the SECOND `StreamConfigKey` put-handler, so it runs AFTER
    /// [StreamPartitionManager#onStreamConfigPut] has hydrated the stream into `replicaCatalog()`
    /// (KVNotificationRouter dispatches handlers in registration order) — the reconcile scan therefore
    /// sees the new stream. [ReplicaSetController#reconcile] returns immediately (the work is serialized
    /// on the controller's own executor), so the KV-apply thread never blocks; it is idempotent and
    /// coalescing (safe to fire per config Put, on every applying node — each does per-node HRW
    /// placement) and writes only `StreamPartitionOwnershipKey`, never a `StreamConfigKey`, so it cannot
    /// re-trigger this edge. No-op until the controller is bound; boot-time streams are covered by the
    /// initial reconcile fired once membership is available.
    @Contract
    private static void reconcileReplicaSetOnConfigPut(AtomicReference<ReplicaSetController> controllerRef) {
        Option.option(controllerRef.get()).onPresent(ReplicaSetController::reconcile);
    }

    /// Periodic activation level-heal tick (see [#NDM_ACTIVATION_RECONCILE_INTERVAL]). Gated on a
    /// LIVE consensus-active sample (`clusterNode::isActive`, the SAME accessor the readiness pong
    /// and leader election use) so it only acts when this node IS in quorum: a dropped ACTIVE edge
    /// then surfaces as NDM still Dormant, and `reconcileActivation()` re-dispatches the missing
    /// `QuorumEstablished`. No-op when consensus is not active (correctly Dormant) or once NDM has
    /// activated.
    @Contract
    private static void reconcileNodeActivation(BooleanSupplier consensusActive,
                                                NodeDeploymentManager nodeDeploymentManager) {
        if (!consensusActive.getAsBoolean()) {
            return;
        }

        nodeDeploymentManager.reconcileActivation();
    }

    /// `-1` for empty) by paging its partition over the forward-read transport, or FAIL when the peer is
    /// unreachable. The success/failure split is the data-safety signal the backfill deadlock-break needs:
    /// a reachable peer's watermark is comparable; an unreachable peer's is unknown (and might be ahead),
    /// so self must not self-promote past it.
    private static Promise<Long> probePeerWatermark(StreamForwardClient forwardClient,
                                                    NodeId target,
                                                    String streamName,
                                                    int partition) {
        return pagePeerWatermark(forwardClient, target, streamName, partition, 0L);
    }

    private static Promise<Long> pagePeerWatermark(StreamForwardClient forwardClient,
                                                   NodeId target,
                                                   String streamName,
                                                   int partition,
                                                   long cursor) {
        return forwardClient.readRemote(target, streamName, partition, cursor, STREAM_CATCHUP_BATCH_SIZE)
                            .flatMap(result -> continuePeerWatermark(forwardClient,
                                                                     target,
                                                                     streamName,
                                                                     partition,
                                                                     cursor,
                                                                     result));
    }

    private static Promise<Long> continuePeerWatermark(StreamForwardClient forwardClient,
                                                       NodeId target,
                                                       String streamName,
                                                       int partition,
                                                       long cursor,
                                                       StreamForwardClient.ReadForwardResult result) {
        var events = result.events();

        if (events.isEmpty()) {
            return Promise.success(cursor - 1);
        }

        var lastOffset = events.getLast().offset();

        return events.size() >= STREAM_CATCHUP_BATCH_SIZE
               ? pagePeerWatermark(forwardClient, target, streamName, partition, lastOffset + 1)
               : Promise.success(lastOffset);
    }

    private static Result<AetherNode> assembleNode(AetherNodeConfig config,
                                                   MessageRouter.DelegateRouter delegateRouter,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   SliceRegistry sliceRegistry,
                                                   DeferredSliceInvokerFacade deferredInvoker,
                                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                   RabiaMetricsCollector rabiaMetricsCollector,
                                                   Serializer serializer,
                                                   Deserializer deserializer,
                                                   SliceCodec nodeCodec,
                                                   DHTNode dhtNode,
                                                   OwnershipEpochHighWater ownershipEpochHighWater,
                                                   AtomicLong leaderTerm,
                                                   HlcClock hlcClock,
                                                   GenerationSnapshotSource snapshotSource,
                                                   AtomicReference<MembershipFsm> membershipFsmRef,
                                                   AtomicReference<ClusterSyncCollector> metricsCollectorRef,
                                                   org.pragmatica.cluster.node.rabia.SyncHoldRegistry syncHoldRegistry,
                                                   Runnable jvmExit,
                                                   Option<EncryptionKeyring> storageKeyring) {
        // #329: leader-pinned task-group ownership is gated on consensus catch-up. A freshly
        // elected replacement leader whose Rabia log is still draining (isPendingCatchUp) is
        // isActive but not caught up; granting it ownership funnels every leader-pinned op
        // through a wedged node (synchronous node.apply().await stalls -> 503). The gate withholds
        // ownership (notAssigned -> HttpForwarder 3x200ms re-resolution retry) only while lagging
        // and only for a bounded window, so it can never become a permanent no-owner wedge.
        // Shared by both taskGroupOwnerResolver sites below (the AppHttpServer wiring var and the
        // ManageableNode.taskGroupOwnerResolver() override).
        var leaderCatchUpGate = LeaderCatchUpGate.leaderCatchUpGate(config.self(),
                                                                    () -> clusterNode.leaderManager()
                                                                                     .leader(),
                                                                    clusterNode::isPendingCatchUp);
        // Concrete adapter (not a lambda) so we can override `sendOutcome` and forward
        // it to the QUIC transport's tracked-write API. The default DHTNetwork impl
        // just calls `send` and reports Sent — but the DHT quorum collectors need the
        // real synchronous refusal signal to fail-fast against unreachable replicas
        // (see aether/docs/specs/dht-resilience-spec.md Layer 1/3).
        DHTNetwork dhtNetwork = new DHTNetwork() {
            @Contract
            @Override
            public void send(NodeId target, org.pragmatica.consensus.ProtocolMessage msg) {
                var _ = clusterNode.network().send(target, msg);
            }

            @Override
            public Promise<org.pragmatica.consensus.net.WriteOutcome> sendOutcome(NodeId target,
                                                                                  org.pragmatica.consensus.ProtocolMessage msg) {
                return clusterNode.network()
                                  .sendOutcome(target, msg);
            }

            @Override
            public Set<NodeId> livePeers() {
                // Membership-FSM unification (Wave D, consumer #3) + scale-down zombie-write fix
                // (2026-06-12): the DHT live-peer set is sourced from the authoritative
                // MembershipFsm.dhtRoutableMembers() (OBSERVED + MEMBER + SUSPECT) plus self. This is
                // the NARROWER projection that, unlike broadcastEligibleMembers(), ALSO excludes
                // DEPARTING: a drained node (commanded to leave on a scale-down) is about to halt, so
                // routing a NEW DHT replica write/read to it produces a silent zombie target — the QUIC
                // send returns Sent, the QuorumCollector never sees a failure, and the op stalls until
                // the per-op timeout (surfacing as a 500 on a write issued shortly after scale-down).
                // Excluding DEPARTING closes that window at drain-COMMAND time (~1s post-scale-down via
                // the leader ping → DrainRequested), before the node halts and far ahead of the ~14s
                // SWIM aging path. This governs only NEW-op target selection; in-flight ops (tracked by
                // correlation id) and the drainer's own InFlightRequestTracker are untouched, so
                // in-flight drain semantics are preserved. An OBSERVED joining replacement and a
                // SUSPECT flapping member are still UP and can serve reads, so they remain in the set;
                // the per-op timeout plus the new total<quorum fast-fail guard are the safety net. Self
                // is always included (it handles local replicas directly). Before the FSM is published
                // (early boot / non-cluster worker paths) this degrades to the prior transport view
                // (connectedPeers + self), preserving back-compat.
                var live = Option.option(membershipFsmRef.get())
                                 .map(MembershipFsm::dhtRoutableMembers)
                                 .or(() -> new HashSet<>(clusterNode.network().connectedPeers()));

                live.add(config.self());

                return live;
            }
        };
        // #499 zombie fix + #644 arming fix: every periodic SharedScheduler task this node wants is
        // DEFERRED here as an arming thunk, armed only once cluster formation resolves in start(),
        // and cancelled first in stop(). SharedScheduler is JVM-global, so an uncancelled fixed-rate
        // task outlives the node and keeps operating against torn-down state — the in-JVM killed-node
        // zombie backfill loop that mimicked the #499 RF-restoration deadlock in the forge harness.
        // And a task armed at ASSEMBLY runs on a node that was never started (#642's evidence run:
        // two held-back Ember nodes, 274 snapshot ticks each) — see PeriodicTasks for the contract.
        var periodicTasks = PeriodicTasks.periodicTasks();
        var dhtClient = DistributedDHTClient.distributedDHTClient(dhtNode,
                                                                  dhtNetwork,
                                                                  config.artifactRepo(),
                                                                  KvOwnerEpochSource.kvOwnerEpochSource(kvStore,
                                                                                                        BootstrapModule.CORE_PARTITION_ID));
        var aetherMaps = AetherMaps.aetherMaps(dhtClient.scoped(DHTConfig.FULL));
        var cacheDhtClient = dhtClient.scoped(config.cache());
        var dhtClientOption = Option.<DHTClient> some(dhtClient);
        // #253 BLOCKING #1 (2026-09-04 ruling): a configured storage instance that fails to create
        // is a boot failure -- `createAll` now returns `Result` and this aborts naming the instance
        // and the cause, the same way `streamStorageResult`'s failure is propagated below, rather
        // than silently dropping that one instance (the old behavior) and letting boot continue on
        // whatever was left, e.g. a `wrapLocalDisk` refusal over plaintext artifacts.
        var baseStorageSetupsResult = StorageFactory.createAll(config.storageConfig(),
                                                               config.self().id(),
                                                               dhtClientOption,
                                                               storageKeyring);

        if (baseStorageSetupsResult.isFailure()) {
            return baseStorageSetupsResult.map(ignored -> null);
        }

        var baseStorageSetups = baseStorageSetupsResult.fold(_ -> null, setups -> setups);
        // Stream storage is a first-class, disk-backed snapshot-capable StorageSetup (memory -> disk
        // -> DHT) keyed under "streams". It is built here (not via `createAll`, which is config-map
        // driven) so it can be folded into `storageSetups` — a later snapshot scheduler iterates that
        // map — while its `instance()` is reused as `streamStorage` at the stream wiring site below.
        // #253: streams has no per-instance `StorageConfig.encrypted()` of its own to consult --
        // `streams_encrypted` is a dedicated top-level `[storage.encryption]` flag -- so the gate is
        // applied here, and `defaultStreamStorage` can fail (unlike the plaintext-only 3-arg
        // overload) when the segments dir already holds unmarked plaintext blocks from a prior
        // unencrypted boot; that failure is propagated exactly like `keyringResolution`'s above.
        var streamsEncrypted = config.storageEncryption().map(StorageEncryptionConfig::streamsEncrypted).or(false);
        var streamsKeyring = streamsEncrypted
                             ? storageKeyring
                             : Option.<EncryptionKeyring> empty();
        var streamStorageResult = StorageFactory.defaultStreamStorage(dhtClientOption,
                                                                      streamDataDir(config),
                                                                      config.self().id(),
                                                                      streamsKeyring);

        if (streamStorageResult.isFailure()) {
            return streamStorageResult.map(ignored -> null);
        }

        var streamStorageSetup = streamStorageResult.fold(_ -> null, setup -> setup);
        var storageSetups = withStreamSetup(baseStorageSetups, streamStorageSetup);
        // #253 BLOCKING #1 (2026-09-04 ruling): `createAll`'s postcondition on success is that
        // "artifacts" is ALWAYS present in `baseStorageSetups` -- either the operator's
        // `[storage.artifacts]` config or the synthesized default, and either one failing now
        // aborts boot at the `baseStorageSetupsResult` guard above, before this line is ever
        // reached. The old `.or(StorageFactory.defaultArtifactStorage(...))` fallback substituted a
        // second, independently-built, always-unencrypted instance whenever "artifacts" happened to
        // be missing from the map -- which is exactly how a `wrapLocalDisk` refusal over
        // pre-existing plaintext used to boot successfully anyway (BLOCKING #1's root cause).
        // `defaultArtifactStorage` is retired as dead code; there is no longer a legitimate reason
        // for "artifacts" to be absent here.
        //
        // #253 review round 2 NOTE: `Objects.requireNonNull` turns a violated invariant into a named
        // failure pointing at THIS comment's reasoning, instead of a bare NPE at `.instance()` that
        // gives a future reader no hint the absence was ever supposed to be impossible.
        var artifactStorage = Objects.requireNonNull(storageSetups.get("artifacts"),
                                                     "storageSetups missing \"artifacts\" after createAll succeeded -- invariant violated")
                                     .instance();
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
                                               config.sliceAction(),
                                               resourceProviderSetup.nodeComposite(),
                                               Option.some(nodeCodec),
                                               resourceProviderSetup.secretResolver(),
                                               sliceClassLoader -> SliceScopedResourceProvider.sliceScopedResourceProvider(sliceClassLoader,
                                                                                                                           resourceProviderSetup.facade())
                                               // #773: the node is the only place that can see BOTH
                                               // the resource SPI and the slice loader, so the
                                               // slice-scoped overlay is built here and injected
                                               // down. The parameter is required rather than
                                               // optional precisely so this cannot be dropped
                                               // silently — omitting it is a compile error, not a
                                               // slice that deploys and then fails at load.
                                              );
        var dhtRebalancer = DHTRebalancer.dhtRebalancer(dhtNode, dhtNetwork, config.artifactRepo());
        var dhtTopologyListener = DHTTopologyListener.dhtTopologyListener(dhtNode, dhtRebalancer);
        var dhtAntiEntropy = DHTAntiEntropy.dhtAntiEntropy(dhtNode, dhtNetwork, config.artifactRepo());
        var switchableCluster = SwitchableClusterNode.switchableClusterNode(clusterNode);
        var corePeerIds = config.topology()
                                .coreNodes()
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
                          OwnershipEpochHighWater ownershipEpochHighWaterInstance,
                          SliceRegistry sliceRegistry,
                          SliceStore sliceStore,
                          RabiaNode<KVCommand<AetherKey>> clusterNode,
                          Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
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
                          WorkerMetricsAggregator workerMetricsAggregator,
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
                          SegmentIndex streamSegmentIndex,
                          StreamReadRouter streamReadRouter,
                          StreamWriteRouter streamWriteRouter,
                          ConsumerGroupCoordinator consumerGroupCoordinator,
                          ConsumerGroupRegistry consumerGroupRegistry,
                          StreamConsumerManager streamConsumerManager,
                          StreamNamespacesService streamNamespacesService,
                          Map<String, StorageFactory.StorageSetup> storageSetups,
                          ClusterTopologyManager clusterTopologyManagerInstance,
                          EventLoopMetricsCollector eventLoopMetricsCollector,
                          CoreSwimHealthDetector swimHealthDetector,
                          PresenceSampler presenceSampler,
                          LeaderReconciler leaderReconciler,
                          MembershipFsm membershipFsm,
                          QuorumLossDetector quorumLossDetector,
                          CoreAbsenceDetector coreAbsenceDetector,
                          TransitionJournal transitionJournal,
                          Runnable startSwimTrigger,
                          Option<ManagementServer> managementServer,
                          Option<DiscoveryProvider> discoveryProvider,
                          Option<CertificateRenewalScheduler> certRenewalScheduler,
                          InFlightRequestTracker inFlightRequestTracker,
                          NodeLifecycle nodeLifecycle,
                          HlcClock hlcClock,
                          Option<DHTClient> dhtClient,
                          Option<DHTNode> dhtNode,
                          Supplier<AetherValue.ClusterPhase> clusterPhaseSupplier,
                          Supplier<Epoch> currentGenerationEpochSupplier,
                          // #642 stop-hook audit: components that arm work on the process-wide
                          // SharedScheduler on behalf of THIS node and had no reachable cancel path.
                          // They are carried here for the sole purpose of being quiesced in stop() —
                          // an uncancelled one keeps acting for a node that no longer exists, and in a
                          // shared JVM its effects land on the next incarnation of the same id.
                          ObservabilityBaseline observabilityBaseline,
                          SpokesmanPingLoop spokesmanPingLoop,
                          AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                          RetentionEnforcer streamRetentionEnforcer,
                          StreamConsumerRuntime streamConsumerRuntime,
                          long startTimeMs,
                          AtomicLong swimBootAt,
                          PeriodicTasks periodicTasks) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNode.class);

            @Override
            public Epoch currentGenerationEpoch() {
                return currentGenerationEpochSupplier.get();
            }

            @Override
            public NodeId self() {
                return config.self();
            }

            @Override
            public boolean tlsEnabled() {
                return config.tls()
                             .isPresent();
            }

            @Override
            public TopologyManager topologyManager() {
                return clusterNode.topologyManager();
            }

            @Override
            public Promise<Unit> start() {
                log.info("Starting Aether node {}", self());
                // #642: anchor the SWIM cold-boot convergence window HERE, so it measures this node's
                // RUNNING life. Assembly-anchored, a node held back and started later than
                // COLD_BOOT_CONVERGENCE_WINDOW_MS after construction began with the window already
                // expired and no cold-boot FAULTY-suppression at all.
                swimBootAt.set(System.currentTimeMillis());
                snapshotCollector.start();
                workerMetricsAggregator.start();
                SliceRuntime.setSliceInvoker(sliceInvoker);
                certRenewalScheduler.onPresent(CertificateRenewalScheduler::start);

                return managementServer.map(ManagementServer::start)
                                       .or(Promise.unitPromise())
                                       .flatMap(_ -> appHttpServer.start())
                                       // v2 §5 cold-start fix: register SWIM start on the network's
                                       // transport-ready signal BEFORE starting the cluster. SWIM must
                                       // start when the QUIC transport binds — NOT after startClusterAsync()
                                       // resolves, because clusterNode.start() resolves only on consensus
                                       // quorum, which itself needs the peers SWIM discovers (the deadlock
                                       // §5.4 exposed once the static dial-set seed was removed).
                                       .onSuccess(_ -> clusterNode.network()
                                                                  .whenReady(startSwimTrigger))
                                       .flatMap(_ -> startClusterAsync())
                                       // #858: check/write each DHT-tier instance's encryption marker
                                       // here — AFTER startClusterAsync() resolves (cluster formation
                                       // complete, dhtClient can route) and BEFORE armPeriodicTasks /
                                       // "started" (no DHT read is reachable before this point: deployed
                                       // slices need leader election + quorum, which themselves need
                                       // formation). Was boot-time, inside the constructor path
                                       // (createAll → createOne), where it always blocked the full 30 s
                                       // DHT_MARKER_TIMEOUT because the DHTClient handed to the
                                       // constructor cannot route yet (#858). A no-keyring instance whose
                                       // marker is present fails HERE with
                                       // EncryptionError.EncryptedTierRequiresKeyring, which aborts
                                       // start() and stops the node — same cause as before, just later.
                                       .flatMap(_ -> verifyDhtMarkers())
                                       // #644: arm the deferred periodic tasks only now, once cluster
                                       // formation has resolved — a created-but-unstarted node performs
                                       // no periodic work, and none of the deferred tasks participates
                                       // in formation itself (clusterNode.start() resolves inside the
                                       // consensus stack; the activation level-heal's own gate,
                                       // clusterNode::isActive, is true by this point in the one
                                       // dropped-edge scenario it exists to heal). A MAP stage, not an
                                       // onSuccess observer, deliberately: observers race an await() on
                                       // the same promise (CI caught the race), while a stage makes
                                       // "start() resolved ⇒ periodic work armed" a real guarantee.
                                       // PeriodicTasks.arm() is a no-op after stop(), closing the
                                       // late-resolution race in the other direction.
                                       .map(this::armPeriodicTasks)
                                       .onSuccess(_ -> log.info("Aether node {} started, cluster forming...",
                                                                self()));
            }

            /// #858: post-formation DHT encryption-marker check/write, run once from [#start] between
            /// `startClusterAsync()` and `armPeriodicTasks`. Generic over `storageSetups`' CONTENTS —
            /// iterates whatever [StorageFactory.StorageSetup#dhtMarkerCheck] entries are present, no
            /// hardcoded instance list — so an instance that starts carrying a DHT tier later (#783:
            /// `content` routed through `createAll`) is covered automatically without touching this
            /// method. No `dhtClient` (no DHT-backed instance in this config) short-circuits to
            /// `Promise.UNIT` — nothing to check.
            private Promise<Unit> verifyDhtMarkers() {
                var checks = storageSetups.values()
                                          .stream()
                                          .map(StorageFactory.StorageSetup::dhtMarkerCheck)
                                          .flatMap(Option::stream)
                                          .toList();

                return dhtClient.map(client -> StorageFactory.verifyDhtMarkers(client, checks))
                                .or(Promise.UNIT);
            }

            /// The #644 arming stage of [#start]'s chain — a named identity so the arm rides the
            /// promise PIPELINE (resolution of the returned stage happens-after the arm) instead of
            /// an observer that races awaiters.
            private Unit armPeriodicTasks(Unit unit) {
                periodicTasks.arm();

                return unit;
            }

            @Override
            public Promise<Unit> stop() {
                log.info("Stopping Aether node {}", self());
                // #499 zombie fix: cancel this node's periodic SharedScheduler tasks FIRST — the
                // scheduler is JVM-global, so anything left armed keeps firing against torn-down
                // state after stop (the killed-node zombie backfill loop). cancel(false) inside:
                // an in-flight tick finishes benignly; no new tick starts. Also discards any
                // still-unarmed thunks and refuses a late arm() (#644): a cluster-formation promise
                // resolving after this line must not schedule work for a torn-down node.
                periodicTasks.cancel();
                router.route(ClusterStateNotification.passive());
                router.quiesce();
                controlLoop.stop();
                workerMetricsAggregator.stop();
                metricsScheduler.stop();
                deploymentMetricsScheduler.stop();
                ttmManager.stop();
                scheduledTaskManager.stop();
                snapshotCollector.stop();
                SliceRuntime.clear();
                // #488: detach declarative stream consumers BEFORE the partition manager closes, so
                // each one flushes its cursor while its ring buffer and cursor store are still alive.
                streamConsumerManager.stop();
                // #642, bound by that SAME #488 ordering constraint. close() flushes every consumer
                // cursor (a consensus write) and then removes push listeners through
                // partitionManager.partitionBuffer(...) lookups. Run after streamPartitionManager.close()
                // those lookups resolve empty — the listener removal is silently inert — and the cursor
                // write races clusterNode.stop() at the tail of this method.
                streamConsumerRuntime.close();
                streamPartitionManager.close();
                certRenewalScheduler.onPresent(CertificateRenewalScheduler::stop);
                swimHealthDetector.stop();
                presenceSampler.stop();
                // #590: cancel the core-absence tick. Forge runs many nodes in ONE JVM on a shared
                // scheduler, so a tick left armed after stop() outlives its node.
                coreAbsenceDetector.stop();
                // #642, same hazard one component over: the quorum-loss timers are on the same shared
                // scheduler, and presenceSampler.stop() above freezes the member count below threshold,
                // so an unstopped detector re-arms off that frozen count until its cold-boot window
                // expires and then drains — by node id, against the LIVE registry, i.e. this node's NEXT
                // incarnation.
                quorumLossDetector.stop();
                // #642 stop-hook audit — the rest of the SharedScheduler-armed, node-scoped work that
                // had no reachable cancel path. Each of these was still acting on behalf of a stopped
                // node: re-announcing a governor roster into consensus, pinging as spokesman, sweeping
                // retention (a DESTRUCTIVE sweep), recomputing a sampling rate. (The consumer runtime
                // belongs to this audit too, but its cancel is ordering-bound and sits above.)
                spokesmanPingLoop.stop();
                Option.option(governorAnnouncerHolder.get()).onPresent(GovernorAnnouncer::stop);
                streamRetentionEnforcer.close();
                observabilityBaseline.sampler().onPresent(AdaptiveSampler::stop);
                discoveryProvider.onPresent(this::deregisterFromDiscovery);
                // #642: the cluster-deployment FSM's fixed-rate reconcile timer is cancelled only by
                // Active.onExit, which nothing reached at shutdown — a stopped node kept running full
                // deployment reconciles. deactivate() is the same designed path a leader loss takes.
                //
                // It heads the chain, so the rest of the shutdown DEPENDS on it staying infallible: it
                // currently dispatches an FSM event and returns unitPromise(), never a failure. Should
                // it ever gain a failure path, every stop below — management server, app HTTP, slice
                // invoker, cluster node — would be skipped by flatMap short-circuit. Recover here at
                // that point rather than leaving the node half-stopped.
                return clusterDeploymentManager.deactivate()
                                               .flatMap(_ -> managementServer.map(ManagementServer::stop)
                                                                             .or(Promise.unitPromise()))
                                               .flatMap(_ -> appHttpServer.stop())
                                               .flatMap(_ -> sliceInvoker.stop())
                                               .flatMap(_ -> clusterNode.stop())
                                               .onSuccess(_ -> log.info("Aether node {} stopped",
                                                                        self()));
            }

            private Promise<Unit> startClusterAsync() {
                return clusterNode.start()
                                  .onSuccess(_ -> {
                                                 log.info("Aether node {} cluster formation complete",
                                                          self());
                                                 clusterNode.network()
                                                            .server()
                                                            .onPresent(server -> {
                                                                           eventLoopMetricsCollector.register(server.bossGroup());
                                                                           eventLoopMetricsCollector.register(server.workerGroup());
                                                                           log.info("Registered EventLoopGroups for metrics collection");
                                                                       });
                                                 clusterNode.leaderManager()
                                                            .triggerElection();
                                                 discoveryProvider.onPresent(this::registerWithDiscovery);
                                                 applyNodeIdTag();
                                             })
                                  .onSuccess(_ -> printStartupBanner())
                                  .onFailure(cause -> log.error("Cluster formation failed: {}",
                                                                cause.message()));
            }

            private void registerWithDiscovery(DiscoveryProvider dp) {
                Option.from(config.topology().coreNodes().stream().filter(n -> n.id()
                                                                                .equals(self())).findFirst())
                      .map(n -> new PeerInfo(n.address().host(),
                                             n.address().port(),
                                             Map.of("role",
                                                    "core",
                                                    "nodeId",
                                                    self().id())))
                      .onPresent(peerInfo -> dp.registerSelf(peerInfo)
                                               .await()
                                               .onSuccess(_ -> log.info("Registered self with discovery provider"))
                                               .onFailure(cause -> log.warn("Failed to register with discovery: {}",
                                                                            cause.message())));
            }

            private void applyNodeIdTag() {
                config.environment().flatMap(EnvironmentIntegration::compute).onPresent(this::tagSelfInstance);
            }

            private void tagSelfInstance(ComputeProvider provider) {
                provider.listInstances()
                        .onSuccess(instances -> tagMatchingInstance(provider, instances))
                        .onFailure(cause -> log.warn("Failed to list instances for self-tagging: {}",
                                                     cause.message()));
            }

            /// RFC-0017 C4 — the formation signal bootstrap observes WITHOUT touching any inbound
            /// port: this tag lands only after cluster formation succeeded, via the IP-match
            /// self-identification that needs no `selfServerId` (unlike `registerSelf`, which is
            /// inert in production because nothing populates that id). `applyTags` MERGES on the
            /// provider side, so the create-stamped labels survive. Bootstrap polls
            /// `aether-cluster=<name>, aether-formed=true` until the expected core count appears.
            private void tagMatchingInstance(ComputeProvider provider, List<InstanceInfo> instances) {
                findSelfInstance(instances).onPresent(instance -> provider.applyTags(instance.id(),
                                                                                     Map.of("aether-node-id",
                                                                                            self().id(),
                                                                                            "aether-formed",
                                                                                            "true"))
                                                                          .onSuccess(_ -> log.info("Applied aether-node-id tag to instance {}",
                                                                                                   instance.id().value()))
                                                                          .onFailure(cause -> log.warn("Failed to apply aether-node-id tag: {}",
                                                                                                       cause.message())));
            }

            private Option<InstanceInfo> findSelfInstance(List<InstanceInfo> instances) {
                return selfAddress().flatMap(selfIp -> Option.from(instances.stream()
                                                                            .filter(i -> i.addresses()
                                                                                          .contains(selfIp))
                                                                            .findFirst()));
            }

            private Option<String> selfAddress() {
                return Option.from(config.topology()
                                         .coreNodes()
                                         .stream()
                                         .filter(n -> n.id()
                                                       .equals(self()))
                                         .map(n -> n.address()
                                                    .host())
                                         .findFirst());
            }

            private void deregisterFromDiscovery(DiscoveryProvider dp) {
                dp.stopWatching()
                  .await()
                  .onFailure(cause -> log.warn("Failed to stop discovery watching: {}",
                                               cause.message()));
                dp.deregisterSelf()
                  .await()
                  .onFailure(cause -> log.warn("Failed to deregister from discovery: {}",
                                               cause.message()));
            }

            private void printStartupBanner() {
                var nodeId = self().id();
                var clusterPort = Option.from(config.topology()
                                                    .coreNodes()
                                                    .stream()
                                                    .filter(n -> n.id()
                                                                  .equals(self()))
                                                    .findFirst())
                                        .map(n -> n.address()
                                                   .port())
                                        .or(0);
                var mgmtPort = config.managementPort();
                var appHttpPort = config.appHttp().enabled()
                                  ? config.appHttp().port()
                                  : 0;
                var peerCount = config.topology().coreNodes().size();
                var ttmEnabled = ttmManager.isEnabled();
                var tlsEnabled = config.tls().isPresent();

                log.info("{}", "+-----------------------------------------------------------------+");
                log.info("{}", "|                     AETHER NODE v" + VERSION + "                       |");
                log.info("{}", "+-----------------------------------------------------------------+");
                log.info("|  Node ID:        {}", pad(nodeId, 46) + "|");
                log.info("|  Cluster Port:   {}", pad(String.valueOf(clusterPort), 46) + "|");
                if (mgmtPort > 0) {
                    log.info("|  Management:     {}", pad("http://localhost:" + mgmtPort, 46) + "|");
                } else {
                    log.info("|  Management:     {}", pad("disabled", 46) + "|");
                }

                if (appHttpPort > 0) {
                    log.info("|  App HTTP:       {}", pad("http://localhost:" + appHttpPort, 46) + "|");
                } else {
                    log.info("|  App HTTP:       {}", pad("disabled", 46) + "|");
                }

                log.info("|  Peers:          {}", pad(peerCount + " configured", 46) + "|");
                log.info("|  TTM:            {}",
                         pad(ttmEnabled
                             ? "enabled"
                             : "disabled",
                             46) + "|");
                log.info("|  TLS:            {}",
                         pad(tlsEnabled
                             ? "enabled"
                             : "disabled",
                             46) + "|");
                var discoveryEnabled = discoveryProvider.isPresent();

                log.info("|  Discovery:      {}",
                         pad(discoveryEnabled
                             ? "enabled"
                             : "disabled",
                             46) + "|");
                log.info("{}", "+-----------------------------------------------------------------+");
                logCloudBootstrapHintIfNoStaticSeeds(peerCount);
            }

            private void logCloudBootstrapHintIfNoStaticSeeds(int peerCount) {
                if (peerCount > 0) {
                    return;
                }

                log.info("No static seed peers configured — cluster requires operator bootstrap.");
                log.info("Run `aether cluster bootstrap <aether-cluster.toml>` after cloud nodes start.");
            }

            private static String pad(String value, int width) {
                if (value.length() >= width) {
                    return value.substring(0, width);
                }

                return value + " ".repeat(width - value.length());
            }

            @Override
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return switchableCluster.apply(commands);
            }

            @Override
            public int connectedNodeCount() {
                return clusterNode.network()
                                  .connectedNodeCount();
            }

            @Override
            public Map<String, Number> transportMetrics() {
                return clusterNode.network()
                                  .transportMetrics();
            }

            @Override
            public Option<ClusterTopologyManager> clusterTopologyManager() {
                return Option.some(clusterTopologyManagerInstance);
            }

            @Override
            public int observedPeakMembership() {
                return presenceSampler.peakMembershipCount();
            }

            @Override
            public Option<ProvisioningDiagnostics> provisioningDiagnostics() {
                return isLeader()
                       ? Option.some(assembleProvisioningDiagnostics(leaderReconciler.currentProvisioningSnapshot()))
                       : Option.none();
            }

            private ProvisioningDiagnostics assembleProvisioningDiagnostics(LeaderReconciler.ProvisioningDecisionSnapshot decision) {
                return new ProvisioningDiagnostics(decision,
                                                   clusterTopologyManagerInstance.circuitBreakerState(),
                                                   clusterTopologyManagerInstance.lastProvisionFailure());
            }

            @Override
            public Option<QuorumLossSnapshot> quorumLossSnapshot() {
                return Option.option(quorumLossDetector).map(QuorumLossSnapshot::from);
            }

            @Override
            public Option<CoreAbsenceSnapshot> coreAbsenceSnapshot() {
                return Option.option(coreAbsenceDetector).map(CoreAbsenceSnapshot::from);
            }

            @Override
            public Option<OwnershipEpochHighWater> ownershipEpochHighWater() {
                return Option.some(ownershipEpochHighWaterInstance);
            }

            @Override
            public Set<NodeId> connectedPeerIds() {
                // Use activePeers (CONNECTED + EVICTED) to match the quorum-counting
                // semantics inside QuicClusterNetwork.activeConnectedCount. Without this,
                // a brief EVICTED transition on any peer flickers the externally-visible
                // /api/cluster/topology.connectedPeerCount below quorum.
                return clusterNode.network()
                                  .activePeers();
            }

            @Override
            public boolean isLeader() {
                return clusterNode.leaderManager()
                                  .isLeader();
            }

            @Override
            public boolean isReady() {
                return clusterNode.isActive();
            }

            @Override
            public Option<NodeId> leader() {
                return clusterNode.leaderManager()
                                  .leader();
            }

            @Override
            public SchemaOrchestratorService schemaOrchestrator() {
                return clusterDeploymentManager.schemaOrchestrator();
            }

            @Override
            public int managementPort() {
                return config.managementPort();
            }

            @Override
            public int appHttpPort() {
                return config.appHttp()
                             .enabled()
                       ? config.appHttp()
                               .port()
                       : 0;
            }

            @Override
            public long uptimeSeconds() {
                return (System.currentTimeMillis() - startTimeMs) / 1000;
            }

            @Override
            public List<NodeId> initialTopology() {
                return config.topology()
                             .coreNodes()
                             .stream()
                             .map(NodeInfo::id)
                             .toList();
            }

            @Override
            public TopologyConfig topologyConfig() {
                return config.topology();
            }

            @Contract
            @Override
            public void route(Message message) {
                router.route(message);
            }

            @Contract
            @Override
            public void blackhole(boolean enabled) {
                clusterNode.network().blackhole(enabled);
                swimHealthDetector.setSwimBlackholed(enabled);
            }

            @Override
            public MembershipView membershipView() {
                // H.2 (spec §H): SWIM does not observe self — the local detector returns
                // only remote peers' health. Inject `self → HEALTHY` so the derived view
                // correctly reports the local node as present (assuming the node has
                // reached `NodeLifecycle.ACTIVE` and is serving requests).
                //
                // RC1 Step 5: external accessor uses the strict factory so a minority-side
                // node reports empty `presentMembers()` instead of leaking local-SWIM-derived
                // claims that the majority has likely re-routed past. Quorum source is the
                // TopologyObserver's `quorumEstablished` AtomicBoolean — single truth.
                //
                // RC1 reachability-aggregator landing: the strict view also consults the
                // leader-broadcast cluster-canonical reachability snapshot to confirm
                // presence when local SWIM hasn't yet acked HEALTHY (closes the
                // per-reader-variance window without breaking the SWIM-faulty downgrade).
                // See aether/docs/specs/reachability-aggregator-spec.md Layer 5.
                return MembershipView.strict(() -> {
                                                 var swim = swimHealthDetector.currentHealth()
                                                                              .or(() -> HealthSnapshot.healthSnapshot(Map.of()));
                                                 var merged = new HashMap<>(swim.peerHealth());

                                                 merged.putIfAbsent(config.self(), SwimHealth.HEALTHY);

                                                 return Option.some(HealthSnapshot.healthSnapshot(merged));
                                             },
                                             ((TopologyObserver) clusterNode.topologyManager()).inQuorum());
            }
        }
        var httpRoutePublisher = HttpRoutePublisher.httpRoutePublisher(config.self(),
                                                                       clusterNode,
                                                                       GenerationSnapshotSource.noop(),
                                                                       routeMountMode(config.appHttp()));
        var invocationMetrics = InvocationMetricsCollector.invocationMetricsCollector();
        var logLevelRegistry = LogLevelRegistry.logLevelRegistry(clusterNode, kvStore);
        var traceStore = InvocationTraceStore.invocationTraceStore();
        // #277 increment 5a: the strategy-cell system is the ONE observability engine. Unconfigured
        // injection points run the fleet baseline (sampling + tracing + depth-leveled logging + counting),
        // absorbed here from the retired ObservabilityInterceptor. depthThreshold < 0 disables observability
        // -> counting-only baseline (no fleet emission), the prior noOp-interceptor posture.
        var observabilityBaseline = config.observability().depthThreshold() < 0
                                    ? ObservabilityBaseline.countingOnly()
                                    : ObservabilityBaseline.fleet(AdaptiveSampler.adaptiveSampler(config.observability()
                                                                                                        .targetTracesPerSec()),
                                                                  traceStore,
                                                                  config.self().id(),
                                                                  config.observability().depthThreshold());
        var configRegistry = ObservabilityConfigRegistry.observabilityConfigRegistry(clusterNode,
                                                                                     kvStore,
                                                                                     observabilityBaseline);
        var invocationHandler = InvocationHandler.invocationHandler(config.self(),
                                                                    clusterNode.network(),
                                                                    invocationMetrics,
                                                                    config.timeouts().invocation().timeout(),
                                                                    serializer,
                                                                    deserializer,
                                                                    httpRoutePublisher);
        // #277 increment 2: the config registry IS the write-side cell registrar for both dispatch
        // seams — bind it so bridge (east-west) and route (north-south) cells register at slice load /
        // route publish and deregister at unload, and a KV config put swaps their behaviour in place.
        invocationHandler.setObservabilityCellRegistrar(configRegistry);
        httpRoutePublisher.setObservabilityCellRegistrar(configRegistry);
        var deploymentMetricsCollector = DeploymentMetricsCollector.deploymentMetricsCollector(config.self(),
                                                                                               clusterNode.network());
        var deploymentMetricsScheduler = DeploymentMetricsScheduler.deploymentMetricsScheduler(config.self(),
                                                                                               clusterNode.network(),
                                                                                               deploymentMetricsCollector);
        var initialTopology = config.topology().coreNodes().stream().map(NodeInfo::id).toList();
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
        // #298 — fleet cap. Scoped by cluster name (stamped by Main from the boot-gated
        // AETHER_CLUSTER_NAME) and bounded by autoHeal().maxNodes(). Both absent by default, in
        // which case this is exactly the previous unbounded behavior.
        var lifecycleManager = NodeLifecycleManager.nodeLifecycleManager(computeProvider,
                                                                         config.clusterName(),
                                                                         config.autoHeal().maxNodes());
        var deploymentMap = DeploymentMap.deploymentMap();
        // #114 W2c + Wave 2 / W3+W6: CDM membership source is the per-node MembershipFsm's
        // CORE-SCOPED counting projection (lazy deref — the FSM holder is populated before the
        // CDM reconcile loop first runs). Core-scoping at THIS seam makes role assignment count
        // cores only (W3 — a would-be core below coreMax is never mis-assigned WORKER because
        // workers inflated the count) and keeps workers out of activeNodes()/allocatableNodes()/
        // AllocationPool.coreNodes, so CORE_ONLY placement can never land on a worker (W6).
        // Wave 9 item 5 (M4 sentinel): before the FSM holder is populated (boot window), the
        // supplier yields the identity-distinguished `MEMBERSHIP_NOT_WIRED` sentinel (NOT an
        // empty set) so a stale-entry cleanup that races the wiring no-ops instead of
        // mass-classifying every KV-known member as departed.
        Supplier<Set<NodeId>> cdmCoreCountedMembersSupplier = () -> Option.option(membershipFsmRef.get())
                                                                          .map(MembershipFsm::coreCountedMembers)
                                                                          .or(MembershipFsm.MEMBERSHIP_NOT_WIRED);
        // B4 (membership v2 §7.5): the CDM allocatable-gate reads the leader readiness view (READY
        // peers + self) instead of a KV lifecycle atom. Late-bound — the pong fan + self-state holder are
        // constructed further below; this ref mirrors the late-bound supplier pattern.
        var cdmReadyNodesRef = new AtomicReference<Supplier<Set<NodeId>>>(Set::of);
        Supplier<Set<NodeId>> stableCdmReadyNodesSupplier = () -> cdmReadyNodesRef.get()
                                                                                  .get();
        // Membership-v2: CDM DRAINING set sourced from the real node-authoritative
        // NodeReportedState.DRAINING (metrics pong) — late-bound like the READY ref, since the
        // pong fan + self-state holder are constructed further below.
        var cdmDrainingNodesRef = new AtomicReference<Supplier<Set<NodeId>>>(Set::of);
        Supplier<Set<NodeId>> stableCdmDrainingNodesSupplier = () -> cdmDrainingNodesRef.get()
                                                                                        .get();
        // #241 (worker-membership-spec §4.1 / D2): the CDM resolves a joining worker's membership
        // source at role-assignment time to mint/reuse its per-source community. Reads the last-wins
        // MemberDescriptor.source from the membership FSM (retained even across DEAD/rejoin), through
        // the same shared membershipFsmRef the other CDM suppliers deref. Absent → none() → "default".
        Function<NodeId, Option<String>> cdmMemberSourceSupplier = nodeId -> Option.option(membershipFsmRef.get())
                                                                                   .flatMap(fsm -> fsm.memberDescriptor(nodeId))
                                                                                   .map(MemberDescriptor::source);
        var clusterDeploymentManager = ClusterDeploymentManager.clusterDeploymentManager(config.self(),
                                                                                         clusterNode,
                                                                                         kvStore,
                                                                                         delegateRouter,
                                                                                         initialTopology,
                                                                                         clusterNode.topologyManager(),
                                                                                         config.atomicity(),
                                                                                         config.topology().coreMax(),
                                                                                         config.timeouts()
                                                                                               .deployment()
                                                                                               .reconciliationInterval(),
                                                                                         schemaOrchestrator,
                                                                                         cdmCoreCountedMembersSupplier,
                                                                                         stableCdmReadyNodesSupplier,
                                                                                         stableCdmDrainingNodesSupplier,
                                                                                         cdmMemberSourceSupplier,
                                                                                         config.deploymentDefaults()
                                                                                               .communitySizing());
        var loadBalancerManager = config.environment()
                                        .flatMap(EnvironmentIntegration::loadBalancer)
                                        .map(provider -> LoadBalancerManager.loadBalancerManager(config.self(),
                                                                                                 kvStore,
                                                                                                 clusterNode.topologyManager(),
                                                                                                 provider,
                                                                                                 config.appHttp().port()));
        var discoveryProvider = config.environment().flatMap(EnvironmentIntegration::discovery);
        var endpointRegistry = EndpointRegistry.endpointRegistry();
        var topicSubscriptionRegistry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        var scheduledTaskRegistry = ScheduledTaskRegistry.scheduledTaskRegistry();
        // #488: constructed here (not next to the consumer manager further down) because the KV
        // notification routes are collected before the stream subsystem is built. The registry only
        // accumulates declarations; StreamConsumerManager attaches itself as its change listener.
        var streamConsumerRegistry = StreamConsumerRegistry.streamConsumerRegistry();
        var scheduledTaskStateRegistry = ScheduledTaskStateRegistry.scheduledTaskStateRegistry();
        var httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();
        var metricsCollector = ClusterSyncCollector.clusterSyncCollector(config.self(),
                                                                         clusterNode.network(),
                                                                         config.timeouts()
                                                                               .observability()
                                                                               .metricsSlidingWindow()
                                                                               .millis());
        // Publish the collector into the deferred holder captured by the leader-election FSM's
        // leader-acting lease predicate (built in createNode, before this collector existed). From
        // now on the FSM's Led-tenure lease reads real leader-ping freshness via
        // `metricsCollector.hasAuthoritativeReadiness()` instead of the lease-neutral default.
        metricsCollectorRef.set(metricsCollector);
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
        // Membership is presence-derived (SWIM/QUIC via NTT); node readiness/drain is
        // heartbeat-reported and leader-cached — never stored in or committed to the KV-Store.
        // (The former leader-side reachability aggregator that folded a separate peer set is
        // deleted; see the P3 note immediately below.)
        // P3 (membership unification): the leader-side ReachabilityAggregator is deleted —
        // SWIM (fed by QUIC hints) is the single liveness signal, so the pong-derived
        // reachability fold (and the KV-tracked-peers quorum-N it used) is redundant. The
        // metricsCollector local-snapshot supplier is left unset (defaults to none()).
        // Membership v2 — the leader-side `ClusterSyncPongSignalFan` no longer drives a
        // readyCandidate→ForceOnDuty path (the FSM is gone); readiness is node-reported on the
        // control-heartbeat pong and consumed by the CDM allocatable gate (B4).
        ClusterSyncPongSignalFan.ReadyCandidateSink readyCandidateSink = (sender, candidate) -> {};
        // B1 (membership v2 §7.5): node-reported readiness reads a LIVE consensus-active LEVEL on
        // every pong via `clusterNode::isActive` — the SAME `RabiaEngine::isActive` accessor leader
        // election wires as its `consensusReadySupplier` (RabiaNode.isActive -> consensus().isActive()).
        // Sampling the level each pong self-heals: the former edge-cached flag could stick SYNCING
        // when a PASSIVE edge was not followed by a fresh ACTIVE edge.
        var nodeReportedStateHolder = NodeReportedStateHolder.nodeReportedStateHolder(clusterNode::isActive);
        var drainCommandRegistry = DrainCommandRegistry.drainCommandRegistry();

        metricsCollector.setNodeReportedStateSupplier(nodeReportedStateHolder::current);
        // Incarnation supplier is wired to the SWIM self-incarnation below, once
        // `swimHealthDetector` is constructed (single `(NodeId, incarnation)` authority).
        var pongSignalFan = ClusterSyncPongSignalFan.clusterSyncPongSignalFan(clusterNode.leaderManager(),
                                                                              readyCandidateSink);

        metricsCollector.setPongSignalFan(pongSignalFan);
        // Readiness-broadcast (failover-readability): wire the leader manager + clock + ping interval
        // so the collector serves the authoritative fan view on the leader and the cached leader view
        // on followers (and so `/api/nodes/lifecycle` returns 503 + leader hint on a cold follower
        // instead of a misleading `200 []`).
        metricsCollector.setReadinessViewContext(clusterNode.leaderManager(),
                                                 System::nanoTime,
                                                 config.timeouts().cluster().pingInterval());
        // B3 (membership v2 §7.5.5): the leader readiness view self-cleans via a periodic stale-sweep
        // (catches the QUIC-open-but-silent black-hole case, where no disconnect event fires). Clean
        // departures are evicted faster by the routed TransportObservation.PeerDisconnected route below.
        // The map is leader-only (fanIfLeader-gated), so the sweep is a no-op on followers.
        var readinessSweepMaxAgeNanos = config.timeouts().cluster().pingInterval().nanos() * 3;

        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> pongSignalFan.sweepStale(readinessSweepMaxAgeNanos),
                                                                      config.timeouts().cluster().pingInterval(),
                                                                      config.timeouts().cluster().pingInterval()));
        Supplier<Long> rabiaTermSupplier = leaderTerm::get;
        // #114 W1: the cluster-sync ping carries a monotonic generation counter. Each node bumps its
        // OWN counter only while it is leader — ONCE PER pingInterval (1s default), so the counter is
        // a LEADERSHIP-TENURE TICK, not an event count: "1:4254" means rabiaTerm 1 with ~71 minutes of
        // uninterrupted leadership, which is ordinary (#634 follow-up — this exact value was once
        // investigated as an anomaly because the per-interval semantics were written down nowhere).
        // The supplier merely READS it (it is consumed in four contexts; incrementing inside would
        // over-count). No reset is needed: Epoch ordering is term-dominant, so on a leader change
        // leaderTerm increments and a new leader's lower local counter still orders strictly after
        // the prior epoch via the term.
        var generationCounter = new AtomicLong(0L);
        Supplier<Epoch> leaderEpochSupplier = () -> Epoch.epoch(leaderTerm.get(), generationCounter.get());

        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> bumpGenerationIfLeader(isLeaderSupplier,
                                                                                                   generationCounter),
                                                                      config.timeouts().cluster().pingInterval(),
                                                                      config.timeouts().cluster().pingInterval()));
        // B4 (membership v2 §7.5): bind the CDM readiness supplier now that the pong fan + self-state
        // holder exist (READY peers from the leader view + self when locally READY).
        cdmReadyNodesRef.set(() -> nodesReporting(pongSignalFan,
                                                  nodeReportedStateHolder,
                                                  config.self(),
                                                  NodeReportedState.READY));
        cdmDrainingNodesRef.set(() -> nodesReporting(pongSignalFan,
                                                     nodeReportedStateHolder,
                                                     config.self(),
                                                     NodeReportedState.DRAINING));
        var metricsScheduler = ClusterSyncScheduler.clusterSyncScheduler(config.self(),
                                                                         clusterNode.network(),
                                                                         metricsCollector,
                                                                         config.timeouts().cluster().pingInterval(),
                                                                         rabiaTermSupplier,
                                                                         ClusterSyncScheduler.DEFAULT_PING_TIMEOUT_THRESHOLD,
                                                                         leaderEpochSupplier,
                                                                         peerObservationStore,
                                                                         periodicConfig,
                                                                         isLeaderSupplier);
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
        metricsScheduler.setDrainTargets(drainCommandRegistry::drainTargets);
        // #114 — the leader never pings itself, so metricsCollector.observedEpoch() (follower-style,
        // updated only by RECEIVED pings) stays at 0:0 on the leader forever. The CURRENT generation
        // epoch is therefore the leader-minted epoch on the leader and the observed ping epoch elsewhere.
        // gen-route + await-quiesced + NDM read this supplier so deploy barriers see current+1.
        Supplier<Epoch> currentGenerationEpochSupplier = () -> isLeaderSupplier.getAsBoolean()
                                                               ? leaderEpochSupplier.get()
                                                               : metricsCollector.observedEpoch();
        // E2 Phase 2a (2026-05-28): leader-side φ-accrual (#231) is removed. SWIM owns the
        // liveness decision directly; the v2 NTT path is the replacement debounce.
        // P3 (membership unification): the leader/spokesman-gated ReachabilityAggregator
        // pong ingest is removed with the aggregator. SWIM owns liveness; pongs no longer
        // feed a reachability fold.
        metricsCollector.setPeerObservationBuffer(peerObservationStore);
        Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader = () -> kvStore.get(AetherKey.ClusterConfigKey.CURRENT)
                                                                                            .filter(v -> v instanceof AetherValue.ClusterConfigValue)
                                                                                            .map(v -> (AetherValue.ClusterConfigValue) v);
        java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier = commands -> clusterNode.apply(commands);
        var leaderAwareSnapshotSource = snapshotSource;
        // Drain infrastructure: tracker is shared with NodeLifecycleRoutes (/api/node/inflight).
        // The DRAINING-atom-writing ConsensusDrainCoordinator was removed in Phase 2b — the
        // FSM-integrated DrainCoordinator wired into MembershipFsm/CTM is now a no-op stub.
        // The §8.2 unified drain procedure is owned by DrainProcedure (constructed below).
        var inFlightTrackerForDrain = InFlightRequestTracker.inFlightRequestTracker();
        // E2 Phase 2b (2026-05-28): DrainProcedure replaces SelfDrainCoordinator's execution
        // surface. It is a *pure procedure* — no triggers, no periodic ticks, no orphan
        // checker. The single caller in Phase 2b is the QuorumLossDetector quorum-loss
        // listener wired further down. Operator/CTM-initiated drain is delivered as a `DRAIN`
        // command on the leader↔node heartbeat (membership v2 §7.5.4) — there is no KV drain
        // record. Drain is heartbeat-reported and leader-cached, never stored in the KV-Store.
        //
        // `jvmExit` is threaded in from the factory: production is
        // `Runtime.getRuntime().halt(2)`, single-JVM hosts (Forge/Ember) pass a per-node
        // callback that stops the node gracefully without taking down the whole test JVM.
        //
        // `swimLeaveEmitter` is a no-op for Phase 2b — the SWIM `LEAVE` acceleration wiring
        // (spec §8.2 step 3) lands in Phase 6 once the SwimHealthDetector exposes a
        // `voluntaryLeave` API. The drain still halts the node; LEAVE is purely an
        // acceleration of peer-side suspect aging, not a correctness requirement.
        // Item-8 graft (stream-namespaces rebuild): forward-declared best-effort emitter resolved
        // once the ClusterEventAggregator is constructed below. DrainProcedure invokes it once at
        // the INACTIVE→DRAINING transition (single-shot via its CAS gate); the lambda no-ops until
        // the aggregator is bound. Kept as a Consumer<DrainReason> so aether-deployment stays free
        // of any ClusterEvent dependency.
        var clusterEventDrainEmitterRef = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<org.pragmatica.aether.deployment.cluster.DrainReason>>(reason -> {});
        // #427 graceful-departure push: the leaving node pushes its held DHT chunks to their new
        // replicas, ack-gated, before it halts. The DrainProcedure invokes this supplier once at
        // DRAINING and its quiesced-fork exit waits for it (bounded by the push's own budget; the
        // grace fork never does). The observer — resolved once the ClusterEventAggregator is bound
        // below — turns a budget overrun into a DeparturePushIncomplete event; it stays a no-op until
        // then, keeping aether-deployment free of any ClusterEvent / DHT-event dependency.
        var departurePushObserverRef = new java.util.concurrent.atomic.AtomicReference<>(DeparturePushObserver.noop());
        Supplier<Promise<Unit>> departurePush = () -> dhtRebalancer.pushOnDeparture(departurePushObserverRef.get());
        var drainProcedure = DrainProcedure.drainProcedure(inFlightTrackerForDrain,
                                                           () -> {},
                                                           reason -> clusterEventDrainEmitterRef.get()
                                                                                                .accept(reason),
                                                           departurePush,
                                                           jvmExit);
        Supplier<Option<NodeId>> healthLeaderSupplier = () -> clusterNode.leaderManager()
                                                                         .leader();
        // P3 (membership unification): ClusterPhase derives from QUIC quorum + leader presence.
        // ClusterPhaseView adds the leader-awareness semantic (NORMAL requires a leader, else
        // RECOVERING).
        var clusterPhaseView = ClusterPhaseView.clusterPhaseView(((TopologyObserver) clusterNode.topologyManager()).inQuorum(),
                                                                 () -> healthLeaderSupplier.get()
                                                                                           .isPresent());
        Supplier<AetherValue.ClusterPhase> effectivePhaseSupplier = clusterPhaseView::compute;
        // #336 — the leader's OWN resolved config (its per-node overlay rendered by the CLI with
        // ${env:...}/${secrets:...} resolved to literals at bootstrap), parsed ONCE (Lazy) from the
        // node's config file. Threaded into the CTM so the render path can substitute the placeholders
        // a replacement's composed overlay inherits from the deliberately-unresolved persisted KV TOML
        // with the leader's literals at the same TOML path — so a CTM-provisioned scale-up / auto-heal
        // node boots with resolved credentials instead of crashing on placeholders. Returns none()
        // gracefully when no config path was published (forge/tests) or the file can't be parsed.
        var resolvedLocalConfig = Lazy.lazy(AetherNode::parseOwnResolvedConfig);
        var clusterTopologyManager = ClusterTopologyManager.clusterTopologyManager((TopologyObserver) clusterNode.topologyManager(),
                                                                                   lifecycleManager,
                                                                                   config.autoHeal(),
                                                                                   deploymentMap,
                                                                                   leaderAwareSnapshotSource,
                                                                                   clusterConfigReader,
                                                                                   clusterCommandApplier,
                                                                                   effectivePhaseSupplier,
                                                                                   target -> requestDrainThroughFsm(drainCommandRegistry,
                                                                                                                    membershipFsmRef,
                                                                                                                    target),
                                                                                   drainCommandRegistry::clearDrain,
                                                                                   resolvedLocalConfig::get);
        // E2 Phase 2b (2026-05-28): OrphanSelfDrainChecker deleted; NTT (§6) drives departure
        // detection and the §8 unified drain handles surplus dissolution. Membership v2 finale:
        // the leader-pinned `LifecycleReconciler` (and the FSM it wrote through) are gone — the
        // NTT-side `LeaderReconciler` (wired below) is the sole provisioning driver.
        periodicTasks.defer(phaseChangeWatcherArmer(effectivePhaseSupplier, clusterTopologyManager));
        var controller = DecisionTreeController.decisionTreeController(config.controllerConfig());
        var blueprintService = BlueprintService.blueprintService(clusterNode,
                                                                 kvStore,
                                                                 repository,
                                                                 artifactStore,
                                                                 resourceProviderSetup.nodeComposite());
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
        var eventLoopMetricsCollector = EventLoopMetricsCollector.eventLoopMetricsCollector(config.timeouts()
                                                                                                  .observability()
                                                                                                  .eventLoopProbe()
                                                                                                  .millis());
        var snapshotCollector = ComprehensiveSnapshotCollector.comprehensiveSnapshotCollector(gcMetricsCollector,
                                                                                              eventLoopMetricsCollector,
                                                                                              rabiaMetricsCollector,
                                                                                              invocationMetrics,
                                                                                              minuteAggregator);
        var artifactMetricsCollector = ArtifactMetricsCollector.artifactMetricsCollector(artifactStore);
        // Stream-namespaces rebuild (B5b) — cluster events over system:cluster-events:1.0.0.
        //
        // Producer handlers build a sealed `ClusterEvent` record and `emit(...)` it into the
        // framework events stream; reads go through the stream consumer. This replaces the rc1
        // transitional stack (ClusterEventLogPublisher rate-capped KV writer + ClusterEventLogSweeper
        // GC + in-process RingBuffer materialised view). The events stream is now a real
        // partition-managed, replicated stream (provisioned via SystemStreamFactories below);
        // retention is enforced by the stream's production RetentionPolicy (count/byte/age caps).
        //
        // Publisher + consumer are bound deferred via AtomicReferences: the stream stack is
        // constructed further down (after streamPartitionManager), so during the bootstrap window
        // the aggregator falls back to log-only emit / empty reads (spec §13.3). Stream lifecycle
        // events (STREAM_REGISTERED / STREAM_DELETED) are NOT emitted yet — lifecycle-event emission
        // is deferred to RC2 (Wave-5B); nothing produces them today. When it lands, the
        // self-referential STREAM_REGISTERED loop for system:cluster-events must be gated by
        // StreamLifecycleEventPolicy.shouldEmit, the guard already in place for that work.
        var clusterEventsHlcClock = hlcClock;
        var clusterEventsPublisherRef = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.slice.stream.FrameworkStreamPublisher<ClusterEvent>>();
        var clusterEventsConsumerRef = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.slice.stream.FrameworkStreamConsumer<ClusterEvent>>();
        // B5b owner-gate: emit only when THIS node owns (system:cluster-events:1.0.0, partition 0).
        // The ReplicaSetController is constructed further down (after the stream stack), so the
        // owner-check derefs it lazily through this ref. Until the controller is bound — and until the
        // topology observer reports members — the check returns false and emits are dropped+logged
        // (bootstrap window). Once members are visible, isOwner is computed directly from the live HRW
        // placement (no registry/reconcile dependency), so a steady-state single-node cluster is the
        // owner and emits.
        var clusterEventsControllerRef = new java.util.concurrent.atomic.AtomicReference<ReplicaSetController>();
        var clusterEventsStreamName = org.pragmatica.aether.slice.stream.SystemStreams.CLUSTER_EVENTS.asString();
        java.util.function.BooleanSupplier clusterEventsOwnerCheck = () -> Option.option(clusterEventsControllerRef.get())
                                                                                 .map(replicaController -> replicaController.isOwner(clusterEventsStreamName,
                                                                                                                                     0))
                                                                                 .or(false);
        // Leader-gate for consensus-committed membership-DEPARTURE emits (NODE_FAILED / NODE_LEFT):
        // the authoritative emitter of a committed membership decision is the cluster leader, which is
        // never the just-removed node for its own committed decision and is unaffected by the
        // partition-0 HRW churn the owner-gate suffers during the deferred membership reconcile window.
        // Reuses the node's existing leadership signal (clusterNode.leaderManager()::isLeader, captured
        // as isLeaderSupplier above).
        java.util.function.BooleanSupplier clusterEventsLeaderCheck = isLeaderSupplier;
        var eventAggregator = ClusterEventAggregator.clusterEventAggregator(clusterEventsPublisherRef::get,
                                                                            clusterEventsConsumerRef::get,
                                                                            clusterEventsOwnerCheck,
                                                                            config.self(),
                                                                            clusterEventsHlcClock,
                                                                            clusterTopologyManager.observer()::clusterSize,
                                                                            kvStore::isReplaying,
                                                                            clusterEventsLeaderCheck);
        // Item-8 graft: best-effort SelfDrainInitiated emit on drain initiation. The aggregator is
        // forward-declared to DrainProcedure (constructed earlier) via this ref; the emitter lambda
        // resolves it lazily and no-ops until bound. NOT leader-gated — the draining node is the only
        // authoritative source for "I am self-draining".
        // #565: that contract requires `emitLocal`, NOT `emit`. `emit` applies the OWNER gate (a
        // different gate from the leader gate this comment rules out), and a self-fencing node is by
        // definition not the owner of the cluster-events partition — its placement view is the
        // PRE-partition one, whose partition-0 owner is typically among the nodes it just lost. So the
        // one event that explains why a node left was suppressed at DEBUG, invisible at default INFO.
        // `emitLocal`'s own contract cites SelfDrainInitiated as the exemplar for this exact class of
        // per-node fact; this call site was the one that did not follow it.
        clusterEventDrainEmitterRef.set(reason -> eventAggregator.emitLocal(new ClusterEvent.SelfDrainInitiated(clusterEventsHlcClock.now(),
                                                                                                                ClusterEvent.Severity.WARNING,
                                                                                                                "Self-drain initiated on " + config.self()
                                                                                                                                                   .id()
                                                                                                               + " (reason=" + reason
                                                                                                               + ")",
                                                                                                                Map.of("nodeId",
                                                                                                                       config.self()
                                                                                                                             .id(),
                                                                                                                       "reason",
                                                                                                                       String.valueOf(reason)))));
        // #427: resolve the departure-push observer now the aggregator is bound — a budget overrun
        // during graceful departure emits DeparturePushIncomplete (per-node fact, un-gated emitLocal).
        departurePushObserverRef.set((keysAtRisk, sampleKeys) -> eventAggregator.onDeparturePushIncomplete(config.self(),
                                                                                                           keysAtRisk,
                                                                                                           sampleKeys));
        // Operator-driven inject endpoints replicate via the events stream so peer nodes return
        // injected items on cross-node reads. AlertManager emits AlertInjected; InvocationTraceStore
        // emits TraceInjected through a thin sink that keeps the aether-invoke module free of any
        // ClusterEvent dependency.
        alertManager.bindEventSink(eventAggregator::emit, clusterEventsHlcClock);
        alertManager.bindClusterEventsSource(eventAggregator::events);
        traceStore.bindTraceEventSink((operation, requestId, depth, durationMs, metadata) -> eventAggregator.emit(new ClusterEvent.TraceInjected(clusterEventsHlcClock.now(),
                                                                                                                                                 ClusterEvent.Severity.INFO,
                                                                                                                                                 "Injected trace: " + operation,
                                                                                                                                                 metadata)));
        traceStore.bindClusterEventsSource(() -> eventAggregator.events()
                                                                .map(AetherNode::projectClusterTraceInjections));
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
                                                  config.controllerConfig().scalingConfig().evaluationInterval(),
                                                  config.controllerConfig(),
                                                  delegateRouter::route);
        var workerMetricsAggregator = WorkerMetricsAggregator.workerMetricsAggregator(config.self(),
                                                                                      () -> config.self()
                                                                                                  .id(),
                                                                                      invocationMetrics,
                                                                                      snapshot -> delegateRouter.route(new NetworkServiceMessage.Broadcast(snapshot)),
                                                                                      config.controllerConfig()
                                                                                            .scalingConfig()
                                                                                            .evaluationInterval()
                                                                                            .millis());
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
                                                     config.timeouts().invocation().invokerTimeout().millis(),
                                                     config.timeouts().observability().invocationCleanup().millis(),
                                                     deploymentManager);

        deferredInvoker.setDelegate(sliceInvoker);
        var scheduledTaskManager = ScheduledTaskManager.scheduledTaskManager(scheduledTaskRegistry,
                                                                             sliceInvoker,
                                                                             config.self(),
                                                                             command -> clusterNode.apply(List.of(command)),
                                                                             scheduledTaskStateRegistry::stateFor,
                                                                             clusterNode.leaderManager());

        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> {
                                            // #253 ruling (2026-09-04): the `content` instance below is
                                            // provisioned via the keyring-less `defaultContentStorage` (see
                                            // #783 note at compositeDemotionManager/compositeGarbageCollector
                                            // below) -- so when a node-wide keyring IS configured, its
                                            // coverage silently excludes this instance. Named here, at boot,
                                            // so the gap is operator-visible and not only documented.
                                            storageKeyring.onPresent(_ -> LOG.warn("Storage encryption is configured "
                                                                                  + "([storage.encryption]) but the 'content' "
                                                                                  + "storage instance is NOT covered (#783): "
                                                                                  + "its blocks are stored in plaintext"));
                                            registerRuntimeExtensions(spi,
                                                                      topicSubscriptionRegistry,
                                                                      sliceInvoker,
                                                                      cacheDhtClient,
                                                                      StorageFactory.defaultContentStorage(dhtClientOption));
                                        });
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
                                                                                            config.timeouts()
                                                                                                  .deployment()
                                                                                                  .activationChain(),
                                                                                            config.timeouts()
                                                                                                  .deployment()
                                                                                                  .transitionRetryDelay(),
                                                                                            currentGenerationEpochSupplier);
        var serverBossGroup = clusterNode.network().server().map(Server::bossGroup);
        var serverWorkerGroup = clusterNode.network().server().map(Server::workerGroup);
        // #231 Step 3: every control-plane TaskGroup is leader-pinned, so the owner of any group is
        // the current leader. #329: gated on consensus catch-up via leaderCatchUpGate (shared with the
        // ManageableNode.taskGroupOwnerResolver() override) — a lagging replacement leader is withheld
        // ownership (notAssigned) for a bounded window so leader-local commits do not stall and 503.
        Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver = leaderCatchUpGate::resolve;
        // Silent-death fix: the data-path forwarder narrows candidates to currently-reachable nodes so
        // a hard-killed peer (QUIC still CONNECTED until eviction) is dropped from selection/retry long
        // before the forward timeout would otherwise burn. Membership-FSM unification (Wave D, consumer
        // #2): reachability is now decided by the authoritative MembershipFsm.reachableMembers (counted
        // MEMBER + SUSPECT) rather than NTT.keepOnlyAccessible. The FSM is constructed later (membership
        // v2 wiring below), so the filter derefs the shared membershipFsmRef at request time; before the
        // FSM exists it degrades to identity (forward falls back to the connectedPeers-only behavior).
        AccessibilityFilter accessibilityFilter = candidates -> Option.option(membershipFsmRef.get())
                                                                      .map(fsm -> fsm.reachableMembers(candidates))
                                                                      .or(candidates);
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
                                                        Option.some(taskGroupOwnerResolver),
                                                        accessibilityFilter);
        // #231 Step 1: ClusterSyncScheduler (metricsScheduler) is quorum-driven via its
        // onQuorumStateChange route below; task-assignment registration was redundant AND harmful
        // (deactivate() drove the FSM to Dormant on METRICS-group reassignment even while quorum
        // held, never resuming — the leader-change detection-blindness bug). DeploymentMetricsScheduler
        // is leader-pinned via toggleDeploymentMetricsOnLeaderChange instead of task-assignment.
        // #231 Step 2: the remaining control-plane components (CDM, LoadBalancer, ControlLoop,
        // TTMManager, RollbackManager, AbTestManager, DeploymentManager, StorageAdapter, StreamingCoordinator)
        // are leader-pinned via their toggle*OnLeaderChange routes in collectRouteEntries / allEntries below.
        // #250: real demotion + GC, fanned out across every storage setup this node holds via
        // StorageFactory.composite* -- currently `artifacts` and `streams` (`storageSetups`, built
        // above). The `content` tier is NOT covered: `defaultContentStorage` (registered above via
        // registerRuntimeExtensions) provisions a bare StorageInstance for ContentStore outside
        // `storageSetups`, so it never reaches compositeDemotionManager/compositeGarbageCollector
        // and gets no demotion or GC from this driver (#783). Leader-pinned activation below
        // (toggleStorageOnLeaderChange) is unchanged — only the no-op stand-in is replaced. Both
        // DefaultDemotionManager and DefaultStorageGarbageCollector self-gate on their internal
        // `active` flag, so the periodic driver below can call demote()/collectGarbage()
        // unconditionally on every node (leader or not) and it safely no-ops until this node's
        // adapter is activated.
        var compositeDemotionManager = StorageFactory.compositeDemotionManager(storageSetups);
        var compositeGarbageCollector = StorageFactory.compositeGarbageCollector(storageSetups);
        var delegatedStorageAdapter = DelegatedStorageAdapter.delegatedStorageAdapter(compositeDemotionManager,
                                                                                      compositeGarbageCollector);
        var storageMaintenanceDriver = StorageMaintenanceDriver.storageMaintenanceDriver(compositeDemotionManager,
                                                                                         compositeGarbageCollector);

        LOG.info("Storage maintenance enabled: demotion+GC cadence={}, storage setups={}",
                 config.timeouts().storageMaintenance().interval(),
                 storageSetups.keySet());
        var consumerGroupRegistry = ConsumerGroupRegistry.consumerGroupRegistry();
        var consumerGroupCoordinator = ConsumerGroupCoordinator.consumerGroupCoordinator(clusterNode);
        var managementServerRef = new AtomicReference<Option<ManagementServer>>(Option.empty());
        // Hoisted before collectRouteEntries (Wave 9 Fix A): the PASSIVE→process-exit-drain bridge
        // routes through this detector's windowed path, so the route-collection needs the ref
        // (the detector itself is constructed below and `set` into the ref before routing starts).
        var quorumLossDetectorRef = new AtomicReference<QuorumLossDetector>();
        var aetherEntries = collectRouteEntries(kvStore,
                                                nodeDeploymentManager,
                                                clusterDeploymentManager,
                                                endpointRegistry,
                                                topicSubscriptionRegistry,
                                                streamConsumerRegistry,
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
                                                configRegistry,
                                                logLevelRegistry,
                                                ownershipEpochHighWater,
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
                                                consumerGroupCoordinator,
                                                consumerGroupRegistry,
                                                quorumLossDetectorRef,
                                                managementServerRef,
                                                config.self());

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
        // #427: a graceful-departure push's ack resolves the departing node's pending push, so its
        // drain can confirm the chunk reached a surviving replica before halting.
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataAck.class, dhtRebalancer::onMigrationDataAck));
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
        @SuppressWarnings({"unchecked", "rawtypes"})
        MessageRouter.Entry forwardRequestRoute = MessageRouter.Entry.route(ForwardApplyRequest.class,
                                                                            (ForwardApplyRequest request) -> handleForwardApplyRequest(request,
                                                                                                                                       clusterNode));

        aetherEntries.add(forwardRequestRoute);
        @SuppressWarnings({"unchecked", "rawtypes"})
        MessageRouter.Entry forwardResponseRoute = MessageRouter.Entry.route(ForwardApplyResponse.class,
                                                                             forwardingClusterNode::onForwardApplyResponse);

        aetherEntries.add(forwardResponseRoute);
        var growthLog = LoggerFactory.getLogger(AetherNode.class);
        var selfId = config.self();
        var rotatingEncryptor = createGossipEncryptor(config);
        var gossipKeyRotationHandler = GossipKeyRotationHandler.gossipKeyRotationHandler(rotatingEncryptor);
        // Worker-mode activation needs the SWIM detector to feed the governor announcer
        // (GAP 2). The detector is a local declared further down (after the SWIM config is
        // built), but this registration lambda is constructed here — so capture it through a
        // holder seeded immediately after the detector is created. The ActivationDirectiveKey
        // ValuePut handler only fires long after seeding, so the holder is always populated by
        // read time. A holder (not a reorder) keeps the router-build / replay-burst ordering
        // documented below intact.
        var swimHealthDetectorHolder = new AtomicReference<CoreSwimHealthDetector>();
        // #642: worker-mode subsystems are created lazily, when a WORKER activation directive arrives —
        // long after assembly — so the announcer cannot be a plain local. The holder is how stop()
        // reaches it to cancel the re-announce tick; empty on a node that never became a worker.
        var governorAnnouncerHolder = new AtomicReference<GovernorAnnouncer>();
        var activationKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
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
                                                                                                                                                                                swimHealthDetectorHolder::get,
                                                                                                                                                                                governorAnnouncerHolder,
                                                                                                                                                                                growthLog))
                                                     .onPut(AetherKey.GossipKeyRotationKey.class,
                                                            gossipKeyRotationHandler::onGossipKeyRotationPut)
                                                     .build();
        // Gossip-key delivery (§5.8 AMENDED): the GossipKeyRotationKey subscription above is the
        // SOLE delivery path. A late joiner that synced AFTER the rotation PUT receives the
        // current rotation as a replayed ValuePut on this normal subscription once the engine
        // activates (sync → activate → replay) — no ad-hoc replayFromStore needed. The replay
        // burst structurally precedes any live apply, so the joiner adopts the cluster key before
        // it sends its first SWIM datagram. applyRotation is idempotent, so a later live rotation
        // re-PUT is harmless.
        var allEntries = new ArrayList<>(clusterNode.routeEntries());

        allEntries.addAll(aetherEntries);
        allEntries.addAll(activationKvRouter.asRouteEntries());
        var swimTimeouts = config.timeouts().swim();
        var swimConfig = SwimConfig.fromTimeouts(swimTimeouts.period(),
                                                 swimTimeouts.probeTimeout(),
                                                 swimTimeouts.suspectTimeout())
                                   .withSwimPortOffset(CoreSwimHealthDetector.SWIM_PORT_OFFSET)
                                   // Arm the cross-cluster ANNOUNCE gate. `fromTimeouts` inherits
                                   // DEFAULT's empty cluster name, and empty means "no gating" — so
                                   // until this call the guard existed, was wired, and rejected
                                   // nothing. The name is also what `announceJoin` stamps on
                                   // outbound ANNOUNCEs below, so both sides of the comparison come
                                   // from here. Absent (in-process harnesses that never pass through
                                   // Main) leaves it inert exactly as before.
                                   .withClusterName(config.clusterName().map(ClusterName::value).or(""));
        // Phase-aware SWIM cold-boot suppression (D.3, 2026-05-11). SWIM suppresses
        // FAULTY-for-never-HEALTHY peers ONLY while the cluster is in `COLD_BOOT`
        // (initial formation, never reached quorum). In `NORMAL` and `RECOVERING`,
        // FAULTY edges always emit — the `RECOVERING` branch is the critical fix
        // for compose-restart: peers were Healthy in the prior NORMAL period and a
        // post-restart kill must produce `FaultyObserved` (drives DECOMMISSIONED
        // write + NODE_LEFT / NODE_FAILED downstream event).
        // E.6 / E.8 (spec §7.2): SWIM phase-suppression gate routes through `ClusterPhaseView`
        // (the single source of truth post-E.8).
        //
        // A6 cold-boot convergence window (2026-06-28): the phase-only gate flips out of COLD_BOOT at
        // FIRST quorum (e.g. 3/5), but on a simultaneous full-cluster restart the remaining nodes' QUIC
        // links may not have formed yet — the transport single-dialer force-dials a straggler only after
        // a 60s grace (QuicClusterNetwork.RECONCILE_BACKOFF_CAP_MS). Without a window SWIM would
        // FAULTY-evict (then authoritatively REMOVE) the not-yet-connected seeds at ~quorum time, wedging
        // the cluster permanently below full membership (terminal-removal is irreversible). OR-in a
        // one-shot window anchored at THIS node's boot so never-HEALTHY SEEDS stay UNKNOWN (the existing,
        // tested COLD_BOOT suppression branch — no tombstone, formation-safe) until the transport has
        // exhausted its dial attempts. Proven-HEALTHY peers are unaffected: every suppression branch
        // short-circuits on everSeenHealthy, so a real death still FAULTYs immediately regardless.
        //
        // #642: "boot" is the instant this node STARTS, not the instant it was assembled. The holder is
        // seeded here so an assembled-but-never-started node behaves exactly as before, and re-stamped
        // by start() (`swimBootAt` record component) so the window always covers the node's first
        // COLD_BOOT_CONVERGENCE_WINDOW_MS of RUNNING life. Anchoring at assembly meant a node started
        // more than that window after construction — routine in a staggered-restart harness — booted
        // with the suppression already expired, i.e. zero cold-boot protection.
        var swimBootAtMs = new AtomicLong(System.currentTimeMillis());
        BooleanSupplier swimIsBootingSupplier = () -> coldBootConvergenceActive(effectivePhaseSupplier.get() == AetherValue.ClusterPhase.COLD_BOOT,
                                                                                swimBootAtMs.get(),
                                                                                System.currentTimeMillis(),
                                                                                COLD_BOOT_CONVERGENCE_WINDOW_MS);
        // Leader-faulty evictor (2026-05-09): bridges SWIM-FAULTY → QUIC disconnect when
        // the FAULTY peer IS the current cluster leader. Breaks the consensus.apply
        // broadcast stall on cloud Container kill-leader (post-Step-3 architecture
        // otherwise depends on consensus to evict, but consensus is what's stuck).
        // Narrow trigger preserves Step 3's removal of the N+1 fan-out cascade for
        // general FAULTY peers. See SwimHealthContext.faultyLeaderEvictor field doc.
        Consumer<NodeId> faultyLeaderEvictor = peer -> clusterNode.network()
                                                                  .disconnect(new NetworkServiceMessage.DisconnectNode(peer));
        // Gate RCA fix (2026-06-11): SWIM transport-connectivity veto — a never-HEALTHY peer past
        // its join grace is FAULTY'd ONLY if it ALSO has no live QUIC connection (co-confirmed
        // ghost). Sourced from the network's live connectedPeers() view, deref'd per call (the
        // network reference is live long before SWIM probing starts, so construction order is
        // safe). One-way evidence: transport may VETO a death; it never reports life.
        Predicate<NodeId> swimTransportConnected = peer -> clusterNode.network()
                                                                      .connectedPeers()
                                                                      .contains(peer);
        var swimHealthDetector = CoreSwimHealthDetector.coreSwimHealthDetector(delegateRouter,
                                                                               config.topology(),
                                                                               serializer,
                                                                               deserializer,
                                                                               leaderEpochSupplier,
                                                                               isLeaderSupplier,
                                                                               peerObservationStore,
                                                                               swimConfig,
                                                                               swimIsBootingSupplier,
                                                                               faultyLeaderEvictor,
                                                                               swimTransportConnected);

        swimHealthDetectorHolder.set(swimHealthDetector);
        // Single `(NodeId, incarnation)` authority: the metrics readiness epoch is sourced from
        // the SWIM self-incarnation, floored at a captured boot value so it never reports below
        // boot-millis during the pre-announce window (before `announceJoin` seeds the SWIM counter).
        // The SAME `bootIncarnation` seeds `announceJoin` below, so SWIM's seed == the metrics floor.
        var bootIncarnation = System.currentTimeMillis();

        metricsCollector.setIncarnationSupplier(() -> Math.max(bootIncarnation, swimHealthDetector.selfIncarnation()));
        // RC1 (S01 fix) — wire the SWIM-backed liveness check for owner-broadcast eviction
        // hints. Followers REFUSE to act on the owner's `ClusterSyncPing.evictionHints` for
        // peers SWIM observes as HEALTHY; the owner's hint is a SUGGESTION, not authority.
        // SWIM HEALTHY requires actual probe-ack within `suspectTimeout`, so this gate is
        // robust against owner partial-network errors. See `ClusterSyncCollector.processEvictionHints`.
        metricsCollector.setPeerLocallyAlive(nodeId -> swimHealthDetector.healthOf(nodeId) == SwimHealth.HEALTHY);
        // Option 1 (S01) — feed cluster-sync missed-pong into SWIM as a transport-unreachable HINT
        // instead of a destructive disconnect. SWIM drives the SUSPECT → 3s-floored-FAULTY →
        // DepartedObserved → synchronous-DEAD pipeline (the same path the QUIC `onPeerLeft` listener
        // feeds) and refutes the hint when pongs resume, so a transient flap no longer false-evicts a
        // healthy peer. The owner-side SWIM-HEALTHY early-skip in `emitPingTimeoutIfExceeded` still
        // suppresses the hint for a peer SWIM already trusts (avoids conflicting evidence).
        metricsCollector.setUnreachableReporter(nodeId -> swimHealthDetector.recordTransportHint(new TransportObservation.PeerUnreachable(nodeId,
                                                                                                                                          QuicTransportCause.PING_TIMEOUT)));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> swimHealthDetector.onLeaderChanged(change.leaderId())));
        var announceTopology = config.topology();
        var selfNodeInfo = announceTopology.coreNodes()
                                           .stream()
                                           .filter(n -> n.id()
                                                         .equals(announceTopology.self()))
                                           .findFirst()
                                           .orElse(null);
        var swimSeeds = announceTopology.coreNodes()
                                        .stream()
                                        .filter(n -> !n.id()
                                                       .equals(announceTopology.self()))
                                        .map(n -> InetSocketAddress.createUnresolved(n.address().host(),
                                                                                     n.address()
                                                                                      .port() + CoreSwimHealthDetector.SWIM_PORT_OFFSET))
                                        .toList();
        Runnable announceJoinTrigger = selfNodeInfo == null
                                       ? () -> {}
                                       : () -> swimHealthDetector.announceJoin(selfNodeInfo,
                                                                               swimConfig.clusterName(),
                                                                               bootIncarnation,
                                                                               swimSeeds);
        // SWIM start is deferred to transport-ready (invoked from the boot chain after
        // `startClusterAsync()`), NOT gated on quorum — see `startSwim` doc. This trigger
        // closes over the encryptor + announce trigger that are only in scope here.
        Runnable startSwimTrigger = () -> startSwim(swimHealthDetector,
                                                    clusterNode.network(),
                                                    rotatingEncryptor,
                                                    announceJoinTrigger);
        // ---------------------------------------------------------------------
        // Membership v2 — NTT wiring (spec §6, §7.4). E2 Phase 2a (2026-05-28) made the
        // observation unconditional: NTT + QuorumLossDetector + LeaderReconciler are
        // constructed and listeners registered on every node. The migration-ramp
        // observation-flag and DivergenceLogger are gone.
        var membershipConfig = config.membership().or(MembershipConfig::membershipConfig);
        IntSupplier configuredCoreCountSupplier = () -> clusterConfigReader.get()
                                                                           .map(AetherValue.ClusterConfigValue::coreCount)
                                                                           .or(() -> config.topology()
                                                                                           .coreNodes()
                                                                                           .size());
        var leaderReconcilerRef = new AtomicReference<LeaderReconciler>();
        Runnable nttReconcileTrigger = () -> onNttReconcile(quorumLossDetectorRef,
                                                            membershipFsmRef,
                                                            leaderReconcilerRef,
                                                            config.self());
        Supplier<HealthSnapshot> nttHealthSupplier = () -> swimHealthDetector.currentHealth()
                                                                             .or(() -> HealthSnapshot.healthSnapshot(Map.of()));
        var presenceSampler = PresenceSampler.presenceSampler(membershipConfig,
                                                              config.self(),
                                                              nttHealthSupplier,
                                                              nttReconcileTrigger);

        presenceSampler.start();
        // P3 (membership unification): quorum-loss self-drain is sourced from NTT's stable
        // member count via the nttReconcileTrigger above; the grace window + the
        // arm-after-first-quorum guard live in QuorumLossDetector (ported from the deleted
        // LocalQuorumWatcher, whose drain firing was dormant). The drain chain is registered
        // below once drainProcedure/leaderReconciler exist.
        var quorumLossDetector = QuorumLossDetector.quorumLossDetector(membershipConfig, configuredCoreCountSupplier);

        quorumLossDetectorRef.set(quorumLossDetector);
        Supplier<Option<ClusterName>> clusterNameSupplier = () -> clusterConfigReader.get()
                                                                                     .map(AetherValue.ClusterConfigValue::clusterName)
                                                                                     .flatMap(ClusterName::maybeClusterName);
        // Membership v2 Phase 2 LIVE — the per-member MembershipFsm is the authoritative membership-
        // death decision-maker. It is ALWAYS-ON per node (no leader gate): every node drives its own
        // per-member FSMs from its tapped SWIM/liveness edges. Wave 7 (cluster-topology-overhaul):
        // the FSM is the SOLE membership authority — the legacy FSM→PresenceSampler eviction is GONE
        // (the sampler is a pure debounce sensor feeding Up/DownHysteresisMet edges INTO the FSM);
        // the prompt heal/quorum nudge the eviction used to provide now rides the FSM's own death
        // edge (onConfirmedDeparture chain below). Only scaling (LeaderReconciler) stays
        // leader-gated. The FSM is constructed BEFORE the reconciler because the reconciler COUNTS
        // this FSM's coreCountedMembers() as its base membership set (#68/#94 churn cutover).
        // #68 — restores the TTL the FSM migration dropped: the quiesce SUSPECTED health-hint now
        // ages out after the auto-heal SWIM-hints TTL (config.autoHeal().swimHintsTtl(), the SAME
        // value the legacy SwimHintsRegistry uses below; .millis() is milliseconds). A stale one-shot
        // SWIM-suspect on a still-present node (load/churn false positive, no SwimHealthy recovery
        // edge, present so no co-confirmed DEAD) decays out of the quiesce gate after the TTL instead
        // of sticking the cluster DEGRADED forever. Default System clock.
        // Wave 7 (H2 + M10): the DEPARTING timeout and join-grace windows are both wired from the
        // configured nttDepartureTimeout — the single membership departure-debounce constant (the
        // same one the sampler derives its down-hysteresis from).
        var membershipFsm = MembershipFsm.membershipFsm(config.autoHeal().swimHintsTtl().millis(),
                                                        membershipConfig.splitTimeout(),
                                                        membershipConfig.splitTimeout(),
                                                        membershipConfig.splitTimeout());
        // Publish the FSM into the deferred holder so the membership consumers wired earlier (DHT
        // livePeers, accessibility filter, quorum-count propagation) read the authoritative FSM set.
        membershipFsmRef.set(membershipFsm);
        // Wave-1 Enrichment A (cluster-topology-overhaul spec): per-node TRANSITION JOURNAL —
        // bounded per-layer ring buffer recording EVERY MembershipFsm transition and EVERY
        // PeerState transition, dumpable via GET /api/cluster/journal. Diagnostic-only and
        // permanent. The FSM listener is installed BEFORE the boot seed below so the seed's
        // OBSERVED→MEMBER promotions are the journal's first entries. The PEER-layer listener
        // and the §6.4 boot future-history feed are wired further down, once clusterNetworkRef
        // is in scope.
        var transitionJournal = TransitionJournal.transitionJournal();

        membershipFsm.onTransition(record -> onFsmTransition(transitionJournal,
                                                             quorumLossDetectorRef,
                                                             membershipFsm,
                                                             record,
                                                             config.self()));
        // Wave-4 (cluster-topology-overhaul, #245): the MembershipDeltaProjector is the SOLE
        // emitter of MembershipDecision, fed by the FSM's own JOINED/REMOVED delta edge —
        // installed BEFORE the boot seed below so the seeded members' OBSERVED→MEMBER
        // promotions baseline them (their later deaths must emit NodeRemoved; the #245 victim
        // was exactly a member promoted outside the old evaluateQuorumState-coupled diff). The
        // projector preserves the old emission contract exactly: quorum-gated via the
        // observer's inQuorum() bit (suppressed while non-quorate; pendings flush on the
        // shared-bus ClusterStateNotification ACTIVE echo routed below — the ConsensusBridge
        // re-emission of the engine activation the quorum edge itself causes), stamped with
        // (observedRabiaTerm, HLC now), routed on the shared bus, and pruning departed peers
        // through the observer's pre-existing removeNode path. Fully decoupled from
        // evaluateQuorumState per spec §3.1 — the FSM promotion edge never reaches it.
        var topologyObserver = (TopologyObserver) clusterNode.topologyManager();
        var membershipDeltaProjector = MembershipDeltaProjector.membershipDeltaProjector(topologyObserver.inQuorum(),
                                                                                         snapshotSource::observedRabiaTerm,
                                                                                         hlcClock::now,
                                                                                         delegateRouter::route,
                                                                                         delegateRouter::route,
                                                                                         topologyObserver::pruneDeparted);

        membershipFsm.onMembershipDelta(membershipDeltaProjector::onDelta);
        allEntries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                                 membershipDeltaProjector::onClusterStateNotification));
        // One-time boot seed from the configured topology member set. The FSM's SWIM observation tap
        // (added below, before SWIM starts via the whenReady(startSwimTrigger) lifecycle) already
        // promotes members naturally on their first HealthyObserved edge, so this seed is
        // belt-and-suspenders: it promotes only still-OBSERVED ids and never resurrects a DEAD one.
        // Item 1 (activation): seed FSM descriptors (address/role/source) from the configured topology
        // BEFORE the member seed below, so `desiredConnections()` carries dial addresses IMMEDIATELY at
        // boot. Without this the descriptor map is empty until SWIM supplies NodeInfo observations, the
        // desired-connection set would have no address-known members, and formation would stall waiting
        // for the first gossip round. Last-wins: SWIM-resolved addresses overwrite these as they arrive.
        config.topology().coreNodes().forEach(membershipFsm::onMemberDescriptor);
        membershipFsm.seed(config.topology().coreNodes().stream().map(NodeInfo::id).collect(Collectors.toSet()));
        // Route NTT's down-hysteresis crossing edge into the FSM (post-construction installer — the FSM
        // exists only now, AFTER presenceSampler). A sustained-absence SUSPECT member is then bounded by the FSM
        // (SUSPECT→DEPARTING per spec §3.3 / invariant I4) so a genuinely-gone node still departs the
        // count exactly once, rather than NTT independently removing it from its own set.
        presenceSampler.onDownHysteresisCrossing(membershipFsm::onDownHysteresisMet);
        var leaderReconciler = LeaderReconciler.leaderReconciler(membershipConfig,
                                                                 presenceSampler,
                                                                 membershipFsm,
                                                                 configuredCoreCountSupplier,
                                                                 leaderTerm::get,
                                                                 clusterTopologyManager,
                                                                 clusterNameSupplier,
                                                                 TimeSource.system(),
                                                                 SharedScheduler::schedule);

        leaderReconcilerRef.set(leaderReconciler);
        // Provisioning-stickiness fix — read-only supplier wiring (no consensus). The leader's metrics
        // ping carries its in-flight provisioning set (LeaderReconciler -> ClusterSyncPing.dispatchedNodes);
        // followers retain it term-fenced. On leadership gain LeaderReconciler seeds its in-flight set
        // from that retained set (ClusterSyncCollector::retainedDispatchedNodes) so a new leader does
        // not re-dispatch replacements the prior leader already provisioned (the over-provisioning bug).
        metricsScheduler.setDispatchedNodesSupplier(leaderReconciler::inFlightProvisioningKeys);
        leaderReconciler.setRetainedDispatchedSupplier(metricsCollector::retainedDispatchedNodes);
        // Drain-victim slice-owner exclusion (Approach 3): wire the authoritative KV-Store-backed
        // active-slice-ownership predicate so the reconciler never drains a node currently serving /
        // hosting slices as scale-down or over-provision surplus. KV-derived (leader-agnostic,
        // reconstructible from cluster state) and consulted through a narrow Predicate<NodeId> so
        // the membership-layer reconciler keeps no hard dependency on the deployment FSM.
        leaderReconciler.setOwnsActiveSlices(SliceOwnershipQuery.ownsActiveSlices(kvStore));
        swimHealthDetector.addObservationListener(presenceSampler::onSwimObservation);
        // E2 Phase 1.5 — symmetric "surplus appeared" trigger: a SWIM HealthyObserved
        // signals a peer became reachable; if the leader is in surplus the reconcile
        // will dispatch drains. NTT catches shortage (DepartedObserved), this catches
        // surplus. KEPT — provisioning surplus trigger, NOT death.
        swimHealthDetector.addObservationListener(obs -> {
            if (obs instanceof SwimObservation.HealthyObserved h) {
                leaderReconciler.onSwimMemberHealthy(h.peer(), h.incarnation());
            }
        });
        // Phase 2 LIVE: feed every SWIM-plane edge into the authoritative MembershipFsm. HEALTHY /
        // SUSPECT / FAULTY / DEPARTED / UNKNOWN map to the matching onSwim* ingress; the FSM drives the
        // per-member lifecycle and evicts on the DEAD edge. Leader-gating happens inside the FSM.
        swimHealthDetector.addObservationListener(obs -> routeSwimEdgeToMembershipFsm(obs, membershipFsm));
        // P3 (membership unification): quorum-loss is detected by QuorumLossDetector from the
        // unified tracker's stable membership (armed after first quorum; grace =
        // quorumLossDrainThreshold) and drives the §8.2 unified `DrainProcedure` directly.
        // Trigger detection (QuorumLossDetector) and procedure execution (DrainProcedure)
        // stay separated.
        Consumer<QuorumLossIntent> quorumLossChain = ((Consumer<QuorumLossIntent>) leaderReconciler::onQuorumLossIntent).andThen(intent -> drainProcedure.initiate(DrainReason.QUORUM_LOSS))
                                                                                                                        .andThen(intent -> nodeReportedStateHolder.onDrainStarted());

        quorumLossDetector.setQuorumLossListener(quorumLossChain);
        // Fix C (split-brain self-fence co-confirmation): before the QUORUM_LOSS drain commits, the
        // detector consults this snapshot of the FSM's counted (MEMBER+SUSPECT) set vs raw-SWIM
        // liveness of the members missing from the STRICT (MEMBER-only) count. The strict count is
        // the detector's numerator; a false-FAULTY storm can wedge a still-present, SWIM-alive peer
        // pre-MEMBER (SUSPECT), dropping strict below quorum while the cluster is intact. Suppression
        // requires counted-still-quorate AND every stuck member SWIM-alive — a genuine partition
        // fails both (unreachable members are NOT SWIM-alive and age out of SUSPECT), so the real
        // self-fence is never masked.
        quorumLossDetector.setCoConfirmationSupplier(() -> buildQuorumCoConfirmation(membershipFsm, swimHealthDetector));
        // A6: gate the quorum-loss self-drain with the SAME cold-boot window the SWIM FAULTY-suppression
        // uses. On a simultaneous full-cluster restart SWIM's first probe-acks lag the QUIC attach, so the
        // detector's SWIM-alive count momentarily decays below threshold and healthy nodes would self-fence
        // before convergence (the 3/5-wedge root cause). Deferring the fence during the bounded cold-boot
        // window lets all nodes reform; a genuine minority still self-fences once the window elapses.
        quorumLossDetector.setColdBootSupplier(swimIsBootingSupplier);
        metricsCollector.setDrainCommandHandler(() -> commandedDrain(drainProcedure, nodeReportedStateHolder));
        // #590 community tier: the core spokesman pings on `pingInterval`, so the SILENCE of that ping
        // is this node's core-liveness signal — the only one that does not depend on the node being
        // able to write to the core, which under isolation it cannot. Observed per node rather than
        // per governor, mirroring quorumLossDetector: no intra-community coordination, and a
        // partitioned subset fences exactly itself. The core independently stops placing work here on
        // the strictly longer `community_absence` window, which is the no-double-active ordering.
        var coreAbsenceDetector = CoreAbsenceDetector.coreAbsenceDetector(config.timeouts().cluster().coreAbsence(),
                                                                          config.timeouts().cluster().pingInterval());

        coreAbsenceDetector.setCoreAbsenceListener(_ -> drainProcedure.initiate(DrainReason.CORE_ABSENCE));
        // FAIL-SAFE GATE. The ping is leader-broadcast and leader-only, and a broadcast never reaches
        // its own sender — so on the CORE tier the signal is structurally absent twice over: the leader
        // never receives its own pings, and during an election nobody receives any. Ungated this drained
        // the new leader ten seconds after every election. Core liveness is quorumLossDetector's job;
        // this fence is the COMMUNITY tier's, so only a node positively known NOT to be core may fire it.
        // An empty/unresolved core view reads as SUPPRESS — fencing on an unknown view is the dangerous
        // direction, and the view is empty during boot.
        coreAbsenceDetector.setFenceSuppressor(() -> {
            var cores = topologyObserver.coreNodes();

            return cores.isEmpty() || cores.contains(config.self());
        });
        metricsCollector.setCorePingObserver(coreAbsenceDetector::recordCorePing);
        coreAbsenceDetector.start();
        // #590 core half, same exchange in the other direction: every live node answers the leader's
        // broadcast ping, so pong silence is the leader's OWN observation that a community member has
        // gone. It replaces `GovernorAnnouncementValue.memberCount` as the FSM's liveness input —
        // that field is the community's self-report and FREEZES under partition instead of expiring,
        // which is why an unreachable community stayed ACTIVE and kept being given work.
        var communityAbsenceNanos = config.timeouts().cluster().communityAbsence().nanos();

        clusterDeploymentManager.setCommunityLiveness(node -> metricsCollector.sinceLastPongNanos(node)
                                                                              .map(since -> since >= communityAbsenceNanos)
                                                                              .or(false));
        // QUIC reconnect feeds NTT's soft up-bias (unchanged) AND the FSM's peer-connected tap.
        Consumer<NodeId> nttConnectTap = ((Consumer<NodeId>) presenceSampler::onQuicReconnect).andThen(membershipFsm::onPeerConnected);
        // QUIC disconnect feeds BOTH NTT's soft down-bias (unchanged) AND the liveness half of the
        // confirmed-death gate in the authoritative MembershipFsm. A routed QUIC disconnect fires on a
        // bare transport drop — a genuine transport-gone sensor. The 3-missed-pong ClusterSync
        // ping-timeout no longer manufactures a disconnect (option 1); it now feeds SWIM a transport-
        // unreachable hint, which reaches the FSM via SWIM-FAULTY co-confirmation instead of this tap.
        // Bare disconnect alone does NOT evict — the FSM gates it behind SWIM-FAULTY co-confirmation.
        // Leader-gated inside the FSM.
        Consumer<NodeId> nttDisconnectTap = ((Consumer<NodeId>) presenceSampler::onQuicDisconnect).andThen(membershipFsm::onLivenessGone);
        // P5: the NTT-reconciler leader-toggle (auto-heal activation) is now wired into the
        // live router. Safe because LeaderReconciler is identity-aware — it arms provisioning
        // only after the cluster first reaches configuredCoreCount, so it never provisions a
        // phantom replacement for a configured core peer that simply hasn't joined yet (the
        // former Bug C death-spiral). After arming, a genuine departure triggers auto-heal.
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> toggleNttReconcilerOnLeaderChange(change, leaderReconciler)));
        // P4 (Bug B FIX): readiness + evict routes are now added to `allEntries` (the list the
        // router is actually built from below). Previously they were appended to the
        // already-merged `aetherEntries`, so they never installed — node readiness was never
        // reported, CDM `allocatableNodes` stayed empty, and zero slices were placed.
        // B1 (membership v2 §7.5): node-reported readiness no longer routes a ClusterStateNotification
        // edge into the holder — consensus-active is now a LIVE LEVEL sampled per pong via
        // `clusterNode::isActive` at the holder-construction site above, eliminating edge desync.
        // B3 (membership v2 §7.5.5): evict a node from the leader readiness view on routed QUIC
        // disconnect (TransportObservation.PeerDisconnected fires on QUIC REMOVE, not on RECONNECT
        // flaps). Fast clean-departure eviction; the stale-sweep above is the silent-node backstop.
        allEntries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected.class,
                                                 obs -> pongSignalFan.evict(obs.nodeId())));
        // SwimProtocol → router wire-up: SWIM-detected FAULTY peers are forwarded to the
        // cluster-wide `TransportObservation` stream so subscribers (LeaderManager,
        // ClusterFsmRouter, etc.) reach all `TransportObservation.PeerObservedFaulty` edges.
        swimHealthDetector.addTransportObservationEmitter(delegateRouter::route);
        // RC1-9 audit Step 4: ClusterEventAggregator no longer subscribes to SWIM
        // observations directly. NODE_FAILED / NODE_LEFT events are emitted via the
        // canonical MembershipDecision route (MembershipDeltaProjector, Wave 4), keyed off
        // the authoritative MembershipFsm delta edge. The SWIM-witnessed duplicate emit was
        // amplifying the membership-tracker cascade audit identified.
        // RC1-9 audit Step 3: the SWIM-FAULTY-to-disconnect short-circuit lambda is
        // gone. QUIC eviction now flows from `MembershipDecision.NodeRemoved`
        // (routed by the `MembershipDeltaProjector` on the FSM's REMOVED delta edge).
        // There is no node-state KV write on this path.
        // The membership-delta-driven path is a single canonical edge instead of N+1
        // fan-out across every survivor's local SWIM listener.
        var clusterNetworkRef = clusterNode.network();

        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                                 (MembershipDecision.NodeRemoved removed) -> clusterNetworkRef.disconnect(new NetworkServiceMessage.DisconnectNode(removed.nodeId()))));
        allEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                                 (MembershipDecision.NodeDecommissioned decommissioned) -> clusterNetworkRef.departurePermanent(decommissioned.nodeId())));
        swimHealthDetector.addObservationListener(obs -> {
            switch (obs) {
                // v2 §5 formation: feed every SWIM-known peer into the TopologyObserver dial set via the
                // canonical discovery message, routed through `delegateRouter` exactly like an inbound
                // network `DiscoveredNodes` (`DiscoveredNodes` → `handleDiscoveredNodes` → `addNode`).
                // Routing — rather than a direct call — dispatches on the router thread (no cross-thread
                // `nodeStatesById` mutation) and avoids widening the narrow `TopologyManager` interface.
                // addNode populates the dial set, requests the connection, re-evaluates quorum, and makes
                // the peer reconcile-eligible so a failed first dial is retried. Two SWIM edges feed it:
                // `JoinAnnounced` (a directly-received ANNOUNCE) and `MemberDiscovered` (a peer learned via
                // SWIM membership gossip/probe — covers late joiners that missed the ANNOUNCE window). Both
                // converge on the idempotent addNode, so the overlap is harmless.
                case SwimObservation.JoinAnnounced j -> delegateRouter.route(new NetworkMessage.DiscoveredNodes(config.self(),
                                                                                                                List.of(j.nodeInfo())));
                case SwimObservation.MemberDiscovered m -> delegateRouter.route(new NetworkMessage.DiscoveredNodes(config.self(),
                                                                                                                   List.of(m.nodeInfo())));
                case SwimObservation.FaultyObserved f -> clusterNetworkRef.disconnect(new NetworkServiceMessage.DisconnectNode(f.peer()));
                case SwimObservation.DepartedObserved d -> clusterNetworkRef.departurePermanent(d.peer());
                default -> {}
            }
        });
        clusterNetworkRef.setSwimHealthGate(nodeId -> {
            var h = swimHealthDetector.healthOf(nodeId);

            return h != SwimHealth.FAULTY && h != SwimHealth.UNKNOWN;
        });
        // Fix A (split-brain root): raw-SWIM proof-of-life gate for the INBOUND tombstone-readmit
        // path. A peer driven to terminal FSM-DEAD by a false-FAULTY storm — the LOWEST-id node,
        // which nobody dials by the single-dialer rule, so the dial-path readmit can never run for
        // it — re-dials forever and is rejected in ~6ms per attempt (the #185 livelock) because
        // the FSM-membership gate (swimMembershipAllows/coreNodes()) is poisoned by the terminal
        // DEAD verdict. This gate is the NON-FSM signal: it reads raw SwimProtocol health, which a
        // false-DEAD survivor restores to HEALTHY/SUSPECTED once its link is back (SWIM incarnation
        // refutation), while a DELIBERATELY departed/decommissioned node — whose DepartedObserved
        // cleared SWIM aliveness — reads FAULTY/UNKNOWN and stays tombstoned (anti-resurrection
        // preserved). The completed authenticated Hello is the other half of the proof-of-life
        // (a genuinely partitioned / halted node cannot complete one); the transport requires BOTH.
        clusterNetworkRef.setSwimLivenessGate(nodeId -> {
            var h = swimHealthDetector.healthOf(nodeId);

            return h == SwimHealth.HEALTHY || h == SwimHealth.SUSPECTED;
        });
        // Item 2 (activation): the per-node MembershipFsm is now the connection AUTHORITY. The transport
        // reconciler reconciles live connections against this FSM desired core-member set (counted MEMBER+
        // SUSPECT, non-worker, address-known) instead of static topology, dropping the legacy SWIM
        // membership/health dial gates — the set already encodes them. `setDesiredConnections` is a
        // QuicClusterNetwork-only capability (not on the ClusterNetwork interface), so it is wired through
        // the same `instanceof QuicClusterNetwork` guard used elsewhere for QUIC-specific wiring; with
        // this wired the `setSwimHealthGate` call above is LEGACY-INERT (it now serves only the unwired
        // reconciler path, which the desired-set reconciler bypasses).
        // Item 3 (activation): co-confirmed DEAD (swimFaulty ∧ livenessGone) now drops the dead peer's
        // QUIC link promptly via departurePermanent, instead of lingering ~14s until SWIM emits
        // DepartedObserved. ADDITIVE — the graceful paths (NodeDecommissioned, DepartedObserved above)
        // already fire departurePermanent; this adds the co-confirmed-death edge. departurePermanent is
        // idempotent on an already-removed peer, so the overlap is harmless.
        // Wave 4 (ratified D7): the 2026-06-09 ReevaluateMembership re-poke is DELETED — the
        // confirmed-departure edge drops the QUIC link. The membership delta itself
        // (NodeRemoved emission + observer prune) rides the FSM's REMOVED delta edge through
        // the MembershipDeltaProjector wired above, structurally decoupled from
        // evaluateQuorumState (spec §3.1).
        // Wave 7 (parallel-view collapse): the FSM no longer evicts the PresenceSampler on death,
        // so the prompt heal/quorum nudge that eviction's onReconcileNeeded used to fire now rides
        // this same death edge — behaviour parity, sampler out of the loop. Without it the nudge
        // would wait for the sampler's natural ~nttDepartureTimeout down-hysteresis crossing.
        Consumer<NodeId> dropDeadPeerLink = clusterNetworkRef::departurePermanent;

        membershipFsm.onConfirmedDeparture(departed -> {
            onMembershipDeath(departed,
                              dropDeadPeerLink,
                              quorumLossDetectorRef,
                              membershipFsmRef,
                              leaderReconcilerRef,
                              config.self());
            // #210: emit the user-facing NODE_FAILED from this ungated DEAD edge — the SAME confirmed-
            // death signal that drives auto-heal above — instead of the quorum-gated
            // MembershipDecision.NodeRemoved, which the MembershipDeltaProjector drops during the
            // post-kill re-election window so the event never reached /api/events on cloud. Leader-gated
            // inside the aggregator (fires on every node's FSM; only the leader publishes).
            eventAggregator.onConfirmedDeparture(departed);
        });
        // Join-grace leak fix: a CTM-provisioned replacement that boots but NEVER reaches
        // SWIM-healthy within the M10 join-grace window is reaped OBSERVED→DEAD by the FSM, but
        // nothing terminated its container/JVM — it ran on as a non-member zombie (Docker count
        // drift / paid cloud orphan). Wire the dedicated never-healthy-reap edge to the leader-gated
        // LeaderReconciler.onJoinGraceReap, which drains the reaped id through ctm.drainNode(..,
        // JOIN_GRACE_REAP) so the existing grace-terminate backstop reaps the container/instance.
        // The leader observes membership too, so its own FSM reap fires this; non-leader reaps no-op
        // via the reconciler's isLeader gate (same idiom as the other leader-only actuations).
        // ADDITIVE — onConfirmedDeparture above still fires for ALL DEAD paths.
        membershipFsm.onJoinGraceReap(leaderReconciler::onJoinGraceReap);
        // seed-500 part 2: prune the DHT ring at the DEPARTING edge. A scale-down drain reaches the
        // FSM as DrainRequested → DEPARTING ~1s after the leader ping; without this the drained
        // node lingers in the consistent-hash ring until the SWIM-driven NodeRemoved DEAD-edge
        // (seconds later), so a key whose ring replicas include the drainer fast-fails (part 1's
        // liveness filter drops it → live targets < quorum) and exhausts ArtifactStore's ~850ms
        // retry budget against the still-stale ring → HTTP 500. Pruning at DEPARTING re-replicates
        // that key's ownership to LIVE owners inside the retry window so the retried write reaches
        // quorum. Reuses the SAME ring-prune + rebalance as dhtTopologyListener::onNodeRemoved
        // (the DEAD edge) and is idempotent with it (ConsistentHashRing.removeNode no-ops an
        // absent node). SAFE vs the reverted d3e54717e storm: DEPARTING is reached ONLY by the
        // deliberate-departure transitions (DrainRequested / graceful SwimDeparted /
        // DownHysteresisMet) — the transient PeerDisconnected/LivenessGone flaps stop at SUSPECT
        // and never enter DEPARTING — so this fires once per deliberate drain, never per QUIC flap.
        membershipFsm.onEnteredDeparting(dhtTopologyListener::onNodeDeparting);
        // seed-500 part 2 (symmetry): re-add to the DHT ring on the DEPARTING→MEMBER recovery edge.
        // A drainer that refutes its drain via a strictly-newer incarnation (recoverFromDepartingIfNewer)
        // recovers DEPARTING→MEMBER, but because it was already JOINED the FSM suppresses the Wave-4
        // JOINED delta → no MembershipDecision.NodeJoined → dhtTopologyListener::onNodeJoined never
        // re-adds it → it stays pruned (the onNodeDeparting prune above) → under-replication. Wire the
        // recovery edge to the symmetric ring RE-ADD, idempotent with onNodeJoined (ConsistentHashRing.addNode
        // no-ops a node already present), so a concurrent NodeJoined is harmless.
        membershipFsm.onDepartingRecovery(dhtTopologyListener::onNodeRecovered);
        clusterNetworkRef.setDesiredConnections(() -> desiredDialTargets(membershipFsm));
        // Wave-1 Enrichment A: PEER-layer transition journal feed — every PeerState phase
        // mutation (plus the §6.1 dialer expected-vs-actual Hello diagnostic) lands in the
        // per-node journal. Diagnostic-only; the listener default is a no-op until wired here.
        clusterNetworkRef.setPeerTransitionListener(record -> appendPeerTransition(transitionJournal, record));
        // Wave-1 §6.4 (detect-only, ratified D9): boot-time future-history check — RabiaEngine
        // WARNs and notifies when this node's persisted Rabia phase exceeds the cluster-reported
        // sync phase (mixed-wipe / down -v hazard); the journal keeps the structured record.
        clusterNode.onBootFutureHistory((persisted, cluster) -> recordBootFutureHistory(transitionJournal,
                                                                                        config.self(),
                                                                                        persisted,
                                                                                        cluster));
        // Wave-1 item 3 diagnostic, Wave-4 successor: periodic DEBUG dump comparing the
        // projector's announced decision-stream baseline (the replacement of the deleted
        // TopologyObserver previousCoreMembers diff baseline) vs the FSM core-member set vs
        // the presence-sampler view. Diag-only cadence; log-level-gated (DEBUG), no
        // control-flow effect.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> logMembershipBaselineTrace(membershipDeltaProjector,
                                                                                                       membershipFsm,
                                                                                                       presenceSampler),
                                                                      MEMBERSHIP_BASELINE_TRACE_INTERVAL));
        var topologyForSwim = clusterNode.topologyManager();
        // P1 fix: prefer the transport-supplied `NodeInfo` (QUIC/Netty Hello handshake)
        // over the static-topology lookup. CTM-replaced or topology-forgotten peers are
        // absent from `topologyForSwim`, so the legacy NodeId-only path produced a SWIM
        // `PeerConnected(id, none())` that the FSM's `resolveSwimAddress` could not
        // resolve — leaving the peer permanently UNKNOWN and rejected by the gate.
        // Wave 2 role-reach (cluster-topology-overhaul): the QUIC transport now supplies the
        // Hello NodeInfo for ALL peers (known + unknown), so feed it to the membership FSM's
        // descriptor FIRST — every node a worker dials (workers dial all cores, always
        // including the leader) learns the worker's self-asserted role/source on the first
        // handshake, independent of SWIM seed lists or label-less gossip MembershipUpdate.
        // The descriptor upsert is blank-downgrade-guarded, so a label-less Hello never
        // erases a known role. Runs on the router dispatch thread like the SWIM feed below;
        // `onMemberDescriptor` is thread-safe (concurrent map + per-member monitor).
        allEntries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                                 connection -> connection.nodeInfo()
                                                                         .onPresent(membershipFsm::onMemberDescriptor)
                                                                         .orElse(() -> topologyForSwim.get(connection.nodeId()))
                                                                         .onPresent(swimHealthDetector::onNodeConnected)
                                                                         .onEmpty(() -> swimHealthDetector.onNodeConnected(connection.nodeId()))));
        Supplier<BootstrapModule.ClusterConfigBaseline> configBaselineSupplier = () -> clusterConfigBaseline(config.topology());
        // Membership-FSM unification (Wave D, consumer #4): the cluster-quiescence gate's health
        // source is the authoritative MembershipFsm (healthHints()). The SwimHintsRegistry tap stays
        // wired (peer-health observability) but no longer feeds any consumer; the FSM is the single
        // membership-health authority.
        var swimHints = SwimHintsRegistry.swimHintsRegistry(java.time.Duration.ofMillis(config.autoHeal()
                                                                                              .swimHintsTtl()
                                                                                              .millis()),
                                                            () -> {});

        peerObservationStore.subscribeHealth(swimHints::onPeerHealth);
        // Wave 2 call-site audit: BootstrapModule's `coreMembersSupplier` seeds the DHT "core"
        // partition — core-scoped by NAME and intent, so it reads coreCountedMembers() (the
        // role-blind countedMembers() previously wired here could seed a worker as a DHT core
        // partition member).
        var bootstrapModule = BootstrapModule.bootstrapModule(isLeaderSupplier,
                                                              rabiaTermSupplier,
                                                              () -> isLeaderSupplier.getAsBoolean()
                                                                    ? Option.some(leaderTerm.get())
                                                                    : Option.<Long> none(),
                                                              hlcClock,
                                                              membershipFsm::coreCountedMembers,
                                                              kvStore::snapshot,
                                                              config::self,
                                                              configBaselineSupplier,
                                                              clusterNode);

        attachQuicConnectivityReporter(clusterNode.network(),
                                       isLeaderSupplier,
                                       peerObservationStore,
                                       leaderEpochSupplier,
                                       nttConnectTap,
                                       nttDisconnectTap);
        attachQuicPeerStateListener(clusterNode.network(), swimHealthDetector);
        attachBroadcastMembershipView(clusterNode.network(), membershipFsm);
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> onLeaderChangeForPublisher(change,
                                                                                      leaderTerm,
                                                                                      bootstrapModule)));
        // RC1 Step 2: snapshot-then-tail wiring. BootstrapModule consumes the current KV snapshot
        // via its `kvSnapshotSupplier` at the time of `projectFromCommittedAtoms`. The route attached
        // below provides the "tail" — every MembershipDecision variant routed by the
        // MembershipDeltaProjector (single canonical emitter, Wave 4) signals bootstrap retry.
        // Membership derives from the authoritative MembershipFsm delta edge; there is no
        // node-state KV listener.
        wireMembershipDecisionTail(allEntries, bootstrapModule::onMembershipDecision);
        var healthKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                 .onPut(AetherKey.SpokesmanKey.class,
                                                        _ -> bootstrapModule.retryIfNeeded())
                                                 .onPut(AetherKey.ClusterConfigKey.class,
                                                        (KVStoreNotification.ValuePut<AetherKey.ClusterConfigKey, AetherValue.ClusterConfigValue> put) -> onClusterConfigPut(put,
                                                                                                                                                                             clusterTopologyManager,
                                                                                                                                                                             leaderReconciler))
                                                 .build();

        allEntries.addAll(healthKvRouter.asRouteEntries());
        var spokesmanPingLoop = SpokesmanPingLoop.spokesmanPingLoop(config.self(),
                                                                    clusterNode.network(),
                                                                    config.timeouts().cluster().pingInterval(),
                                                                    rabiaTermSupplier,
                                                                    metricsCollector::allMetrics,
                                                                    communityId -> lookupGovernor(kvStore, communityId),
                                                                    SpokesmanPingLoop.SpokesmanStatusWriter.fromCluster(clusterNode));

        spokesmanPingLoop.start();
        metricsCollector.setCommunityReportSupplier(spokesmanPingLoop::currentReports);
        var spokesmanKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                    .onPut(AetherKey.SpokesmanKey.class,
                                                           spokesmanPingLoop::onSpokesmanPut)
                                                    .onRemove(AetherKey.SpokesmanKey.class,
                                                              spokesmanPingLoop::onSpokesmanRemove)
                                                    .build();

        allEntries.addAll(spokesmanKvRouter.asRouteEntries());
        metricsCollector.addPongListener(spokesmanPingLoop::onClusterSyncPong);
        var streamMaxMemoryBytes = resolveStreamMaxMemoryBytes();
        // A6: activate replication. The replica registry (populated by the A2 ReplicaSetController from
        // HRW placement) and a network-backed ReplicationTransport are constructed here — ahead of the
        // StreamPartitionManager — so SPM can be given a REAL DefaultReplicationManager instead of
        // ReplicationManager.NONE. On every publishLocal the owner now replicates the event to its
        // registered replica set; the receive/apply side (streamReplicationReceiveHandler, wired below)
        // lands replicated events offset-preserving WITHOUT re-replicating (appendRecovered).
        var streamReplicaRegistry = ReplicaRegistry.replicaRegistry(config.streaming().caughtUpMaxLagOffsets());
        org.pragmatica.aether.stream.replication.ReplicationTransport streamReplicationTransport = clusterNode.network()::send;
        // #261: the live-ack path promotes a replica to CAUGHT_UP only when its confirmed offset
        // reaches back to the owner's earliest retained offset. The partition manager is constructed
        // just below (it needs this manager), so the earliest-retained seam reads through a holder set
        // immediately after.
        var streamPartitionManagerRef = new AtomicReference<StreamPartitionManager>();
        var streamReplicationManager = ReplicationManager.replicationManager(config.self(),
                                                                             streamReplicaRegistry,
                                                                             streamReplicationTransport,
                                                                             (streamName, partition) -> Option.option(streamPartitionManagerRef.get())
                                                                                                              .map(spm -> spm.earliestRetainedOffset(streamName,
                                                                                                                                                     partition))
                                                                                                              .or(-1L));
        var streamSegmentIndex = new SegmentIndex();
        // A3b restore-at-boot: the streams MetadataStore was already restored from its latest disk
        // snapshot when `streamStorageSetup` was assembled (StorageFactory.restoreAndSignalReady), so
        // the durable `streams/` refs are present here. Rebuild the in-memory offset→segment index from
        // them so sealed segments are readable immediately after a same-node restart, before any new
        // append re-populates the index.
        streamSegmentIndex.rebuildFromRefs(streamStorageSetup.metadataStore());
        var streamStorage = streamStorageSetup.instance();
        // A4/A5: per-node durable cursor store + tiered reader over the same disk-backed stream storage;
        // registered as SPI extensions below so StreamAccessFactory persists consumer offsets and serves
        // post-restart reads from sealed cold segments.
        var streamCursorStore = CursorStore.cursorStore(streamStorage);
        var streamTieredReader = TieredStreamReader.tieredStreamReader(streamSegmentIndex, streamStorage);
        // #336 epoch-adoption: the committed owner-epoch source is SHARED between the live-append stamp
        // (StreamPartitionManager, below) and the backfill/recovery seam (streamPartitionRecovery, below), so
        // a recovered/backfilled event carries the SAME committed fencing token a live append would. Both
        // read the identical committed StreamPartitionOwnershipValue.ownerEpoch the fence high-water derives
        // from — otherwise the recovery seam's Epoch.ZERO (0:0) is rejected by an advanced high-water (1:N).
        var streamOwnerEpochSource = KvStreamOwnerEpochSource.kvStreamOwnerEpochSource(kvStore);
        var streamPartitionManager = StreamPartitionManager.streamPartitionManager(streamMaxMemoryBytes,
                                                                                   SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(streamStorage,
                                                                                                                                                     streamSegmentIndex)),
                                                                                   streamReplicationManager,
                                                                                   clusterNode,
                                                                                   ownershipEpochHighWater,
                                                                                   streamOwnerEpochSource,
                                                                                   resolveStreamWalDir(config),
                                                                                   streamSegmentIndex::lastSealedOffset);

        streamPartitionManagerRef.set(streamPartitionManager);
        // `[streaming] reshuffle_concurrency` — set BEFORE any materialization, since it replaces the permit
        // pool wholesale. Until 2026-08-16 this bound was a compile-time constant while the paced-materialize
        // error message named it as a config key, so an operator whose backfills were starving had nothing to
        // turn. ConfigValidator rejects < 1 up front.
        streamPartitionManager.reshuffleConcurrency(config.streaming().reshuffleConcurrency());
        // stream-offheap-budget-spec §4.5c / reconciliation #14: route off-heap budget exhaustion
        // (create-floor + growth) OUT of aether-stream via the injected Consumer<Exhaustion> sink and
        // INTO the cluster-event aggregator, which stamps this node's id and emits a
        // StreamMemoryExceeded event through its un-gated emitLocal path (per-node fact, like
        // SelfDrainInitiated). The aggregator owns the per-(stream,phase) 60s throttle.
        streamPartitionManager.exhaustionSink(eventAggregator::onStreamMemoryExceeded);
        // #336: the two StreamConfigKey put-handlers run in registration order — hydrate the committed
        // config into replicaCatalog() FIRST (onStreamConfigPut), then place its replica set
        // (reconcileReplicaSetOnConfigPut). clusterEventsControllerRef is the late-bound holder for the
        // stream ReplicaSetController (set below, after the controller is built).
        var streamConfigKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              streamPartitionManager::onStreamConfigPut)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              _ -> reconcileReplicaSetOnConfigPut(clusterEventsControllerRef))
                                                       .onRemove(AetherKey.StreamConfigKey.class,
                                                                 streamPartitionManager::onStreamConfigRemove)
                                                       .build();

        allEntries.addAll(streamConfigKvRouter.asRouteEntries());
        // B5b — provision system:cluster-events:1.0.0 as a REAL partition-managed stream.
        //
        // The events stream is now created in the StreamPartitionManager (single partition) via
        // SystemStreamFactories, exactly like app/system streams: createStream publishes the
        // StreamConfigKey into cluster KV, so the stream appears in replicaCatalog() → the
        // ReplicaSetController computes its HRW placement → the replica registry is populated →
        // replication (RF = systemReplicationFactor(N)) and PartitionedStreamAccess read-forwarding
        // both go live. The in-heap ClusterEventStreamBuffer/ClusterEventStreamWiring are gone.
        //
        // Serializer = the node SliceCodec (Serializer+Deserializer), which includes the generated
        // ApiCodecsNode.CODECS (B5a) → ClusterEvent encodes/decodes over the byte[] transport. This is
        // the same codec app streams are wired with.
        //
        // Retention is production-grade and config-overridable (item 4 / OOM guard): bounded on count,
        // bytes (off-heap hard cap), and age, mode ANY. The publisher/consumer refs the aggregator was
        // constructed with are bound here; until then emits fall back to log-only (bootstrap window).
        var clusterEventsRetention = org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy(resolveClusterEventsMaxCount(),
                                                                                                 resolveClusterEventsMaxBytes(),
                                                                                                 resolveClusterEventsMaxAgeMs(),
                                                                                                 org.pragmatica.aether.slice.RetentionMode.ANY);
        var clusterEventsStreamConfig = org.pragmatica.aether.slice.StreamConfig.streamConfig(clusterEventsStreamName,
                                                                                              1,
                                                                                              clusterEventsRetention,
                                                                                              "earliest",
                                                                                              resolveClusterEventsMaxEventSizeBytes(),
                                                                                              org.pragmatica.aether.slice.ConsistencyMode.EVENTUAL,
                                                                                              1);

        org.pragmatica.aether.stream.SystemStreamFactories.<ClusterEvent> systemStreamPublisher(org.pragmatica.aether.slice.stream.SystemStreams.CLUSTER_EVENTS,
                                                                                                streamPartitionManager,
                                                                                                serializer,
                                                                                                clusterEventsStreamConfig)
                                                          .onSuccess(clusterEventsPublisherRef::set)
                                                          .onFailure(cause -> LOG.warn("cluster-events stream publisher wiring failed: {} — events fall back to log-only",
                                                                                       cause.message()));
        // Fix #3: the forward-capable cluster-events CONSUMER is wired further below, after
        // streamForwardClient + streamReadForwardMetrics are constructed, so a non-replica node
        // read-forwards observability reads to a caught-up replica instead of reading its own empty
        // local partition. The aggregator reads the consumer lazily via clusterEventsConsumerRef::get,
        // so deferring the wiring does not affect construction order.
        // Stage 5: stream-namespaces registry + service.
        //
        // Stage 4 deferred the StreamRegistry registration to here, where the namespace-listing
        // HTTP surface (StreamApiRoutes / StreamNamespacesRoutes) consumes it. The registry is
        // backed by the cluster KV-store (KvBackedStreamRegistry) so it shares the same state the
        // Stage-3 refcount-on-ACTIVE path writes to — one source of truth for stream lifecycle.
        // bootstrap() idempotently registers the system:* streams; failure is tolerated (log-only)
        // so a node still boots when consensus isn't yet quorate at this point.
        var streamRegistry = org.pragmatica.aether.slice.stream.KvBackedStreamRegistry.kvBackedStreamRegistry(clusterNode,
                                                                                                              kvStore);
        var streamNamespacesService = new StreamNamespacesService(streamRegistry,
                                                                  new org.pragmatica.aether.slice.stream.SystemStreamBootstrap(streamRegistry));
        // System-stream registration (system:cluster-events etc.) is driven LEVEL-TRIGGERED by the
        // self-healing SystemStreamRegistrar (Fix #1), not here. This point runs on [main] during
        // construction, seconds before consensus activates, so an eager bootstrap() always failed
        // "Node is inactive". The registrar (constructed + LeaderChange-wired below) retries on bounded
        // backoff until both legs commit, so a commit timeout in the post-leader-gain window no longer
        // leaves the stream unregistered (which made /api/events|alerts|traces return 200 []).
        // deleted alongside the audit publisher and RecentCommandsBuffer.
        var streamWatermarkTracker = WatermarkTracker.watermarkTracker();
        var streamSegmentReader = SegmentReader.segmentReader(streamStorage, streamSegmentIndex);
        var streamRetentionEnforcer = RetentionEnforcer.retentionEnforcer(streamStorage,
                                                                          streamSegmentIndex,
                                                                          DEFAULT_STREAM_RETENTION_MS,
                                                                          (stream, partition) -> entityRetentionFloor(kvStore,
                                                                                                                      stream,
                                                                                                                      partition));
        // A6: streamReplicaRegistry is created earlier (above StreamPartitionManager) so it can be
        // shared with the now-active DefaultReplicationManager. The same registry instance is the one
        // the A2 ReplicaSetController populates from HRW placement.
        // A4: lands backfilled/recovered events into the local ring offset-preserving WITHOUT
        // re-replicating (the receiver is not an owner), replacing the StreamPartitionRecovery NOOP
        // for both governor-failover recovery and the A4 backfill path below.
        // #336 epoch adoption (NOT invention): stamp the recovery/backfill append with the SAME committed
        // owner epoch a live publish stamps (streamOwnerEpochSource reads the committed
        // StreamPartitionOwnershipValue.ownerEpoch — the identical source the fence high-water is seeded
        // from), so a backfilled event is equal-or-newer than the partition high-water and passes the fence
        // instead of being rejected at the Epoch.ZERO floor (0:0 < 1:N). Cold-start/unowned arcs read
        // Epoch.ZERO == the fence's ZERO default (equal → passes), so fresh-stream recovery is not regressed.
        // The fence itself is untouched — this is a truthful stamp, not a wholesale exemption.
        StreamPartitionRecovery streamPartitionRecovery = (s, p, payload, ts) -> streamPartitionManager.appendRecovered(s,
                                                                                                                        p,
                                                                                                                        payload,
                                                                                                                        ts,
                                                                                                                        streamOwnerEpochSource.currentOwnerEpoch(s,
                                                                                                                                                                 p));
        var streamFailoverHandler = GovernorFailoverHandler.governorFailoverHandler(streamReplicaRegistry,
                                                                                    streamPartitionRecovery);
        // A4: production catch-up wiring. The forward transport/client are constructed here (ahead of
        // the A6 read-forwarding wiring further below, which reuses the same instances) so the
        // ReplicaSetController can be given a real onBecameReplica backfill callback instead of the
        // A2 no-op seam.
        //   - catch-up transport: pages StreamForwardClient.readRemote until the source is drained.
        //   - PartitionBackfill: picks the best caught-up peer, applies events, flips self CAUGHT_UP.
        var streamForwardTransport = createStreamForwardTransport(clusterNode.network());
        var streamingConfig = config.streaming();
        var streamReadForwardMetrics = StreamReadForwardMetrics.inMemory();
        var streamForwardClient = StreamForwardClient.streamForwardClient(config.self(),
                                                                          streamForwardTransport,
                                                                          streamingConfig.publishForwardTimeout(),
                                                                          streamingConfig.readForwardTimeout(),
                                                                          streamReadForwardMetrics);
        // cluster-events CONSUMER: forward-capable (ANY_REPLICA). `/api/events` is now an ANY-target
        // route (#267) — served from any core node, not leader-bound — so the endpoint stays available
        // during leader churn/election (the prior LEADER binding 503'd exactly when operators most need
        // events). A node that is NOT a cluster-events replica read-forwards to a CAUGHT_UP replica via
        // the StreamForwardClient instead of reading its own empty partition (returning 200 []); a node
        // that IS a caught-up replica reads locally; during the bootstrap window (no caught-up replica
        // visible yet) it fails soft to the local partition. NODE_FAILED/NODE_LEFT are still emitted
        // leader-gated to the leader's local partition-0 buffer, then replicated to the replica set.
        //
        // HISTORY (#94/B5): forward-capable reads were previously reverted to LOCAL because a fresh
        // replica did not actually hold the partition's history (no real backfill) and offsets were
        // unverified, so a forwarded fetch derived from leader-local metadata mismatched the replica and
        // returned 200 []. Wave-1 #260 (receiver verifies fromOffset, rejects gaps) + #261 (backfill
        // fires on becoming a replica; CAUGHT_UP only after history coverage) fixed that root: a
        // CAUGHT_UP replica now genuinely holds [earliest..N], so a forwarded fetch from the retained
        // tail returns the full window. Staleness: a forwarded read reflects the replica's CAUGHT_UP
        // watermark, which may trail the owner by the in-flight replication window (sub-second under
        // steady load) — acceptable for observability and far preferable to a 503.
        org.pragmatica.aether.stream.SystemStreamFactories.<ClusterEvent> systemStreamConsumer(org.pragmatica.aether.slice.stream.SystemStreams.CLUSTER_EVENTS,
                                                                                               streamPartitionManager,
                                                                                               serializer,
                                                                                               deserializer,
                                                                                               clusterEventsStreamConfig,
                                                                                               streamReplicaRegistry,
                                                                                               Option.some(streamForwardClient),
                                                                                               config.self(),
                                                                                               Option.some(() -> Option.option(clusterEventsControllerRef.get()).flatMap(clusterEventsController -> clusterEventsController.ownerFor(clusterEventsStreamName,
                                                                                                                                                                                                                                     0))),
                                                                                               streamReadForwardMetrics)
                                                          .onSuccess(clusterEventsConsumerRef::set)
                                                          .onFailure(cause -> LOG.warn("cluster-events stream consumer wiring failed: {} — events reads return empty",
                                                                                       cause.message()));
        var streamCatchupTransport = ForwardCatchupTransport.forwardCatchupTransport(streamForwardClient,
                                                                                     STREAM_CATCHUP_BATCH_SIZE);
        // Cold-start deadlock-break: a watermark+reachability probe over the same forward-read transport.
        // A REACHABLE peer answers with the highest local offset it holds (its watermark; -1 when empty);
        // an UNREACHABLE peer's readRemote fails (timeout) and the probe propagates that failure, which
        // the orchestrator treats as "do not self-promote past a node that might hold newer state".
        ReplicaWatermarkProbe streamWatermarkProbe = (target, streamName, partition) -> probePeerWatermark(streamForwardClient,
                                                                                                           target,
                                                                                                           streamName,
                                                                                                           partition);
        // Self's local watermark: the local partition's HIGHEST held offset (ring head; -1 when the
        // partition is absent/empty). Read locally — this is the SAME notion the peer probe computes
        // remotely (its highest offset), so the highest-watermark contest is symmetric, AND it is the
        // base for the #333 owner-source backfill `fromOffset` (head + 1 = next-expected, contiguous).
        // NOTE: this is `headOffset` (highest), NOT `tailOffset` (earliest retained) — using the tail
        // understated self's position, breaking both the cold-start contest symmetry and the owner-source
        // contiguous fromOffset.
        SelfWatermark streamSelfWatermark = (streamName, partition) -> streamPartitionManager.partitionInfo(streamName,
                                                                                                            partition)
                                                                                             .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                                                             .or(-1L);
        // #491 F4: the committed StreamPartitionOwnershipValue.owner source, hoisted here so the backfill
        // orchestrator can gate HRW self-election on it (a diverged/empty-ring node must not self-promote
        // while a DIFFERENT node is the committed owner). Reused by the #265 owner-release guard below.
        //
        // #568: the committed record is only authoritative while its holder is ALIVE. Committed stream
        // ownership has no relinquish path other than the owner itself releasing it, so when the committed
        // owner dies the record outlives it forever. That wedged a partition permanently: a fresh
        // CTM replacement HRW-ranked itself owner of a stream with existing history, but `isSelfOwner`
        // reads COMMITTED ownership and answered false, so `promoteOwner` was never taken; HRW-owner==self
        // then filtered to none() and the flow fell to `backfillViaRegistryOrColdStart` ->
        // `handleNoSource` -> `waitThenPromote`, which suppressed self-promotion precisely BECAUSE a
        // committed owner existed (correctly preserving the #445 empty-owner distrust gate). Every step
        // correct; composed, no exit. Observed live: an owner stuck SYNCING@-1 for 13+ minutes, looping
        // every 20s, while four replicas sat CAUGHT_UP holding the data.
        //
        // Filtering the record by liveness closes the class rather than the instance — ANY partition whose
        // committed owner dies is otherwise exposed, not just system streams. `countedMembers()` is the
        // right set: ROLE-BLIND (a stream owner may be a worker) and MEMBER+SUSPECT, so a transient
        // SUSPECT still holds ownership and only a confirmed DEAD member releases it.
        //
        // The empty-set guard is load-bearing: before the FSM has any members (boot window) an unguarded
        // filter would reject EVERY committed owner and reintroduce the #491 F4 self-promote this gate
        // exists to prevent. Empty membership means "cannot judge liveness", not "nobody is alive".
        var rawStreamCommittedOwnerSource = KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore);
        CommittedStreamOwnerSource streamCommittedOwnerSource = (stream, partition) -> rawStreamCommittedOwnerSource.committedOwner(stream,
                                                                                                                                    partition)
                                                                                                                    .filter(committed -> committedOwnerStillAlive(membershipFsm,
                                                                                                                                                                  committed.owner()));
        var streamPartitionBackfill = PartitionBackfill.partitionBackfill(streamReplicaRegistry,
                                                                          streamPartitionRecovery,
                                                                          streamCatchupTransport,
                                                                          streamReplicationTransport,
                                                                          streamWatermarkProbe,
                                                                          streamSelfWatermark,
                                                                          config.self(),
                                                                          streamingConfig.backfillSourceWaitBound(),
                                                                          () -> streamPlacementMembers(clusterEventsControllerRef,
                                                                                                       clusterTopologyManager),
                                                                          streamCommittedOwnerSource);
        var streamBackfillExecutor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable,
                                                                                                "stream-partition-backfill"));
        // A2: per-node controller that reconciles the (previously never-populated) ReplicaRegistry
        // against the HRW-derived desired replica set on every membership change. Members + cluster
        // size come from the consensus topology observer; the stream catalog (name/partitions/
        // minSyncReplicas + partition-has-data) is adapted from the partition manager. The A4
        // catch-up seam now runs backfill off the reconcile thread on a dedicated executor.
        //
        // 1d-iii / #265: the leader-only StreamPartitionOwnershipWriter, fired by the controller's
        // onReconcilePassComplete batch driver seam. It mirrors the DHT BootstrapModule ownership writer:
        //   - isLeaderSupplier / rabiaTermSupplier / hlcClock are the same suppliers the DHT writer uses,
        //   - CommittedOwnership reads the committed StreamPartitionOwnershipValue exactly as
        //     KvStreamOwnerEpochSource does (getTyped on the StreamPartitionOwnershipKey),
        //   - HrwOwner late-binds to streamReplicaSetController::ownerFor through clusterEventsControllerRef
        //     (set to the same controller below, before the first reconcile fires the driver).
        // The writer self-limits: writeOwnershipChange returns none() on a follower (its isLeaderSupplier
        // gate short-circuits BEFORE any KV read) and none() for an unchanged owner, so the driver only
        // emits a consensus Put on the leader and only for genuinely-moved partitions. #265 increment 6:
        // the driver is now BATCH-shaped — the controller flushes ONE reconciled (stream, partition) list
        // per reconcile pass (onReconcilePassComplete) and driveStreamOwnership applies the emitted Puts as
        // a SINGLE consensus batch, so a mass reshuffle commits one batch per pass, not one apply per moved
        // partition. This keeps the reshuffle fan-out bound the promise #265 makes.
        var streamOwnershipWriter = StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(isLeaderSupplier,
                                                                                                  rabiaTermSupplier,
                                                                                                  hlcClock,
                                                                                                  (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                                                  partition),
                                                                                                                                          StreamPartitionOwnershipValue.class),
                                                                                                  (stream, partition) -> Option.option(clusterEventsControllerRef.get()).flatMap(ownershipController -> ownershipController.ownerFor(stream,
                                                                                                                                                                                                                                     partition)));
        var streamReplicaSetController = ReplicaSetController.replicaSetController(streamReplicaRegistry,
                                                                                   config.self(),
                                                                                   () -> List.copyOf(clusterTopologyManager.observer()
                                                                                                                           .coreNodes()),
                                                                                   clusterTopologyManager.observer()::clusterSize,
                                                                                   streamPartitionManager.replicaCatalog(),
                                                                                   (streamName, partition) -> streamBackfillExecutor.execute(() -> materializeThenBackfill(streamPartitionManager,
                                                                                                                                                                           streamPartitionBackfill,
                                                                                                                                                                           streamName,
                                                                                                                                                                           partition)),
                                                                                   (List<PartitionKey> reconciled) -> driveStreamOwnership(streamOwnershipWriter,
                                                                                                                                           clusterCommandApplier,
                                                                                                                                           reconciled));
        // B5b: bind the owner-gate ref so ClusterEventAggregator.emit can consult isOwner(...) for
        // (system:cluster-events:1.0.0, partition 0). isOwner is computed from the live HRW placement
        // against the current topology, independent of reconcile, so it is correct as soon as members
        // are visible (and true for a steady-state single-node cluster).
        clusterEventsControllerRef.set(streamReplicaSetController);
        // #265 increment 1/2: late-bind the placement-role supplier now that the controller exists. The
        // controller is constructed AFTER StreamPartitionManager (it consumes replicaCatalog()), so this
        // is the same construction-order inversion the streamPartitionManagerRef seam resolves above —
        // resolved here by a settable field rather than an AtomicReference. Increment 2 flipped the gate:
        // hydrate/create now materialize a partition ring IFF roleFor(stream, partition) ∈ {OWNER, REPLICA},
        // so a non-replica node holds the partition metadata-only (no ring, no off-heap bytes) — THE memory
        // win. The reconcile hook (materializeThenBackfill, bound above via onBecameReplica) and the
        // owner-append safety valve materialize a deferred ring once the role resolves. Forge/unit managers
        // keep the default always-OWNER supplier (materialize-everything, unchanged).
        streamPartitionManager.placementRoleSupplier(streamReplicaSetController::roleFor);
        // #265 increment 4: bind the aggregate partition-guard's cluster-size source to the SAME
        // topology-observer count the ReplicaSetController uses for HRW placement, so the create-time guard's
        // node count matches placement. The default `() -> 0` in Forge/unit/legacy managers disables the
        // aggregate guard (only the per-stream ceiling applies).
        streamPartitionManager.clusterSizeSupplier(clusterTopologyManager.observer()::clusterSize);
        // Write-forward race fix: bind the owner-side forwarded-publish config source to committed KV state
        // so a forwarded publish that races ahead of this owner's onStreamConfigPut can lazily materialize
        // from the committed StreamConfigKey (preserving RF); absent config yields the retryable
        // StreamConfigNotYetVisible the forwarder backs off on. Forge/unit managers keep the default (none).
        streamPartitionManager.committedConfigSource(name -> kvStore.getTyped(AetherKey.StreamConfigKey.streamConfigKey(name),
                                                                              AetherValue.StreamConfigValue.class)
                                                                    .map(AetherValue.StreamConfigValue::config));
        // #265 increment 5: reshuffle lifecycle. The release catch-up gate reads the live replica registry
        // (self-excluded CAUGHT_UP count + self caught-up flag); the owner rule reads the committed
        // StreamPartitionOwnershipValue (release only once ownership names a DIFFERENT node than self). Both
        // are bound through static helpers so the supplier lambdas stay single-expression. The committed
        // owner source is the one hoisted above for the #491 F4 backfill self-election gate.
        streamPartitionManager.replicaCatchupSource((stream, partition) -> streamCatchupView(streamReplicaRegistry,
                                                                                             config.self(),
                                                                                             stream,
                                                                                             partition));
        streamPartitionManager.ownerReleaseGuard((stream, partition) -> committedOwnerElsewhere(streamCommittedOwnerSource,
                                                                                                config.self(),
                                                                                                stream,
                                                                                                partition));
        // Reconcile on every membership decision (all variants via the tail helper) and on
        // ClusterStateNotification edges (PASSIVE suppresses; PASSIVE->ACTIVE re-reconciles).
        wireMembershipDecisionTail(allEntries, streamReplicaSetController::onMembershipDecision);
        allEntries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                                 streamReplicaSetController::onQuorumStateChange));
        // Initial reconcile once membership is available; serialized on the controller executor, so
        // this is a safe no-op until the topology observer reports core members.
        streamReplicaSetController.reconcile();
        // A4 periodic backfill re-drive: reconcilePartition fires backfill ONCE per reconcile edge, so a
        // cold-start replica that saw NO_SOURCE on its first attempt would never re-attempt and stay
        // SYNCING forever (the system:cluster-events GET /api/events -> 200 [] deadlock). Re-drive every
        // STREAM_BACKFILL_REDRIVE_INTERVAL onto the SAME backfill executor so the owner-immediate /
        // bounded-wait cold-start promotion is actually reached; CAUGHT_UP partitions are skipped.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> redriveIncompleteBackfills(streamPartitionBackfill,
                                                                                                       streamBackfillExecutor),
                                                                      STREAM_BACKFILL_REDRIVE_INTERVAL));
        // A3b snapshot driver: `SnapshotManager.maybeSnapshot()` has no other caller — without this tick
        // no metadata snapshot is ever taken and nothing survives a restart. Iterate ALL storage setups
        // (streams + artifacts/content), so every MetadataStore's refs are persisted; maybeSnapshot
        // self-gates on its mutation-count / interval trigger, so a 10s tick is cheap when not due.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> snapshotAllSetups(storageSetups),
                                                                      METADATA_SNAPSHOT_INTERVAL));
        // #250 storage maintenance driver: demote()/collectGarbage() have no other caller — without this
        // tick, tiered storage never shrinks in production regardless of the leader-pinned adapter's
        // activation state. Cadence is operator-configurable ([timeouts.storage_maintenance] interval,
        // default 5m); both operations self-gate internally, so this tick is cheap when inactive or idle.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(storageMaintenanceDriver::tick,
                                                                      config.timeouts().storageMaintenance().interval()));
        // W5 WAL disk-reclamation driver: truncate every partition's write-ahead log up to its DURABLE
        // last-sealed offset so the WAL does not grow unbounded. Records <= lastSealedOffset are already in
        // durable cold segments (served post-restart by the tiered reader), so dropping them from the WAL
        // loses nothing; the un-sealed tail stays in the WAL. truncate is threshold-lazy, so this tick is
        // cheap when nothing new has sealed. Driven off the durable sealed bound (not the void
        // eviction->seal listener) to avoid any truncated-before-durable window.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::truncateWalsToSealed,
                                                                      WAL_TRUNCATE_INTERVAL));
        // #265 increment 5 reshuffle-lifecycle driver: each tick frees reshuffle-concurrency slots for
        // finished (self CAUGHT_UP) / owner / role-lost partitions, evaluates release candidates (role-loss
        // debounce → catch-up + owner gate → release, freeing ring memory + budget while KEEPING the WAL on
        // disk), then drains the materialization queue (system first) so budget a release just freed admits a
        // queued partition in the same tick. 5s cadence → the 2-tick flap debounce is ≈10s; a steady-state
        // tick is a cheap sweep of materialized partitions over empty queues.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::reconcileReshuffle,
                                                                      STREAM_RESHUFFLE_RECONCILE_INTERVAL));
        // #345 item 1e-a: the committed-owner source, the linearizable no-op-round barrier, and the
        // shared owner-side serve pipeline. The barrier orders one no-op consensus round through the
        // cluster apply path and awaits this node's local apply of it (spec §8.1 `no-op-round`); the
        // mode is the parsed `[durable-entity] read-linearization` knob (only NO_OP_ROUND ships — a
        // `lease` value is rejected at config parse). The owner-serve pipeline is shared by the local
        // read path (StreamReadRouter) AND the forwarded read path (StreamForwardHandler) so a
        // forwarded LINEARIZABLE read is re-guarded at the owner instead of served unguarded.
        //
        // #535 HOISTED: this block used to sit BELOW the declarative-consumer wiring. It moved up as a
        // UNIT because the consumer runtime now reads through `streamReadRouter`. Only
        // `committedStreamOwnerSource` and `linearizableBarrier` were created after that point, and both
        // depend solely on state built far earlier (kvStore, clusterCommandApplier, streamingConfig), so
        // the move needs no late-binding seam. Keep the block together if it ever moves again —
        // linearizableOwnerServe and streamForwardHandler consume both of those locals.
        var committedStreamOwnerSource = KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore);
        Option<LinearizableBarrier> linearizableBarrier = streamingConfig.readLinearization() == ReadLinearizationMode.NO_OP_ROUND
                                                          ? Option.some(LinearizableBarrier.noOpRound(clusterCommandApplier,
                                                                                                      streamingConfig.readForwardTimeout()))
                                                          : Option.none();
        var linearizableOwnerServe = LinearizableOwnerServe.<OffHeapRingBuffer.RawEvent> linearizableOwnerServe(config.self(),
                                                                                                                Option.some(streamReplicaRegistry),
                                                                                                                Option.some(committedStreamOwnerSource),
                                                                                                                Option.some(ownershipEpochHighWater),
                                                                                                                linearizableBarrier,
                                                                                                                (stream, partition, fromOffset, maxEvents) -> streamPartitionManager.readLocal(stream,
                                                                                                                                                                                               partition,
                                                                                                                                                                                               fromOffset,
                                                                                                                                                                                               maxEvents)
                                                                                                                                                                                    .async());
        var streamForwardHandler = StreamForwardHandler.streamForwardHandler(config.self(),
                                                                             streamPartitionManager,
                                                                             streamForwardTransport,
                                                                             streamingConfig.maxReadResponseBytes(),
                                                                             streamReadForwardMetrics,
                                                                             Option.some(linearizableOwnerServe));
        var streamReadRouter = StreamReadRouter.streamReadRouter(streamPartitionManager,
                                                                 Option.some(streamReplicaRegistry),
                                                                 Option.some(streamForwardClient),
                                                                 config.self(),
                                                                 streamReplicaSetController::ownerFor,
                                                                 streamReadForwardMetrics,
                                                                 Option.some(committedStreamOwnerSource),
                                                                 Option.some(ownershipEpochHighWater),
                                                                 linearizableBarrier);
        // #488/#535: declarative `[streams.X]` consumer delivery. `NodeDeploymentState` writes a
        // StreamRegistrationKey per deployment; that key is NOT node-scoped, so it is a cluster-wide
        // DECLARATION, never an assignment. Which node consumes which partition is decided LOCALLY and
        // identically on every node: the partition's HRW owner when the slice is ACTIVE there, else the
        // HRW pick over the nodes where it IS active. Gating on ownership ALONE was the #488 model and
        // is exactly why a default deployment (slice on 3 of 5) delivered nothing — the owner usually
        // did not host the slice. Gating on "the ring is local" is not the alternative: REPLICA nodes
        // materialize rings too (shouldMaterialize admits OWNER and REPLICA), so that would deliver
        // every event once per replica.
        //
        // An assignee that is not the owner has no ring, so the runtime reads through the router with
        // GOVERNOR preference — local when materialized here, forwarded to the HRW owner on
        // PARTITION_NOT_LOCAL. NEAREST would be wrong: it also forwards on an EMPTY read, which for a
        // tail-polling consumer means a forward on nearly every poll.
        //
        // No role-change callback is available (onBecameReplica / onReconcilePassComplete are
        // single-consumer seams already bound above), hence the poll.
        var streamClusterCursorStore = ClusterCursorStore.clusterCursorStore(streamCursorStore,
                                                                             cursorKey -> kvStore.getTyped(cursorKey,
                                                                                                           AetherValue.StreamCursorCheckpointValue.class)
                                                                                                 .map(AetherValue.StreamCursorCheckpointValue::committedOffset),
                                                                             command -> clusterNode.apply(List.of(command))
                                                                                                   .mapToUnit());
        // #386 durable pub-sub: dead letters for `topic:*` streams are durable — re-enveloped
        // group-attributed and appended to the topic's `.dlq` stream through the same min-sync
        // barrier as the source (publisher memoized per DLQ stream, full owner-forward routing so a
        // dispatch node that does not own the DLQ partition forwards instead of stalling). All
        // other streams keep the in-memory default; its durability question is a separate ticket's
        // business (ruling on #386).
        var topicDlqPublishers = new ConcurrentHashMap<String, StreamPublisher<DlqEnvelope>>();
        var streamDeadLetterSink = new RoutingDeadLetterSink(new DlqStreamSink(nodeCodec,
                                                                               streamPartitionManager,
                                                                               dlqStream -> topicDlqPublishers.computeIfAbsent(dlqStream,
                                                                                                                               name -> DefaultStreamPublisher.streamPublisher(streamPartitionManager,
                                                                                                                                                                              nodeCodec,
                                                                                                                                                                              name,
                                                                                                                                                                              1,
                                                                                                                                                                              Option.none(),
                                                                                                                                                                              ConsistencyMode.EVENTUAL,
                                                                                                                                                                              Option.none(),
                                                                                                                                                                              streamPartitionManager.minSyncReplicasFor(name),
                                                                                                                                                                              Option.some(streamForwardClient),
                                                                                                                                                                              Option.some(() -> taskGroupOwnerResolver.apply(TaskGroup.STREAMING)
                                                                                                                                                                                                                      .option()),
                                                                                                                                                                              Option.some(partition -> streamReplicaSetController.ownerFor(name,
                                                                                                                                                                                                                                           partition)),
                                                                                                                                                                              Option.some(config.self())))),
                                                             DeadLetterHandler.deadLetterHandler());
        var streamConsumerRuntime = StreamConsumerRuntime.streamConsumerRuntime(streamPartitionManager,
                                                                                streamDeadLetterSink,
                                                                                streamClusterCursorStore,
                                                                                (stream, partition, fromOffset, maxEvents) -> streamReadRouter.read(stream,
                                                                                                                                                    partition,
                                                                                                                                                    fromOffset,
                                                                                                                                                    maxEvents,
                                                                                                                                                    ReadPreference.GOVERNOR));
        var streamConsumerOwnership = streamConsumerOwnership(streamPartitionManager, streamReplicaSetController);
        var streamConsumerManager = StreamConsumerManager.streamConsumerManager(streamConsumerRegistry,
                                                                                streamConsumerRuntime,
                                                                                sliceInvoker,
                                                                                invocationHandler,
                                                                                nodeCodec,
                                                                                streamConsumerOwnership,
                                                                                artifact -> sliceDeploymentStates(deploymentMap,
                                                                                                                  artifact),
                                                                                config.self(),
                                                                                TopicGroupDeclarationSource.topicGroupDeclarationSource(topicSubscriptionRegistry,
                                                                                                                                        streamName -> streamConsumerOwnership.partitionCount(streamName)
                                                                                                                                                                             .isPresent()));
        // #499: the handle is retained in `periodicTasks`, which stop() cancels wholesale. A declarative
        // consumer that outlived its node would deliver into a torn-down slice.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(streamConsumerManager::reconcile,
                                                                      STREAM_CONSUMER_RECONCILE_INTERVAL));
        var streamingCoordinator = StreamingCoordinator.streamingCoordinator(streamFailoverHandler,
                                                                             streamRetentionEnforcer,
                                                                             streamPartitionManager,
                                                                             streamWatermarkTracker,
                                                                             streamSegmentIndex,
                                                                             streamSegmentReader);
        // #231 Step 2: StreamingCoordinator (STREAMING) and the STORAGE adapter are leader-pinned
        // via toggle*OnLeaderChange routes appended below (after the collectRouteEntries leader-change
        // block, preserving the required activation order ... → StreamingCoordinator → StorageAdapter).
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> toggleStreamingOnLeaderChange(change, streamingCoordinator)));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> toggleStorageOnLeaderChange(change, delegatedStorageAdapter)));
        // System streams (system:cluster-events + the namespace catalog backing /api/events|alerts|
        // traces) are registered LEVEL-TRIGGERED by the self-healing SystemStreamRegistrar (Fix #1).
        // The prior fire-once-on-leader-gain handler logged "will retry on next leader change" but never
        // did — a commit timeout in the post-leader-gain window (consensus not yet quorate) left the
        // stream unregistered forever on a stable cluster. The registrar instead retries on bounded
        // backoff until both legs commit (or hit a terminal config error), latches each leg DONE, and
        // disarms on leadership loss. Both legs are idempotent + consensus-committed, so re-arming on
        // each leader-gain is safe and self-heals across re-elections.
        var systemStreamRegistrar = SystemStreamRegistrar.systemStreamRegistrar(() -> streamPartitionManager.createStream(clusterEventsStreamConfig),
                                                                                streamNamespacesService::bootstrap);

        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 systemStreamRegistrar::onLeaderChange));
        // #290: cluster-formation bootstrap admin API key. On first leadership, if no admin key is yet
        // present in the KV store, generate one cluster-wide and print the plaintext once. Self-healing
        // and idempotent (mirrors SystemStreamRegistrar); the leg uses the same clusterNode.apply
        // consensus path as the API-key routes and the same KV store the validator reads.
        var bootstrapAdminKeyRegistrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore,
                                                                                                                                         isLeaderSupplier::getAsBoolean,
                                                                                                                                         clusterCommandApplier));

        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 bootstrapAdminKeyRegistrar::onLeaderChange));
        // Publish-side mirror: same forward client + HRW owner resolver the read router uses, so a
        // management publish landing on a metadata-only node write-forwards to the owner (#265) instead
        // of failing PARTITION_NOT_LOCAL on a local append.
        var streamWriteRouter = StreamWriteRouter.streamWriteRouter(streamPartitionManager,
                                                                    Option.some(streamForwardClient),
                                                                    config.self(),
                                                                    streamReplicaSetController::ownerFor);
        // Entity owner-forwarding (#596). Same `network::send` seam the stream forward client uses, so a
        // command reaches the committed owner over the ordinary FORWARD lane rather than a new transport.
        var entityForwardService = EntityForwardService.entityForwardService(config.self(),
                                                                             clusterNode.network()::sendOutcome,
                                                                             ENTITY_FORWARD_TIMEOUT);

        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityUpdateForward.class,
                                                 entityForwardService::onEntityUpdateForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityCreateForward.class,
                                                 entityForwardService::onEntityCreateForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityDeleteForward.class,
                                                 entityForwardService::onEntityDeleteForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityUpdateForwardResponse.class,
                                                 entityForwardService::onEntityUpdateForwardResponse));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityGetForward.class,
                                                 entityForwardService::onEntityGetForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityGetForwardResponse.class,
                                                 entityForwardService::onEntityGetForwardResponse));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityScheduleTimerForward.class,
                                                 entityForwardService::onEntityScheduleTimerForward));
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityScheduleTimerForwardResponse.class,
                                                 entityForwardService::onEntityScheduleTimerForwardResponse));
        // Cancel answers with EntityUpdateForwardResponse, already routed above — no response route of its own.
        allEntries.add(MessageRouter.Entry.route(EntityForwardMessage.EntityCancelTimerForward.class,
                                                 entityForwardService::onEntityCancelTimerForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.PublishForward.class,
                                                 streamForwardHandler::onPublishForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.PublishForwardResponse.class,
                                                 streamForwardClient::onPublishForwardResponse));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForward.class,
                                                 streamForwardHandler::onReadForward));
        allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForwardResponse.class,
                                                 streamForwardClient::onReadForwardResponse));
        // A6: replica-side receive/apply. ReplicateEvents from an owner are landed into the local
        // partition ring offset-preserving via appendRecovered (NON-replicating — a replicated event is
        // never re-emitted, so there is no replicate→apply→replicate loop), then the highest applied
        // offset is acked back. ReplicateAck flows into the active DefaultReplicationManager so it can
        // advance the watermark and resolve any awaitReplication(minAcks) promise. Routed on the same
        // partition message transport as the forward handler.
        var streamReplicationReceiveHandler = ReplicationReceiveHandler.replicationReceiveHandler(config.self(),
                                                                                                  streamPartitionManager::appendRecovered,
                                                                                                  streamPartitionManager::nextExpectedOffset,
                                                                                                  streamReplicationTransport,
                                                                                                  (streamName, partition) -> streamBackfillExecutor.execute(() -> streamPartitionBackfill.backfill(streamName,
                                                                                                                                                                                                   partition)),
                                                                                                  streamPartitionManager::syncReplicated);

        allEntries.add(MessageRouter.Entry.route(ReplicationMessage.ReplicateEvents.class,
                                                 streamReplicationReceiveHandler::onReplicateEvents));
        allEntries.add(MessageRouter.Entry.route(ReplicationMessage.ReplicateAck.class,
                                                 streamReplicationManager::handleAck));
        registerStreamForwardExtensions(resourceProviderSetup,
                                        streamForwardClient,
                                        taskGroupOwnerResolver,
                                        streamReplicaSetController::ownerFor,
                                        streamPartitionManager,
                                        streamCursorStore,
                                        streamTieredReader,
                                        serializer,
                                        deserializer,
                                        config.self(),
                                        streamReplicaRegistry,
                                        committedStreamOwnerSource,
                                        ownershipEpochHighWater,
                                        linearizableBarrier);
        // #345 I1(d): the durable-entity side of the same SPI channel. `DurableEntityFactory` refuses to
        // provision without these, so a node that skipped this call fails an entity slice loudly at deploy
        // instead of handing it a private unfenced map (the five-writers-per-key shape I0 measured). The
        // barrier is gated on the SAME `[durable-entity] read-linearization` knob as the stream barrier
        // above and reuses the same applier and timeout budget, so entity and stream linearizable rounds
        // cannot disagree about how long a round may take.
        // The entity-arc ownership reconciler (see EntityOwnershipReconciler for the two-halves
        // contract, the 02w hosting-set rationale, and the exclusive-authority boundary with the stream
        // ownership driver). Its writer shares the stream writer's leader gate, epoch discipline and
        // record family; it differs ONLY in the HrwOwner, which the reconciler binds to the keyspace's
        // registered hosts ∩ the SAME reconciled member snapshot stream placement uses (#445 single
        // placement view). The clusterNode::isActive argument is the removal half's prune gate (#702) —
        // the same live consensus-active sample reconcileNodeActivation reads. Without it, a
        // constructed-but-never-started node whose KV replica carries committed self-registrations
        // mass-removes them into consensus off its empty declared set.
        var entityOwnershipReconciler = EntityOwnershipReconciler.entityOwnershipReconciler(kvStore,
                                                                                            config.self(),
                                                                                            streamReplicaSetController::reconciledMembers,
                                                                                            clusterNode::isActive,
                                                                                            entityArcOwner -> StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(isLeaderSupplier,
                                                                                                                                                                            rabiaTermSupplier,
                                                                                                                                                                            hlcClock,
                                                                                                                                                                            (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                                                                                                                            partition),
                                                                                                                                                                                                                    StreamPartitionOwnershipValue.class),
                                                                                                                                                                            entityArcOwner),
                                                                                            clusterCommandApplier);
        var entityCheckpointDriver = EntityCheckpointDriver.entityCheckpointDriver();
        var entityTimerDriver = EntityTimerDriver.entityTimerDriver();
        // #345 I3: entity state lives on a fenced, fsync-durable, replicated stream partition, and its
        // checkpoints are blocks in stream storage pointed at from consensus KV. The catch-up source is
        // the same one the partition manager gates replica release on — a fold must not trust its local
        // head offset until the cluster agrees this node's copy is complete.
        var entityLogSubstrate = StreamEntityLogSubstrate.streamEntityLogSubstrate(streamPartitionManager,
                                                                                   (stream, partition) -> streamCatchupView(streamReplicaRegistry,
                                                                                                                            config.self(),
                                                                                                                            stream,
                                                                                                                            partition),
                                                                                   streamStorage,
                                                                                   kvStore,
                                                                                   clusterCommandApplier);

        registerEntityExtensions(resourceProviderSetup,
                                 kvStore,
                                 ownershipEpochHighWater,
                                 entityOwnershipReconciler,
                                 streamingConfig.readLinearization() == ReadLinearizationMode.NO_OP_ROUND
                                 ? Option.some(EntityLinearizableBarrier.noOpRound(clusterCommandApplier,
                                                                                   streamingConfig.readForwardTimeout()))
                                 : Option.none(),
                                 entityLogSubstrate,
                                 entityCheckpointDriver,
                                 entityTimerDriver,
                                 entityForwardService);
        // The only thing that ever bounds an entity log: until a partition is checkpointed, the retention
        // floor refuses to reclaim any of its segments (correctly — nothing has been folded anywhere), so
        // a node that never ticks grows its entity logs without limit.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(entityCheckpointDriver::tick,
                                                                      ENTITY_CHECKPOINT_INTERVAL));
        // Scheduled here with the other assembly-time periodic tasks, deliberately rather than
        // special-cased: #644 tracks the whole family (tasks started at assembly run on nodes that never
        // start), and a driver that opted out of the pattern would hide from that sweep. The per-partition
        // owner gate ahead of the fire path is what is expected to make a tick on a never-started node
        // inert; that expectation is recorded on #644 to be VERIFIED by the sweep, not assumed here.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(entityTimerDriver::tick, ENTITY_TIMER_INTERVAL));
        // #345 I1 narrow C: mint the ownership records the entity fence reads, reusing the stream-side
        // writer mechanism, record family, leader gate and batching rather than an entity-specific
        // driver. The keyspace's LOG is a real stream since I3 (StreamEntityLogSubstrate.ensureLog),
        // but its REGISTRATION deliberately is not derived from stream records, and its OWNERSHIP has
        // exactly one authority — this reconciler; the stream ownership driver excludes entity arcs
        // (see driveStreamOwnership).
        //
        // The 02w hosting-set fix: an entity write is served only by a node hosting the keyspace's
        // declaring slice, so with `instances` below the cluster size the previously-shared writer
        // minted owners that refused every write they were handed (22/40 creates in the 02w run). The
        // reconciler's candidate set is the keyspace's registered hosts intersected with the live
        // member snapshot, so a dead host is never chosen and failover re-placement stays within the
        // hosting set by construction.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(entityOwnershipReconciler::tick,
                                                                      ENTITY_OWNERSHIP_RECONCILE_INTERVAL));
        var certRenewalScheduler = createCertRenewalScheduler(config,
                                                              clusterNode,
                                                              appHttpServer,
                                                              managementServerRef::get);
        var startTimeMs = System.currentTimeMillis();
        var nodeLifecycle = NodeLifecycle.nodeLifecycle();
        var node = new aetherNode(config,
                                  delegateRouter,
                                  kvStore,
                                  ownershipEpochHighWater,
                                  sliceRegistry,
                                  sliceStore,
                                  clusterNode,
                                  taskGroupOwnerResolver,
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
                                  workerMetricsAggregator,
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
                                  streamSegmentIndex,
                                  streamReadRouter,
                                  streamWriteRouter,
                                  consumerGroupCoordinator,
                                  consumerGroupRegistry,
                                  streamConsumerManager,
                                  streamNamespacesService,
                                  storageSetups,
                                  clusterTopologyManager,
                                  eventLoopMetricsCollector,
                                  swimHealthDetector,
                                  presenceSampler,
                                  leaderReconciler,
                                  membershipFsm,
                                  quorumLossDetector,
                                  coreAbsenceDetector,
                                  transitionJournal,
                                  startSwimTrigger,
                                  Option.empty(),
                                  discoveryProvider,
                                  certRenewalScheduler,
                                  inFlightTrackerForDrain,
                                  nodeLifecycle,
                                  hlcClock,
                                  dhtClientOption,
                                  Option.some(dhtNode),
                                  effectivePhaseSupplier,
                                  currentGenerationEpochSupplier,
                                  observabilityBaseline,
                                  spokesmanPingLoop,
                                  governorAnnouncerHolder,
                                  streamRetentionEnforcer,
                                  streamConsumerRuntime,
                                  startTimeMs,
                                  swimBootAtMs,
                                  periodicTasks);

        nodeDeploymentManager.setShutdownCallback(node::stop);
        // #634-4, the periodic half (owner-ruled: on-read + periodic alert). The watch binds the three
        // SHARED components directly — never a node record instance, of which two are constructed on
        // the management-enabled path — WARN-logs and raises one operator alert per partition violated
        // on two consecutive ticks, through the injection path: the metrics-threshold path is
        // evaluated only while a dashboard client is connected.
        var retentionInvariantWatch = RetentionRoutes.RetentionInvariantWatch.retentionInvariantWatch(streamPartitionManager,
                                                                                                      streamSegmentIndex,
                                                                                                      kvStore,
                                                                                                      (name, message) -> alertManager.inject(name,
                                                                                                                                             RetentionRoutes.ALERT_SEVERITY,
                                                                                                                                             message,
                                                                                                                                             Option.none(),
                                                                                                                                             Option.none())
                                                                                                                                     .onFailure(cause -> LOG.warn("Retention alert injection failed: {}",
                                                                                                                                                                  cause.message())));

        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(retentionInvariantWatch::tick,
                                                                      RETENTION_INVARIANT_CHECK_INTERVAL));
        nodeDeploymentManager.setSelfReadySignal(() -> markSubsystemsReady(nodeLifecycle::signalReady,
                                                                           nodeReportedStateHolder));
        // Activation level-heal: a cold-start `restart_all_nodes` can drop the single CAS-latched
        // ClusterStateNotification.ACTIVE edge while the router delegate is being rebuilt, leaving NDM
        // stuck in Dormant (self-ready/subsystemsReady never fire) so the node reports SYNCING forever.
        // This tick re-dispatches QuorumEstablished only while still Dormant AND consensus is live —
        // a dropped edge self-heals within one interval; a no-op on every healthy node thereafter.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> reconcileNodeActivation(clusterNode::isActive,
                                                                                                    nodeDeploymentManager),
                                                                      NDM_ACTIVATION_RECONCILE_INTERVAL));
        nodeLifecycle.subsystemsReady();
        // Review catch (#634 batch): the guard cannot run MEANINGFULLY earlier — `aetherEntries`
        // ACCUMULATES across
        // the whole assembly, so the routed set is only complete here, after the periodic tasks are
        // armed — and a guard placed at any earlier point would silently see a PARTIAL set and pass
        // against it, with every future entry insertion after that point narrowing its view further. The hazard that ordering creates is real though: on a guard failure nothing calls
        // stop(), and in a single-JVM host (Forge/Ember) the armed timers would keep firing against
        // half-built state — the #499 zombie class. So a failed guard CANCELS what this assembly
        // armed before the failure propagates; production's exit makes it moot, in-JVM hosts need it.
        return verifyRoutedTypesEncodable(allEntries, nodeCodec).onFailure(_ -> cancelArmedWork(periodicTasks,
                                                                                                presenceSampler,
                                                                                                coreAbsenceDetector,
                                                                                                sliceInvoker,
                                                                                                observabilityBaseline))
                                         .flatMap(_ -> RabiaNode.buildAndWireRouter(delegateRouter, allEntries))
                                         .map(_ -> {
                                                  if (config.managementPort() > 0) {
                                                  var mgmtSecurityEnabled = config.appHttp()
                                                                                  .securityEnabled();
                                                  // #573: the false arm must DENY, not permit-all. It previously used the validator now
                                                  // named `permitAllValidator`, which grants ADMIN + SERVICE to every caller — so a node
                                                  // whose `[app-http] enabled` was false (the DEFAULT) served an unauthenticated,
                                                  // fully-privileged Management API. `denyUnlessPublicValidator` refuses anything but a
                                                  // Public route, and by FAILING rather than succeeding it also lets the
                                                  // `kvStoreAwareValidator` below consult the cluster's bootstrap admin keys, which the
                                                  // unconditionally-succeeding grant short-circuited entirely.
                                                  var configValidator = mgmtSecurityEnabled
                                                                        ? SecurityValidator.apiKeyValidator(config.appHttp()
                                                                                                                  .apiKeys())
                                                                        : SecurityValidator.denyUnlessPublicValidator();

                                                  if (!mgmtSecurityEnabled) {
                                                  LOG.warn("Management API on port {} has NO configured credentials — non-public routes will be"
                                                          + " REFUSED unless a cluster bootstrap admin key is present in KV. Set [app-http] enabled"
                                                          + " = true with [app-http.api-keys.<key>] to authenticate operators (#573).",
                                                           config.managementPort());
                                              }

                                                  var mgmtSecurityValidator = SecurityValidator.kvStoreAwareValidator(configValidator,
                                                                                                                      () -> node.kvStore());
                                                  var managementServer = ManagementServer.managementServer(config.managementPort(),
                                                                                                           () -> node,
                                                                                                           entityCheckpointDriver,
                                                                                                           alertManager,
                                                                                                           configRegistry,
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
                                                                                                           Option.some(deserializer),
                                                                                                           target -> requestDrainThroughFsm(drainCommandRegistry,
                                                                                                                                            membershipFsmRef,
                                                                                                                                            target),
                                                                                                           drainCommandRegistry::drainTargets);

                                                  managementServerRef.set(Option.some(managementServer));
                                                  // #278: expose the node's real MeterRegistry to slice-facing resource
                                                  // provisioning so MetricsInterceptorFactory records into the SAME
                                                  // registry the Management API scrapes, instead of each interceptor
                                                  // fabricating its own disconnected one.
                                                  resourceProviderSetup.spiProvider()
                                                                       .onPresent(spi -> spi.registerExtension(MeterRegistry.class,
                                                                                                               managementServer.meterRegistry()));

                                                  return new aetherNode(config,
                                                                        delegateRouter,
                                                                        kvStore,
                                                                        ownershipEpochHighWater,
                                                                        sliceRegistry,
                                                                        sliceStore,
                                                                        clusterNode,
                                                                        taskGroupOwnerResolver,
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
                                                                        workerMetricsAggregator,
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
                                                                        streamSegmentIndex,
                                                                        streamReadRouter,
                                                                        streamWriteRouter,
                                                                        consumerGroupCoordinator,
                                                                        consumerGroupRegistry,
                                                                        streamConsumerManager,
                                                                        streamNamespacesService,
                                                                        storageSetups,
                                                                        clusterTopologyManager,
                                                                        eventLoopMetricsCollector,
                                                                        swimHealthDetector,
                                                                        presenceSampler,
                                                                        leaderReconciler,
                                                                        membershipFsm,
                                                                        quorumLossDetector,
                                                                        coreAbsenceDetector,
                                                                        transitionJournal,
                                                                        startSwimTrigger,
                                                                        Option.some(managementServer),
                                                                        discoveryProvider,
                                                                        certRenewalScheduler,
                                                                        inFlightTrackerForDrain,
                                                                        nodeLifecycle,
                                                                        hlcClock,
                                                                        dhtClientOption,
                                                                        Option.some(dhtNode),
                                                                        effectivePhaseSupplier,
                                                                        currentGenerationEpochSupplier,
                                                                        observabilityBaseline,
                                                                        spokesmanPingLoop,
                                                                        governorAnnouncerHolder,
                                                                        streamRetentionEnforcer,
                                                                        streamConsumerRuntime,
                                                                        startTimeMs,
                                                                        swimBootAtMs,
                                                                        periodicTasks);
                                              }

                                                  return node;
                                              });
    }

    TimeSpan PHASE_WATCH_INTERVAL = TimeSpan.timeSpan(1).seconds();
    /// Wave-1 item 3 (#245 baseline diagnostic) cadence for [#logMembershipBaselineTrace].
    TimeSpan MEMBERSHIP_BASELINE_TRACE_INTERVAL = TimeSpan.timeSpan(30).seconds();

    /// Single FSM-transition fan-out installed at the central transition chokepoint
    /// ([`MembershipFsm#onTransition`], fired once per ACTUAL per-member state change under the FSM's
    /// synchronized per-member dispatch monitor). Records the transition into the per-node journal
    /// (Wave-1 Enrichment A) AND — when the transition crosses the exact-`Member` boundary — re-feeds
    /// the strict-core count to the quorum-loss detector. This prompt re-feed (NOT only the 15s
    /// presence down-hysteresis `onNttReconcile` / DEAD `onMembershipDeath` paths) is what arms the
    /// self-drain window when several cores enter SUSPECT at once, and CANCELS it on a SUSPECT→MEMBER
    /// refutation. Safe-by-precedent: `onMembershipDeath` already drives the same
    /// `propagateMemberCount`→`strictCoreMemberCount` chain from inside this same dispatch monitor.
    @Contract
    private static void onFsmTransition(TransitionJournal journal,
                                        AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                        MembershipFsm membershipFsm,
                                        MembershipTransitionRecord record,
                                        NodeId self) {
        appendFsmTransition(journal, record);
        if (crossesMemberBoundary(record)) {
            propagateMemberCount(quorumLossDetectorRef, membershipFsm, self);
        }
    }

    /// Wave-1 Enrichment A: map one FSM transition observation into the per-node journal.
    @Contract
    private static void appendFsmTransition(TransitionJournal journal, MembershipTransitionRecord record) {
        journal.append(TransitionJournal.Layer.FSM,
                       record.nodeId().id(),
                       record.fromState(),
                       record.toState(),
                       record.cause(),
                       record.incarnation(),
                       record.role());
    }

    /// Wave-1 Enrichment A: map one PeerState transition observation into the per-node journal.
    @Contract
    private static void appendPeerTransition(TransitionJournal journal, PeerTransitionRecord record) {
        journal.append(TransitionJournal.Layer.PEER,
                       record.peerId().id(),
                       record.from().name(),
                       record.to().name(),
                       record.cause(),
                       - 1L,
                       "");
    }

    /// Wave-1 §6.4 (detect-only): journal the boot future-history detection as a structured
    /// FSM-layer entry (`cause = boot-future-history`, from/to carry the two phase values).
    @Contract
    private static void recordBootFutureHistory(TransitionJournal journal,
                                                NodeId self,
                                                long persistedPhase,
                                                long clusterPhase) {
        journal.append(TransitionJournal.Layer.FSM,
                       self.id(),
                       "persistedPhase:" + persistedPhase,
                       "clusterPhase:" + clusterPhase,
                       "boot-future-history",
                       - 1L,
                       "");
    }

    /// Wave-1 item 3 diagnostic, Wave-4 successor: DEBUG dump of the three membership views
    /// whose divergence WAS the #245 baseline gap — the `MembershipDeltaProjector`'s announced
    /// decision-stream baseline (which replaced the deleted TopologyObserver
    /// `previousCoreMembers` diff baseline), the authoritative FSM core-member set, and the
    /// presence-sampler view. Log-only.
    @Contract
    private static void logMembershipBaselineTrace(MembershipDeltaProjector projector,
                                                   MembershipFsm membershipFsm,
                                                   PresenceSampler presenceSampler) {
        LOG.debug("Membership baseline trace (Wave-4 #245 diag): announcedCoreMembers={} fsmCoreMembers={} presenceMembers={}",
                  projector.announcedCoreMembers(),
                  membershipFsm.coreMembers(),
                  presenceSampler.currentMembers());
    }

    /// `ClusterConfigKey` KV-commit fan-out. CTM keeps its config-changed notification, AND —
    /// H1 / #257 (cluster-topology-overhaul Wave 8 item 1, pulled forward) — the leader's
    /// reconciler is triggered with `trigger=CONFIG_CHANGE`: a committed config-driven target
    /// change (scale up/down, restore-to-N) must produce a reconcile pass within the debounce
    /// window instead of waiting for unrelated SWIM churn (live-confirmed: a 2-core deficit
    /// sat 10 minutes unhealed because no reconcile ever fired). `onConfigChange` is
    /// leader-gated and rides the reconciler's standard CAS-debounce machinery.
    ///
    /// **One quorum denominator (cluster-topology-overhaul Wave 9 item 1).** `ClusterConfigKey`
    /// is the single SOURCE of the cluster's core-count denominator. The consensus-side
    /// `TopologyObserver.effectiveClusterSize` atomic is a DERIVED cell: this aether-side
    /// `ClusterConfigKey` subscription pushes the committed `coreCount` into it via the
    /// observer's pre-existing `handleSetClusterSize` trigger (§3.1: that trigger is preserved,
    /// only its denominator-write origin changes — it is now driven by the KV commit, not a
    /// separately-routed operator `SetClusterSize`). The observer cannot read aether KV
    /// directly (module boundary), so this is the single bridge from KV → the atomic.
    /// Boot ordering: the atomic is initialized from `TopologyConfig.clusterSize()` at
    /// construction and stays at that seed until the FIRST `ClusterConfigKey` value arrives
    /// (bootstrap seeds it on leader gain); both sources agree on the configured core count, so
    /// the seed is correct until the KV value supersedes it.
    @Contract
    private static void onClusterConfigPut(ValuePut<AetherKey.ClusterConfigKey, AetherValue.ClusterConfigValue> put,
                                           ClusterTopologyManager clusterTopologyManager,
                                           LeaderReconciler leaderReconciler) {
        clusterTopologyManager.observer()
                              .handleSetClusterSize(new TopologyManagementMessage.SetClusterSize(put.cause()
                                                                                                    .value()
                                                                                                    .coreCount()));
        clusterTopologyManager.onClusterConfigChanged();
        // RFC-0017 stage 5 — every committed config change (scale, apply, restore) converges worker
        // topology through ONE trigger source. Leader-gated and serialized inside the CTM.
        clusterTopologyManager.reconcileWorkerTopology();
        leaderReconciler.onConfigChange();
    }

    /// Post-E.8 phase-change publisher. Polls `phaseSupplier` at `PHASE_WATCH_INTERVAL` and
    /// dispatches `ctm.onClusterPhaseChanged(newPhase)` on every observed transition.
    /// Returns the ARMING THUNK for the deferred-arming list (#644) — ticking begins only when the
    /// node starts. The baseline is DELIBERATELY seeded here, at assembly, not when the thunk runs:
    /// the first armed tick then reports whatever transition happened between assembly and start as
    /// one edge (e.g. the formation transition the CTM converges worker topology on), instead of
    /// swallowing it into a start-time baseline. What the deferral removes is only the pre-start
    /// PUBLISHING — the #644 defect — never the edge itself.
    private static Supplier<ScheduledFuture<?>> phaseChangeWatcherArmer(Supplier<AetherValue.ClusterPhase> phaseSupplier,
                                                                        ClusterTopologyManager ctm) {
        var lastPhase = new AtomicReference<>(phaseSupplier.get());

        return () -> SharedScheduler.scheduleAtFixedRate(() -> publishPhaseChange(phaseSupplier, ctm, lastPhase),
                                                         PHASE_WATCH_INTERVAL,
                                                         PHASE_WATCH_INTERVAL);
    }

    @Contract
    private static void publishPhaseChange(Supplier<AetherValue.ClusterPhase> phaseSupplier,
                                           ClusterTopologyManager ctm,
                                           AtomicReference<AetherValue.ClusterPhase> lastPhase) {
        var current = phaseSupplier.get();
        var previous = lastPhase.getAndSet(current);

        if (previous != current) {
            ctm.onClusterPhaseChanged(current);
        }
    }

    /// B1 (membership v2 §7.5): the node's self-ready signal also marks subsystems ready for the
    /// node-reported readiness state (READY requires a live consensus-active level AND subsystems-ready).
    @Contract
    private static void markSubsystemsReady(Runnable signalReady, NodeReportedStateHolder holder) {
        signalReady.run();
        holder.onSubsystemsReady();
    }

    /// B4/C-1 (membership v2 §7.5): nodes the leader observes in a given reported state — peers from
    /// the readiness view PLUS self when the local holder reports that state (the leader does not pong
    /// itself, so it is absent from the aggregated pong view; its own state is authoritative locally).
    private static Set<NodeId> nodesReporting(ClusterSyncPongSignalFan fan,
                                              NodeReportedStateHolder selfHolder,
                                              NodeId self,
                                              NodeReportedState target) {
        var matching = new HashSet<NodeId>();

        fan.readinessSnapshot().forEach((nodeId, state) -> addIfMatching(matching, nodeId, state, target));
        if (selfHolder.current() == target) {
            matching.add(self);
        }

        return matching;
    }

    @Contract
    private static void addIfMatching(Set<NodeId> matching,
                                      NodeId nodeId,
                                      NodeReportedState state,
                                      NodeReportedState target) {
        if (state == target) {
            matching.add(nodeId);
        }
    }

    /// One LEADERSHIP-TENURE TICK: fired once per `pingInterval` (1s default), so the counter's value
    /// is elapsed leadership time in ping intervals — never an event count. Deliberately unlogged
    /// (1 Hz per leader forever, for a value readable on demand from `/api/cluster/generation`).
    @Contract
    private static void bumpGenerationIfLeader(BooleanSupplier isLeader, AtomicLong generationCounter) {
        if (isLeader.getAsBoolean()) {
            generationCounter.incrementAndGet();
        }
    }

    private static Set<NodeId> ctmProvisionedFromFsm(MembershipFsm fsm) {
        return fsm.memberDescriptors()
                  .entrySet()
                  .stream()
                  .filter(entry -> "ctm".equalsIgnoreCase(entry.getValue().source()))
                  .map(Map.Entry::getKey)
                  .collect(Collectors.toUnmodifiableSet());
    }

    /// #557's boot-quorum projection as a NAMED seam (extracted under #644's assembly pass, per the
    /// composition note on that ticket). The quorum numerator MUST read the OBSERVED-reachability
    /// projection: swapping `coreObservedMembers` for `coreCountedMembers` here silently restores
    /// #557 — boot quorum declared from configuration with zero packets moved. The wiring used to be
    /// an inline lambda no test could reach, so `PresenceGenerationSnapshotSourceQuorumCompositionTest`
    /// could only mirror it; `PresenceMemberSupplierSeamTest` now pins THIS method against a real
    /// seeded FSM. `or(Set.of())` guards the pre-FSM-published boot window (lazy supplier; the FSM
    /// holder is populated before any snapshot is taken).
    static Supplier<Set<NodeId>> presenceMemberSupplier(Supplier<MembershipFsm> membershipFsm, NodeId self) {
        return () -> Option.option(membershipFsm.get())
                           .map(fsm -> fsm.coreObservedMembers(self))
                           .or(Set.of());
    }

    @Contract
    private static void commandedDrain(DrainProcedure drainProcedure, NodeReportedStateHolder holder) {
        drainProcedure.initiate(DrainReason.COMMANDED);
        holder.onDrainStarted();
    }

    /// E2 Phase 2b (2026-05-28): bridge the consensus-derived `ClusterStateNotification`
    /// quorum-presence edge into the §8.2 process-exit drain. **Wave 9 Fix A
    /// (cluster-topology-overhaul):** the PASSIVE edge no longer triggers an IMMEDIATE
    /// `initiate(QUORUM_LOSS)` — that instant drain bypassed both the `QuorumLossDetector` window
    /// and the split-timeout `T`, and was the post-partition-heal self-destruct (a transient
    /// false-FAULTY storm dropped counted-members below quorum → PASSIVE → all nodes Exited(2)
    /// before the storm cleared). Instead the edge is debounced through the detector's windowed
    /// path (arm at `T` from THIS node's local PASSIVE observation, cancel on ACTIVE-regain,
    /// drain once iff still lost after `T`) — completing the ratified Wave-9 item-2 design
    /// (the minority measures `T` from its own local-quorum-loss observation). The read-path
    /// quiesce (`AppHttpServer::onQuorumStateChange`) stays IMMEDIATE on PASSIVE — read-path
    /// protection is cheap to undo on regain; only the process-exit drain gets the window.
    @Contract
    private static void routeQuorumDisappearedToDrain(ClusterStateNotification notification,
                                                      AtomicReference<QuorumLossDetector> quorumLossDetectorRef) {
        Option.option(quorumLossDetectorRef.get()).onPresent(detector -> detector.onQuorumPresence(notification.state() != ClusterStateNotification.State.PASSIVE));
    }

    private static NodeAddress findSelfAddress(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(info -> info.id()
                                         .equals(config.self()))
                     .map(NodeInfo::address)
                     .findFirst()
                     .orElse(new NodeAddress("", 0));
    }

    private static AetherValue.ProvisioningSource detectProvisioningSource() {
        var raw = Option.option(System.getenv("AETHER_PROVISIONED_BY"))
                        .filter(v -> !v.isBlank())
                        .map(String::trim)
                        .map(String::toLowerCase);

        return raw.map(AetherNode::provisioningSourceFrom)
                  .or(AetherValue.ProvisioningSource.MANUAL);
    }

    /// #336 — parse this node's OWN resolved config file (the per-node overlay the CLI rendered at
    /// bootstrap with `${env:...}` / `${secrets:...}` placeholders resolved to literals) into a
    /// [TomlDocument], so the leader's CTM render path can substitute the placeholders a replacement's
    /// composed overlay inherits from the deliberately-unresolved persisted KV TOML with these
    /// literals at the same TOML path. The config path is published by [org.pragmatica.aether.Main]
    /// via the `aether.config.path` system property where it resolves the `--config=` argument (the
    /// node module's established pattern of reading runtime context from props/env at the assemble
    /// site). Returns [Option#none] gracefully when no path was published (forge / tests / a config
    /// loaded by other means) or the file cannot be parsed — the CTM then passes composed overlays
    /// through unchanged, preserving prior behavior.
    private static Option<TomlDocument> parseOwnResolvedConfig() {
        return Option.option(System.getProperty(CONFIG_PATH_PROPERTY))
                     .filter(path -> !path.isBlank())
                     .map(Path::of)
                     .filter(path -> path.toFile()
                                         .exists())
                     .flatMap(AetherNode::parseConfigFile);
    }

    private static Option<TomlDocument> parseConfigFile(Path path) {
        return TomlParser.parseFile(path)
                         .onFailure(cause -> LOG.warn("#336: failed to parse own resolved config at {} for CTM placeholder resolution: {}",
                                                      path,
                                                      cause.message()))
                         .option();
    }

    private static AetherValue.ProvisioningSource provisioningSourceFrom(String raw) {
        return switch (raw) {
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

    /// Wire the FSM-backed membership-view supplier into the QUIC transport so consensus
    /// broadcasts target only peers the membership authority still considers reachable
    /// ([`MembershipFsm#broadcastEligibleMembers`] — every tracked member NOT terminally DEAD:
    /// OBSERVED + MEMBER + SUSPECT + DEPARTING). The transport `peers` table is a connection
    /// cache, not the membership authority: a co-confirmed-DEAD-but-still-CONNECTED peer must
    /// never receive consensus broadcasts, otherwise it drives the #68 dead-ULID CONSENSUS
    /// retry-storm. Joining/suspected peers stay targets so a replacement (OBSERVED) catches up.
    /// No-ops on a non-QUIC network.
    private static void attachBroadcastMembershipView(ClusterNetwork network, MembershipFsm membershipFsm) {
        if (network instanceof QuicClusterNetwork quicNetwork) {
            quicNetwork.setBroadcastMembership(membershipFsm::broadcastEligibleMembers);
        }
    }

    private static void attachQuicPeerStateListener(ClusterNetwork network, CoreSwimHealthDetector swimDetector) {
        LOG.debug("attachQuicPeerStateListener: network class={}",
                  Option.option(network).map(n -> n.getClass()
                                                   .getName()).or("null"));
        if (! (network instanceof QuicClusterNetwork quicNetwork)) {
            LOG.warn("attachQuicPeerStateListener: network is NOT QuicClusterNetwork — skipping listener attachment");

            return;
        }

        var listener = new QuicPeerStateListener() {
            @Override
            @Contract
            public void onPeerJoined(NodeId nodeId) {
                LOG.debug("QuicPeerState: onPeerJoined({}) — recordTransportHint(reachable)", nodeId);
                swimDetector.recordTransportHint(new TransportObservation.PeerReachable(nodeId));
            }

            @Override
            @Contract
            public void onPeerReconnected(NodeId nodeId) {
                LOG.debug("QuicPeerState: onPeerReconnected({}) — recordTransportHint(reachable)", nodeId);
                swimDetector.recordTransportHint(new TransportObservation.PeerReachable(nodeId));
            }

            @Override
            @Contract
            public void onPeerLeft(NodeId nodeId) {
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
        PEER_LEFT("QUIC peer connection closed"),
        PING_TIMEOUT("cluster-sync ping/pong timeout (missed-pong threshold)");
        private final String message;
        QuicTransportCause(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// Installs a single `PeerConnectivityReporter` on EVERY node (leader + followers).
    /// On EVERY transition observation, push into the local `PeerObservationBuffer` so the
    /// next outbound `ClusterSyncPong` carries the observation (follower→leader relay path;
    /// benign on the leader, which does not pong itself), and feed the NTT/membership-tracker
    /// QUIC connect/disconnect hints via `onNttConnect`/`onNttDisconnect`.
    ///
    /// P3 (membership unification): the former leader-side `ReachabilityAggregator`
    /// `ingestSelfTransition` fast-path is removed — SWIM (fed by these QUIC hints) is now the
    /// single liveness signal, so the separate reachability fold is gone.
    private static void attachQuicConnectivityReporter(ClusterNetwork network,
                                                       BooleanSupplier isLeaderSupplier,
                                                       PeerObservationBuffer buffer,
                                                       Supplier<Epoch> epochSupplier,
                                                       Consumer<NodeId> onNttConnect,
                                                       Consumer<NodeId> onNttDisconnect) {
        if (! (network instanceof QuicClusterNetwork quicNetwork)) {
            return;
        }

        PeerConnectivityReporter reporter = new PeerConnectivityReporter() {
            @Contract
            @Override
            public void onPeerDisconnected(NodeId peerId, long term, long counter, boolean deathPathInitiated) {
                var now = System.currentTimeMillis();

                buffer.pushConnectivity(new PeerConnectivityObservation(peerId,
                                                                        ConnectivityState.DISCONNECTED,
                                                                        term,
                                                                        counter,
                                                                        now));
                // Wave 9 Fix B: only an ORGANIC close feeds the FSM liveness-gone co-confirmation
                // input. A death-path-initiated REMOVE (our own departurePermanent reacting to a
                // SWIM-FAULTY/gossip verdict) must NOT co-confirm the death it caused — that
                // circular co-confirmation is the post-partition-heal self-destruct. The
                // connectivity observation above is still buffered (transport coherence).
                if (!deathPathInitiated) {
                    onNttDisconnect.accept(peerId);
                }
            }

            @Contract
            @Override
            public void onPeerConnected(NodeId peerId, long term, long counter) {
                var now = System.currentTimeMillis();

                buffer.pushConnectivity(new PeerConnectivityObservation(peerId,
                                                                        ConnectivityState.CONNECTED,
                                                                        term,
                                                                        counter,
                                                                        now));
                onNttConnect.accept(peerId);
            }
        };
        QuicClusterNetwork.ObservedEpochSupplier epochAdapter = new QuicClusterNetwork.ObservedEpochSupplier() {
            @Override
            public long term() {
                return epochSupplier.get()
                                    .rabiaTerm();
            }

            @Override
            public long counter() {
                return epochSupplier.get()
                                    .localCounter();
            }
        };

        quicNetwork.setFollowerObservationWiring(isLeaderSupplier, reporter, epochAdapter);
    }

    /// Route a SWIM observation edge into the authoritative [`MembershipFsm`]. HEALTHY / SUSPECT /
    /// FAULTY / DEPARTED / UNKNOWN map to the matching `onSwim*` ingress; JoinAnnounced /
    /// MemberDiscovered are ignored (the FSM tracks liveness transitions, not discovery). The FSM
    /// drives the per-member lifecycle and, on the DEAD edge, hard-evicts from NTT — the membership-
    /// death authority. Leader-gating happens inside the FSM.
    private static void routeSwimEdgeToMembershipFsm(SwimObservation observation, MembershipFsm membershipFsm) {
        switch (observation) {
            case SwimObservation.HealthyObserved healthy -> membershipFsm.onSwimHealthy(healthy.peer(),
                                                                                        healthy.incarnation());
            case SwimObservation.SuspectObserved suspect -> membershipFsm.onSwimSuspect(suspect.peer(),
                                                                                        suspect.incarnation());
            case SwimObservation.FaultyObserved faulty -> membershipFsm.onSwimFaulty(faulty.peer(), faulty.incarnation());
            case SwimObservation.DepartedObserved departed -> membershipFsm.onSwimDeparted(departed.peer(),
                                                                                           departed.incarnation());
            case SwimObservation.UnknownObserved unknown -> membershipFsm.onSwimUnknown(unknown.peer(),
                                                                                        unknown.incarnation());
            case SwimObservation.JoinAnnounced joined -> membershipFsm.onMemberDescriptor(joined.nodeInfo());
            case SwimObservation.MemberDiscovered discovered -> membershipFsm.onMemberDescriptor(discovered.nodeInfo());
            default -> {}
        }
    }

    private static Option<NodeId> lookupGovernor(KVStore<AetherKey, AetherValue> kvStore, String communityId) {
        return kvStore.get(AetherKey.GovernorAnnouncementKey.forCommunity(communityId))
                      .filter(v -> v instanceof AetherValue.GovernorAnnouncementValue)
                      .map(v -> ((AetherValue.GovernorAnnouncementValue) v).governorId());
    }

    /// Project the node's static cluster configuration into the bootstrap-seed baseline used to
    /// seed `ClusterConfigKey.CURRENT` on leader gain when absent. Sourced from `TopologyConfig`
    /// (configured cluster size / core bounds) rather than the live committed-topology member
    /// count, so a freshly-elected leader can seed a faithful baseline on the non-bootstrap and
    /// restart-with-empty-KV paths before presence-derived membership has converged.
    private static BootstrapModule.ClusterConfigBaseline clusterConfigBaseline(TopologyConfig topology) {
        return new BootstrapModule.ClusterConfigBaseline(topology.clusterSize(), topology.coreMin(), topology.coreMax());
    }

    private static void onLeaderChangeForPublisher(LeaderNotification.LeaderChange change,
                                                   AtomicLong leaderTerm,
                                                   BootstrapModule bootstrap) {
        if (change.localNodeIsLeader()) {
            leaderTerm.incrementAndGet();
            bootstrap.onLeaderGained();
        } else {
            bootstrap.onLeaderLost();
        }
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void toggleCtmOnLeaderChange(LeaderNotification.LeaderChange change, ClusterTopologyManager ctm) {
        if (change.localNodeIsLeader()) {
            ctm.activate();
        } else {
            ctm.deactivate();
        }
    }

    /// #231 Step 1 — leader-pin DeploymentMetricsScheduler (the genuinely task-assignment-gated
    /// METRICS component: its activate() calls startPinging() and onQuorumStateChange only stops
    /// on DISAPPEARED, never self-starts). Mirrors toggleCtmOnLeaderChange so it runs on the leader.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleDeploymentMetricsOnLeaderChange(LeaderNotification.LeaderChange change,
                                                              DeploymentMetricsScheduler s) {
        if (change.localNodeIsLeader()) {
            s.activate();
        } else {
            s.deactivate();
        }
    }

    /// Membership v2 — leader-pin the NTT-side `LeaderReconciler`. Activate on leader gain
    /// (drain NTT map, fire initial reconcile tick, start periodic ticks); deactivate on
    /// leader loss (cancel ticks, clear in-flight provisioning).
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleNttReconcilerOnLeaderChange(LeaderNotification.LeaderChange change,
                                                          LeaderReconciler reconciler) {
        if (change.localNodeIsLeader()) {
            reconciler.activate();
        } else {
            reconciler.deactivate();
        }
    }

    /// #231 Step 2 — leader-pin the remaining control-plane components. Each mirrors
    /// toggleCtmOnLeaderChange:
    /// activate() on leader gain, deactivate() on leader loss. All return Promise<Unit> (discarded
    /// like the template). CDM — TaskGroup DEPLOYMENT.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleCdmOnLeaderChange(LeaderNotification.LeaderChange change, ClusterDeploymentManager cdm) {
        if (change.localNodeIsLeader()) {
            cdm.activate();
        } else {
            cdm.deactivate();
        }
    }

    /// LoadBalancer — TaskGroup DEPLOYMENT; routed AFTER CDM (reconciles CDM-produced routes).
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleLbOnLeaderChange(LeaderNotification.LeaderChange change, LoadBalancerManager lbm) {
        if (change.localNodeIsLeader()) {
            lbm.activate();
        } else {
            lbm.deactivate();
        }
    }

    /// ControlLoop — TaskGroup SCALING.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleControlLoopOnLeaderChange(LeaderNotification.LeaderChange change,
                                                        ControlLoop controlLoop) {
        if (change.localNodeIsLeader()) {
            controlLoop.activate();
        } else {
            controlLoop.deactivate();
        }
    }

    /// TTMManager — TaskGroup SCALING.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleTtmOnLeaderChange(LeaderNotification.LeaderChange change, TTMManager ttmManager) {
        if (change.localNodeIsLeader()) {
            ttmManager.activate();
        } else {
            ttmManager.deactivate();
        }
    }

    /// RollbackManager — TaskGroup SCALING.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleRollbackOnLeaderChange(LeaderNotification.LeaderChange change,
                                                     RollbackManager rollbackManager) {
        if (change.localNodeIsLeader()) {
            rollbackManager.activate();
        } else {
            rollbackManager.deactivate();
        }
    }

    /// AbTestManager — TaskGroup STRATEGIES.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleAbTestOnLeaderChange(LeaderNotification.LeaderChange change,
                                                   AbTestManager abTestManager) {
        if (change.localNodeIsLeader()) {
            abTestManager.activate();
        } else {
            abTestManager.deactivate();
        }
    }

    /// DeploymentManager (update/DeploymentManager) — leader-pinned control-plane singleton.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleDeploymentManagerOnLeaderChange(LeaderNotification.LeaderChange change,
                                                              DeploymentManager deploymentManager) {
        if (change.localNodeIsLeader()) {
            deploymentManager.activate();
        } else {
            deploymentManager.deactivate();
        }
    }

    /// StreamingCoordinator — TaskGroup STREAMING.
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleStreamingOnLeaderChange(LeaderNotification.LeaderChange change,
                                                      StreamingCoordinator streamingCoordinator) {
        if (change.localNodeIsLeader()) {
            streamingCoordinator.activate();
        } else {
            streamingCoordinator.deactivate();
        }
    }

    /// StorageAdapter — TaskGroup STORAGE (harmless for noOp, correct for the real adapter).
    @SuppressWarnings("JBCT-RET-01")
    private static void toggleStorageOnLeaderChange(LeaderNotification.LeaderChange change,
                                                    DelegatedStorageAdapter storageAdapter) {
        if (change.localNodeIsLeader()) {
            storageAdapter.activate();
        } else {
            storageAdapter.deactivate();
        }
    }

    // SWIM now starts at transport-ready (after `startClusterAsync()` brings the QUIC
    // server up), NOT gated on consensus quorum. The join ANNOUNCE is bootstrap-discovery:
    // it is the only way survivors dial a freshly-provisioned replacement on the consensus
    // plane, so gating it behind the replacement's own quorum deadlocked sub-quorum
    // auto-heal. The gossip encryptor is ready at boot (createGossipEncryptor), and the
    // COLD_BOOT/`isBooting` FAULTY-suppression keeps pre-quorum SWIM safe.
    private static void startSwim(CoreSwimHealthDetector swimHealthDetector,
                                  ClusterNetwork network,
                                  RotatingGossipEncryptor encryptor,
                                  Runnable announceJoinTrigger) {
        var workerGroup = network.server().map(Server::workerGroup);

        swimHealthDetector.start(workerGroup, encryptor);
        announceJoinTrigger.run();
    }

    @SuppressWarnings({"JBCT-RET-01"})
    private static void handleActivationDirective(ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put,
                                                  NodeId selfId,
                                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                  SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                                  ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                                  AetherNodeConfig config,
                                                  MessageRouter.DelegateRouter delegateRouter,
                                                  KVStore<AetherKey, AetherValue> kvStore,
                                                  SliceStore sliceStore,
                                                  SliceInvoker sliceInvoker,
                                                  Supplier<CoreSwimHealthDetector> swimHealthDetectorSupplier,
                                                  AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                                                  Logger growthLog) {
        if (!put.cause().key().nodeId().equals(selfId)) {
            return;
        }

        var role = put.cause().value().role();
        var communityId = put.cause().value().communityId();

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
                               communityId,
                               swimHealthDetectorSupplier.get(),
                               governorAnnouncerHolder,
                               growthLog);
        }
    }

    @SuppressWarnings({"JBCT-RET-01"})
    private static void activateWorkerMode(NodeId selfId,
                                           RabiaNode<KVCommand<AetherKey>> clusterNode,
                                           SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                           ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                           AetherNodeConfig config,
                                           MessageRouter.DelegateRouter delegateRouter,
                                           KVStore<AetherKey, AetherValue> kvStore,
                                           SliceStore sliceStore,
                                           SliceInvoker sliceInvoker,
                                           String communityId,
                                           CoreSwimHealthDetector swimHealthDetector,
                                           AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                                           Logger log) {
        clusterNode.authorizeObservation();
        switchableCluster.switchTo(forwardingClusterNode);
        log.info("Worker {} switched to forwarding mode", selfId.id());
        var decisionRelay = DecisionRelay.decisionRelay(selfId, delegateRouter);
        var mutationForwarder = MutationForwarder.mutationForwarder(selfId, delegateRouter);
        var workerBootstrap = WorkerBootstrap.workerBootstrap(selfId, delegateRouter, kvStore);
        var governorMesh = GovernorMesh.governorMesh(delegateRouter);
        var groupMembershipTracker = GroupMembershipTracker.groupMembershipTracker(selfId,
                                                                                   config.workerConfig()
                                                                                         .map(WorkerConfig::groupName)
                                                                                         .or(WorkerConfig.DEFAULT_GROUP_NAME),
                                                                                   config.workerConfig()
                                                                                         .map(WorkerConfig::maxGroupSize)
                                                                                         .or(WorkerConfig.DEFAULT_MAX_GROUP_SIZE));
        var workerDeploymentManager = WorkerDeploymentManager.workerDeploymentManager(selfId,
                                                                                      sliceStore,
                                                                                      mutationForwarder,
                                                                                      List.of(),
                                                                                      () -> communityId);
        var workerHlc = HlcClock.hlcClock(selfId);
        var workerTcpAddress = resolveSelfTcpAddress(config);
        // GAP 1.5 (announce → consensus): the announcer must apply through the forwarding
        // cluster node, NOT the raw RabiaNode. A worker is observation-only and cannot drive
        // consensus locally; forwardingClusterNode relays cluster.apply to a core peer (the
        // leader), so the GovernorAnnouncementKey Put reaches consensus instead of being a
        // dropped local apply.
        var governorAnnouncer = GovernorAnnouncer.governorAnnouncer(selfId,
                                                                    forwardingClusterNode,
                                                                    workerHlc,
                                                                    () -> communityId,
                                                                    () -> workerTcpAddress,
                                                                    () -> Epoch.ZERO);

        governorAnnouncer.start();
        // #642: publish the handle so AetherNode.stop() can cancel the re-announce tick. Without it a
        // stopped worker kept writing GovernorAnnouncementKey through consensus on its own behalf.
        governorAnnouncerHolder.set(governorAnnouncer);
        // GAP 2 (SWIM → governor): SWIM observations are edge-triggered, so on every edge we
        // re-read the full ALIVE set and hand the community-filtered slice to the announcer.
        // CommunityMembershipFilter scopes by the committed ActivationDirectiveValue.communityId
        // (kvStore), NOT SwimMember source-labels: labels are absent for gossip-learned members
        // and would silently drop community peers, whereas the committed directive is
        // authoritative consensus state. Source-scoping (one community per source) is the
        // single-community-per-source form; communityId-scoping is the refinement once the
        // growth comparator splits a source into multiple communities.
        swimHealthDetector.addObservationListener(observation -> announceCommunityMembership(governorAnnouncer,
                                                                                             swimHealthDetector,
                                                                                             kvStore,
                                                                                             communityId));
        log.info("Worker {} subsystems created, ready for SWIM-based community formation", selfId.id());
    }

    @SuppressWarnings({"JBCT-RET-01"})
    private static void announceCommunityMembership(GovernorAnnouncer governorAnnouncer,
                                                    CoreSwimHealthDetector swimHealthDetector,
                                                    KVStore<AetherKey, AetherValue> kvStore,
                                                    String communityId) {
        governorAnnouncer.onMembershipChange(CommunityMembershipFilter.communityAliveMembers(swimHealthDetector.aliveMembers(),
                                                                                             kvStore,
                                                                                             communityId));
    }

    private static String resolveSelfTcpAddress(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(info -> info.id()
                                         .equals(config.self()))
                     .map(info -> info.address()
                                      .host() + ":" + info.address()
                                                          .port())
                     .findFirst()
                     .orElse("");
    }

    @SuppressWarnings({"unchecked", "rawtypes", "JBCT-RET-01"})
    private static void handleForwardApplyRequest(ForwardApplyRequest request,
                                                  RabiaNode<KVCommand<AetherKey>> clusterNode) {
        clusterNode.apply((List) request.commands())
                   .onSuccess(results -> sendSuccessResponse(clusterNode,
                                                             request,
                                                             (List) results))
                   .onFailure(cause -> sendFailureResponse(clusterNode,
                                                           request,
                                                           (Cause) cause));
    }

    @SuppressWarnings({"rawtypes"})
    private static void sendSuccessResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            ForwardApplyRequest request,
                                            List<?> results) {
        var response = new ForwardApplyResponse<>(clusterNode.self(), request.correlationId(), results, Option.empty());

        clusterNode.network().send(request.sender(), response);
    }

    @SuppressWarnings({"rawtypes"})
    private static void sendFailureResponse(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            ForwardApplyRequest request,
                                            Cause cause) {
        var response = new ForwardApplyResponse<>(clusterNode.self(),
                                                  request.correlationId(),
                                                  List.of(),
                                                  Option.some(cause.message()));

        clusterNode.network().send(request.sender(), response);
    }

    /// Build the boot-time SWIM gossip encryptor. Delegates to the shared
    /// [SwimGossipEncryptors] factory so the running node and the boot-time self-address
    /// reflection probe ([org.pragmatica.aether.SelfAddressResolver]) encrypt gossip with
    /// byte-identical key material (a divergent encryptor would silently fail to decrypt the
    /// seed's `WhoAmI` reply).
    private static RotatingGossipEncryptor createGossipEncryptor(AetherNodeConfig config) {
        return SwimGossipEncryptors.fromCertificateProvider(config.certificateProvider());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Option<CertificateRenewalScheduler> createCertRenewalScheduler(AetherNodeConfig config,
                                                                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                  AppHttpServer appHttpServer,
                                                                                  Supplier<Option<ManagementServer>> managementServerSupplier) {
        return config.certificateProvider()
                     .flatMap(provider -> buildCertRenewalScheduler(config,
                                                                    provider,
                                                                    clusterNode,
                                                                    appHttpServer,
                                                                    managementServerSupplier));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Option<CertificateRenewalScheduler> buildCertRenewalScheduler(AetherNodeConfig config,
                                                                                 org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                 AppHttpServer appHttpServer,
                                                                                 Supplier<Option<ManagementServer>> managementServerSupplier) {
        var nodeId = config.self().id();
        var hostname = resolveHostname(config);

        return provider.issueCertificate(nodeId, hostname)
                       .map(bundle -> createSchedulerFromBundle(provider,
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
                                                                         Supplier<Option<ManagementServer>> managementServerSupplier) {
        return CertificateRenewalScheduler.certificateRenewalScheduler(provider,
                                                                       nodeId,
                                                                       hostname,
                                                                       newBundle -> onCertificateRenewed(newBundle,
                                                                                                         clusterNode,
                                                                                                         appHttpServer,
                                                                                                         managementServerSupplier),
                                                                       bundle.notAfter());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void onCertificateRenewed(CertificateBundle newBundle,
                                             RabiaNode<KVCommand<AetherKey>> clusterNode,
                                             AppHttpServer appHttpServer,
                                             Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        log.info("Certificate renewed, valid until {}", newBundle.notAfter());
        Result.all(QuicSslContextFactory.createServerFromBundle(newBundle,
                                                                ClientAuthPolicy.REQUIRED,
                                                                QuicTlsProvider.CLUSTER_PROTOCOL),
                   QuicSslContextFactory.createClientFromBundle(newBundle, QuicTlsProvider.CLUSTER_PROTOCOL))
              .id()
              .onSuccess(tuple -> triggerCertRotation(clusterNode,
                                                      tuple.first(),
                                                      tuple.last(),
                                                      newBundle,
                                                      appHttpServer,
                                                      managementServerSupplier))
              .onFailure(cause -> log.error("Failed to build SSL contexts from renewed certificate: {}",
                                            cause.message()));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void triggerCertRotation(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                            io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                            CertificateBundle newBundle,
                                            AppHttpServer appHttpServer,
                                            Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        rotateQuicNetwork(clusterNode, serverSsl, clientSsl, log);
        managementServerSupplier.get().onPresent(mgmt -> rotateManagementServer(mgmt, newBundle, log));
        rotateAppHttpServer(appHttpServer, newBundle, log);
    }

    /// Item 4 (activation): map the authoritative MembershipFsm desired-connection set into the transport's
    /// `Collection<NodeInfo>` dial contract. Each `PeerTarget` already carries the FSM-resolved dial address
    /// (`MemberDescriptor.fromNodeInfo` stored `NodeInfo.resolvedAddress()`), so `target.address()` is the
    /// exact address the transport must dial. The per-target descriptor supplies the role/source labels.
    private static Collection<NodeInfo> desiredDialTargets(MembershipFsm membershipFsm) {
        return membershipFsm.desiredConnections()
                            .stream()
                            .map(target -> dialNodeInfo(target,
                                                        membershipFsm.memberDescriptor(target.id())))
                            .toList();
    }

    /// Build the dial `NodeInfo` for a desired target. The 4-arg `NodeInfo.nodeInfo` factory defaults
    /// `resolvedAddress()` to the supplied `address`, and the transport's `connectPeer` dials
    /// `resolvedAddress()`; since `target.address()` IS the FSM-resolved address, the dialed address is
    /// correct. Role/source labels come from the per-member descriptor (empty map when none is known yet).
    private static NodeInfo dialNodeInfo(PeerTarget target, Option<MemberDescriptor> descriptor) {
        var labels = descriptor.map(d -> Map.of(NodeInfo.LABEL_ROLE,
                                                d.role(),
                                                NodeInfo.LABEL_SOURCE,
                                                d.source()))
                               .or(Map.of());

        return NodeInfo.nodeInfo(target.id(), target.address(), labels);
    }

    private static void rotateQuicNetwork(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                          io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                          io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                          Logger log) {
        if (clusterNode.network() instanceof QuicClusterNetwork quicNetwork) {
            quicNetwork.rotateCertificate(serverSsl, clientSsl)
                       .onSuccess(_ -> log.info("QUIC certificate rotation complete"))
                       .onFailure(cause -> log.error("QUIC certificate rotation failed: {}",
                                                     cause.message()));
        } else {
            log.warn("QUIC certificate rotation skipped: network is not QUIC-based");
        }
    }

    private static void rotateManagementServer(ManagementServer mgmt, CertificateBundle newBundle, Logger log) {
        mgmt.rotateCertificate(newBundle)
            .onSuccess(_ -> log.info("Management server certificate rotation complete"))
            .onFailure(cause -> log.error("Management server certificate rotation failed: {}",
                                          cause.message()));
    }

    private static void rotateAppHttpServer(AppHttpServer appHttpServer, CertificateBundle newBundle, Logger log) {
        appHttpServer.rotateCertificate(newBundle)
                     .onSuccess(_ -> log.info("App HTTP server certificate rotation complete"))
                     .onFailure(cause -> log.error("App HTTP server certificate rotation failed: {}",
                                                   cause.message()));
    }

    private static long resolveStreamMaxMemoryBytes() {
        return Option.option(System.getenv("STREAM_MAX_MEMORY_BYTES"))
                     .filter(s -> !s.isBlank())
                     .flatMap(s -> Result.lift(() -> Long.parseLong(s)).option())
                     .or(128 * 1024 * 1024L);
    }

    /// Resolve a long-valued config knob from an environment variable, falling back to `defaultValue`
    /// when unset, blank, or unparseable. Mirrors the [#resolveStreamMaxMemoryBytes] env-var pattern —
    /// the established node-boot config surface for stream/system operational settings.
    private static long resolveLongEnv(String name, long defaultValue) {
        return Option.option(System.getenv(name))
                     .filter(s -> !s.isBlank())
                     .flatMap(s -> Result.lift(() -> Long.parseLong(s.trim())).option())
                     .or(defaultValue);
    }

    /// `system:cluster-events:1.0.0` retention `maxCount` (OOM guard, count dimension).
    /// Default 10_000 — matches [#CLUSTER_EVENTS_MAX_RETAINED], the aggregator read window.
    /// Override: `CLUSTER_EVENTS_MAX_COUNT`.
    private static long resolveClusterEventsMaxCount() {
        return resolveLongEnv("CLUSTER_EVENTS_MAX_COUNT", CLUSTER_EVENTS_MAX_RETAINED);
    }

    /// `system:cluster-events:1.0.0` retention `maxBytes` — the byte hard-cap OOM guard for the
    /// off-heap partition store. Default 16MB. Cluster events are small JSON records, so 16MB retains
    /// many thousands of them while leaving the bulk of the per-node stream budget
    /// (`DEFAULT_MAX_TOTAL_BYTES`, 128MB) for app streams — the prior 64MB default reserved HALF the
    /// budget for one system stream and starved app-stream creation (STREAM_MEMORY_EXCEEDED).
    /// Override: `CLUSTER_EVENTS_MAX_BYTES`.
    private static long resolveClusterEventsMaxBytes() {
        return resolveLongEnv("CLUSTER_EVENTS_MAX_BYTES", 16L * 1024 * 1024);
    }

    /// `system:cluster-events:1.0.0` retention `maxAgeMs`. Default ~24h.
    /// Override: `CLUSTER_EVENTS_MAX_AGE_MS`.
    private static long resolveClusterEventsMaxAgeMs() {
        return resolveLongEnv("CLUSTER_EVENTS_MAX_AGE_MS", 24L * 60 * 60 * 1000);
    }

    /// `system:cluster-events:1.0.0` per-event size cap. Default ~64KB.
    /// Override: `CLUSTER_EVENTS_MAX_EVENT_SIZE_BYTES`.
    private static long resolveClusterEventsMaxEventSizeBytes() {
        return resolveLongEnv("CLUSTER_EVENTS_MAX_EVENT_SIZE_BYTES", 64L * 1024);
    }

    private static String resolveHostname(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(n -> n.id()
                                   .equals(config.self()))
                     .findFirst()
                     .map(n -> n.address()
                                .host())
                     .orElse("localhost");
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void handleRemotePutResponse(DHTNetwork dhtNetwork,
                                                AetherMaps aetherMaps,
                                                DHTMessage.PutRequest request,
                                                DHTMessage.PutResponse response) {
        dhtNetwork.send(request.sender(), response);
        if (response.success() && !response.superseded()) {
            aetherMaps.dispatchRemotePut(request.key(), request.value());
        }
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void handleRemoteRemoveResponse(DHTNetwork dhtNetwork,
                                                   AetherMaps aetherMaps,
                                                   DHTMessage.RemoveRequest request,
                                                   DHTMessage.RemoveResponse response) {
        dhtNetwork.send(request.sender(), response);
        if (response.found()) {
            aetherMaps.dispatchRemoteRemove(request.key());
        }
    }

    private static List<MessageRouter.Entry<?>> collectRouteEntries(KVStore<AetherKey, AetherValue> kvStore,
                                                                    NodeDeploymentManager nodeDeploymentManager,
                                                                    ClusterDeploymentManager clusterDeploymentManager,
                                                                    EndpointRegistry endpointRegistry,
                                                                    TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                                    StreamConsumerRegistry streamConsumerRegistry,
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
                                                                    ObservabilityConfigRegistry configRegistry,
                                                                    LogLevelRegistry logLevelRegistry,
                                                                    OwnershipEpochHighWater ownershipEpochHighWater,
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
                                                                    ConsumerGroupCoordinator consumerGroupCoordinator,
                                                                    ConsumerGroupRegistry consumerGroupRegistry,
                                                                    AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                                                    AtomicReference<Option<ManagementServer>> managementServerRef,
                                                                    NodeId self) {
        var entries = new ArrayList<MessageRouter.Entry<?>>();
        var kvRouterBuilder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
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
                                                  .onPut(AetherKey.ObservabilityConfigKey.class,
                                                         configRegistry::onObservabilityConfigPut)
                                                  .onRemove(AetherKey.ObservabilityConfigKey.class,
                                                            configRegistry::onObservabilityConfigRemove)
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
                                                  // #488: declarative stream-consumer declarations. Put/remove
                                                  // drives StreamConsumerManager's subscribe/unsubscribe.
                                                  .onPut(AetherKey.StreamRegistrationKey.class,
                                                         streamConsumerRegistry::onStreamRegistrationPut)
                                                  .onRemove(AetherKey.StreamRegistrationKey.class,
                                                            streamConsumerRegistry::onStreamRegistrationRemove)
                                                  .onPut(AetherKey.ScheduledTaskKey.class,
                                                         scheduledTaskRegistry::onScheduledTaskPut)
                                                  .onRemove(AetherKey.ScheduledTaskKey.class,
                                                            scheduledTaskRegistry::onScheduledTaskRemove)
                                                  .onPut(AetherKey.ScheduledTaskStateKey.class,
                                                         scheduledTaskStateRegistry::onStatePut)
                                                  .onRemove(AetherKey.ScheduledTaskStateKey.class,
                                                            scheduledTaskStateRegistry::onStateRemove)
                                                  // Membership v2: the NodeLifecycleKey atom is deleted. NodeDeploymentManager and
                                                  // ClusterDeploymentManager derive membership from MembershipDecision (routed near the
                                                  // generationSnapshotPublisher wiring above); there are no remaining NodeLifecycleKey consumers.
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
        // Stream-namespaces rebuild (Stage 4): the ClusterEventLogKey KV materialised-view
        // subscription is gone — cluster events now flow through the system:cluster-events:1.0.0
        // stream (publish via ClusterEventAggregator.emit, read via the stream consumer), not the
        // replicated KV log. The old `eventAggregator::onClusterEventLogPut` hook was removed.
        loadBalancerManager.onPresent(lbm -> kvRouterBuilder.onPut(AetherKey.NodeRoutesKey.class, lbm::onNodeRoutesPut)
                                                            .onRemove(AetherKey.NodeRoutesKey.class,
                                                                      lbm::onNodeRoutesRemove));
        dynamicConfigManager.onPresent(dcm -> kvRouterBuilder.onPut(AetherKey.ConfigKey.class, dcm::onConfigPut)
                                                             .onRemove(AetherKey.ConfigKey.class, dcm::onConfigRemove));
        kvRouterBuilder.onPut(AetherKey.ConsumerGroupKey.class, consumerGroupRegistry::onConsumerGroupPut);
        kvRouterBuilder.onRemove(AetherKey.ConsumerGroupKey.class, consumerGroupRegistry::onConsumerGroupRemove);
        // #345 1b: feed the per-ownership-domain epoch high-water table from committed EpochBearing puts.
        // No onRemove — the high-water is monotonic by definition, so ownership/governor removes are intentionally ignored.
        kvRouterBuilder.onPut(AetherKey.GovernorAnnouncementKey.class,
                              ownershipEpochHighWater::onGovernorAnnouncementPut);
        kvRouterBuilder.onPut(AetherKey.DhtPartitionOwnershipKey.class, ownershipEpochHighWater::onDhtOwnershipPut);
        kvRouterBuilder.onPut(AetherKey.StreamPartitionOwnershipKey.class,
                              ownershipEpochHighWater::onStreamPartitionOwnershipPut);
        entries.addAll(kvRouterBuilder.build().asRouteEntries());
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, nodeDeploymentManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, controlLoop::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, metricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                              deploymentMetricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, scheduledTaskManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, appHttpServer::onQuorumStateChange));
        // E2 Phase 2b (2026-05-28): the consensus-derived `ClusterStateNotification.DISAPPEARED`
        // signal is bridged directly into the §8.2 `DrainProcedure`. Rabia's `Paused` state
        // fires on the same DISAPPEARED signal — both legacy `onQuorumDisappeared` /
        // `onRabiaPaused` entry points collapse into the single `initiate(QUORUM_LOSS)` call
        // (single-shot CAS makes the duplicate harmless).
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                              notification -> routeQuorumDisappearedToDrain(notification,
                                                                                            quorumLossDetectorRef)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              consumerGroupCoordinator::onLeaderChange));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleCtmOnLeaderChange(change, clusterTopologyManager)));
        // #231 Step 2 — CDM (DEPLOYMENT) leader-pinned. LoadBalancer (DEPLOYMENT) MUST be registered
        // AFTER CDM since it reconciles routes CDM produces.
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleCdmOnLeaderChange(change, clusterDeploymentManager)));
        loadBalancerManager.onPresent(lbm -> entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                                                   change -> toggleLbOnLeaderChange(change,
                                                                                                                    lbm))));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleDeploymentMetricsOnLeaderChange(change,
                                                                                              deploymentMetricsScheduler)));
        // #231 Step 2 — SCALING (ControlLoop, TTMManager, RollbackManager), STRATEGIES (AbTestManager),
        // and DeploymentManager leader-pinned. ControlLoop did NOT previously receive LeaderChange
        // (only ClusterStateNotification + MembershipDecision), so this toggle is additive — no duplicate.
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleControlLoopOnLeaderChange(change, controlLoop)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleTtmOnLeaderChange(change, ttmManager)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleRollbackOnLeaderChange(change, rollbackManager)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleAbTestOnLeaderChange(change, abTestManager)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> toggleDeploymentManagerOnLeaderChange(change, deploymentManager)));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> rabiaMetricsCollector.updateRole(change.localNodeIsLeader(),
                                                                                         change.leaderId()
                                                                                               .map(NodeId::id))));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              scheduledTaskManager::onLeaderChange));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              rollbackManager::onAllInstancesFailed));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              clusterDeploymentManager::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              clusterDeploymentManager::onMembershipDecision));
        // #728: the non-core join channel. Workers never appear in MembershipDecision (the core
        // topology stream), so without this route assignNodeRole is unreachable for exactly the
        // nodes that need a community — they reach FSM Member and are never assigned a role.
        entries.add(MessageRouter.Entry.route(WorkerJoinDecision.class, clusterDeploymentManager::onWorkerJoin));
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
                                              metricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              metricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              metricsScheduler::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class, controlLoop::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class, controlLoop::onMembershipDecision));
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
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              appHttpServer::onNodeDecommissioned));
        // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                              appHttpServer::onSelfShutdown));
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
        // NODE_LEFT (graceful departures) is sourced from MembershipDecision. NODE_FAILED is NO LONGER
        // sourced here (#210) — it now rides the ungated FSM DEAD edge (membershipFsm.onConfirmedDeparture
        // → eventAggregator.onConfirmedDeparture, wired above) because the quorum-gated NodeRemoved
        // decision is dropped by the projector during post-kill churn. The NodeRemoved route stays for the
        // (now no-op) decision case; NodeDecommissioned still maps to NODE_LEFT.
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              eventAggregator::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              eventAggregator::onMembershipDecision));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, eventAggregator::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class, abTestManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              eventAggregator::onSliceFailure));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledUp.class, eventAggregator::onScaledUp));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledDown.class, eventAggregator::onScaledDown));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaleCapped.class, eventAggregator::onScaleCapped));
        entries.add(MessageRouter.Entry.route(ClusterDeploymentManager.ReconciliationAdjustment.class,
                                              eventAggregator::onReconciliationAdjustment));
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
        entries.add(MessageRouter.Entry.route(HttpForwardMessage.HttpForwardRequest.class,
                                              request -> demuxHttpForwardRequest(request,
                                                                                 appHttpServer,
                                                                                 managementServerRef.get())));
        entries.add(MessageRouter.Entry.route(HttpForwardMessage.HttpForwardResponse.class,
                                              response -> demuxHttpForwardResponse(response,
                                                                                   appHttpServer,
                                                                                   managementServerRef.get())));
        entries.add(MessageRouter.Entry.route(KVStoreLocalIO.Request.Find.class, kvStore::find));
        entries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                              notification -> handleLeaderCommit(notification, leaderManager)));
        loadBalancerManager.onPresent(lbm -> {
            entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class, lbm::onMembershipDecision));
            entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class, lbm::onMembershipDecision));
            entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class, lbm::onMembershipDecision));
            // Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown is not a cluster decision.
            entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                                  lbm::onSelfShutdown));
        });

        return entries;
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void demuxHttpForwardRequest(HttpForwardMessage.HttpForwardRequest request,
                                                AppHttpServer appHttpServer,
                                                Option<ManagementServer> managementServer) {
        if (request.pipeline() == HttpForwardMessage.Pipeline.MANAGEMENT) {
            managementServer.onPresent(ms -> ms.onHttpForwardRequest(request));
        } else {
            appHttpServer.onHttpForwardRequest(request);
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void demuxHttpForwardResponse(HttpForwardMessage.HttpForwardResponse response,
                                                 AppHttpServer appHttpServer,
                                                 Option<ManagementServer> managementServer) {
        if (response.pipeline() == HttpForwardMessage.Pipeline.MANAGEMENT) {
            managementServer.onPresent(ms -> ms.onHttpForwardResponse(response));
        } else {
            appHttpServer.onHttpForwardResponse(response);
        }
    }

    private static void handleLeaderCommit(KVStoreNotification.ValuePut<?, ?> notification,
                                           LeaderManager leaderManager) {
        if (notification.cause().key() instanceof LeaderKey) {
            var value = (LeaderValue) notification.cause().value();
            // H4 fence: carry the committed viewSequence so the election baseline advances —
            // this node's next proposal is fenced strictly above the committed sequence.
            leaderManager.onLeaderCommitted(value.leader(), value.viewSequence());
        }
    }

    /// Resolve the cluster-level #198 route-mount mode (§7) from the app-HTTP config. Path mode is
    /// the default; header mode carries the configured API-version header name into dispatch.
    private static RouteMountMode routeMountMode(AppHttpConfig appHttp) {
        return appHttp.apiVersioningDetection()
                      .isHeaderMode()
               ? RouteMountMode.headerMode(appHttp.apiVersionHeaderName())
               : RouteMountMode.pathMode();
    }

    private static SharedLibraryClassLoader createSharedLibraryLoader(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        return config.sliceAction()
                     .frameworkJarsPath()
                     .fold(() -> {
                               log.debug("No framework JARs path configured, using Application ClassLoader as parent");

                               return new SharedLibraryClassLoader(AetherNode.class.getClassLoader());
                           },
                           path -> FrameworkClassLoader.fromDirectory(path)
                                                       .onFailure(cause -> log.warn("Failed to create FrameworkClassLoader from {}: {}. "
                                                                                   + "Falling back to Application ClassLoader.",
                                                                                    path,
                                                                                    cause.message()))
                                                       .map(loader -> {
                                                                log.info("Using FrameworkClassLoader with {} JARs as parent",
                                                                         loader.getLoadedJars().size());

                                                                return new SharedLibraryClassLoader(loader);
                                                            })
                                                       .or(new SharedLibraryClassLoader(AetherNode.class.getClassLoader())));
    }

    record ResourceProviderSetup(ResourceProviderFacade facade,
                                 Option<DynamicConfigurationProvider> dynamicProvider,
                                 Option<ConfigurationProvider> nodeComposite,
                                 Option<SpiResourceProvider> spiProvider,
                                 Option<Fn1<Promise<String>, String>> secretResolver) {}

    private static ResourceProviderSetup createResourceProviderFacade(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        return config.configProvider()
                     .fold(() -> {
                               log.debug("No configuration provider configured, resource provisioning disabled");

                               return new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                Option.empty(),
                                                                Option.empty(),
                                                                Option.empty(),
                                                                Option.empty());
                           },
                           configProvider -> {
                               log.info("Creating ConfigService and ResourceProvider from configuration provider");
                               // Captured once and reused both for node.toml's own resolution below AND
                               // threaded down to SliceStore for the slice-intrinsic resources.toml layer
                               // (#269) — the SAME environment-secrets capability, not a second one. This
                               // stays valid even if node.toml's OWN secret fails to resolve below: that
                               // failure is about one node.toml key, not about the SecretsProvider itself
                               // being broken, so slice-level resolution is independent of it.
                               var secretsProvider = config.environment()
                                                           .flatMap(EnvironmentIntegration::secrets);
                               var secretResolver = secretsProvider.map(sp -> (Fn1<Promise<String>, String>) sp::resolveSecret);
                               var resolvedProvider = secretsProvider.fold(() -> Result.success(configProvider),
                                                                           sp -> ConfigurationProvider.withSecretResolution(configProvider,
                                                                                                                            sp::resolveSecret));

                               return resolvedProvider.fold(cause -> {
                                                                log.error("Failed to resolve secrets in configuration: {}",
                                                                          cause.message());

                                                                return new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                                                 Option.empty(),
                                                                                                 Option.empty(),
                                                                                                 Option.empty(),
                                                                                                 secretResolver);
                                                            },
                                                            provider -> {
                                                                // KV-overlay layer: holds operator config puts only.
                                                                // Base is empty — the merged view is composed below via
                                                                // LayeredConfigProvider, so the dynamic overlay sees no
                                                                // node.toml fall-through and DynamicConfigManager can
                                                                // operate on the overlay in isolation (Batch 1 of the
                                                                // hierarchical config refactor).
                                                                var emptyBase = ConfigurationProvider.builder().build();
                                                                var dynamicProvider = DynamicConfigurationProvider.dynamicConfigurationProvider(emptyBase);
                                                                // node-composite = KV-overlay ⊕ node.toml
                                                                var kvLayer = NamedConfigProvider.namedConfigProvider("KV",
                                                                                                                      dynamicProvider);
                                                                var nodeTomlLayer = NamedConfigProvider.namedConfigProvider("node.toml",
                                                                                                                            provider);
                                                                ConfigurationProvider nodeComposite = LayeredConfigProvider.layered(List.of(kvLayer,
                                                                                                                                            nodeTomlLayer));
                                                                var configService = ProviderBasedConfigService.providerBasedConfigService(nodeComposite);

                                                                ConfigService.setInstance(configService);
                                                                var resourceProvider = SpiResourceProvider.spiResourceProvider();

                                                                ResourceProvider.setInstance(resourceProvider);
                                                                log.info("ConfigService and ResourceProvider initialized with hierarchical composite (KV-overlay + node.toml)");

                                                                return new ResourceProviderSetup(new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                                                                                                     return resourceProvider.provide(resourceType,
                                                                                                                                     configSection);
                                                                                                 }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                                                                                                     return resourceProvider.provide(resourceType,
                                                                                                                                     configSection,
                                                                                                                                     context);
                                                                                                 }
        },
                                                                                                 Option.some(dynamicProvider),
                                                                                                 Option.some(nodeComposite),
                                                                                                 Option.some(resourceProvider),
                                                                                                 secretResolver);
                                                            });
                           });
    }

    private static ResourceProviderFacade noOpResourceProviderFacade() {
        return new ResourceProviderFacade() {
            private static final Cause NOT_CONFIGURED = Causes.cause("Resource provisioning not configured. Use AetherNodeConfig.withConfigProvider() to enable.");

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return NOT_CONFIGURED.promise();
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return NOT_CONFIGURED.promise();
            }
        };
    }

    private static Repository compositeRepository(List<Repository> repositories) {
        if (repositories.isEmpty()) {
            return artifact -> Causes.cause("No repositories configured").promise();
        }

        return repositories.getFirst();
    }

    private static void registerRuntimeExtensions(SpiResourceProvider spi,
                                                  TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                  SliceInvoker sliceInvoker,
                                                  DHTClient cacheDhtClient,
                                                  StorageInstance contentStorage) {
        spi.registerExtension(TopicSubscriptionRegistry.class, topicSubscriptionRegistry);
        spi.registerExtension(SliceInvoker.class, sliceInvoker);
        spi.registerExtension(DHTClient.class, cacheDhtClient);
        // #251 (#99 regression): ContentStoreFactory.provision() requires a StorageInstance extension.
        // Register a tiered content store so slice-facing ContentStore resources can provision.
        spi.registerExtension(StorageInstance.class, contentStorage);
    }

    /// A6 cold-boot convergence window: how long after THIS node's `start()` the SWIM cold-boot
    /// FAULTY-suppression stays active even after the cluster phase has flipped out of COLD_BOOT
    /// (which happens at first quorum, e.g. 3/5). Sized to cover the transport single-dialer
    /// force-dial grace (`QuicClusterNetwork.RECONCILE_BACKOFF_CAP_MS` = 60s) + one reconcile tick
    /// (~5s) + a suspect margin (~10s), so a straggler whose QUIC link forms only via the 60s
    /// force-dial is not FAULTY-evicted before it can connect on a simultaneous full-cluster restart.
    ///
    /// The anchor is the START instant, not the assembly instant (#642): the window has to cover the
    /// node's first moments of RUNNING life, which is when SWIM is converging. Anchored at assembly it
    /// could be fully expired before SWIM ever ran.
    long COLD_BOOT_CONVERGENCE_WINDOW_MS = 75_000L;

    /// Pure predicate for the SWIM cold-boot suppression gate (A6): cold-boot convergence is still
    /// active iff the cluster phase is COLD_BOOT, OR this node started within the convergence window.
    /// Extracted as a static pure function so the window logic is unit-testable without standing up a
    /// node. See `swimIsBootingSupplier` wiring for the rationale.
    static boolean coldBootConvergenceActive(boolean phaseIsColdBoot, long bootAtMs, long nowMs, long windowMs) {
        return phaseIsColdBoot || (nowMs - bootAtMs) < windowMs;
    }

    private static StreamForwardTransport createStreamForwardTransport(ClusterNetwork network) {
        return network::send;
    }

    private static void registerStreamForwardExtensions(ResourceProviderSetup resourceProviderSetup,
                                                        StreamForwardClient forwardClient,
                                                        Fn1<Result<NodeId>, TaskGroup> ownerResolver,
                                                        Fn2<Option<NodeId>, String, Integer> partitionOwnerResolver,
                                                        StreamPartitionManager streamPartitionManager,
                                                        CursorStore streamCursorStore,
                                                        TieredStreamReader streamTieredReader,
                                                        Serializer serializer,
                                                        Deserializer deserializer,
                                                        NodeId self,
                                                        ReplicaRegistry streamReplicaRegistry,
                                                        CommittedStreamOwnerSource committedStreamOwnerSource,
                                                        OwnershipEpochHighWater ownershipEpochHighWater,
                                                        Option<LinearizableBarrier> linearizableBarrier) {
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerForwardExtensionsOnSpi(spi,
                                                                              forwardClient,
                                                                              ownerResolver,
                                                                              partitionOwnerResolver,
                                                                              streamPartitionManager,
                                                                              streamCursorStore,
                                                                              streamTieredReader,
                                                                              serializer,
                                                                              deserializer,
                                                                              self,
                                                                              streamReplicaRegistry,
                                                                              committedStreamOwnerSource,
                                                                              ownershipEpochHighWater,
                                                                              linearizableBarrier));
    }

    private static void registerForwardExtensionsOnSpi(SpiResourceProvider spi,
                                                       StreamForwardClient forwardClient,
                                                       Fn1<Result<NodeId>, TaskGroup> ownerResolver,
                                                       Fn2<Option<NodeId>, String, Integer> partitionOwnerResolver,
                                                       StreamPartitionManager streamPartitionManager,
                                                       CursorStore streamCursorStore,
                                                       TieredStreamReader streamTieredReader,
                                                       Serializer serializer,
                                                       Deserializer deserializer,
                                                       NodeId self,
                                                       ReplicaRegistry streamReplicaRegistry,
                                                       CommittedStreamOwnerSource committedStreamOwnerSource,
                                                       OwnershipEpochHighWater ownershipEpochHighWater,
                                                       Option<LinearizableBarrier> linearizableBarrier) {
        spi.registerExtension(StreamForwardClient.class, forwardClient);
        spi.registerExtension(StreamPartitionManager.class, streamPartitionManager);
        spi.registerExtension(CursorStore.class, streamCursorStore);
        spi.registerExtension(TieredStreamReader.class, streamTieredReader);
        spi.registerExtension(Serializer.class, serializer);
        spi.registerExtension(Deserializer.class, deserializer);
        // A6: self identity so the app StreamAccessFactory can wire owner-routed publish (compare the
        // resolved HRW owner against this node).
        spi.registerExtension(NodeId.class, self);
        // A6 read-forwarding: expose the node-level replica registry so the app StreamAccessFactory wires
        // NEAREST app-stream reads — a non-replica consumer forwards to the partition's HRW owner instead
        // of reading its own empty local partition (the asymmetry that left GOVERNOR-default reads empty).
        spi.registerExtension(ReplicaRegistry.class, streamReplicaRegistry);
        // #47: the STREAMING publish resolver carries BOTH the arg-less leader resolver (retained for
        // the STRONG / forward-fallback path) AND the (stream, partition)-aware HRW owner-resolver from
        // the ReplicaSetController. App-stream EVENTUAL publishes now route to the HRW owner — the SAME
        // placement authority that owns the replica set — instead of the consensus leader. The general
        // taskGroupOwnerResolver (leader) is unchanged; only the STREAMING publish path is repointed.
        spi.registerExtension(StreamPublisherFactory.GovernorResolver.class,
                              new StreamPublisherFactory.GovernorResolver(() -> ownerResolver.apply(TaskGroup.STREAMING)
                                                                                             .option(),
                                                                          Option.some(partitionOwnerResolver)));
        // #345 item 1e-c: expose the LINEARIZABLE pipeline components so the app StreamAccessFactory
        // wires a typed LINEARIZABLE read through the SAME committed-owner routing + owner-side
        // fence/round/catch-up pipeline as the raw StreamReadRouter path (1e-a). The barrier is present
        // only when [durable-entity] read-linearization is NO_OP_ROUND; when absent the typed
        // LINEARIZABLE arm degrades to the replica-routed read exactly as the raw path does.
        spi.registerExtension(CommittedStreamOwnerSource.class, committedStreamOwnerSource);
        spi.registerExtension(OwnershipEpochHighWater.class, ownershipEpochHighWater);
        linearizableBarrier.onPresent(barrier -> spi.registerExtension(LinearizableBarrier.class, barrier));
    }

    /// #345 I1(d): expose the durable-entity fence collaborators on the SAME node-wide SPI extension
    /// channel the stream side uses, so `DurableEntityFactory` can build a `PartitionFencedDurableEntity`
    /// — per-`(keyspace, partition)` write fence AND owner-routed linearizable reads — instead of the
    /// unfenced in-process map it returned before this landed.
    ///
    /// `NodeId`, `Serializer`, `Deserializer` and `OwnershipEpochHighWater` are already registered by
    /// [#registerForwardExtensionsOnSpi]; only the three entity-specific collaborators are added here. The
    /// entity's `EntityPartitionArc` is deliberately NOT one of them: its partition count is per-keyspace
    /// config, so the factory builds it inside `provision`.
    private static void registerEntityExtensions(ResourceProviderSetup resourceProviderSetup,
                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                 OwnershipEpochHighWater ownershipEpochHighWater,
                                                 EntityKeyspaceRegistrar entityKeyspaceRegistrar,
                                                 Option<EntityLinearizableBarrier> entityBarrier,
                                                 EntityLogSubstrate entityLogSubstrate,
                                                 EntityCheckpointDriver entityCheckpointDriver,
                                                 EntityTimerDriver entityTimerDriver,
                                                 EntityForwardService entityForwardService) {
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerEntityExtensionsOnSpi(spi,
                                                                             kvStore,
                                                                             ownershipEpochHighWater,
                                                                             entityKeyspaceRegistrar,
                                                                             entityBarrier,
                                                                             entityLogSubstrate,
                                                                             entityCheckpointDriver,
                                                                             entityTimerDriver,
                                                                             entityForwardService));
    }

    private static void registerEntityExtensionsOnSpi(SpiResourceProvider spi,
                                                      KVStore<AetherKey, AetherValue> kvStore,
                                                      OwnershipEpochHighWater ownershipEpochHighWater,
                                                      EntityKeyspaceRegistrar entityKeyspaceRegistrar,
                                                      Option<EntityLinearizableBarrier> entityBarrier,
                                                      EntityLogSubstrate entityLogSubstrate,
                                                      EntityCheckpointDriver entityCheckpointDriver,
                                                      EntityTimerDriver entityTimerDriver,
                                                      EntityForwardService entityForwardService) {
        // The entity's DURABLE backing (#345 I3). Entity state is the fold of a fenced, fsync-durable,
        // replicated log — one stream named entity:<keyspace> per keyspace — so it now survives both a
        // restart of its owner and the loss of that owner. This replaces the MemoryStorageEngine that
        // backed the entity through I1: that engine was HA-only, its contents died with the process, and
        // the epoch fence that sat on it has moved to the log's own append gate over the SAME arc.
        spi.registerExtension(EntityLogSubstrate.class, entityLogSubstrate);
        // One committed-ownership lookup drives BOTH entity halves: admission reads `owner` from it to
        // decide who may write, and the LINEARIZABLE read path routes on the same record, so a write
        // admission and a read route can never come from different records. Reads the same Rabia-backed
        // StreamPartitionOwnershipValue family the stream side uses — the entity keyspace reuses those
        // arcs rather than minting a record type.
        spi.registerExtension(CommittedPartitionOwnerSource.class,
                              KvCommittedPartitionOwnerSource.kvCommittedPartitionOwnerSource(kvStore));
        // Absent barrier is NOT fatal: BOUNDED_STALE reads still work and a LINEARIZABLE read is refused
        // per-read with EntityError.LinearizableUnavailable (#345 I1 owner ruling — a missing barrier
        // costs freshness, a missing fence costs safety, so only the latter refuses the whole resource).
        entityBarrier.onPresent(barrier -> spi.registerExtension(EntityLinearizableBarrier.class, barrier));
        // The keyspace declaration seam. Mandatory, like the fence collaborators: without it a provisioned
        // entity would never reach the leader's ownership reconcile, so every write would refuse forever
        // with the transient cause — a permanent outage wearing a "retry me" message. Loud at deploy beats
        // that.
        spi.registerExtension(EntityKeyspaceRegistrar.class, entityKeyspaceRegistrar);
        // The checkpoint driver. Optional at the factory (an absent driver costs boundedness, not
        // correctness) but ALWAYS registered here — without it no entity log is ever reclaimed and the
        // retention floor pins every segment forever.
        spi.registerExtension(EntityCheckpointDriver.class, entityCheckpointDriver);
        // The timer driver, registered on the same terms and for the same reason: the factory treats an
        // absent driver as a TIMELINESS cost rather than a refusal, so registering it here is what makes
        // that absence an assembly bug rather than a supported mode.
        spi.registerExtension(EntityTimerDriver.class, entityTimerDriver);
        // Owner-forwarding (#596), both halves of the same service: the SENDER a non-owner uses to reach
        // the committed owner, and the REGISTRY an arriving command is applied through. Registered
        // together because a node that can send but not receive would forward into silence.
        spi.registerExtension(EntityOwnerForward.class, entityForwardService);
        spi.registerExtension(EntityForwardRegistry.class, entityForwardService);
    }

    /// How far a stream's sealed log may be reclaimed without destroying state something still needs
    /// (#345 I3).
    ///
    /// Non-entity streams get [Long#MAX_VALUE] — no floor, so the age policy alone decides and retention
    /// behaves exactly as it did before this existed.
    ///
    /// An `entity:<keyspace>` partition instead reports its COMMITTED checkpoint's `throughOffset`.
    /// Everything at or below that is already folded into a durable snapshot and is genuinely garbage;
    /// everything above it is still the only copy of a mutation. Without this the enforcer — which is
    /// built once per node with a single age policy and never reads per-stream config — would delete an
    /// entity's segments a fixed interval after its last write, silently destroying the state of any key
    /// not recently touched.
    ///
    /// A keyspace with NO committed checkpoint reclaims nothing (`-1`): nothing has been folded anywhere
    /// yet, so every record is still load-bearing. The log grows until the first checkpoint, which is the
    /// correct trade — bounded growth is the checkpoint's job, and until one exists the only safe answer
    /// is to keep everything.
    private static long entityRetentionFloor(KVStore<AetherKey, AetherValue> kvStore, String stream, int partition) {
        if (!stream.startsWith(EntityPartitionArc.ARC_PREFIX)) {
            return Long.MAX_VALUE;
        }

        var keyspace = stream.substring(EntityPartitionArc.ARC_PREFIX.length());

        return kvStore.getTyped(EntityCheckpointKey.entityCheckpointKey(keyspace, partition),
                                EntityFoldCheckpointValue.class)
                      .map(EntityFoldCheckpointValue::throughOffset)
                      .or(-1L);
    }

    /// Project the aggregator's materialised `ClusterEvent` view onto
    /// `InvocationTraceStore.ClusterTraceEvent` records — filters by `TRACE_INJECTED` and
    /// extracts the metadata stamped by `InvocationTraceStore.publishInjectionToClusterLog`.
    /// Lives here (not in aether-invoke) because `ClusterEvent` is owned by aether/node.
    private static List<InvocationTraceStore.ClusterTraceEvent> projectClusterTraceInjections(List<ClusterEvent> events) {
        var list = new ArrayList<InvocationTraceStore.ClusterTraceEvent>();

        for (var event : events) {
            if (! (event instanceof ClusterEvent.TraceInjected)) {
                continue;
            }

            var details = event.details();
            var requestId = details.get("requestId");

            if (requestId == null) {
                continue;
            }

            var operation = details.getOrDefault("operation", "");
            var durationMs = parseLongDetail(details.get("durationMs"), 0L);
            var depth = (int) parseLongDetail(details.get("depth"), 0L);
            var timestamp = parseLongDetail(details.get("timestamp"), 0L);
            var nodeId = event.sourceNode().id();

            list.add(new InvocationTraceStore.ClusterTraceEvent(requestId,
                                                                operation,
                                                                durationMs,
                                                                depth,
                                                                timestamp,
                                                                nodeId));
        }

        return list;
    }

    // RET-06: `raw` is a nullable detail-map value; the null coalesce to a default is parse-boundary handling.
    @SuppressWarnings("JBCT-RET-06")
    private static long parseLongDetail(String raw, long defaultValue) {
        if (raw == null) {
            return defaultValue;
        }

        return org.pragmatica.lang.parse.Number.parseLong(raw)
                                               .or(defaultValue);
    }
}
