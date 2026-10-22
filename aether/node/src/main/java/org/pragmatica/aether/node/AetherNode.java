// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.AllInstancesFailedDetector;
import org.pragmatica.aether.controller.RollbackEvent;
import org.pragmatica.aether.controller.RollbackManager;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService;
import org.pragmatica.aether.node.backup.BackupGenesis;
import org.pragmatica.aether.node.backup.BackupPreflight;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator;
import org.pragmatica.aether.node.backup.BackupWarning;
import org.pragmatica.aether.node.backup.GitBackupRepository;
import org.pragmatica.aether.node.backup.KvBackupService;
import org.pragmatica.aether.node.backup.RestoreGate;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.node.prefetch.PrefetchGossipStats;
import org.pragmatica.aether.node.prefetch.StoragePrefetchChannel;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaProjector;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumCoConfirmation;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.deployment.schema.SchemaPolicy;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.dht.HighWaterOwnerEpochGate;
import org.pragmatica.aether.dht.KvCommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.KvOwnerEpochSource;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.SecurityOverrideSynchronizer;
import org.pragmatica.aether.http.forward.HttpForwardMessage;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.node.entityforward.CommittedEntityStake;
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
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.storage.DelegatedStorageAdapter;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.KvStreamOwnerEpochSource;
import org.pragmatica.aether.stream.KvCommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.LinearizableBarrier;
import org.pragmatica.aether.stream.DurableSealedOffsetSource;
import org.pragmatica.aether.stream.LinearizableOwnerServe;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.OwnerPeerReads;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.node.projection.CommitWitness;
import org.pragmatica.aether.node.projection.PartitionBounds;
import org.pragmatica.aether.node.projection.ProjectionAwareCursorStore;
import org.pragmatica.aether.node.projection.ProjectionNodeSupport;
import org.pragmatica.aether.node.projection.ProjectionRegistry;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter;
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
import org.pragmatica.aether.stream.replication.IsrMonitor;
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
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.replication.AlignedRecovery;
import org.pragmatica.aether.stream.replication.WatermarkTracker;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.aether.stream.segment.RefDurability;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.StreamFootprint;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.repository.CompositeRepository;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.ttm.AdaptiveDecisionTree;
import org.pragmatica.aether.ttm.TTMManager;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.aether.worker.isolation.CoreAbsenceDetector;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.SpokesmanPingLoop;
import org.pragmatica.aether.worker.metrics.WorkerMetricsAggregator;
import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.BuildInfo;
import org.pragmatica.aether.config.ReadLinearizationMode;
import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.cluster.RollbackPolicyParser;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage;
import org.pragmatica.cluster.metrics.ClusterSyncMessage;
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
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.ObserverResumedNotification;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.dht.LoggingResolveFallbackObserver;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.aether.node.assembly.DrainAssembly;
import org.pragmatica.aether.node.assembly.SwimAssembly;
import org.pragmatica.aether.node.assembly.StorageAssembly;
import org.pragmatica.aether.node.assembly.TopologyAssembly;
import org.pragmatica.aether.node.assembly.ManagementAssembly;
import org.pragmatica.aether.node.assembly.SliceRuntimeAssembly;
import org.pragmatica.aether.node.assembly.ClusterEventsAssembly;
import org.pragmatica.aether.node.assembly.MembershipAssembly;
import org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.swim.RotatingGossipEncryptor;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.TransportObservation;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface AetherNode extends ManageableNode {
    Logger LOG = LoggerFactory.getLogger(AetherNode.class);
    /// How often a node evicts idle operator-warning throttle keys (#1617 R3): once per throttle window.
    TimeSpan OPERATOR_WARNING_EVICTION_INTERVAL = TimeSpan.timeSpan(60).seconds();
    /// Read-window upper bound for `events()` on the `system:cluster-events:1.0.0` stream
    /// (B5b — replicated partition transport). The stream's actual retention `maxCount` is now
    /// config-overridable (see [ClusterEventsLimits]); this constant is the read window the
    /// aggregator requests and must stay >= that maxCount so a single fetch covers the full retained
    /// window (the B5a/#239 fix). The default retention maxCount equals this value.
    long CLUSTER_EVENTS_MAX_RETAINED = org.pragmatica.aether.api.ClusterEventAggregator.MAX_RETAINED_EVENTS;
    /// How often a node re-sends cluster events whose publish has not landed yet (#1640).
    TimeSpan CLUSTER_EVENT_REDELIVERY_INTERVAL = TimeSpan.timeSpan(1).seconds();
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
    StreamPartitionManager streamPartitionManager();
    StreamReadRouter streamReadRouter();
    ConsumerGroupCoordinator consumerGroupCoordinator();
    ConsumerGroupRegistry consumerGroupRegistry();
    StreamNamespacesService streamNamespacesService();
    /// #1543 E: begin, inspect and settle node replacements (the leader drives the phases).
    NodeReplacementService nodeReplacementService();
    /// #1543 F: start, inspect, pause, resume and abort the rolling upgrade (the leader drives it, one replacement at a time).
    UpgradeRunService upgradeRunService();
    Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver();
    Map<String, StorageFactory.StorageSetup> storageSetups();
    Option<CertificateRenewalScheduler> certRenewalScheduler();
    int connectedNodeCount();
    Map<String, Number> transportMetrics();

    default Map<String, Long> metadataResourceMetrics() {
        return Map.of();
    }

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

    /// Test-only per-peer network partition (#1730): drop QUIC messages from, and SWIM datagrams to and from,
    /// `unreachable`. Applied on both sides of a cut it is a symmetric partition; an empty set heals. Unlike
    /// [#blackhole(boolean)] it cuts only the named peers, so a minority keeps talking among itself.
    @Contract
    default void partitionFrom(java.util.Set<NodeId> unreachable) {}

    /// Additive test fault injection; production authenticated-message policy remains enforced.
    default Unit setInboundFaultFilter(java.util.function.BiPredicate<NodeId, Message.Wired> filter) {
        return Unit.unit();
    }

    /// Process exit code after a completed drain (`DrainProcedure`) and a failed SWIM start.
    int EXIT_DRAINED = 2;
    /// Process exit code when the cluster refused this process's identity (#1558, terminal removal): its
    /// NodeId belongs to a retired or different process, so it can never be admitted. Distinct from
    /// [#EXIT_DRAINED] so orchestration and the CTM can tell "refused — replace with a fresh NodeId" from
    /// "drained" by exit status alone. 78 is `EX_CONFIG` in sysexits(3): the node's configured identity is
    /// unusable, and restarting it unchanged cannot succeed.
    int EXIT_IDENTITY_REFUSED = 78;
    /// Process exit code when this node's OWN persisted consensus history could not be restored and the
    /// node stayed fenced in `JOINING` for the whole bound (#1468, [OwnRestoreWatchdog]). 74 is `EX_IOERR` in
    /// sysexits(3): the node's snapshot could not be read. Restarting it unchanged reads the same snapshot, so
    /// a supervisor should not (`RestartPreventExitStatus=74`); the node never started with partial state.
    int EXIT_CONSENSUS_RESTORE_FAILED = 74;
    /// Process exit code when this node restored (or adopted) consensus state but could not persist it, and stayed
    /// fenced in `JOINING` for the whole bound (#1516, [OwnRestoreWatchdog]). 75 is `EX_TEMPFAIL` in sysexits(3):
    /// the failure is the node's storage, not its snapshot, so unlike 74 a restart after the storage fault is fixed
    /// can succeed (the node re-syncs); a restart on the same fault stops the same way.
    int EXIT_CONSENSUS_STATE_PERSIST_FAILED = 75;
    /// Process exit code when the SWIM gossip-key divergence gate refuses the boot (#683): gossip arrives
    /// under a key epoch this node does not hold and none has ever decrypted. Production reaches it through
    /// `System.exit` so the node's shutdown hooks run ([GossipKeyDivergenceGuard] measured that path); an
    /// in-JVM host records it and stops only that node (#1312).
    int EXIT_GOSSIP_KEY_DIVERGED = 1;

    static Result<AetherNode> aetherNode(AetherNodeConfig config) {
        return aetherNode(config,
                          MessageRouter.DelegateRouter.delegate(),
                          NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
    }

    /// `processExit` receives the node's exit code — [#EXIT_DRAINED] after a completed drain,
    /// [#EXIT_IDENTITY_REFUSED] when the cluster refuses this process's identity, [#EXIT_GOSSIP_KEY_DIVERGED]
    /// when the gossip-key divergence gate refuses the boot. An in-JVM host records it and stops the node;
    /// production enters through [#aetherNode(AetherNodeConfig)] instead.
    static Result<AetherNode> aetherNode(AetherNodeConfig config, IntConsumer processExit) {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var nodeCodec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

        return aetherNode(config,
                          delegateRouter,
                          nodeCodec,
                          () -> processExit.accept(EXIT_DRAINED),
                          () -> processExit.accept(EXIT_IDENTITY_REFUSED),
                          () -> processExit.accept(EXIT_GOSSIP_KEY_DIVERGED));
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
                          () -> Runtime.getRuntime().halt(EXIT_DRAINED),
                          () -> Runtime.getRuntime().halt(EXIT_IDENTITY_REFUSED),
                          () -> System.exit(EXIT_GOSSIP_KEY_DIVERGED));
    }

    /// In-JVM hosts: the one `jvmExit` hook serves the drain exit, an identity refusal and a gossip-key
    /// divergence refusal.
    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec,
                                         Runnable jvmExit) {
        return aetherNode(config, delegateRouter, nodeCodec, jvmExit, jvmExit, jvmExit);
    }

    /// `identityRefusedExit` runs when the cluster refuses this process's identity (production:
    /// `halt(EXIT_IDENTITY_REFUSED)`); `jvmExit` runs after a completed drain (production: `halt(EXIT_DRAINED)`);
    /// `gossipKeyDivergedExit` runs when the gossip-key divergence gate refuses the boot (production:
    /// `System.exit(EXIT_GOSSIP_KEY_DIVERGED)`, #1312).
    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec,
                                         Runnable jvmExit,
                                         Runnable identityRefusedExit,
                                         Runnable gossipKeyDivergedExit) {
        return aetherNode(config,
                          delegateRouter,
                          nodeCodec,
                          jvmExit,
                          identityRefusedExit,
                          gossipKeyDivergedExit,
                          variable -> Option.option(System.getenv(variable)));
    }

    /// The same boot with the process environment supplied as a function. Production passes `System.getenv`
    /// (the overload above); a boot test passes a map, so an environment-driven refusal such as
    /// `CLUSTER_EVENTS_MAX_COUNT` (#1571) can be exercised in-process without mutating the JVM's environment.
    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec,
                                         Runnable jvmExit,
                                         Runnable identityRefusedExit,
                                         Runnable gossipKeyDivergedExit,
                                         Fn1<Option<String>, String> environment) {
        return aetherNode(config,
                          delegateRouter,
                          nodeCodec,
                          jvmExit,
                          identityRefusedExit,
                          gossipKeyDivergedExit,
                          environment,
                          BackupPreflight::requireGit);
    }

    /// The same boot with the `[backup]` git probe supplied (#2007): production passes [BackupPreflight#requireGit]; a boot
    /// test passes a stand-in for a git that is missing, hangs or is fine, and counts whether it was asked.
    static Result<AetherNode> aetherNode(AetherNodeConfig config,
                                         MessageRouter.DelegateRouter delegateRouter,
                                         SliceCodec nodeCodec,
                                         Runnable jvmExit,
                                         Runnable identityRefusedExit,
                                         Runnable gossipKeyDivergedExit,
                                         Fn1<Option<String>, String> environment,
                                         Supplier<Result<Unit>> gitProbe) {
        return config.validate()
                     .flatMap(_ -> createNode(config,
                                              delegateRouter,
                                              nodeCodec,
                                              jvmExit,
                                              identityRefusedExit,
                                              gossipKeyDivergedExit,
                                              environment,
                                              gitProbe));
    }

    /// #1549: the `CLUSTER_EVENTS_MAX_*` overrides are checked before anything is built — an out-of-range
    /// value refuses the boot ([ClusterEventsLimits.InvalidLimit]) instead of reaching the stream engine.
    private static Result<AetherNode> createNode(AetherNodeConfig config,
                                                 MessageRouter.DelegateRouter delegateRouter,
                                                 SliceCodec nodeCodec,
                                                 Runnable jvmExit,
                                                 Runnable identityRefusedExit,
                                                 Runnable gossipKeyDivergedExit,
                                                 Fn1<Option<String>, String> environment,
                                                 Supplier<Result<Unit>> gitProbe) {
        return requireBackupGit(config, gitProbe).flatMap(_ -> ClusterEventsLimits.clusterEventsLimits(environment))
                               .flatMap(limits -> createNodeWithBootToken(config,
                                                                          delegateRouter,
                                                                          nodeCodec,
                                                                          jvmExit,
                                                                          identityRefusedExit,
                                                                          gossipKeyDivergedExit,
                                                                          BootToken.bootToken(),
                                                                          limits));
    }

    /// #2007: `[backup]` shells out to git, so a node that has it enabled and cannot run git refuses to boot here, naming `[backup]` and
    /// git, instead of leaving the restore BLOCKED and cluster-state writes refused after the cluster is up.
    private static Result<Unit> requireBackupGit(AetherNodeConfig config, Supplier<Result<Unit>> gitProbe) {
        return enabledBackup(config).fold(() -> Result.success(Unit.unit()),
                                          _ -> gitProbe.get());
    }

    private static Result<AetherNode> createNodeWithBootToken(AetherNodeConfig config,
                                                              MessageRouter.DelegateRouter delegateRouter,
                                                              SliceCodec nodeCodec,
                                                              Runnable jvmExit,
                                                              Runnable identityRefusedExit,
                                                              Runnable gossipKeyDivergedExit,
                                                              long bootToken,
                                                              ClusterEventsLimits clusterEventsLimits) {
        return resolveStorageEncryptionKeyring(config).flatMap(keyring -> createNodeWithStorage(config,
                                                                                                delegateRouter,
                                                                                                nodeCodec,
                                                                                                jvmExit,
                                                                                                identityRefusedExit,
                                                                                                gossipKeyDivergedExit,
                                                                                                keyring,
                                                                                                resolvePersistence(config),
                                                                                                bootToken,
                                                                                                clusterEventsLimits));
    }

    private static Result<AetherNode> createNodeWithStorage(AetherNodeConfig config,
                                                            MessageRouter.DelegateRouter delegateRouter,
                                                            SliceCodec nodeCodec,
                                                            Runnable jvmExit,
                                                            Runnable identityRefusedExit,
                                                            Runnable gossipKeyDivergedExit,
                                                            Option<EncryptionKeyring> storageKeyring,
                                                            RabiaPersistence<KVCommand<AetherKey>> persistence,
                                                            long bootToken,
                                                            ClusterEventsLimits clusterEventsLimits) {
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

        if (!configuredWorker(config)) {
            dhtRing.addNode(config.self());
        }

        config.topology()
              .coreNodes()
              .stream()
              .filter(peer -> !peer.id()
                                   .equals(config.self()) || !configuredWorker(config))
              .filter(peer -> "core".equalsIgnoreCase(peer.labels().getOrDefault(NodeInfo.LABEL_ROLE, "core")))
              .forEach(peer -> dhtRing.addNode(peer.id()));
        // #1777 track 1: the DHT's replication factors come from the cluster's COMMITTED `[replication]` section, which
        // this node can read only once its consensus state is restored. Until then the node places nothing and its
        // clients refuse (retryable); [#resolveDhtReplication] applies the factors. FULL replication (the Ember
        // harness) has no placement and needs none.
        var dhtNode = config.artifactRepo().isFullReplication()
                      ? DHTNode.dhtNode(config.self(), dhtStorage, dhtRing, config.artifactRepo())
                      : DHTNode.dhtNodeAwaitingReplication(config.self(),
                                                           dhtStorage,
                                                           dhtRing,
                                                           config.artifactRepo(),
                                                           HlcClock.hlcClock(config.self()));
        // #1777 track 2: the store starts empty, so every partition this core owns starts catching up — it
        // refuses rather than answers "absent" until anti-entropy has filled it from the nodes that may hold
        // its data (the 1 s catch-up tick armed below with the node's other periodic work).
        if (!configuredWorker(config)) {
            dhtNode.beginCatchUp();
        }

        var sliceRegistry = SliceRegistry.sliceRegistry();
        var deferredInvoker = DeferredSliceInvokerFacade.deferredSliceInvokerFacade();
        var nodeConfig = NodeConfig.nodeConfig(config.protocol(),
                                               config.topology(),
                                               config.activationGated(),
                                               config.clusterFormation());
        var rabiaMetricsCollector = RabiaMetricsCollector.rabiaMetricsCollector();
        var leaderTerm = LeaderTerm.leaderTerm(config.self(),
                                               () -> kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class));
        Supplier<Long> rabiaTermSupplier = leaderTerm::current;
        var epochSources = epochSources(kvStore, leaderTerm);
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
        // projection (MEMBER + SUSPECT, only explicitly admitted CORE identities) — a
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
        var installedVoters = new AtomicReference<>(configuredVoters(config));
        var presenceMemberSupplier = presenceMemberSupplier(membershipFsmRef::get, config.self(), installedVoters::get);
        IntSupplier presenceCoreSizeSupplier = () -> installedVoters.get()
                                                                    .size();
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
                                                                                               rabiaTermSupplier,
                                                                                               epochSources.incarnation()::current);
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
                        .flatMap(clusterNode -> initializeVoterConfiguration(clusterNode,
                                                                             config,
                                                                             installedVoters,
                                                                             membershipFsmRef))
                        .map(clusterNode -> installRestoreGate(clusterNode, config, kvStore))
                        .flatMap(clusterNode -> configuredWorker(config)
                                                ? clusterNode.configurePassiveClient()
                                                             .map(ignored -> clusterNode)
                                                : Result.success(clusterNode))
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
                                                             epochSources,
                                                             hlcClock,
                                                             snapshotSource,
                                                             membershipFsmRef,
                                                             metricsCollectorRef,
                                                             syncHoldRegistry,
                                                             jvmExit,
                                                             identityRefusedExit,
                                                             gossipKeyDivergedExit,
                                                             storageKeyring,
                                                             bootToken,
                                                             clusterEventsLimits));
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

    /// #957/#969 — the node's alert config, or the shipped defaults when no `[alerts]` section was
    /// loaded. Validation already happened at boot in `Main.resolveAlertConfig`, so anything arriving
    /// here has passed [org.pragmatica.aether.config.AlertConfig#check]; an absent section needs none.
    static AlertConfig resolveAlertConfig(AetherNodeConfig config) {
        return config.alerts()
                     .or(AlertConfig.alertConfig());
    }

    /// Cores AND workers run in-memory Rabia (owner ruling, session 28: #1390's durable control
    /// storage is removed; a dead NodeId never returns). A whole-cluster cold restart gets its state back
    /// from the change-triggered KV backup instead (#1532/#1533, [BackupRestoreCoordinator]), so own-snapshot
    /// adoption can only ever install state this same process saved.
    private static RabiaPersistence<KVCommand<AetherKey>> resolvePersistence(AetherNodeConfig config) {
        return RabiaPersistence.inMemory();
    }

    /// `[backup]` enabled with a path — the one condition for the backup service, its restore source, and
    /// the restore gate on this node's submit path.
    private static Option<BackupConfig> enabledBackup(AetherNodeConfig config) {
        return config.backupConfig()
                     .filter(BackupConfig::enabled)
                     .filter(backup -> !backup.path()
                                              .isBlank());
    }

    /// #1533 — the central restore gate: on a backup-enabled node every batch this node submits to
    /// consensus is checked by [RestoreGate] before it enters the log.
    private static RabiaNode<KVCommand<AetherKey>> installRestoreGate(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                      AetherNodeConfig config,
                                                                      KVStore<AetherKey, AetherValue> kvStore) {
        enabledBackup(config).onPresent(_ -> clusterNode.installSubmitGuard(RestoreGate.restoreGate(kvStore)));

        return clusterNode;
    }

    /// #1532 — the change-triggered KV backup, present only when `[backup] enabled = true` with a path. Its
    /// repository is `<path>/kv-backup`, apart from anything else written under `[backup] path`.
    private static Option<KvBackupService> createKvBackupService(AetherNodeConfig config,
                                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                                 SliceCodec nodeCodec,
                                                                 BackupWarning.Sink warnings) {
        return enabledBackup(config).map(backup -> KvBackupService.kvBackupService(kvStore,
                                                                                   BackupEntryCodec.backupEntryCodec(nodeCodec),
                                                                                   kvBackupRepository(backup),
                                                                                   warnings));
    }

    /// #1533 — the restore source, when this node backs up: the service and the configured restore mode.
    private static Option<BackupRestoreCoordinator.Source> restoreSource(AetherNodeConfig config,
                                                                         Option<KvBackupService> kvBackupService) {
        return Option.all(kvBackupService, enabledBackup(config)).map((service, backup) -> BackupRestoreCoordinator.Source.source(service,
                                                                                                                                  backup.restore()));
    }

    private static GitBackupRepository kvBackupRepository(BackupConfig backup) {
        return GitBackupRepository.gitBackupRepository(Path.of(backup.path()).resolve("kv-backup"),
                                                       Option.option(backup.remote()),
                                                       "kv-backup",
                                                       GitBackupRepository.DEFAULT_TIMEOUT);
    }

    /// A6: the repair plane's current protocol — stamp digests and paged pulls — and the freshness-gated verified read
    /// of content-addressed keys, answered with a Get- or ExistsResponse. The legacy DHT routes stay beside these for
    /// peers still on the previous version during a rolling upgrade. Without the verified-read route every remote
    /// verified read waits out its phase deadline before falling back to the quorum read.
    @Contract
    static void routeDhtRepairPlane(List<MessageRouter.Entry<?>> allEntries,
                                    DHTNode dhtNode,
                                    DHTNetwork dhtNetwork,
                                    DHTAntiEntropy dhtAntiEntropy) {
        allEntries.add(MessageRouter.Entry.route(DHTMessage.StampDigestRequest.class,
                                                 request -> dhtNode.handleStampDigestRequest(request,
                                                                                             response -> dhtNetwork.send(request.sender(),
                                                                                                                         response))));
        allEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationPageRequest.class,
                                                 request -> dhtNode.handleMigrationPageRequest(request,
                                                                                               response -> dhtNetwork.send(request.sender(),
                                                                                                                           response))));
        allEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationPageResponse.class,
                                                 dhtAntiEntropy::onMigrationPageResponse));
        allEntries.add(MessageRouter.Entry.route(DHTMessage.VerifiedReadRequest.class,
                                                 request -> dhtNode.handleVerifiedReadRequest(request,
                                                                                              response -> dhtNetwork.send(request.sender(),
                                                                                                                          response))));
    }

    @SuppressWarnings("unchecked")
    private static void routeKvBackup(List<MessageRouter.Entry<?>> allEntries, KvBackupService service) {
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, service::onValuePut));
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, service::onValueRemove));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, service::onLeaderChange));
    }

    /// Re-evaluate the quorum-loss detector on the two input changes that carry no FSM transition record
    /// (#1853): a voter-configuration INSTALL — genesis, a §4 command applied at its slot, or a sync adoption of
    /// a newer epoch; the electorate is both a filter of the count and the source of the threshold — and a
    /// member's reachability latch flipping true. Together with the presence-sampler edge, the Member-boundary
    /// crossing and the death edge these are every input change of the derived count. The voter source calls
    /// the listener immediately when a configuration is already installed, so wiring after genesis misses
    /// nothing.
    @Contract
    static void wireQuorumCountTriggers(QuorumLossDetector detector,
                                        MembershipFsm membershipFsm,
                                        Consumer<Consumer<VoterConfiguration>> voterInstallSource) {
        voterInstallSource.accept(_ -> detector.reevaluate());
        membershipFsm.onReachabilityLatched(_ -> detector.reevaluate());
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
    ///
    /// #1560: both sets are narrowed to the installed voters, but the counted set is NEVER the voter
    /// set itself. The voter set is health-blind, so counting it re-admitted every peer the FSM had
    /// already demoted (DEPARTING / DEAD) as a "stuck" member; on an isolated core SWIM still reads
    /// those peers SUSPECTED (Lifeguard stretches suspicion while every local probe fails), so the
    /// fence was suppressed with effective quorum 5 of 5 while the node could reach nobody.
    static QuorumCoConfirmation buildQuorumCoConfirmation(MembershipFsm membershipFsm,
                                                          Function<NodeId, SwimHealth> swimHealth,
                                                          Set<NodeId> voters) {
        var counted = membershipFsm.coreCountedMembers().stream().filter(voters::contains).collect(Collectors.toSet());
        var strict = membershipFsm.strictCoreMembers().stream().filter(voters::contains).collect(Collectors.toSet());
        var stuck = counted.stream().filter(id -> !strict.contains(id)).toList();
        var swimAliveStuck = stuck.stream().filter(id -> swimAliveForCoConfirmation(swimHealth, id)).toList();
        var swimDeadStuck = stuck.stream().filter(id -> !swimAliveForCoConfirmation(swimHealth, id)).toList();

        return QuorumCoConfirmation.quorumCoConfirmation(strict.size(), counted.size(), swimAliveStuck, swimDeadStuck);
    }

    /// Raw-SWIM aliveness for the Fix C stuck-member discrimination: a stuck (SUSPECT) member counts
    /// as alive when the SWIM protocol layer reports HEALTHY or SUSPECTED — distinguishing a wedged
    /// promotion (reachable, refuting) from a real partition (FAULTY / UNKNOWN).
    static boolean swimAliveForCoConfirmation(Function<NodeId, SwimHealth> swimHealth, NodeId id) {
        var health = swimHealth.apply(id);

        return health == SwimHealth.HEALTHY || health == SwimHealth.SUSPECTED;
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
    /// #1339 residual: a FAILED stream-ownership batch write changes no placement input (the committed record stays
    /// where it was), so no trigger re-runs the pass, and with a steady leader and stable membership the partition stays
    /// write-refused. The failure is itself the edge: it re-arms the coalescing reconcile after a delay that doubles from
    /// this base up to [#STREAM_OWNERSHIP_RETRY_CAP] and resets on the next successful write. The re-armed pass derives
    /// from current state, so a write that a later trigger already superseded costs one no-op pass.
    TimeSpan STREAM_OWNERSHIP_RETRY_BASE = TimeSpan.timeSpan(500).millis();
    /// Upper bound of the [#STREAM_OWNERSHIP_RETRY_BASE] backoff: a persistently failing write retries at this cadence.
    TimeSpan STREAM_OWNERSHIP_RETRY_CAP = TimeSpan.timeSpan(30).seconds();
    /// Cadence for the metadata snapshot driver (A3b). Drives `SnapshotManager.maybeSnapshot()` across
    /// every storage setup; the manager self-gates on its mutation-count / time-interval trigger, so
    /// this tick only needs to be frequent enough to bound the post-trigger snapshot latency.
    TimeSpan METADATA_SNAPSHOT_INTERVAL = TimeSpan.timeSpan(10).seconds();
    /// How long a genesis-pending core with no connection to any configured core waits before it dials
    /// regardless of the single-dialer order (see `bootstrapInitiator`). Cold-start candidates connect
    /// through designated dials well inside it.
    TimeSpan PENDING_CORE_ISOLATION_GRACE = TimeSpan.timeSpan(2).seconds();
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
    /// #1730: how often an owner re-evaluates its partitions' in-sync replica sets. Lag itself is bounded by
    /// `[streaming] isr_lag_max`; this cadence only adds up to one interval to it.
    TimeSpan ISR_MONITOR_INTERVAL = TimeSpan.timeSpan(1).seconds();
    /// #903: bound on closing the shared resource scope during stop. A factory close that never resolves must
    /// not hold storage and the cluster node open behind it; past the bound the step is logged and skipped.
    TimeSpan SHARED_SCOPE_CLOSE_BOUND = TimeSpan.timeSpan(10).seconds();
    /// #1730: how soon a refused (moved-record) ownership write is re-decided.
    TimeSpan OWNERSHIP_REDRIVE_DELAY = TimeSpan.timeSpan(1).seconds();
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

    /// Where an EVENT-triggered entity reconcile runs (the retract of a keyspace on unload, a committed
    /// registration removal): the shared scheduler, off the caller's thread, with no delay. This is a hand-off
    /// to the same executor the periodic tick runs on, not a timer — the periodic tick stays as the
    /// level-triggered backstop that heals a kick lost to a failed apply.
    Executor ENTITY_RECONCILE_KICK = command -> SharedScheduler.schedule(command,
                                                                         TimeSpan.timeSpan(0).millis());

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
    static Path streamDataDir(AetherNodeConfig config) {
        return streamDataDir(config.storageConfig(), config.self());
    }

    /// #249: [#streamDataDir(AetherNodeConfig)] over the node's storage map, which is the DERIVED one
    /// ([StorageFactory#derivedStorage], applied once in `Main`): a shared-only `artifacts` reads its derived default
    /// path here, never the blank path of its raw configuration.
    static Path streamDataDir(Map<String, StorageConfig> storage, NodeId self) {
        var artifactsConfig = Option.option(storage.get("artifacts")).or(StorageConfig.storageConfig());

        return Path.of(artifactsConfig.diskPath())
                   .resolveSibling("stream-segments")
                   .resolve(self.id());
    }

    /// The per-node stream WAL BASE directory (#634-3): the `streams` storage instance's explicit
    /// `wal_path` when set, else the derived pre-#634-3 sibling (`<streamDataDir>/wal`). The node-id
    /// suffix on the explicit path keeps co-located nodes (EmberCluster, containers sharing a mount)
    /// from ever sharing one WAL dir — the same invariant `streamDataDir` enforces, independent of
    /// operator input.
    static Path streamWalBaseDir(AetherNodeConfig config) {
        return streamWalBaseDir(config.storageConfig(), config.self());
    }

    /// #249: [#streamWalBaseDir(AetherNodeConfig)] over the node's derived storage map, as [#streamDataDir(Map, NodeId)].
    static Path streamWalBaseDir(Map<String, StorageConfig> storage, NodeId self) {
        var streamsConfig = Option.option(storage.get("streams")).or(StorageConfig.storageConfig());

        return effectiveWalBase(streamsConfig, self, streamDataDir(storage, self).resolve("wal"));
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

    /// The stream partition logs open through the `streams` storage instance (#1567), under its log root —
    /// the WAL dir [#resolveStreamWalDir] resolved when the instance was built; none when that dir was
    /// unusable and streams degraded to no WAL.
    private static Option<AppendLog.Opener> streamLogs(StorageInstance streamStorage) {
        return streamStorage.logRoot()
                            .<AppendLog.Opener> map(_ -> AppendLog.Opener.opener(streamStorage::openLog,
                                                                                 streamStorage::inspectLog));
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

        return decideWalAvailability(walDir, createStreamDirectory(walDir), nonDurableStreamsAllowed()).mapToUnit();
    }

    /// #1567 F12/N2 — the durable-block counterpart of [#verifyWalBootable], at the same production entry
    /// point. The `streams` instance's local-disk tier lives in `<streamDataDir>/segments`; when it cannot be
    /// created the instance is built from memory and the in-memory DHT tier alone, where every seal refuses
    /// (`StorageError.NoDurableTier`) -- so nothing is ever "sealed" into RAM and the WAL is never truncated
    /// past it, but sealing stops and the WAL grows without bound. A node must not boot silently into that
    /// state: without the same explicit non-durable opt-in, boot is refused. Directly constructed nodes
    /// (Forge, Ember, tests) never pass through Main and keep the loud degrade, exactly as the WAL gate does.
    public static Result<Unit> verifyStreamSegmentsBootable(AetherNodeConfig config) {
        var segmentsDir = streamDataDir(config).resolve("segments");

        return decideSegmentsAvailability(segmentsDir, createStreamDirectory(segmentsDir), nonDurableStreamsAllowed());
    }

    /// Every stream directory a node creates -- the WAL base and `segments/` -- is created with its entry
    /// forced in its parent (#1602 N1): the files made durable inside it later are only as durable as the
    /// directory entry that names it. The gates run first, so they are the ones that create them.
    static Result<Unit> createStreamDirectory(Path dir) {
        return FileOps.createDirectoriesDurable(dir).mapToUnit();
    }

    /// The pure decision, package-visible for [#verifyStreamSegmentsBootable]'s test.
    static Result<Unit> decideSegmentsAvailability(Path segmentsDir, Result<Unit> probe, boolean allowNonDurable) {
        return probe.fold(cause -> allowNonDurable
                                   ? Result.unitResult()
                                   : StorageFactory.StreamDiskTierUnavailable.streamDiskTierUnavailable(segmentsDir,
                                                                                                        cause.message())
                                                                             .result(),
                          _ -> Result.unitResult());
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

    static Option<Path> walDisabled(Path walDir, Cause cause) {
        LOG.warn("Stream WAL disabled ({} not writable): {} — streaming runs non-crash-durable (in-memory tail)"
                + " [explicitly allowed by aether.allowNonDurableStreams]",
                 walDir,
                 cause.message());

        return Option.none();
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
    /// safety valve is the backstop). A PACED refusal is not lost with this log line: the manager queues it and
    /// re-drives it on every reconcile tick until the ring exists (#1805).
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
    /// first reconcile it falls back to the live placement projection ([#livePlacementMembers]), the SAME
    /// pre-reconcile fallback `roleFor` uses, so cold-start owner-immediate self-promotion is unaffected.
    /// #1730: the committed ISR of `(stream, partition)`; none for a record minted before #1730 (`isrVersion` 0),
    /// which carries no committed ISR, and for a partition with no record.
    static Option<List<NodeId>> committedIsr(KVStore<AetherKey, AetherValue> kvStore, String stream, int partition) {
        return committedIsrRecord(kvStore, stream, partition).map(StreamPartitionOwnershipValue::isr);
    }

    static Option<StreamPartitionOwnershipValue> committedIsrRecord(KVStore<AetherKey, AetherValue> kvStore,
                                                                    String stream,
                                                                    int partition) {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition),
                                StreamPartitionOwnershipValue.class)
                      .filter(record -> record.isrVersion() > 0);
    }

    /// #1730: what the leader's ownership writer reads besides the committed record — the live placement members and
    /// the placement a first record's ISR is minted from — both from the stream controller.
    static StreamPartitionOwnershipWriter.IsrInputs streamIsrInputs(AtomicReference<ReplicaSetController> controllerRef) {
        return new StreamPartitionOwnershipWriter.IsrInputs() {
            @Override
            public List<NodeId> liveMembers() {
                return Option.option(controllerRef.get())
                             .map(ReplicaSetController::reconciledMembers)
                             .or(List.of());
            }

            @Override
            public List<NodeId> initialIsr(String stream, int partition, NodeId owner) {
                return Option.option(controllerRef.get())
                             .map(controller -> controller.placementWithOwner(stream, partition, owner))
                             .or(List.of(owner));
            }
        };
    }

    /// #1873 (KIP-320): how an owner commits where its epoch begins, before it is activated: a guarded write of the exact
    /// committed record, witnessed by the committed leader, that records the start at `start` and, when its ring was
    /// rebuilt, first takes the next ownership term. Refusal is not a failed promise (the applier answers it with a result),
    /// so #1976: the write's own [KVCommand.TransactionResult] decides — accepted or the commit FAILS. Never a re-read of
    /// the record: on a restart the old record already names a start, so a refused commit would read as landed.
    static OwnerActivation.LineageCommit streamLineageCommit(Supplier<Option<LeaderValue>> committedLeader,
                                                             java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                             HlcClock clock) {
        return (stream, partition, current, start, restarted) -> committedLeader.get()
                                                                                .fold(() -> OwnerActivation.ActivationError.NO_COMMITTED_LEADER.<Unit> promise(),
                                                                                      leader -> lineageWrite(applier,
                                                                                                             StreamPartitionOwnershipWriter.guardedOwnershipWrite(leader,
                                                                                                                                                                  stream,
                                                                                                                                                                  partition,
                                                                                                                                                                  Option.some(current),
                                                                                                                                                                  restarted
                                                                                                                                                                  ? current.restarted(start,
                                                                                                                                                                                      clock.now())
                                                                                                                                                                  : current.withEpochStart(start))));
    }

    private static Promise<Unit> lineageWrite(java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                              KVCommand.LeaderTransaction<AetherKey, AetherValue> write) {
        var transactionId = write.transactionId();

        return applier.apply(List.of(write))
                      .flatMap(results -> acceptedBy(results, transactionId)
                                          ? Promise.success(Unit.unit())
                                          : OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED.<Unit> promise());
    }

    static boolean acceptedBy(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }

    /// #1730: the partitions this node owns AND serves (activated for the committed record), with their record and
    /// appended head — the ISR monitor's input.
    static List<IsrMonitor.Owned> ownedPartitions(StreamPartitionManager manager,
                                                  KVStore<AetherKey, AetherValue> kvStore,
                                                  OwnerActivation activation,
                                                  NodeId self) {
        return manager.materializedHeads()
                      .stream()
                      .flatMap(head -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(head.streamName(),
                                                                                                                head.partition()),
                                                        StreamPartitionOwnershipValue.class)
                                              .filter(record -> record.owner()
                                                                      .equals(self) && activation.isActivated(head.streamName(),
                                                                                                              head.partition()))
                                              .map(record -> new IsrMonitor.Owned(head.streamName(),
                                                                                  head.partition(),
                                                                                  record,
                                                                                  head.head()))
                                              .stream())
                      .toList();
    }

    static List<NodeId> streamPlacementMembers(AtomicReference<ReplicaSetController> controllerRef,
                                               Supplier<List<NodeId>> placementMembers) {
        return Option.option(controllerRef.get())
                     .map(ReplicaSetController::reconciledMembers)
                     .or(placementMembers::get);
    }

    /// #1550 SINGLE SOURCE of the stream placement member set. Built once per node and handed to the
    /// stream `ReplicaSetController` and the backfill orchestrator; every other placement consumer
    /// (ownership writer, entity-ownership reconciler, consumer-group ownership, cluster-events owner gate,
    /// placement-role supplier) reads it through that controller. `LivePlacementMembersWiringTest` fails if
    /// any consumer reads the voter set directly again.
    static Supplier<List<NodeId>> livePlacementMembers(Supplier<Set<NodeId>> voters, MembershipFsm membershipFsm) {
        return () -> livePlacementMembers(voters.get(), membershipFsm);
    }

    /// #1550: the HRW stream-placement member set — the installed voters narrowed to the FSM's counted
    /// core projection (MEMBER + SUSPECT). Since #1390, `TopologyObserver.coreNodes()` returns the
    /// installed voter configuration, which is consensus identity and deliberately health-independent: a
    /// killed voter stays in it. Placing over that set kept a dead owner as the HRW winner forever, so no
    /// survivor was ever promoted. Before #1390 the same read returned the live membership projection;
    /// this restores liveness for placement without changing what consensus reads. Voters are kept as the
    /// outer bound: a fresh-identity core enters placement once the membership add installs it as a voter.
    static List<NodeId> livePlacementMembers(Set<NodeId> voters, MembershipFsm membershipFsm) {
        return voters.stream()
                     .filter(membershipFsm.coreCountedMembers()::contains)
                     .toList();
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

    /// #1230: the committed `StreamPartitionOwnershipValue.owner` of `(stream, partition)` when it names a node
    /// OTHER than self — the node an application append must go to instead. Empty when self is the committed
    /// owner or no ownership is committed.
    private static Option<NodeId> remoteCommittedOwner(CommittedStreamOwnerSource source,
                                                       NodeId self,
                                                       String stream,
                                                       int partition) {
        return source.committedOwner(stream, partition)
                     .map(CommittedStreamOwnerSource.CommittedOwner::owner)
                     .filter(owner -> !owner.equals(self));
    }

    /// The two views of committed stream-partition ownership, and which consumer gets which (#1230 ruling 3).
    ///
    /// `writeAuthority` is the RAW committed `StreamPartitionOwnershipValue`: it gates application appends
    /// ([#writeAdmission]) and validates replication senders. `routing` is the #568 liveness-filtered view
    /// ([#committedOwnerStillAlive]): it drives backfill self-election and the release guard, where treating a
    /// dead holder as absent is what unwedges the partition. Liveness is a local verdict, not a fence — fed to
    /// the write side, the filtered view would read an owner this node's SWIM view has marked DEPARTED (while
    /// it is in fact alive, e.g. partitioned away) or has not yet seen (a join or restart) as "absent", which
    /// admission admits, so a second writer would append while the first still writes. A merely SUSPECT owner
    /// is not dropped by the filter (`countedMembers` is MEMBER + SUSPECT). A dead owner's partition is
    /// unwedged for writes only when the leader commits a new ownership record; until then appends fail with
    /// the retryable `NotOwnerAppend`.
    record StreamOwnershipViews(CommittedStreamOwnerSource writeAuthority, CommittedStreamOwnerSource routing) {
        static StreamOwnershipViews streamOwnershipViews(CommittedStreamOwnerSource committed,
                                                         MembershipFsm membershipFsm) {
            return new StreamOwnershipViews(committed, liveOnly(committed, membershipFsm));
        }

        /// Application-append admission over [#writeAuthority]: the committed owner when it is not `self`.
        StreamPartitionManager.OwnerWriteAdmission writeAdmission(NodeId self) {
            return (stream, partition) -> remoteCommittedOwner(writeAuthority, self, stream, partition);
        }

        private static CommittedStreamOwnerSource liveOnly(CommittedStreamOwnerSource committed,
                                                           MembershipFsm membershipFsm) {
            return (stream, partition) -> liveOwner(committed.committedOwner(stream, partition), membershipFsm);
        }

        private static Option<CommittedStreamOwnerSource.CommittedOwner> liveOwner(Option<CommittedStreamOwnerSource.CommittedOwner> committed,
                                                                                   MembershipFsm membershipFsm) {
            return committed.filter(owner -> committedOwnerStillAlive(membershipFsm, owner.owner()));
        }
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
    /// is logged, never thrown, and re-arms the reconcile with backoff ([#STREAM_OWNERSHIP_RETRY_BASE]).
    @Contract
    static void driveStreamOwnership(StreamPartitionOwnershipWriter writer,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                     StreamOwnershipRetry retry,
                                     List<PartitionKey> reconciled) {
        // Entity arcs are excluded HERE, not in the controller's reconcile: their LOG replicas are
        // placed by the same reconcile as any stream's (wanted), but their OWNERSHIP has exactly one
        // authority — EntityOwnershipReconciler, which places over the keyspace's hosting set. Left
        // unfiltered, this driver would re-place entity arcs over the WHOLE member view on every
        // catalog/membership edge and fight that reconcile record-for-record, parking arcs on
        // non-hosting nodes (which refuse every write) for up to one entity tick each time.
        applyStreamOwnershipBatch(applier,
                                  retry,
                                  writer.writeOwnershipChanges(EntityOwnershipReconciler.withoutEntityArcs(reconciled)));
    }

    /// Apply the reconcile pass's ownership-change Puts as ONE consensus batch. An empty batch (a
    /// follower, or a pass with no moved partitions) applies nothing and logs nothing — the empty apply
    /// is skipped rather than sent (an empty command batch would be rejected by consensus).
    @Contract
    private static void applyStreamOwnershipBatch(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                  StreamOwnershipRetry retry,
                                                  List<KVCommand<AetherKey>> commands) {
        if (commands.isEmpty()) {
            return;
        }

        applier.apply(commands)
               .onSuccess(results -> retry.settled(refusedCount(results)))
               .onFailure(cause -> retry.failed(commands.size(),
                                                cause));
    }

    /// The re-arm half of [#STREAM_OWNERSHIP_RETRY_BASE]: counts consecutive failed ownership batch writes and, on each
    /// failure, hands the reconcile trigger to `schedule` with the backed-off delay. One instance per node.
    record StreamOwnershipRetry(AtomicInteger consecutiveFailures,
                                BiConsumer<Runnable, TimeSpan> schedule,
                                Runnable reconcile) {
        static StreamOwnershipRetry streamOwnershipRetry(BiConsumer<Runnable, TimeSpan> schedule, Runnable reconcile) {
            return new StreamOwnershipRetry(new AtomicInteger(), schedule, reconcile);
        }

        /// Doubles from the base per consecutive failure, capped.
        static TimeSpan delayAfter(int consecutiveFailures) {
            var doubled = STREAM_OWNERSHIP_RETRY_BASE.millis() << Math.min(consecutiveFailures, 16);

            return TimeSpan.timeSpan(Math.min(doubled, STREAM_OWNERSHIP_RETRY_CAP.millis())).millis();
        }

        /// #1730: a write that applied may still be a REFUSED guarded transaction (an owner committed an ISR change
        /// first). That is not a failure to back off from: the decision is re-made against the record that won.
        @Contract
        void settled(long refused) {
            consecutiveFailures.set(0);
            if (refused > 0) {
                LOG.debug("{} stream ownership write(s) refused on a moved record; re-deciding", refused);
                schedule.accept(reconcile, OWNERSHIP_REDRIVE_DELAY);
            }
        }

        @Contract
        void failed(int moved, Cause cause) {
            var delay = delayAfter(consecutiveFailures.getAndIncrement());

            LOG.warn("Stream ownership batch write for {} moved partition(s) failed: {} — reconcile re-armed in {}",
                     moved,
                     cause.message(),
                     delay);
            schedule.accept(reconcile, delay);
        }
    }

    /// #1730: the guarded ownership transactions of a batch the applier refused because their record moved.
    static long refusedCount(List<Object> results) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .filter(result -> !result.accepted())
                      .count();
    }

    /// #336 reachability-evidence: when an app/blueprint stream's committed `StreamConfig` lands via
    /// consensus (e.g. `replication_factor=2 / confirmation_factor=2` at slice activation), place its replica set NOW instead
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
        reconcileReplicaSet(controllerRef);
    }

    /// The one hook every stream-placement input trigger ends in. No-op until the controller is bound.
    @Contract
    static void reconcileReplicaSet(AtomicReference<ReplicaSetController> controllerRef) {
        Option.option(controllerRef.get()).onPresent(ReplicaSetController::reconcile);
    }

    /// The replica-set reconcile is a pure function of CURRENT state — placement members, cluster size, the
    /// committed ownership records, the stream catalog and the leader gate on the ownership writer — and it
    /// has no other way to learn that one of them changed. #1339 and #1732 were the same defect: an input
    /// changed and nothing ran the pass. A replacement core's voter install changed the placement members
    /// after the join decision had already reconciled (#1732: RF never restored); a leadership change
    /// moved the only writer that can commit an owner after the removal decision had already reconciled with
    /// no leader to write (#1339: the partition stayed write-refused until the next membership change). The
    /// pre-existing triggers cover the membership decisions, the quorum edges and the stream-config put; these
    /// are the inputs they missed.
    ///
    ///  - voter-configuration INSTALL (genesis, §4 command, sync adoption): the placement members are the
    ///    installed voters narrowed to the FSM's counted core, and the RF clamp is the voter count. The source
    ///    calls the listener at once when a configuration is already installed, so wiring after genesis
    ///    misses nothing.
    ///  - LEADER change: the ownership writer is leader-only, so a pass that ran while no live leader existed
    ///    wrote nothing, and only a leadership edge can say the writer is now able to.
    ///  - a committed OWNERSHIP RECORD: placement follows the committed owner, so every node's registry is
    ///    re-derived when the leader's write lands (and when it is replayed on a restarted node).
    ///
    /// The fourth input — the FSM's counted set changing WITHOUT a membership decision — is wired where the
    /// FSM's transition listener already is ([#reconcileReplicaSetOnCountedBoundary]). `ReplicaSetController`
    /// coalesces triggers, so a reshuffle that commits many ownership records costs one pass.
    @Contract
    static void wireReplicaSetInputTriggers(List<MessageRouter.Entry<?>> allEntries,
                                            Runnable reconcile,
                                            Consumer<Consumer<VoterConfiguration>> voterInstallSource) {
        voterInstallSource.accept(_ -> reconcile.run());
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, _ -> reconcile.run()));
        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     _ -> reconcile.run())
                                              .build()
                                              .asRouteEntries());
    }

    /// Periodic activation level-heal tick (see [#NDM_ACTIVATION_RECONCILE_INTERVAL]). Gated on a
    /// LIVE consensus-active sample (`clusterNode::isActive`, the SAME accessor the readiness pong
    /// and leader election use) so it only acts when this node IS in quorum: a dropped ACTIVE edge
    /// then surfaces as NDM still Dormant, and `reconcileActivation()` re-dispatches the missing
    /// `QuorumEstablished`. No-op when consensus is not active (correctly Dormant) or once NDM has
    /// activated.
    @Contract
    static void reconcileNodeActivation(BooleanSupplier consensusActive, NodeDeploymentManager nodeDeploymentManager) {
        if (!consensusActive.getAsBoolean()) {
            return;
        }

        nodeDeploymentManager.reconcileActivation();
    }

    /// A peer's watermark (its highest held offset; `-1` for empty) over the catch-up read class, or FAIL when
    /// the peer is unreachable. The success/failure split is the data-safety signal the backfill deadlock-break
    /// needs: a reachable peer's watermark is comparable; an unreachable peer's is unknown (and might be
    /// ahead), so self must not self-promote past it. A peer that HOLDS the partition but has not materialized
    /// it (paced, deferred) or is an owner not yet promoted is REACHABLE ([OwnerPeerReads#replicaWatermark]):
    /// the catch-up class is not gated on the peer's own promotion, and a held-unmaterialized refusal carries
    /// its durable watermark.
    private static Promise<Long> probePeerWatermark(StreamForwardClient forwardClient,
                                                    NodeId target,
                                                    String streamName,
                                                    int partition) {
        return OwnerPeerReads.replicaWatermark(forwardClient::readRemoteCatchup,
                                               target,
                                               streamName,
                                               partition,
                                               STREAM_CATCHUP_BATCH_SIZE);
    }

    /// #1555: the owner promotion gate's probe, over the catch-up read class the gate's catch-up then pulls
    /// with, starting at the peer's oldest available offset ([OwnerPeerReads#appendedWatermark]).
    private static Promise<Long> probePeerAppendedWatermark(StreamForwardClient forwardClient,
                                                            NodeId target,
                                                            String streamName,
                                                            int partition) {
        return OwnerPeerReads.appendedWatermark(forwardClient::readRemoteCatchup,
                                                target,
                                                streamName,
                                                partition,
                                                STREAM_CATCHUP_BATCH_SIZE);
    }

    /// #1555 item 8: how long a promotion may stay blocked on unreachable members before it is reported — two
    /// SWIM suspect windows, so a member that is merely slow to be declared FAULTY does not raise it.
    private static TimeSpan ownerPromotionAlarmWindow(TimeSpan suspectTimeout) {
        return StreamingConfig.ownerPromotionAlarmWindow(suspectTimeout);
    }

    /// #1555: a partition whose owner promotion waits for an operator (a divergent peer, or members unreachable
    /// past the alarm window). The gate raises each distinct block once.
    ///
    /// #1431: a peer whose event exceeds its read cap is raised as its own CRITICAL operator warning (a log line plus
    /// a cluster event), naming the partition, the peer and the offset — never as an unreachable member.
    /// An owner-promotion block as an operator event, and its end as the recovery (#1937). The oversized-event refusal and the
    /// members-unreachable wait each raise on a transition and recover on the opposite one (flood-guarded by the sink); the other
    /// blocks stay a WARN and the partition status read until #1574 gives them a code.
    /// What `GET /api/v1/cluster/provisioning` reports for the leader: the decision snapshot with the manager's circuit, last failure
    /// and current inventory wait. Package-private so that the hand-off of each to the endpoint can be pinned.
    static ProvisioningDiagnostics provisioningDiagnosticsOf(LeaderReconciler.ProvisioningDecisionSnapshot decision,
                                                             ClusterTopologyManager manager) {
        return new ProvisioningDiagnostics(decision,
                                           manager.circuitBreakerState(),
                                           manager.lastProvisionFailure(),
                                           manager.inventoryWait(),
                                           manager.inventoryWaits());
    }

    static OwnerActivation.BlockAlarm ownerPromotionAlarm(OperatorWarningSink sink) {
        return new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                return raiseOwnerPromotionBlock(sink, block);
            }

            @Override
            public Unit resolved(OwnerActivation.ActivationBlock block) {
                return resolveOwnerPromotionBlock(sink, block);
            }

            @Override
            public Unit escaped(OwnerActivation.PromotionEscape escape) {
                return raisePromotionEscape(sink, escape);
            }
        };
    }

    /// #2080: a promotion that went ahead without unreachable members is a CRITICAL operator event, one per escape, from both gates.
    /// The subject carries the gate, the skipped members and the instant, so two escapes of one partition are two events for the event
    /// layer's per-subject throttle (60 s): none is merged into an earlier one.
    private static Unit raisePromotionEscape(OperatorWarningSink sink, OwnerActivation.PromotionEscape escape) {
        return OperatorWarnings.raise(LOG,
                                      sink,
                                      OperatorWarningCode.STREAM_PROMOTION_PAST_UNREACHABLE_PEERS,
                                      escape.streamName()
                                     + "[" + escape.partition()
                                     + "]/" + escape.gate()
                                     + "/without=" + escape.skipped()
                                                           .stream()
                                                           .map(NodeId::id)
                                                           .sorted()
                                                           .toList()
                                     + "@" + System.currentTimeMillis(),
                                      "{}",
                                      escape.message());
    }

    /// What the promoted owner's backfill and its cold-start contest need from the node (#1937, #2080): where its blocks and escapes
    /// are reported, and where it reads whether a node is named in a partition's committed in-sync set. One place, so the production
    /// wiring is the code the wiring test drives.
    static Unit bindPromotionAlarm(PartitionBackfill backfill,
                                   OperatorWarningSink sink,
                                   KVStore<AetherKey, AetherValue> kvStore,
                                   TimeSpan promotionEscapeAfter) {
        var committedIsr = KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore);

        backfill.blockAlarm(ownerPromotionAlarm(sink));
        backfill.promotionEscapeAfter(promotionEscapeAfter);
        backfill.committedIsr((stream, partition, node) -> committedIsr.committedIsr(stream, partition)
                                                                       .contains(node));

        return Unit.unit();
    }

    private static String partitionSubject(OwnerActivation.ActivationBlock block) {
        return block.streamName() + "[" + block.partition() + "]";
    }

    private static Unit raiseOwnerPromotionBlock(OperatorWarningSink sink, OwnerActivation.ActivationBlock block) {
        return switch (block) {
            case OwnerActivation.ActivationBlock.PeerEventExceedsReadCap oversized -> OperatorWarnings.raise(LOG,
                                                                                                             sink,
                                                                                                             OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP,
                                                                                                             partitionSubject(oversized),
                                                                                                             "{}",
                                                                                                             oversized.message());
            case OwnerActivation.ActivationBlock.HoldersUnreachable unreachable -> OperatorWarnings.raise(LOG,
                                                                                                          sink,
                                                                                                          OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE,
                                                                                                          partitionSubject(unreachable),
                                                                                                          "{}",
                                                                                                          unreachable.message());
            case OwnerActivation.ActivationBlock.LineageRefused refused -> OperatorWarnings.raise(LOG,
                                                                                                  sink,
                                                                                                  OperatorWarningCode.STREAM_OWNER_LINEAGE_REFUSED,
                                                                                                  partitionSubject(refused),
                                                                                                  "{}",
                                                                                                  refused.message());
            default -> raiseOwnerPromotionBlock(block);
        };
    }

    private static Unit resolveOwnerPromotionBlock(OperatorWarningSink sink, OwnerActivation.ActivationBlock block) {
        return switch (block) {
            case OwnerActivation.ActivationBlock.PeerEventExceedsReadCap oversized -> OperatorWarnings.raise(LOG,
                                                                                                             sink,
                                                                                                             OperatorWarningCode.STREAM_EVENT_EXCEEDS_READ_CAP_RESOLVED,
                                                                                                             partitionSubject(oversized),
                                                                                                             "Owner promotion of {} is no longer refused for a peer's oversized event.",
                                                                                                             partitionSubject(oversized));
            case OwnerActivation.ActivationBlock.HoldersUnreachable unreachable -> OperatorWarnings.raise(LOG,
                                                                                                          sink,
                                                                                                          OperatorWarningCode.STREAM_OWNER_PROMOTION_HOLDERS_ANSWERING,
                                                                                                          partitionSubject(unreachable),
                                                                                                          "Owner promotion of {} no longer waits for unreachable members.",
                                                                                                          partitionSubject(unreachable));
            case OwnerActivation.ActivationBlock.LineageRefused refused -> OperatorWarnings.raise(LOG,
                                                                                                  sink,
                                                                                                  OperatorWarningCode.STREAM_OWNER_LINEAGE_COMMITTED,
                                                                                                  partitionSubject(refused),
                                                                                                  "Owner promotion of {} is no longer refused at its epoch-start commit.",
                                                                                                  partitionSubject(refused));
            default -> Unit.unit();
        };
    }

    private static Unit raiseOwnerPromotionBlock(OwnerActivation.ActivationBlock block) {
        // TODO(#1574): raise as a CRITICAL OperatorWarning once #1574 merges. Until then the operator-visible
        // surfaces are this WARN and the partition status read (`ownerActivationBlock` on STREAM_REPLICAS).
        LOG.warn("CRITICAL: {}", block.message());

        return Unit.unit();
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
                                                   LeaderTerm leaderTerm,
                                                   EpochSources epochSources,
                                                   HlcClock hlcClock,
                                                   GenerationSnapshotSource snapshotSource,
                                                   AtomicReference<MembershipFsm> membershipFsmRef,
                                                   AtomicReference<ClusterSyncCollector> metricsCollectorRef,
                                                   org.pragmatica.cluster.node.rabia.SyncHoldRegistry syncHoldRegistry,
                                                   Runnable jvmExit,
                                                   Runnable identityRefusedExit,
                                                   Runnable gossipKeyDivergedExit,
                                                   Option<EncryptionKeyring> storageKeyring,
                                                   long bootToken,
                                                   ClusterEventsLimits clusterEventsLimits) {
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
                if (configuredWorker(config)) {
                    return dhtNode.ring()
                                  .nodes()
                                  .stream()
                                  .filter(clusterNode.network().connectedPeers()::contains)
                                  .collect(Collectors.toSet());
                }

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
        var baseDhtClient = DistributedDHTClient.distributedDHTClient(dhtNode,
                                                                      dhtNetwork,
                                                                      KvOwnerEpochSource.kvOwnerEpochSource(kvStore,
                                                                                                            BootstrapModule.CORE_PARTITION_ID));
        // An all-miss resolve reads as a bare "absent" everywhere above the client; this observer writes the WARN that
        // says whether the key is absent everywhere or unreachable, for artifact keys only (other keys' absence is normal
        // traffic and logs at DEBUG). The cache client is scoped from the base client, not from this one:
        // a cache miss is normal traffic and must not WARN.
        var dhtClient = baseDhtClient.withResolveFallbackObserver(LoggingResolveFallbackObserver.loggingResolveFallbackObserver(ArtifactStore::isArtifactMetadataKeyHex,
                                                                                                                                LOG::warn,
                                                                                                                                LOG::debug,
                                                                                                                                LOG::info));
        // The cache namespace declares its own replication in the committed `[cache]` section (#1777 track 1),
        // re-read on every operation; `config.cache()` supplies its timeout and retry policy, and its factors
        // until the committed ones are resolved.
        var cacheDhtConfig = new AtomicReference<>(config.cache());
        var cacheDhtClient = baseDhtClient.scoped(cacheDhtConfig::get);
        var dhtClientOption = Option.<DHTClient> some(dhtClient);
        var storageSetupsResult = StorageAssembly.createStorageSetups(new StorageAssembly.CreateStorageSetupsInputs(config,
                                                                                                                    storageKeyring,
                                                                                                                    dhtClientOption));

        if (storageSetupsResult.isFailure()) {
            return storageSetupsResult.map(ignored -> null);
        }

        var storageSetups = storageSetupsResult.fold(_ -> null, setups -> setups);
        var artifactStorageOut = StorageAssembly.artifactStorage(new StorageAssembly.ArtifactStorageInputs(config,
                                                                                                           kvStore,
                                                                                                           clusterNode,
                                                                                                           dhtClient,
                                                                                                           storageSetups));
        var streamStorageSetup = artifactStorageOut.streamStorageSetup();
        var contentStorage = artifactStorageOut.contentStorage();
        var artifactStore = artifactStorageOut.artifactStore();
        var repositories = artifactStorageOut.repositories();
        var resourceProviderFacadeOut = SliceRuntimeAssembly.resourceProviderFacade(new SliceRuntimeAssembly.ResourceProviderFacadeInputs(config));
        var sharedLibraryLoader = resourceProviderFacadeOut.sharedLibraryLoader();
        var resourceProviderSetupResult = resourceProviderFacadeOut.resourceProviderSetupResult();

        if (resourceProviderSetupResult.isFailure()) {
            return resourceProviderSetupResult.map(ignored -> null);
        }

        var resourceProviderSetup = resourceProviderSetupResult.fold(_ -> null, setup -> setup);
        var sliceStore = SliceRuntimeAssembly.sliceStore(new SliceRuntimeAssembly.SliceStoreInputs(config,
                                                                                                   sliceRegistry,
                                                                                                   deferredInvoker,
                                                                                                   nodeCodec,
                                                                                                   repositories,
                                                                                                   sharedLibraryLoader,
                                                                                                   resourceProviderSetup));
        var dhtRebalancer = DHTRebalancer.dhtRebalancer(dhtNode, dhtNetwork);
        // #1818 round 3: departure pushes are accepted only from departing senders. The predicate needs the
        // ClusterSyncCollector, built further down, so it is resolved through this holder (strict until then).
        var departingSendersRef = new AtomicReference<Predicate<NodeId>>(_ -> false);
        var dhtAntiEntropy = DHTAntiEntropy.dhtAntiEntropy(dhtNode,
                                                           dhtNetwork,
                                                           sender -> departingSendersRef.get()
                                                                                        .test(sender));
        var dhtTopologyListener = DHTTopologyListener.dhtTopologyListener(dhtNode, dhtRebalancer, dhtAntiEntropy);
        var switchableCluster = SwitchableClusterNode.switchableClusterNode(clusterNode);
        // #1777 CTO ruling R1b: a replication change switches the DHT to its new quorums cluster-wide, by a committed fact
        var dhtSettlement = DhtReplicationSettlement.dhtReplicationSettlement(new DhtReplicationSettlement.Inputs(config.self(),
                                                                                                                  !configuredWorker(config),
                                                                                                                  kvStore,
                                                                                                                  dhtNode,
                                                                                                                  config.artifactRepo(),
                                                                                                                  () -> clusterNode.leaderManager()
                                                                                                                                   .isLeader(),
                                                                                                                  switchableCluster::apply,
                                                                                                                  () -> dhtWritingWorkers(Option.option(membershipFsmRef.get())),
                                                                                                                  member -> committedDeparture(Option.option(membershipFsmRef.get()),
                                                                                                                                               member),
                                                                                                                  delegateRouter::route,
                                                                                                                  System::currentTimeMillis,
                                                                                                                  () -> !clusterNode.isPendingCatchUp()
                                                                                                                  // v1882 r5: a replica accepts writes only once consensus has applied its log tail
                                                                                                                 ));

        dhtNode.onReplicationCaughtUp(_ -> dhtSettlement.report());
        // #1777 (owner rule): this node announces its own writes being refused as stale beyond the bound
        var dhtWriterStaleWatch = DhtWriterStaleWatch.dhtWriterStaleWatch(config.self(),
                                                                          dhtNode,
                                                                          delegateRouter::route,
                                                                          System::currentTimeMillis);
        var corePeerIds = config.topology()
                                .coreNodes()
                                .stream()
                                .map(NodeInfo::id)
                                .filter(id -> !id.equals(config.self()))
                                .collect(Collectors.toSet());
        var forwardingClusterNode = WorkerHierarchyAssembly.workerSwitch(new WorkerHierarchyAssembly.WorkerSwitchInputs(config,
                                                                                                                        clusterNode,
                                                                                                                        switchableCluster,
                                                                                                                        corePeerIds));

        record aetherNode(org.pragmatica.aether.worker.metadata.WorkerMetadataChannel workerMetadataChannel,
                          AetherNodeConfig config,
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
                          StreamPartitionManager streamPartitionManager,
                          SegmentIndex streamSegmentIndex,
                          StreamReadRouter streamReadRouter,
                          StreamWriteRouter streamWriteRouter,
                          ConsumerGroupCoordinator consumerGroupCoordinator,
                          ConsumerGroupRegistry consumerGroupRegistry,
                          StreamConsumerManager streamConsumerManager,
                          Option<ProjectionNodeSupport> projectionNodeSupport,
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
                          Consumer<Promise<Unit>> startSwimTrigger,
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
                          PeriodicTasks periodicTasks,
                          Option<KvBackupService> kvBackupService,
                          LeaderTerm leaderTerm,
                          // #932: the stream replica-set controller and backfill executor own one
                          // thread each per node; neither had a close on the stop path.
                          Runnable streamReplicationShutdown,
                          // #1968: ends a backup-less leader's open backup-config-missing alert before the event layer stops.
                          Runnable backupCoordinatorStop,
                          // #903: the provider whose shared (unattributed) scope only node shutdown
                          // can close.
                          Option<SpiResourceProvider> spiResourceProvider,
                          NodeReplacementService nodeReplacementService,
                          UpgradeRunService upgradeRunService,
                          StoragePrefetchChannel prefetchChannel) implements AetherNode {
            private static final Logger log = LoggerFactory.getLogger(aetherNode.class);

            @Override
            public Option<PrefetchGossipStats> prefetchGossipStats() {
                return PrefetchGossipStats.prefetchGossipStats(swimHealthDetector.appGossipStats(), prefetchChannel);
            }

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
                                       // #1308: the SWIM start's outcome is joined into this start —
                                       // see startClusterUnlessSwimFails.
                                       .flatMap(_ -> startClusterUnlessSwimFails())
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
                                       // #858: check/write each DHT-tier instance's encryption marker
                                       // here — AFTER startClusterAsync() resolves (cluster formation
                                       // complete, dhtClient can route). Was boot-time, inside the
                                       // constructor path (createAll → createOne), where it always blocked
                                       // the full 30 s DHT_MARKER_TIMEOUT because the DHTClient handed to
                                       // the constructor cannot route yet (#858).
                                       //
                                       // #1052: AFTER armPeriodicTasks, not before. The check now retries
                                       // for as long as the DHT cannot answer (a ring still converging),
                                       // and LeaderManager picks the lowest id of the sorted topology, so
                                       // a replacement still retrying can already be leader. Holding the
                                       // periodic work behind the check would switch off that leader's
                                       // generation bump, readiness sweep and activation heal. What must
                                       // not precede the check is enforced elsewhere, not by this order:
                                       // every DHT-tier get/put/delete/exists waits on the instance's
                                       // readGate (#874), and the node reports not-ready until every gate
                                       // admits (markSubsystemsReadyOnceDhtAdmitted). Only
                                       // EncryptionError.EncryptedTierRequiresKeyring (a marker present
                                       // with no keyring) fails start() — and Main#exitWithError still
                                       // stops the node on it; a timeout or an unreachable quorum is
                                       // retried and never exits.
                                       .flatMap(_ -> verifyDhtMarkers())
                                       .onSuccess(_ -> log.info("Aether node {} started, cluster forming...",
                                                                self()));
            }

            /// #858: post-formation DHT encryption-marker check/write, run once from [#start] after
            /// `startClusterAsync()` and `armPeriodicTasks`. Generic over `storageSetups`' CONTENTS —
            /// iterates whatever [StorageFactory.StorageSetup#dhtMarkerCheck] entries are present, no
            /// hardcoded instance list — so an instance that starts carrying a DHT tier later (#783:
            /// `content` routed through `createAll`) is covered automatically without touching this
            /// method. No `dhtClient` (no DHT-backed instance in this config) short-circuits to
            /// `Promise.UNIT` — nothing to check.
            ///
            /// #1052: retries until each check completes or definitively refuses; `periodicTasks`'
            /// cancellation is the loop's stop signal, so a stopped node stops retrying. In practice
            /// that signal is `stop()` alone: the other canceller, the failed-boot guard
            /// (`cancelArmedWork`, from `verifyRoutedTypesEncodable`), runs at construction, before
            /// [#start] and therefore before this loop exists. Consequence: a refusal leaves periodic
            /// work armed until whoever owns the node stops it (`Main#exitWithError`, Ember's
            /// `abortStart`, a test's tear-down).
            ///
            /// #1170: a refusal also takes back the disk markers `createAll` committed for this boot
            /// (`StorageFactory#admitDhtMarkers`), so backing out to `encrypted = false` is bootable.
            private Promise<Unit> verifyDhtMarkers() {
                return dhtClient.map(client -> StorageFactory.admitDhtMarkers(client,
                                                                              storageSetups,
                                                                              periodicTasks::isCancelled))
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
                // #1968: first, while the cluster-event layer can still carry the recovery of a backup-config-missing alert.
                backupCoordinatorStop.run();
                kvBackupService.onPresent(KvBackupService::stop);
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
                // #932: after the partition manager, whose close can still be answered by a backfill
                // already in flight; before the cluster node, which the controller's reconcile reads.
                streamReplicationShutdown.run();
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
                // The webhook forwarder owns a JDK HttpClient (a selector thread) when webhooks are enabled.
                alertManager.closeForwarder();
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
                //
                // #1612: the chain lives in NodeStopSequence, where a failed HTTP listener stop is logged and
                // skipped past rather than ending the shutdown.
                return NodeStopSequence.run(NodeStopSequence.Steps.steps(clusterDeploymentManager::deactivate,
                                                                         () -> managementServer.map(ManagementServer::stop)
                                                                                               .or(Promise.unitPromise()),
                                                                         appHttpServer::stop,
                                                                         sliceInvoker::stop,
                                                                         this::closeSharedResources,
                                                                         this::shutdownStorage,
                                                                         clusterNode::stop)).onSuccess(_ -> log.info("Aether node {} stopped",
                                                                                                                     self()));
            }

            /// #903: the shared (unattributed) resource scope is released by no slice's unload, because a
            /// caller with no slice identity has no "last consumer" (#268 R2). Node shutdown is the only
            /// point at which every consumer is gone, so it is closed here, after the slice invoker stops.
            private Promise<Unit> closeSharedResources() {
                return spiResourceProvider.map(SpiResourceProvider::closeShared)
                                          .or(Promise.unitPromise())
                                          .timeout(SHARED_SCOPE_CLOSE_BOUND);
            }

            /// #1078: the three node-owned storage instances (`content`, `artifacts`, `streams`)
            /// were never shut down — `StorageInstance.shutdown()` is the path that drains a
            /// write-behind queue, and no stop reached it. Placed after the slice invoker (the
            /// writers are stopped) and before the cluster node (a DHT tier can still be reached
            /// while draining). Infallible by design: `shutdown()` is a void contract, and a
            /// storage failure here must not skip `clusterNode.stop()` behind it.
            @Contract
            private Unit shutdownStorage() {
                stopStorageMaintenance(storageSetups);
                storageSetups.forEach((name, setup) -> shutdownStorage(name, setup));

                return Unit.unit();
            }

            @Contract
            @SuppressWarnings("JBCT-EX-01")
            private static void shutdownStorage(String name, StorageFactory.StorageSetup setup) {
                try {
                    setup.instance().shutdown();
                } catch (RuntimeException e) {
                    log.warn("Storage instance '{}' failed to shut down cleanly: {}", name, e.getMessage());
                }
            }

            /// #1308: register the SWIM start on transport-ready and JOIN its outcome into the node's
            /// start. `startSwim` fails the node through `failNode` (production: `Runtime.halt(2)`),
            /// but in a single-JVM host (Ember/Forge) `failNode` only stops this node, and a stopped
            /// node's `clusterNode.start()` never resolves — so a start chain that only observed
            /// formation hung forever on a SWIM bind failure (rev1343 BLOCKING-1: `EmberCluster.start()`
            /// sat on a 90 s bound with node 1 stopped and the other two formed). The host's start
            /// now settles with the SWIM failure itself, and takes its own abort path. The trigger fails
            /// `outcome` INLINE, before `failNode` runs (rev1343 NIT-R2): in Ember `failNode` blocks for
            /// the node's own stop, and peers' hellos can complete formation inside that window — a
            /// formation success landing first would report a started node that is being stopped.
            private Promise<Unit> startClusterUnlessSwimFails() {
                var outcome = Promise.<Unit> promise();

                clusterNode.network().whenReady(() -> startSwimTrigger.accept(outcome));

                return formationUnlessSwimFails(startClusterAsync(), outcome);
            }

            /// The in-process formation effects (event-loop metrics, the first election kick) are a DEPENDENT step
            /// (`withSuccess`): whoever awaited the start proceeds with them in place. Discovery registration blocks on the
            /// provider and the instance tag is an external signal bootstrap polls, so those two stay detached.
            private Promise<Unit> startClusterAsync() {
                return clusterNode.start()
                                  .withSuccess(_ -> runFormationEffects())
                                  .onSuccess(_ -> registerFormedNode())
                                  .onSuccess(_ -> printStartupBanner())
                                  .onFailure(cause -> log.error("Cluster formation failed: {}",
                                                                cause.message()));
            }

            // A dependent step must not fail the start it follows: a throw from the effects is logged, not propagated.
            // FER (degrade forward): formation has succeeded, so a failed metrics registration or election kick is absorbed with a
            // WARN. Guarantee earned: the start reports formation, not its by-products. Mechanism: a single run, the WARN is the record.
            @Contract
            private void runFormationEffects() {
                Result.lift(this::onClusterFormed).onFailure(cause -> log.warn("Cluster formation effects failed: {}",
                                                                               cause.message()));
            }

            @Contract
            private void onClusterFormed() {
                log.info("Aether node {} cluster formation complete", self());
                clusterNode.network().server().onPresent(this::registerEventLoopGroups);
                clusterNode.leaderManager().triggerElection();
            }

            private void registerEventLoopGroups(Server server) {
                eventLoopMetricsCollector.register(server.bossGroup());
                eventLoopMetricsCollector.register(server.workerGroup());
                log.info("Registered EventLoopGroups for metrics collection");
            }

            // Detached on purpose: `registerSelf` is awaited (it blocks its thread) and the tag is an external signal;
            // nothing in this process reads either after the start resolves.
            @Contract
            private void registerFormedNode() {
                discoveryProvider.onPresent(this::registerWithDiscovery);
                applyNodeIdTag();
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
            public Map<String, Long> metadataResourceMetrics() {
                return workerMetadataChannel.resourceMetrics();
            }

            @Override
            /// Transport counters (incl. `quic_boot_token_drops_total`, `boot_token_refusals_total`) plus
            /// `membership_process_evidence_refusals_total` — governor/admission evidence the membership
            /// FSM refused under terminal removal (#1528).
            public Map<String, Number> transportMetrics() {
                var metrics = new HashMap<String, Number>(clusterNode.network().transportMetrics());

                metrics.put("membership_process_evidence_refusals_total", membershipFsm.refusedProcessEvidenceCount());

                return Map.copyOf(metrics);
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
                return provisioningDiagnosticsOf(decision, clusterTopologyManagerInstance);
            }

            @Override
            public Option<BackupGenesis> backupGenesis() {
                return kvBackupService.map(BackupGenesis::backupGenesis);
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
            public Option<Integer> communityLiveMembers(String communityId) {
                return clusterDeploymentManager.communityLiveMembers(communityId);
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
            public org.pragmatica.consensus.rabia.VoterReconfigurationStatus voterReconfigurationStatus() {
                return clusterNode.voterReconfigurationStatus();
            }

            @Override
            public Option<Cause> ownRestoreFailure() {
                return clusterNode.ownRestoreFailure();
            }

            @Override
            public Option<Cause> restoredStateSaveFailure() {
                return clusterNode.restoredStateSaveFailure();
            }

            @Override
            public Set<NodeId> coreNodeIds() {
                return configuredWorker(config)
                       ? membershipFsm.memberDescriptors()
                                      .keySet()
                                      .stream()
                                      .filter(node -> isCoreMember(membershipFsm, node))
                                      .collect(Collectors.toUnmodifiableSet())
                       : installedVoterIds(clusterNode);
            }

            @Override
            public boolean hasCompleteClusterView() {
                return config.workerConfig()
                             .isEmpty()
                       && config.topology()
                                .coreNodes()
                                .stream()
                                .anyMatch(info -> info.id()
                                                      .equals(self()) && "core".equalsIgnoreCase(info.labels()
                                                                                                     .getOrDefault(NodeInfo.LABEL_ROLE,
                                                                                                                   "core")));
            }

            @Override
            public boolean isReady() {
                return runtimeReady(clusterNode);
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

            @Contract
            @Override
            public void partitionFrom(java.util.Set<NodeId> unreachable) {
                var cut = java.util.Set.copyOf(unreachable);

                clusterNode.network().setInboundFaultFilter((peer, _) -> !cut.contains(peer));
                swimHealthDetector.setSwimPeerPartition(cut);
            }

            @Override
            public Unit setInboundFaultFilter(java.util.function.BiPredicate<NodeId, Message.Wired> filter) {
                return clusterNode.network()
                                  .setInboundFaultFilter(filter);
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
        var invocationOut = SliceRuntimeAssembly.invocation(new SliceRuntimeAssembly.InvocationInputs(config,
                                                                                                      kvStore,
                                                                                                      clusterNode,
                                                                                                      serializer,
                                                                                                      deserializer,
                                                                                                      switchableCluster));
        var httpRoutePublisher = invocationOut.httpRoutePublisher();
        var invocationMetrics = invocationOut.invocationMetrics();
        var logLevelRegistry = invocationOut.logLevelRegistry();
        var traceStore = invocationOut.traceStore();
        var observabilityBaseline = invocationOut.observabilityBaseline();
        var configRegistry = invocationOut.configRegistry();
        var invocationHandler = invocationOut.invocationHandler();
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
        var lifecycleManager = TopologyAssembly.sourceLifecycle(new TopologyAssembly.SourceLifecycleInputs(config,
                                                                                                           kvStore,
                                                                                                           clusterNode,
                                                                                                           membershipFsmRef));
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
                                                                          .map(fsm -> installedCorePlacementMembers(fsm.coreCountedMembers(),
                                                                                                                    installedVoterIds(clusterNode)))
                                                                          .or(MembershipFsm.MEMBERSHIP_NOT_WIRED);
        // B4 (membership v2 §7.5): the CDM allocatable-gate reads the leader readiness view (READY
        // peers + self) instead of a KV lifecycle atom. Late-bound — the pong fan + self-state holder are
        // constructed further below; this ref mirrors the late-bound supplier pattern.
        var cdmReadyNodesRef = new AtomicReference<Supplier<Set<NodeId>>>(Set::of);
        Supplier<Set<NodeId>> stableCdmReadyNodesSupplier = () -> cdmReadyNodesRef.get()
                                                                                  .get();
        var retirementIndexesOut = TopologyAssembly.retirementIndexes(new TopologyAssembly.RetirementIndexesInputs(kvStore,
                                                                                                                   clusterNode));
        var communityRetirements = retirementIndexesOut.communityRetirements();
        var nodeReplacements = retirementIndexesOut.nodeReplacements();
        var upgradeRuns = retirementIndexesOut.upgradeRuns();
        // #1777 track 1: restored state is when the committed `[replication]` / `[cache]` factors become readable —
        // or are known to be absent, which means the built-in ones. Later commits re-resolve through the
        // ClusterConfigKey put below.
        clusterNode.onStateRestored(() -> {
            resolveDhtReplication(kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                   AetherValue.ClusterConfigValue.class),
                                  dhtNode,
                                  config,
                                  cacheDhtConfig,
                                  dhtSettlement);
            dhtSettlement.reapply();
        });
        var cdmDrainingNodesRef = new AtomicReference<Supplier<Set<NodeId>>>(Set::of);
        Supplier<Set<NodeId>> stableCdmDrainingNodesSupplier = () -> retirementCandidates(communityRetirements.including(cdmDrainingNodesRef.get()
                                                                                                                                            .get()),
                                                                                          clusterNode,
                                                                                          membershipFsmRef::get,
                                                                                          kvStore,
                                                                                          nodeReplacements,
                                                                                          readyCoreCandidates(stableCdmReadyNodesSupplier.get(),
                                                                                                              membershipFsmRef::get));
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
        var registriesOut = SliceRuntimeAssembly.registries();
        var endpointRegistry = registriesOut.endpointRegistry();
        var topicSubscriptionRegistry = registriesOut.topicSubscriptionRegistry();
        var scheduledTaskRegistry = registriesOut.scheduledTaskRegistry();
        var streamConsumerRegistry = registriesOut.streamConsumerRegistry();
        var scheduledTaskStateRegistry = registriesOut.scheduledTaskStateRegistry();
        var httpRouteRegistry = registriesOut.httpRouteRegistry();
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
        var workerProjectionFreshRef = new AtomicReference<java.util.function.BooleanSupplier>(() -> !configuredWorker(config));
        var nodeReportedStateHolder = NodeReportedStateHolder.nodeReportedStateHolder(() -> runtimeReady(clusterNode) && workerProjectionFreshRef.get()
                                                                                                                                                 .getAsBoolean());
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
        Supplier<Long> rabiaTermSupplier = leaderTerm::current;
        // #114 W1: the cluster-sync ping carries a monotonic generation counter. Each node bumps its
        // OWN counter only while it is leader — ONCE PER pingInterval (1s default), so the counter is
        // a LEADERSHIP-TENURE TICK, not an event count: "1:4254" means rabiaTerm 1 with ~71 minutes of
        // uninterrupted leadership, which is ordinary (#634 follow-up — this exact value was once
        // investigated as an anomaly because the per-interval semantics were written down nowhere).
        // The supplier merely READS it (it is consumed in four contexts; incrementing inside would
        // over-count). No reset is needed: Epoch ordering is term-dominant, and the term is the
        // committed LeaderValue.viewSequence of this node's election (S28, see LeaderTerm) — strictly
        // above every prior committed leadership cluster-wide — so a new leader's lower local counter
        // still orders strictly after the prior leader's epoch via the term.
        var generationCounter = epochSources.generationCounter();
        Supplier<Epoch> leaderEpochSupplier = epochSources.leaderEpoch();

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
        var drain = DrainAssembly.drainInfrastructure(new DrainAssembly.InfrastructureInputs(config,
                                                                                             kvStore,
                                                                                             clusterNode,
                                                                                             invocationHandler,
                                                                                             workerProjectionFreshRef,
                                                                                             departingSendersRef,
                                                                                             metricsCollector,
                                                                                             dhtRebalancer,
                                                                                             dhtTopologyListener,
                                                                                             nodeReportedStateHolder,
                                                                                             storageSetups,
                                                                                             jvmExit));
        var inFlightTrackerForDrain = drain.inFlightTrackerForDrain();
        var clusterEventDrainEmitterRef = drain.clusterEventDrainEmitterRef();
        var departurePushObserverRef = drain.departurePushObserverRef();
        var scheduledTaskDrainHookRef = drain.scheduledTaskDrainHookRef();
        var drainProcedure = drain.drainProcedure();
        var communityDrain = drain.communityDrain();
        var topologyManagerOut = TopologyAssembly.topologyManager(new TopologyAssembly.TopologyManagerInputs(config,
                                                                                                             kvStore,
                                                                                                             clusterNode,
                                                                                                             membershipFsmRef,
                                                                                                             lifecycleManager,
                                                                                                             deploymentMap,
                                                                                                             stableCdmReadyNodesSupplier,
                                                                                                             nodeReplacements,
                                                                                                             metricsCollector,
                                                                                                             isLeaderSupplier,
                                                                                                             drainCommandRegistry,
                                                                                                             clusterConfigReader,
                                                                                                             clusterCommandApplier,
                                                                                                             leaderAwareSnapshotSource,
                                                                                                             periodicTasks::defer));
        var effectivePhaseSupplier = topologyManagerOut.effectivePhaseSupplier();
        var swimHealthDetectorHolder = topologyManagerOut.swimHealthDetectorHolder();
        var leaderReconcilerRef = topologyManagerOut.leaderReconcilerRef();
        var configuredCoreCountSupplier = topologyManagerOut.configuredCoreCountSupplier();
        var clusterTopologyManager = topologyManagerOut.clusterTopologyManager();
        var coreAdmission = topologyManagerOut.coreAdmission();
        var controller = DecisionTreeController.decisionTreeController(config.controllerConfig());
        var mavenProtocolHandler = MavenProtocolHandler.mavenProtocolHandler(artifactStore);
        var deploymentManager = DeploymentManager.deploymentManager(clusterNode, kvStore);
        var alertManager = AlertManager.alertManager(clusterNode, kvStore);

        alertManager.bindAlertConfig(resolveAlertConfig(config));
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
        var eventAggregatorOut = ClusterEventsAssembly.eventAggregator(new ClusterEventsAssembly.EventAggregatorInputs(config,
                                                                                                                       kvStore,
                                                                                                                       hlcClock,
                                                                                                                       clusterDeploymentManager,
                                                                                                                       isLeaderSupplier,
                                                                                                                       clusterTopologyManager,
                                                                                                                       periodicTasks::defer));
        var clusterEventsHlcClock = eventAggregatorOut.clusterEventsHlcClock();
        var clusterEventsPublisherRef = eventAggregatorOut.clusterEventsPublisherRef();
        var clusterEventsConsumerRef = eventAggregatorOut.clusterEventsConsumerRef();
        var clusterEventsControllerRef = eventAggregatorOut.clusterEventsControllerRef();
        var clusterEventsStreamName = eventAggregatorOut.clusterEventsStreamName();
        var clusterEventsOwnerCheck = eventAggregatorOut.clusterEventsOwnerCheck();
        var eventAggregator = eventAggregatorOut.eventAggregator();
        var operatorWarningSink = eventAggregatorOut.operatorWarningSink();
        // #1564 / #1617 (R10): deploy warnings are raised through this node's operator-warning sink.
        var blueprintService = BlueprintService.blueprintService(clusterNode,
                                                                 kvStore,
                                                                 repository,
                                                                 artifactStore,
                                                                 resourceProviderSetup.nodeComposite(),
                                                                 operatorWarningSink,
                                                                 config.appHttp()
                                                                       .apiVersioningDetection()
                                                                       .isHeaderMode());

        ClusterEventsAssembly.eventTick(new ClusterEventsAssembly.EventTickInputs(eventAggregator, periodicTasks::defer));
        var placementReconciler = TopologyAssembly.placementReconciler(new TopologyAssembly.PlacementReconcilerInputs(config,
                                                                                                                      kvStore,
                                                                                                                      clusterNode,
                                                                                                                      hlcClock,
                                                                                                                      membershipFsmRef,
                                                                                                                      lifecycleManager,
                                                                                                                      deploymentMap,
                                                                                                                      stableCdmReadyNodesSupplier,
                                                                                                                      stableCdmDrainingNodesSupplier,
                                                                                                                      isLeaderSupplier,
                                                                                                                      clusterCommandApplier,
                                                                                                                      drain,
                                                                                                                      clusterTopologyManager,
                                                                                                                      eventAggregator,
                                                                                                                      operatorWarningSink));

        ClusterEventsAssembly.eventSinks(new ClusterEventsAssembly.EventSinksInputs(config,
                                                                                    clusterNode,
                                                                                    traceStore,
                                                                                    clusterEventDrainEmitterRef,
                                                                                    departurePushObserverRef,
                                                                                    drainProcedure,
                                                                                    alertManager,
                                                                                    clusterEventsHlcClock,
                                                                                    eventAggregator));
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

        metricsScheduler.setSourceMetricsSupplier(() -> List.copyOf(controlLoop.communitySnapshots().values()));
        var workerMetricsAggregator = WorkerMetricsAggregator.workerMetricsAggregator(config.self(),
                                                                                      () -> config.self()
                                                                                                  .id(),
                                                                                      invocationMetrics,
                                                                                      snapshot -> publishSourceMetrics(clusterNode,
                                                                                                                       membershipFsmRef::get,
                                                                                                                       controlLoop,
                                                                                                                       snapshot),
                                                                                      config.controllerConfig()
                                                                                            .scalingConfig()
                                                                                            .evaluationInterval()
                                                                                            .millis());
        // #1573: rollback policy is cluster-wide — the committed cluster TOML's [rollback] section, read at every
        // decision, so committing `enabled = false` turns automatic rollback off without a restart.
        var rollbackManager = RollbackManager.rollbackManager(config.self(),
                                                              () -> committedRollbackPolicy(clusterConfigReader),
                                                              clusterNode,
                                                              kvStore,
                                                              clusterNode.leaderManager(),
                                                              delegateRouter::route);
        // #1573: the one producer of AllInstancesFailed. Every node ships its slice execution outcomes on the
        // cluster-sync pong; the leader alone judges "every ACTIVE instance of this version is broken" and
        // routes the event to rollback, the cluster event and the alert. The tick forgets its state on a
        // non-leader, so a new leader starts from fresh windows.
        var allInstancesFailedDetector = AllInstancesFailedDetector.allInstancesFailedDetector(metricsCollector::allObservations,
                                                                                               () -> activeInstancesByArtifact(deploymentMap,
                                                                                                                               Option.option(membershipFsmRef.get())),
                                                                                               isLeaderSupplier,
                                                                                               delegateRouter::route,
                                                                                               System::currentTimeMillis);

        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(allInstancesFailedDetector::tick,
                                                                      config.timeouts().cluster().pingInterval(),
                                                                      config.timeouts().cluster().pingInterval()));
        var sliceInvokerOut = SliceRuntimeAssembly.sliceInvoker(new SliceRuntimeAssembly.SliceInvokerInputs(config,
                                                                                                            delegateRouter,
                                                                                                            kvStore,
                                                                                                            deferredInvoker,
                                                                                                            clusterNode,
                                                                                                            serializer,
                                                                                                            deserializer,
                                                                                                            baseDhtClient,
                                                                                                            cacheDhtClient,
                                                                                                            contentStorage,
                                                                                                            resourceProviderSetup,
                                                                                                            switchableCluster,
                                                                                                            invocationMetrics,
                                                                                                            invocationHandler,
                                                                                                            endpointRegistry,
                                                                                                            topicSubscriptionRegistry,
                                                                                                            scheduledTaskRegistry,
                                                                                                            scheduledTaskStateRegistry,
                                                                                                            scheduledTaskDrainHookRef,
                                                                                                            deploymentManager,
                                                                                                            operatorWarningSink));
        var abTestManager = sliceInvokerOut.abTestManager();
        var sliceInvoker = sliceInvokerOut.sliceInvoker();
        var scheduledTaskManager = sliceInvokerOut.scheduledTaskManager();
        var selfAddress = findSelfAddress(config);
        var nodeDeploymentManager = NodeDeploymentManager.nodeDeploymentManagerFromSnapshot(config.self(),
                                                                                            selfAddress,
                                                                                            delegateRouter,
                                                                                            sliceStore,
                                                                                            switchableCluster,
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
        // #1935: a declared stream consumer that cannot be registered reaches THIS node's event log.
        nodeDeploymentManager.setOperatorWarningSink(operatorWarningSink);
        var appHttpServerOut = SliceRuntimeAssembly.appHttpServer(new SliceRuntimeAssembly.AppHttpServerInputs(config,
                                                                                                               clusterNode,
                                                                                                               serializer,
                                                                                                               deserializer,
                                                                                                               membershipFsmRef,
                                                                                                               leaderCatchUpGate,
                                                                                                               httpRoutePublisher,
                                                                                                               invocationMetrics,
                                                                                                               httpRouteRegistry,
                                                                                                               workerProjectionFreshRef,
                                                                                                               inFlightTrackerForDrain,
                                                                                                               deploymentManager,
                                                                                                               operatorWarningSink,
                                                                                                               sliceInvoker));
        var serverBossGroup = appHttpServerOut.serverBossGroup();
        var serverWorkerGroup = appHttpServerOut.serverWorkerGroup();
        var taskGroupOwnerResolver = appHttpServerOut.taskGroupOwnerResolver();
        var workerEndpointDirectory = appHttpServerOut.workerEndpointDirectory();
        var appHttpServer = appHttpServerOut.appHttpServer();
        var storageMaintenanceOut = StorageAssembly.storageMaintenance(new StorageAssembly.StorageMaintenanceInputs(config,
                                                                                                                    storageSetups,
                                                                                                                    operatorWarningSink));
        var delegatedStorageAdapter = storageMaintenanceOut.delegatedStorageAdapter();
        var storageMaintenanceDriver = storageMaintenanceOut.storageMaintenanceDriver();
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

        TopologyAssembly.placementDrainRoutes(new TopologyAssembly.PlacementDrainRoutesInputs(config,
                                                                                              clusterNode,
                                                                                              communityDrain,
                                                                                              placementReconciler,
                                                                                              aetherEntries));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.GetRequest.class,
                                                    request -> dhtNode.handleGetRequest(request,
                                                                                        response -> dhtNetwork.send(request.sender(),
                                                                                                                    response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.PutRequest.class,
                                                    request -> dhtNode.handlePutRequest(request,
                                                                                        response -> dhtNetwork.send(request.sender(),
                                                                                                                    response))));
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.RemoveRequest.class,
                                                    request -> dhtNode.handleRemoveRequest(request,
                                                                                           response -> dhtNetwork.send(request.sender(),
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
        routeDhtRepairPlane(aetherEntries, dhtNode, dhtNetwork, dhtAntiEntropy);
        // #427: a graceful-departure push's ack resolves the departing node's pending push, so its
        // drain can confirm the chunk reached a surviving replica before halting.
        aetherEntries.add(MessageRouter.Entry.route(DHTMessage.MigrationDataAck.class, dhtRebalancer::onMigrationDataAck));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                                    event -> {
                                                        if (!configuredWorker(config)) {
                                                            dhtTopologyListener.onNodeJoined(event);
                                                        }
                                                    }));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                                    event -> {
                                                        if (!configuredWorker(config)) {
                                                            dhtTopologyListener.onNodeRemoved(event);
                                                        }
                                                    }));
        aetherEntries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                                    event -> {
                                                        if (!configuredWorker(config)) {
                                                            dhtTopologyListener.onNodeDecommissioned(event);
                                                        }
                                                    }));
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
        var gossipEncryptionOut = SwimAssembly.gossipEncryption(new SwimAssembly.GossipEncryptionInputs(config));
        var rotatingEncryptor = gossipEncryptionOut.rotatingEncryptor();
        var gossipKeyRotationHandler = gossipEncryptionOut.gossipKeyRotationHandler();
        var workerActivationOut = WorkerHierarchyAssembly.workerActivation(new WorkerHierarchyAssembly.WorkerActivationInputs(config,
                                                                                                                              delegateRouter,
                                                                                                                              kvStore,
                                                                                                                              clusterNode,
                                                                                                                              membershipFsmRef,
                                                                                                                              sliceStore,
                                                                                                                              switchableCluster,
                                                                                                                              forwardingClusterNode,
                                                                                                                              communityRetirements,
                                                                                                                              nodeReplacements,
                                                                                                                              upgradeRuns,
                                                                                                                              clusterDeploymentManager,
                                                                                                                              workerProjectionFreshRef,
                                                                                                                              swimHealthDetectorHolder,
                                                                                                                              sliceInvoker,
                                                                                                                              aetherEntries,
                                                                                                                              growthLog,
                                                                                                                              selfId,
                                                                                                                              gossipKeyRotationHandler));
        var governorAnnouncerHolder = workerActivationOut.governorAnnouncerHolder();
        var communityDirectory = workerActivationOut.communityDirectory();
        var activationKvRouter = workerActivationOut.activationKvRouter();
        // Gossip-key delivery (§5.8 AMENDED): the GossipKeyRotationKey subscription above is the
        // SOLE delivery path. A late joiner that synced AFTER the rotation PUT receives the
        // current rotation as a replayed ValuePut on this normal subscription once the engine
        // activates (sync → activate → replay) — no ad-hoc replayFromStore needed. applyRotation
        // is idempotent, so a later live rotation re-PUT is harmless.
        //
        // #683 round 2 — WHAT THIS ORDERING DOES **NOT** GUARANTEE. An earlier version of this
        // comment said the replay burst "structurally precedes any live apply, so the joiner
        // adopts the cluster key before it sends its first SWIM datagram". The first half is true
        // of KV notification ordering; the second is false of SWIM datagram ordering, and it was
        // the safety argument for late joiners. SWIM starts on QUIC transport-ready
        // (`clusterNode.network().whenReady(startSwimTrigger)`), deliberately BEFORE
        // startClusterAsync() resolves — so the joiner's first SWIM datagram is sent strictly
        // EARLIER in boot than the replay, which is reached only via restore → activate → replay
        // inside the consensus engine.
        //
        // The consequence is a cycle, not merely a window: a node booting into a cluster that has
        // already rotated encrypts SWIM under its cluster_secret-derived key, which the rotated
        // accept set no longer contains, so peers drop its datagrams and it drops theirs. SWIM
        // discovers nothing; the QUIC dial set is self-only and SWIM is its sole writer besides
        // self (TopologyObserverTest.SwimOnlyDialSet); without peers there is no quorum, and
        // without quorum there is no sync/activate/replay — so the record that would install the
        // cluster key never arrives. GossipKeyRotationBootDivergenceTest pins both directions of
        // the key divergence this rests on.
        var allEntries = new ArrayList<>(clusterNode.routeEntries());

        allEntries.addAll(epochSources.incarnation().routeEntries());
        allEntries.addAll(aetherEntries);
        allEntries.addAll(activationKvRouter.asRouteEntries());
        var swimDetectorOut = SwimAssembly.swimDetector(new SwimAssembly.SwimDetectorInputs(config,
                                                                                            delegateRouter,
                                                                                            clusterNode,
                                                                                            serializer,
                                                                                            deserializer,
                                                                                            jvmExit,
                                                                                            identityRefusedExit,
                                                                                            gossipKeyDivergedExit,
                                                                                            bootToken,
                                                                                            metricsCollector,
                                                                                            peerObservationStore,
                                                                                            isLeaderSupplier,
                                                                                            leaderEpochSupplier,
                                                                                            effectivePhaseSupplier,
                                                                                            swimHealthDetectorHolder,
                                                                                            operatorWarningSink,
                                                                                            workerMetricsAggregator,
                                                                                            selfId,
                                                                                            rotatingEncryptor,
                                                                                            allEntries));
        var swimBootAtMs = swimDetectorOut.swimBootAtMs();
        var swimIsBootingSupplier = swimDetectorOut.swimIsBootingSupplier();
        var swimHealthDetector = swimDetectorOut.swimHealthDetector();
        var startSwimTrigger = swimDetectorOut.startSwimTrigger();
        var membershipFsmOut = MembershipAssembly.membershipFsm(new MembershipAssembly.MembershipFsmInputs(config,
                                                                                                           clusterNode,
                                                                                                           membershipFsmRef,
                                                                                                           metricsCollector,
                                                                                                           clusterConfigReader,
                                                                                                           leaderReconcilerRef,
                                                                                                           controlLoop,
                                                                                                           quorumLossDetectorRef,
                                                                                                           swimHealthDetector));
        var membershipConfig = membershipFsmOut.membershipConfig();
        var presenceSampler = membershipFsmOut.presenceSampler();
        var quorumLossDetector = membershipFsmOut.quorumLossDetector();
        var clusterNameSupplier = membershipFsmOut.clusterNameSupplier();
        var membershipFsm = membershipFsmOut.membershipFsm();
        var workerMetadataOut = WorkerHierarchyAssembly.workerMetadata(new WorkerHierarchyAssembly.WorkerMetadataInputs(config,
                                                                                                                        kvStore,
                                                                                                                        clusterNode,
                                                                                                                        nodeCodec,
                                                                                                                        dhtNode,
                                                                                                                        epochSources,
                                                                                                                        hlcClock,
                                                                                                                        bootToken,
                                                                                                                        cacheDhtConfig,
                                                                                                                        switchableCluster,
                                                                                                                        dhtSettlement,
                                                                                                                        dhtWriterStaleWatch,
                                                                                                                        forwardingClusterNode,
                                                                                                                        cdmReadyNodesRef,
                                                                                                                        clusterDeploymentManager,
                                                                                                                        metricsCollector,
                                                                                                                        workerProjectionFreshRef,
                                                                                                                        nodeReportedStateHolder,
                                                                                                                        leaderEpochSupplier,
                                                                                                                        eventAggregator,
                                                                                                                        controlLoop,
                                                                                                                        nodeDeploymentManager,
                                                                                                                        workerEndpointDirectory,
                                                                                                                        governorAnnouncerHolder,
                                                                                                                        communityDirectory,
                                                                                                                        allEntries,
                                                                                                                        swimHealthDetector,
                                                                                                                        membershipFsm,
                                                                                                                        periodicTasks::defer));
        var routingCoreIds = workerMetadataOut.routingCoreIds();
        var workerMetadataChannel = workerMetadataOut.workerMetadataChannel();
        var governorLookup = workerMetadataOut.governorLookup();
        var communityHealth = workerMetadataOut.communityHealth();
        var candidateHealth = workerMetadataOut.candidateHealth();
        var workerAdmission = workerMetadataOut.workerAdmission();
        var communityHealthRuntime = workerMetadataOut.communityHealthRuntime();
        // End hierarchical health assembly. Missing reports affect placement, never membership death.
        DrainAssembly.bindDrainReport(pongSignalFan, membershipFsm, clusterDeploymentManager);
        var membershipJournalOut = MembershipAssembly.membershipJournal(new MembershipAssembly.MembershipJournalInputs(config,
                                                                                                                       delegateRouter,
                                                                                                                       clusterNode,
                                                                                                                       hlcClock,
                                                                                                                       snapshotSource,
                                                                                                                       metricsCollector,
                                                                                                                       drainCommandRegistry,
                                                                                                                       metricsScheduler,
                                                                                                                       coreAdmission,
                                                                                                                       alertManager,
                                                                                                                       clusterEventsControllerRef,
                                                                                                                       managementServerRef,
                                                                                                                       quorumLossDetectorRef,
                                                                                                                       allEntries,
                                                                                                                       presenceSampler,
                                                                                                                       membershipFsm));
        var transitionJournal = membershipJournalOut.transitionJournal();
        var topologyObserver = membershipJournalOut.topologyObserver();
        var membershipDeltaProjector = membershipJournalOut.membershipDeltaProjector();
        var configuredTransferPeers = membershipJournalOut.configuredTransferPeers();
        var transferPeer = membershipJournalOut.transferPeer();
        var dialingSinceNanos = membershipJournalOut.dialingSinceNanos();
        var leaderReconcilerOut = TopologyAssembly.leaderReconciler(new TopologyAssembly.LeaderReconcilerInputs(config,
                                                                                                                kvStore,
                                                                                                                clusterNode,
                                                                                                                dhtNode,
                                                                                                                leaderTerm,
                                                                                                                membershipFsmRef,
                                                                                                                lifecycleManager,
                                                                                                                stableCdmReadyNodesSupplier,
                                                                                                                nodeReplacements,
                                                                                                                upgradeRuns,
                                                                                                                metricsCollector,
                                                                                                                isLeaderSupplier,
                                                                                                                metricsScheduler,
                                                                                                                leaderReconcilerRef,
                                                                                                                configuredCoreCountSupplier,
                                                                                                                clusterTopologyManager,
                                                                                                                coreAdmission,
                                                                                                                clusterEventsOwnerCheck,
                                                                                                                operatorWarningSink,
                                                                                                                placementReconciler,
                                                                                                                managementServerRef,
                                                                                                                membershipConfig,
                                                                                                                presenceSampler,
                                                                                                                clusterNameSupplier,
                                                                                                                membershipFsm,
                                                                                                                periodicTasks::defer));
        var leaderReconciler = leaderReconcilerOut.leaderReconciler();
        var replacementWiring = leaderReconcilerOut.replacementWiring();
        var upgradeWiring = leaderReconcilerOut.upgradeWiring();

        MembershipAssembly.swimObservations(new MembershipAssembly.SwimObservationsInputs(clusterTopologyManager,
                                                                                          swimHealthDetector,
                                                                                          presenceSampler,
                                                                                          membershipFsm,
                                                                                          leaderReconciler));
        var quorumLossDrain = DrainAssembly.quorumLossDrain(new DrainAssembly.QuorumLossInputs(config,
                                                                                               clusterNode,
                                                                                               leaderReconciler,
                                                                                               quorumLossDetector,
                                                                                               membershipFsm,
                                                                                               swimHealthDetector,
                                                                                               swimIsBootingSupplier,
                                                                                               metricsCollector,
                                                                                               nodeReportedStateHolder,
                                                                                               drainProcedure,
                                                                                               alertManager,
                                                                                               isLeaderSupplier,
                                                                                               drainCommandRegistry,
                                                                                               operatorWarningSink,
                                                                                               periodicTasks::defer));
        var quorumLossChain = quorumLossDrain.quorumLossChain();
        var coreAbsenceDetector = quorumLossDrain.coreAbsenceDetector();

        WorkerHierarchyAssembly.communityReachability(new WorkerHierarchyAssembly.CommunityReachabilityInputs(config,
                                                                                                              clusterDeploymentManager,
                                                                                                              metricsCollector,
                                                                                                              communityDirectory,
                                                                                                              allEntries,
                                                                                                              membershipFsm,
                                                                                                              communityHealth,
                                                                                                              communityHealthRuntime));
        var membershipEdgesOut = MembershipAssembly.membershipEdges(new MembershipAssembly.MembershipEdgesInputs(config,
                                                                                                                 delegateRouter,
                                                                                                                 kvStore,
                                                                                                                 clusterNode,
                                                                                                                 serializer,
                                                                                                                 deserializer,
                                                                                                                 storageSetups,
                                                                                                                 dhtTopologyListener,
                                                                                                                 dhtSettlement,
                                                                                                                 metricsCollector,
                                                                                                                 drainCommandRegistry,
                                                                                                                 pongSignalFan,
                                                                                                                 metricsScheduler,
                                                                                                                 leaderReconcilerRef,
                                                                                                                 alertManager,
                                                                                                                 eventAggregator,
                                                                                                                 operatorWarningSink,
                                                                                                                 workerEndpointDirectory,
                                                                                                                 quorumLossDetectorRef,
                                                                                                                 communityDirectory,
                                                                                                                 allEntries,
                                                                                                                 swimHealthDetector,
                                                                                                                 presenceSampler,
                                                                                                                 membershipFsm,
                                                                                                                 routingCoreIds,
                                                                                                                 governorLookup,
                                                                                                                 candidateHealth,
                                                                                                                 workerAdmission,
                                                                                                                 transitionJournal,
                                                                                                                 topologyObserver,
                                                                                                                 membershipDeltaProjector,
                                                                                                                 configuredTransferPeers,
                                                                                                                 transferPeer,
                                                                                                                 dialingSinceNanos,
                                                                                                                 leaderReconciler,
                                                                                                                 periodicTasks::defer));
        var nttConnectTap = membershipEdgesOut.nttConnectTap();
        var nttDisconnectTap = membershipEdgesOut.nttDisconnectTap();
        var prefetchChannel = membershipEdgesOut.prefetchChannel();
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
                                                              epochSources.generationEpoch(),
                                                              () -> isLeaderSupplier.getAsBoolean()
                                                                    ? Option.some(leaderTerm.current())
                                                                    : Option.<Long> none(),
                                                              hlcClock,
                                                              membershipFsm::coreCountedMembers,
                                                              kvStore::snapshot,
                                                              config::self,
                                                              configBaselineSupplier,
                                                              clusterNode);

        MembershipAssembly.quicReporters(new MembershipAssembly.QuicReportersInputs(clusterNode,
                                                                                    epochSources,
                                                                                    peerObservationStore,
                                                                                    leaderEpochSupplier,
                                                                                    metricsScheduler,
                                                                                    swimHealthDetector,
                                                                                    membershipFsm,
                                                                                    nttConnectTap,
                                                                                    nttDisconnectTap));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> onLeaderChangeForPublisher(change,
                                                                                      leaderTerm,
                                                                                      bootstrapModule)));
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                                 notification -> onLeaderKeyCommit(notification, leaderTerm)));
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
                                                 // #1533: the ONE restore-commit hook — see onRestoreDecision.
                                                 .onPut(AetherKey.BackupRestoreKey.class,
                                                        (KVStoreNotification.ValuePut<AetherKey.BackupRestoreKey, AetherValue.BackupRestoreValue> put) -> onRestoreDecision(put,
                                                                                                                                                                            bootstrapModule,
                                                                                                                                                                            deploymentManager,
                                                                                                                                                                            abTestManager))
                                                 .onPut(AetherKey.ClusterConfigKey.class,
                                                        (KVStoreNotification.ValuePut<AetherKey.ClusterConfigKey, AetherValue.ClusterConfigValue> put) -> onClusterConfigPut(put,
                                                                                                                                                                             clusterTopologyManager,
                                                                                                                                                                             leaderReconciler))
                                                 // #1777 track 1: a committed `[replication]` change re-places the DHT live; R1b: the
                                                 // leader commits the change record it carries
                                                 .onPut(AetherKey.ClusterConfigKey.class,
                                                        (KVStoreNotification.ValuePut<AetherKey.ClusterConfigKey, AetherValue.ClusterConfigValue> put) -> {
                                                            resolveDhtReplication(Option.some(put.cause().value()),
                                                                                  dhtNode,
                                                                                  config,
                                                                                  cacheDhtConfig,
                                                                                  dhtSettlement);
                                                            dhtSettlement.onConfigCommitted(put.cause().value());
                                                        })
                                                 // #1777 R1b: every node follows the committed change; the leader advances it on reports
                                                 .onPut(AetherKey.DhtReplicationChangeKey.class,
                                                        (KVStoreNotification.ValuePut<AetherKey.DhtReplicationChangeKey, AetherValue.DhtReplicationChangeValue> put) -> dhtSettlement.onChangeCommitted(put.oldValue(),
                                                                                                                                                                                                        put.cause()
                                                                                                                                                                                                           .value()))
                                                 .onPut(AetherKey.DhtReplicationReportKey.class,
                                                        _ -> dhtSettlement.evaluate())
                                                 .build();

        allEntries.addAll(healthKvRouter.asRouteEntries());
        var spokesmanPingLoop = SpokesmanPingLoop.spokesmanPingLoop(config.self(),
                                                                    clusterNode.network(),
                                                                    config.timeouts().cluster().pingInterval(),
                                                                    rabiaTermSupplier,
                                                                    metricsCollector::allObservations,
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
        var replicationNetwork = clusterNode.network();
        org.pragmatica.aether.stream.replication.ReplicationTransport streamReplicationTransport = new org.pragmatica.aether.stream.replication.ReplicationTransport() {
            @Contract
            @Override
            public void send(NodeId target, org.pragmatica.aether.stream.replication.ReplicationMessage message) {
                replicationNetwork.send(target, message);
            }

            @Contract
            @Override
            public void send(NodeId target,
                             org.pragmatica.aether.stream.replication.ReplicationMessage message,
                             TimeSpan callerWait) {
                replicationNetwork.send(target, message, callerWait);
            }
        };
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
        // (StreamPartitionManager, below) and the backfill/recovery seam (streamAlignedRecovery, below), so
        // a recovered/backfilled event carries the SAME committed fencing token a live append would. Both
        // read the identical committed StreamPartitionOwnershipValue.ownerEpoch the fence high-water derives
        // from — otherwise the recovery seam's Epoch.ZERO (0:0) is rejected by an advanced high-water (1:N).
        var streamOwnerEpochSource = KvStreamOwnerEpochSource.kvStreamOwnerEpochSource(kvStore);
        // #1730 phase 2 (B11): the owner counts a replica's confirmation only under the committed epoch now in force.
        streamReplicationManager.ownerEpochs(streamOwnerEpochSource);
        // #1234: the sealer retains each evicted segment until storage has it; those copies are capped at the
        // node's stream memory budget, and only past that cap are appends refused (SEALING_BEHIND).
        // #1345: WAL truncation is bounded by the refs in the latest metadata snapshot ON DISK — the watermark
        // the `rebuildFromRefs` above would compute at the next boot — never by the live index, which runs
        // ahead of disk by every seal since that snapshot (STREAM_SNAPSHOT_* in StorageFactory bound the lag).
        // #1240: the entity log substrate asks the same sealer which evicted offsets are still in flight.
        // #1441: a partition WITHOUT a WAL has nothing above that snapshot but the refs, so its seals force a
        // snapshot before they resolve; boot's rebuilt floor then covers every completed seal on both paths.
        var streamSegmentSealer = SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(streamStorage,
                                                                                                    streamSegmentIndex,
                                                                                                    RefDurability.snapshotted(streamStorageSetup.snapshotManager())),
                                                              streamMaxMemoryBytes);
        var streamPartitionManager = StreamPartitionManager.streamPartitionManager(streamMaxMemoryBytes,
                                                                                   streamSegmentSealer,
                                                                                   streamReplicationManager,
                                                                                   clusterNode,
                                                                                   ownershipEpochHighWater,
                                                                                   streamOwnerEpochSource,
                                                                                   streamLogs(streamStorage),
                                                                                   streamSegmentIndex::lastSealedOffset,
                                                                                   DurableSealedOffsetSource.fromLatestSnapshot(streamStorageSetup.snapshotManager(),
                                                                                                                                streamSegmentIndex::adoptions));

        streamPartitionManagerRef.set(streamPartitionManager);
        // #1278 review: each stream's durable refs (sealed segments, reclaimed-through floors) and WAL are keyed by its
        // incarnation; the manager adopts the committed life before a stream materializes, so another life of the
        // name is never read, and reclaims the other lives' refs best-effort.
        streamPartitionManager.streamFootprint(StreamFootprint.streamFootprint(streamStorage,
                                                                               streamStorageSetup.metadataStore(),
                                                                               streamSegmentIndex));
        // #1596: provenance failures this node detects (an N13 mismatch at catch-up, a log holding records with no
        // owner-epoch history) are raised on the durable, backed-up partition flag -- the same flag the promotion
        // gate and cold-restart detection raise and read.
        streamPartitionManager.partitionFlags(PartitionFlags.kvPartitionFlags(clusterNode, kvStore, nodeCodec));
        streamPartitionManager.operatorWarnings(operatorWarningSink);
        // #1730 phase 2: a durable cut whose repair is not settled within twelve backfill redrive ticks is reported anyway.
        streamPartitionManager.repairReportBound(TimeSpan.timeSpan(STREAM_BACKFILL_REDRIVE_INTERVAL.millis() * 12L).millis());
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
        // #1883: the in-sync set falling below / returning to the confirmation factor, derived from the committed ownership
        // Put AND from a committed config Put that moves the factor. The config handler runs FIRST: it reads the factor
        // the manager enforces before the Put and the one it will enforce after it, which the manager's own handler
        // (next) then installs.
        var streamIsrAnnouncer = StreamIsrAnnouncer.streamIsrAnnouncer(streamPartitionManager::confirmationFactorFor,
                                                                       streamPartitionManager::confirmationFactorAfter,
                                                                       streamPartitionManager::enforcedConfig,
                                                                       (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                       partition),
                                                                                                               StreamPartitionOwnershipValue.class),
                                                                       delegateRouter::route);
        var streamConfigKvRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                       .onPut(AetherKey.StreamConfigKey.class,
                                                              streamIsrAnnouncer::onConfigPut)
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
        // bytes (off-heap hard cap), and age, mode ANY — see ClusterEventsLimits, checked at boot (#1549). The publisher/consumer refs the aggregator was
        // constructed with are bound here; until then emits fall back to log-only (bootstrap window).
        // #1564 (N2): the local partition the publisher/consumer wiring below creates at construction carries the
        // SAME factors the SystemStreamRegistrar commits — the committed `[replication.cluster_events]` as this node
        // sees it now, never a separate hardcoded CF (ClusterEventsLimits.streamConfig).
        var clusterEventsStreamConfig = clusterEventsLimits.streamConfig(clusterEventsStreamName,
                                                                         kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                          AetherValue.ClusterConfigValue.class));
        // Fix #3 / #1230: the forward-capable cluster-events CONSUMER and PUBLISHER are wired further below,
        // after streamForwardClient + streamReadForwardMetrics are constructed, so a non-replica node
        // read-forwards observability reads to a caught-up replica instead of reading its own empty local
        // partition, and a non-owner emit write-forwards to the owner instead of appending to its own replica
        // ring. The aggregator reads both lazily via clusterEventsConsumerRef::get /
        // clusterEventsPublisherRef::get, so deferring the wiring does not affect construction order.
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
        // #1604: the durable segment tier's pressure -- retention warns at 85%, owner publishes are refused at 95%.
        var streamSegmentTierPressure = SegmentTierPressure.localDiskOf(streamStorage);

        streamPartitionManager.segmentTierPressure(streamSegmentTierPressure);
        var streamRetentionEnforcer = RetentionEnforcer.retentionEnforcer(streamStorage,
                                                                          streamSegmentIndex,
                                                                          DEFAULT_STREAM_RETENTION_MS,
                                                                          (stream, partition) -> entityRetentionFloor(kvStore,
                                                                                                                      stream,
                                                                                                                      partition),
                                                                          streamStorageSetup.snapshotManager(),
                                                                          streamStorageSetup.garbageCollector(),
                                                                          streamSegmentReader,
                                                                          streamSegmentTierPressure);
        // A6: streamReplicaRegistry is created earlier (above StreamPartitionManager) so it can be
        // shared with the now-active DefaultReplicationManager. The same registry instance is the one
        // the A2 ReplicaSetController populates from HRW placement.
        // A4 / #1505: every replica-side recovery apply — governor-failover segment replay and the A4 backfill
        // below — lands each event at its OWN owner offset WITHOUT re-replicating (the receiver is not an owner),
        // through the same ordered section as the live receive handler (whose `streamPartitionManager::appendRecovered`
        // binds the offset-addressed overload). A live batch or a lagging replay floor therefore cannot shift an
        // event: an offset already held is verified, never re-appended at the tail (#1505 F1).
        // #336 epoch adoption (NOT invention): stamp the recovery/backfill append with the SAME committed
        // owner epoch a live publish stamps (streamOwnerEpochSource reads the committed
        // StreamPartitionOwnershipValue.ownerEpoch — the identical source the fence high-water is seeded
        // from), so a backfilled event is equal-or-newer than the partition high-water and passes the fence
        // instead of being rejected at the Epoch.ZERO floor (0:0 < 1:N). Cold-start/unowned arcs read
        // Epoch.ZERO == the fence's ZERO default (equal → passes), so fresh-stream recovery is not regressed.
        // The fence itself is untouched — this is a truthful stamp, not a wholesale exemption.
        // #1596: that stamp FENCES the append and never attributes it — a caught-up record may predate the
        // current epoch. Its provenance is the source's owner-epoch slice, which the same seam checks (N13)
        // and records before the page is applied (StreamPartitionManager.alignedRecovery, the fence epoch read
        // from this same streamOwnerEpochSource).
        AlignedRecovery streamAlignedRecovery = streamPartitionManager.alignedRecovery();
        var streamFailoverHandler = GovernorFailoverHandler.governorFailoverHandler(streamReplicaRegistry,
                                                                                    streamAlignedRecovery,
                                                                                    streamPartitionManager::syncReplicated);
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

        ClusterEventsAssembly.eventStreams(new ClusterEventsAssembly.EventStreamsInputs(config,
                                                                                        serializer,
                                                                                        deserializer,
                                                                                        clusterEventsPublisherRef,
                                                                                        clusterEventsConsumerRef,
                                                                                        clusterEventsControllerRef,
                                                                                        clusterEventsStreamName,
                                                                                        streamReplicaRegistry,
                                                                                        streamPartitionManager,
                                                                                        clusterEventsStreamConfig,
                                                                                        streamReadForwardMetrics,
                                                                                        streamForwardClient));
        var streamCatchupTransport = ForwardCatchupTransport.forwardCatchupTransport(streamForwardClient,
                                                                                     STREAM_CATCHUP_BATCH_SIZE,
                                                                                     operatorWarningSink,
                                                                                     System::currentTimeMillis);
        // Cold-start deadlock-break: a watermark+reachability probe over the same forward-read transport.
        // A REACHABLE peer answers with the highest local offset it holds (its watermark; -1 when empty), or,
        // for a partition it holds but has not materialized, its durable watermark; an UNREACHABLE peer's
        // read fails (timeout) and the probe propagates that failure, which the orchestrator treats as "do not
        // self-promote past a node that might hold newer state".
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
        SelfWatermark streamSelfWatermark = streamPartitionManager::appendedHead;
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
        //
        // #1230 ruling 3: the write-authority half (admission + replication sender check) is the RAW record;
        // only the routing half (backfill self-election, release guard) is liveness-filtered. Both come from
        // StreamOwnershipViews so the split is decided — and pinned by StreamOwnershipViewsTest — in one place.
        var streamOwnershipViews = StreamOwnershipViews.streamOwnershipViews(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore),
                                                                             membershipFsm);
        var streamCommittedOwnerSource = streamOwnershipViews.routing();
        var placementMembers = livePlacementMembers(clusterTopologyManager.observer()::coreNodes, membershipFsm);
        var streamPartitionBackfill = PartitionBackfill.partitionBackfill(streamReplicaRegistry,
                                                                          streamAlignedRecovery,
                                                                          streamCatchupTransport,
                                                                          streamReplicationTransport,
                                                                          streamWatermarkProbe,
                                                                          streamSelfWatermark,
                                                                          config.self(),
                                                                          streamingConfig.backfillSourceWaitBound(),
                                                                          () -> streamPlacementMembers(clusterEventsControllerRef,
                                                                                                       placementMembers),
                                                                          streamCommittedOwnerSource,
                                                                          streamPartitionManager::syncReplicated,
                                                                          streamPartitionManager.quarantineView(),
                                                                          Option.some(streamingConfig.backfillFlightIdleBound()))
                                                       // #2077: the RAW committed ISR, never the liveness-filtered routing view above.
                                                       .withCommittedIsr(KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore));
        var streamBackfillExecutor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable,
                                                                                                "stream-partition-backfill"));
        // A2: per-node controller that reconciles the (previously never-populated) ReplicaRegistry
        // against the HRW-derived desired replica set on every membership change. Members are the single
        // live placement source (#1550, placementMembers); cluster size comes from the consensus topology
        // observer; the stream catalog (name/partitions/
        // confirmationFactor + partition-has-data) is adapted from the partition manager. The A4
        // catch-up seam now runs backfill off the reconcile thread on a dedicated executor.
        //
        // 1d-iii / #265: the leader-only StreamPartitionOwnershipWriter, fired by the controller's
        // onReconcilePassComplete batch driver seam. It mirrors the DHT BootstrapModule ownership writer:
        //   - isLeaderSupplier / rabiaTermSupplier / hlcClock are the same suppliers the DHT writer uses,
        //   - CommittedOwnership reads the committed StreamPartitionOwnershipValue exactly as
        //     KvStreamOwnerEpochSource does (getTyped on the StreamPartitionOwnershipKey),
        //   - HrwOwner late-binds to streamReplicaSetController::desiredOwner through clusterEventsControllerRef
        //     (set to the same controller below, before the first reconcile fires the driver) — #1555 sticky
        //     ownership: keep the committed owner while it is in the leader's live set, else HRW rank-0.
        // The writer self-limits: writeOwnershipChange returns none() on a follower (its isLeaderSupplier
        // gate short-circuits BEFORE any KV read) and none() for an unchanged owner, so the driver only
        // emits a consensus Put on the leader and only for genuinely-moved partitions. #265 increment 6:
        // the driver is now BATCH-shaped — the controller flushes ONE reconciled (stream, partition) list
        // per reconcile pass (onReconcilePassComplete) and driveStreamOwnership applies the emitted Puts as
        // a SINGLE consensus batch, so a mass reshuffle commits one batch per pass, not one apply per moved
        // partition. This keeps the reshuffle fan-out bound the promise #265 makes.
        var streamOwnershipWriter = StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(isLeaderSupplier,
                                                                                                  epochSources.generationEpoch(),
                                                                                                  hlcClock,
                                                                                                  (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                                                  partition),
                                                                                                                                          StreamPartitionOwnershipValue.class),
                                                                                                  (stream, partition) -> Option.option(clusterEventsControllerRef.get()).flatMap(ownershipController -> ownershipController.desiredOwner(stream,
                                                                                                                                                                                                                                         partition)),
                                                                                                  streamIsrInputs(clusterEventsControllerRef),
                                                                                                  () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                                         LeaderValue.class));
        // #1339 residual: a failed ownership write re-arms the reconcile through the same coalescing entry, backed off.
        var streamOwnershipRetry = StreamOwnershipRetry.streamOwnershipRetry(SharedScheduler::schedule,
                                                                             () -> reconcileReplicaSet(clusterEventsControllerRef));
        var streamReplicaSetController = ReplicaSetController.replicaSetController(streamReplicaRegistry,
                                                                                   config.self(),
                                                                                   placementMembers,
                                                                                   clusterTopologyManager.observer()::clusterSize,
                                                                                   streamPartitionManager.replicaCatalog(),
                                                                                   (streamName, partition) -> streamBackfillExecutor.execute(() -> materializeThenBackfill(streamPartitionManager,
                                                                                                                                                                           streamPartitionBackfill,
                                                                                                                                                                           streamName,
                                                                                                                                                                           partition)),
                                                                                   (List<PartitionKey> reconciled) -> driveStreamOwnership(streamOwnershipWriter,
                                                                                                                                           clusterCommandApplier,
                                                                                                                                           streamOwnershipRetry,
                                                                                                                                           reconciled));
        // B5b: bind the owner-gate ref so ClusterEventAggregator.emit can consult isOwner(...) for
        // (system:cluster-events:1.0.0, partition 0). isOwner is computed from the live HRW placement
        // against the current topology, independent of reconcile, so it is correct as soon as members
        // are visible (and true for a steady-state single-node cluster).
        clusterEventsControllerRef.set(streamReplicaSetController);
        // #1730 owner ruling: every node derives the failover refusal/resolution event from the committed ownership Put;
        // the cluster-events aggregator publishes only on its partition owner, so it reaches the stream exactly once.
        var streamFailoverAnnouncer = StreamFailoverAnnouncer.streamFailoverAnnouncer(() -> streamIsrInputs(clusterEventsControllerRef).liveMembers(),
                                                                                      delegateRouter::route);
        // #1873: and for an owner beginning a new epoch of its own (its ring was rebuilt).
        var streamLineageAnnouncer = StreamLineageAnnouncer.streamLineageAnnouncer(delegateRouter::route);

        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     streamFailoverAnnouncer::onOwnershipPut)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     streamIsrAnnouncer::onOwnershipPut)
                                              .onPut(AetherKey.StreamPartitionOwnershipKey.class,
                                                     streamLineageAnnouncer::onOwnershipPut)
                                              .build()
                                              .asRouteEntries());
        // #1723: a scheduled task's unknown fire outcome, and its late resolution, derived by every node from the committed
        // task state; the aggregator publishes on the cluster-events owner only, throttled per task.
        var scheduledTaskOutcomeAnnouncer = ScheduledTaskOutcomeAnnouncer.scheduledTaskOutcomeAnnouncer(delegateRouter::route);
        // #1206: two artifacts serving one route, derived by every node from the committed route table.
        var routeCollisionAnnouncer = RouteCollisionAnnouncer.routeCollisionAnnouncer(delegateRouter::route);

        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.NodeRoutesKey.class, routeCollisionAnnouncer::onRoutesPut)
                                              .onRemove(AetherKey.NodeRoutesKey.class,
                                                        routeCollisionAnnouncer::onRoutesRemove)
                                              .build()
                                              .asRouteEntries());
        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.ScheduledTaskStateKey.class,
                                                     scheduledTaskOutcomeAnnouncer::onStatePut)
                                              .build()
                                              .asRouteEntries());
        // #1555 sticky ownership: every node routes by the COMMITTED ownership record (HRW only before a record
        // exists); the leader's writer alone judges liveness (ReplicaSetController#desiredOwner). Backfill sources
        // from, and self-elects against, the same owner.
        streamReplicaSetController.committedOwnerSource((stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                        partition),
                                                                                                StreamPartitionOwnershipValue.class)
                                                                                      .map(StreamPartitionOwnershipValue::owner));
        // #1730: the committed ISR keeps its members placed (so they keep receiving the partition), and a CF >= 2
        // confirmation needs every one of them. A record minted before #1730 (isrVersion 0) carries no committed ISR.
        streamReplicaSetController.committedIsrSource((stream, partition) -> committedIsr(kvStore, stream, partition).or(List.of()));
        streamPartitionBackfill.ownerResolver(streamReplicaSetController::ownerFor);
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
        // #1230: application appends are admitted only on the committed owner. Ring possession authorizes
        // reads and replication receipt, never a write: the epoch fence cannot tell a live replica from the
        // owner, because both stamp the same committed epoch. Reads the RAW committed record, deliberately NOT
        // the #568 liveness-filtered view: liveness is a local verdict, not a fence — filtering would read an
        // owner this node has marked DEPARTED (alive but partitioned away) or has not yet seen as "absent", and
        // admit a second writer while the first still writes. A SUSPECT owner is never dropped by the filter.
        // A dead owner's partition is unwedged only by the leader committing a new ownership record; until
        // then writes fail retryable NotOwnerAppend (CTO ruling on #1230). No record admits (the cold-start
        // window, where the fence is inert and HRW routing alone picks the writer).
        streamPartitionManager.ownerWriteAdmission(streamOwnershipViews.writeAdmission(config.self()));
        // #1555 owner promotion gate: this node acts as a partition's owner (appends, servedByOwner, owner reads)
        // only after a no-op consensus round has refreshed its committed ownership view, the record still names
        // it, and it has caught up to every live placement member's watermark. The round is ALWAYS ordered here,
        // independent of the read-linearization knob, because the gate's correctness depends on it. The probe
        // uses the catch-up read class, so a peer's own promotion state never blocks it.
        var ownerActivation = OwnerActivation.ownerActivation(config.self(),
                                                              (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                              partition),
                                                                                                      StreamPartitionOwnershipValue.class),
                                                              (stream, partition) -> streamReplicaSetController.roleFor(stream,
                                                                                                                        partition) == ReplicaSetController.Role.OWNER,
                                                              Option.some(LinearizableBarrier.noOpRound(clusterCommandApplier,
                                                                                                        streamingConfig.readForwardTimeout())),
                                                              placementMembers,
                                                              (target, stream, partition) -> probePeerAppendedWatermark(streamForwardClient,
                                                                                                                        target,
                                                                                                                        stream,
                                                                                                                        partition),
                                                              streamSelfWatermark,
                                                              streamPartitionBackfill::catchUpOwnerFrom,
                                                              clusterNode::isActive,
                                                              OwnerPeerReads.ownerRange(config.self(),
                                                                                        streamPartitionManager,
                                                                                        streamTieredReader,
                                                                                        streamForwardClient::readRemoteCatchup,
                                                                                        STREAM_CATCHUP_BATCH_SIZE),
                                                              ownerPromotionAlarm(operatorWarningSink),
                                                              ownerPromotionAlarmWindow(config.timeouts()
                                                                                              .swim()
                                                                                              .suspectTimeout()),
                                                              (stream, partition) -> streamPartitionManager.ringIncarnation(stream,
                                                                                                                            partition)
                                                                                                           .or(-1L),
                                                              streamLineageCommit(() -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                         LeaderValue.class),
                                                                                  clusterCommandApplier,
                                                                                  hlcClock));
        // #1873 (KIP-320): every consumer read is checked, on the node that serves it, against the partition's COMMITTED epoch
        // starts; this is where that node reads them.
        streamPartitionManager.ownershipRecords((stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                partition),
                                                                                        StreamPartitionOwnershipValue.class));
        ownerActivation.promotionEscapeAfter(streamingConfig.promotionEscapeAfter());
        // #1937: the promoted owner's backfill refuses for a peer's oversized event too, and reports it the way the gate does.
        // #2080: its cold-start contest proceeds past unreachable co-replicas only for a node the COMMITTED in-sync set names, and
        // reports that escape the way the gate does.
        bindPromotionAlarm(streamPartitionBackfill, operatorWarningSink, kvStore, streamingConfig.promotionEscapeAfter());
        streamPartitionManager.ownerServeGate(ownerActivation::admit);
        // #1730 phase 2: the gate's relaxation for a divergent peer respects the candidate's durable sealed floor, and a peer it
        // leaves out loses the row this registry kept for it from an earlier tenure.
        ownerActivation.sealedFloor(streamSegmentIndex::lastSealedOffset);
        ownerActivation.peerRingTail((node, stream, partition) -> node.equals(config.self())
                                                                  ? Promise.success(streamPartitionManager.visibleBounds(stream,
                                                                                                                         partition)
                                                                                                          .filter(bounds -> bounds.earliestRetained() >= 0L)
                                                                                                          .map(VisibleBounds::earliestRetained))
                                                                  : streamForwardClient.ringTailRemote(node,
                                                                                                       stream,
                                                                                                       partition)
                                                                                       .recover(_ -> Option.<Long> none()));
        ownerActivation.peerRows((stream, partition, peer) -> streamReplicaRegistry.updateWatermark(stream,
                                                                                                    partition,
                                                                                                    peer,
                                                                                                    - 1L,
                                                                                                    ReplicationState.SYNCING));
        // #1730: a partition with no live in-sync replica has no owner to report a block, so the controller reports it.
        streamPartitionManager.ownerBlockSource((stream, partition) -> ownerActivation.blockOf(stream, partition)
                                                                                      .orElse(() -> streamReplicaSetController.noInSyncReplica(stream,
                                                                                                                                               partition)));
        // #1730: the owner keeps its partitions' ISR current — a member lagging longer than [streaming] isr_lag_max
        // leaves, a caught-up replica joins — through guarded consensus writes only (liveness; never safety).
        var isrMonitor = IsrMonitor.isrMonitor(config.self(),
                                               () -> ownedPartitions(streamPartitionManager,
                                                                     kvStore,
                                                                     ownerActivation,
                                                                     config.self()),
                                               streamReplicaRegistry,
                                               () -> kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class),
                                               clusterCommandApplier,
                                               streamingConfig.isrLagMax(),
                                               System::nanoTime);
        // #1730: a CF >= 2 confirmation needs every member of the committed ISR plus every member this owner has
        // proposed to add to it (the maximal ISR). A record minted before #1730 (isrVersion 0) carries no ISR.
        streamReplicationManager.inSyncReplicaSource((stream, partition) -> committedIsrRecord(kvStore,
                                                                                               stream,
                                                                                               partition).map(record -> isrMonitor.maximalIsr(stream,
                                                                                                                                              partition,
                                                                                                                                              record)));
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(isrMonitor::tick, ISR_MONITOR_INTERVAL));
        // #2087: the owner repeats its visible position, so one lost announcement delays a replica by one tick, never longer.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(streamPartitionManager::repeatVisible,
                                                                      ISR_MONITOR_INTERVAL));
        allEntries.add(MessageRouter.Entry.route(ClusterStateNotification.class, ownerActivation::onQuorumStateChange));
        // Reconcile on every membership decision (all variants via the tail helper) and on
        // ClusterStateNotification edges (PASSIVE suppresses; PASSIVE->ACTIVE re-reconciles).
        wireMembershipDecisionTail(allEntries, streamReplicaSetController::onMembershipDecision);
        allEntries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                                 streamReplicaSetController::onQuorumStateChange));
        // #1339/#1732: the placement inputs the two triggers above do not announce — voter install, leadership,
        // committed ownership records. See wireReplicaSetInputTriggers.
        wireReplicaSetInputTriggers(allEntries, streamReplicaSetController::reconcile, clusterNode::onVoterConfiguration);
        // Initial reconcile once membership is available; serialized on the controller executor, so
        // this is a safe no-op until the topology observer reports core members.
        streamReplicaSetController.reconcile();
        // #932: one thread each per node, both daemon and neither closed on stop — nine of each survived a
        // nine-node stop. The controller's close() is shutdownNow on the executor it owns; the backfill
        // executor is this method's own. Controller first: its reconcile is what submits to the backfill.
        Runnable streamReplicationShutdown = () -> {
            streamReplicaSetController.close();
            streamBackfillExecutor.shutdownNow();
        };
        // A4 periodic backfill re-drive: reconcilePartition fires backfill ONCE per reconcile edge, so a
        // cold-start replica that saw NO_SOURCE on its first attempt would never re-attempt and stay
        // SYNCING forever (the system:cluster-events GET /api/events -> 200 [] deadlock). Re-drive every
        // STREAM_BACKFILL_REDRIVE_INTERVAL onto the SAME backfill executor so the owner-immediate /
        // bounded-wait cold-start promotion is actually reached; CAUGHT_UP partitions are skipped.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> redriveIncompleteBackfills(streamPartitionBackfill,
                                                                                                       streamBackfillExecutor),
                                                                      STREAM_BACKFILL_REDRIVE_INTERVAL));
        StorageAssembly.storageTicks(new StorageAssembly.StorageTicksInputs(config,
                                                                            storageSetups,
                                                                            storageMaintenanceDriver,
                                                                            periodicTasks::defer));
        // #420/#1136: the DHT anti-entropy cycle. `DHTAntiEntropy.start()` had no caller, so the
        // "periodic 30 s repair" never ran in production and `[timeouts.dht] anti_entropy_interval`
        // was parsed by nothing. Armed here, with the node's other periodic work (#644: after
        // formation, cancelled first on stop, never a zombie), on the configured interval; the
        // join-time round in DHTTopologyListener is the fast path, this cycle is its retry.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(dhtAntiEntropy::synchronizeNow,
                                                                      config.timeouts().dht().antiEntropyInterval()));
        // #1777 track 2: fills the partitions this node is a replica of but not yet authoritative for —
        // at boot, and after every ring change — within about a tick of their sources answering.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(dhtAntiEntropy::catchUpNow,
                                                                      DHTAntiEntropy.CATCH_UP_INTERVAL));
        // W5 WAL disk-reclamation driver: truncate every partition's write-ahead log up to its DURABLE
        // last-sealed offset so the WAL does not grow unbounded. Records <= that offset are already in
        // cold segments whose refs are in the metadata snapshot on disk (served post-restart by the tiered
        // reader), so dropping them from the WAL loses nothing; the un-sealed tail stays in the WAL.
        // truncate is threshold-lazy, so this tick is cheap when nothing new has sealed. Driven off the
        // CONTIGUOUS sealed bound (#1234: it never passes a segment that failed to seal) as REBUILT from
        // the latest snapshot file (#1345: never the live index, which runs ahead of disk until the next
        // snapshot — a crash in that window used to lose the refs and renumber the survivors).
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
                                                                                                                (stream, partition, fromOffset, maxEvents) -> streamPartitionManager.readServing(stream,
                                                                                                                                                                                                 partition,
                                                                                                                                                                                                 fromOffset,
                                                                                                                                                                                                 maxEvents)
                                                                                                                                                                                    .async());
        var streamForwardHandler = StreamForwardHandler.streamForwardHandler(config.self(),
                                                                             streamPartitionManager,
                                                                             streamForwardTransport,
                                                                             streamingConfig.maxReadResponseBytes(),
                                                                             streamReadForwardMetrics,
                                                                             Option.some(linearizableOwnerServe),
                                                                             Option.some(streamTieredReader));
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
        // #1271: checkpoints carry this node's consumer-assignment token and are refused by the applier
        // once the assignment moves; a forwarding (worker) node sends a Noop barrier before re-reading the
        // verdict, since its publish resolves on the core's reply rather than its own apply.
        // #1271: the committed consumer assignment is the one authority for which node delivers a
        // (group, partition). The leader writes it from the same computation every node used to act on
        // alone; every node attaches only where the committed record names it — and the cursor store's
        // verdict re-reads it before calling a refused checkpoint Fenced (#1335 B1).
        ConsumerAssignmentWriter.CommittedAssignments committedConsumerAssignments = (stream, partition, group) -> kvStore.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                                                                                                partition,
                                                                                                                                                                                group),
                                                                                                                                    ConsumerAssignmentValue.class);
        // #1333: the cluster cursor store is decorated with the projection commit hook — after every
        // resolved commit the group's projection (if this node hosts one) learns the committed cursor,
        // stamped with the committing consumer's rewind epoch. The registry is keyed on the runtime's own
        // group identity (artifactBase#method) and resolves lazily against the topic subscriptions.
        var projectionRegistry = ProjectionRegistry.projectionRegistry(topicSubscriptionRegistry::allSubscriptions);
        Fn1<Option<AetherValue.StreamCursorCheckpointValue>, AetherKey.StreamCursorCheckpointKey> committedCursorReader = cursorKey -> kvStore.getTyped(cursorKey,
                                                                                                                                                        AetherValue.StreamCursorCheckpointValue.class);
        Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> cursorCommandWriter = commands -> switchableCluster.apply(commands)
                                                                                                          .mapToUnit();
        var streamClusterCursorStore = ProjectionAwareCursorStore.projectionAwareCursorStore(ClusterCursorStore.clusterCursorStore(streamCursorStore,
                                                                                                                                   config.self(),
                                                                                                                                   committedCursorReader,
                                                                                                                                   committedConsumerAssignments,
                                                                                                                                   cursorCommandWriter,
                                                                                                                                   () -> switchableCluster.current() instanceof ForwardingClusterNode),
                                                                                             projectionRegistry);
        // #386 durable pub-sub: dead letters for `topic:*` streams are durable — re-enveloped
        // group-attributed and appended to the topic's `.dlq` stream through the same confirmation
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
                                                                                StreamConsumerRuntime.validatingReader((stream, partition, fromOffset, maxEvents) -> streamReadRouter.read(stream,
                                                                                                                                                                                           partition,
                                                                                                                                                                                           fromOffset,
                                                                                                                                                                                           maxEvents,
                                                                                                                                                                                           ReadPreference.GOVERNOR),
                                                                                                                       streamReadRouter::readValidated));

        streamConsumerRuntime.operatorWarnings(operatorWarningSink);
        var streamConsumerOwnership = streamConsumerOwnership(streamPartitionManager, streamReplicaSetController);
        var consumerAssignmentAuthority = StreamConsumerManager.AssignmentAuthority.assignmentAuthority(committedConsumerAssignments,
                                                                                                        ConsumerAssignmentWriter.consumerAssignmentWriter(isLeaderSupplier,
                                                                                                                                                          epochSources.generationEpoch(),
                                                                                                                                                          hlcClock,
                                                                                                                                                          committedConsumerAssignments),
                                                                                                        commands -> switchableCluster.apply(commands)
                                                                                                                                     .mapToUnit());
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
                                                                                                                                                                             .isPresent()),
                                                                                consumerAssignmentAuthority,
                                                                                (streamName, partition, group) -> committedCursorReader.apply(AetherKey.StreamCursorCheckpointKey.streamCursorCheckpointKey(streamName,
                                                                                                                                                                                                            partition,
                                                                                                                                                                                                            group))
                                                                                                                                       .map(AetherValue.StreamCursorCheckpointValue::rewindEpoch));

        streamConsumerManager.setOperatorWarningSink(operatorWarningSink);
        // #1271: a node that loses quorum stops delivering at the self-fence's DETECTION, not at the drain's
        // halt — the majority is free to reassign its partitions from that moment. Re-set here because the
        // manager exists only now; the detector was armed with the same chain earlier and this replaces it
        // with the chain plus the consumer abandon.
        quorumLossDetector.setQuorumLossListener(StreamConsumerManager.abandoningOnQuorumLoss(quorumLossChain,
                                                                                              streamConsumerManager));
        // #499: the handle is retained in `periodicTasks`, which stop() cancels wholesale. A declarative
        // consumer that outlived its node would deliver into a torn-down slice.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(streamConsumerManager::reconcile,
                                                                      STREAM_CONSUMER_RECONCILE_INTERVAL));
        // #1333: a committed checkpoint carrying a newer rewind epoch than the held consumer's restarts
        // that consumer on the next pass, now rather than on the 5s tick. The manager filters the key type.
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class,
                                                 streamConsumerManager::onCheckpointPut));
        // A rebuild's rewind record counts as committed once its accepted-put notification is seen here; a
        // read-back alone is overtaken by the restarted consumer's first checkpoint at the same epoch.
        var rewindWitness = CommitWitness.commitWitness();

        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, rewindWitness::onPut));
        // #1333: what a slice's ProjectionRuntime resource needs from the node — the registry above and
        // the replay cursor's collaborators (partition bounds from the local ring or forwarded to the owner
        // through the read router, the fenced checkpoint put, the committed read-back). Registered beside
        // the entity drivers, as one extension.
        var projectionNodeSupport = ProjectionNodeSupport.projectionNodeSupport(projectionRegistry,
                                                                                streamClusterCursorStore::reportFailureCount,
                                                                                streamConsumerOwnership::partitionCount,
                                                                                PartitionBounds.routed(streamReadRouter),
                                                                                cursorCommandWriter,
                                                                                committedCursorReader,
                                                                                committedConsumerAssignments,
                                                                                epochSources.incarnation()::current,
                                                                                rewindWitness);

        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> spi.registerExtension(ProjectionNodeSupport.class, projectionNodeSupport));
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
        ClusterEventsAssembly.systemStreamRegistrar(new ClusterEventsAssembly.SystemStreamRegistrarInputs(kvStore,
                                                                                                          alertManager,
                                                                                                          operatorWarningSink,
                                                                                                          allEntries,
                                                                                                          streamPartitionManager,
                                                                                                          clusterEventsStreamConfig,
                                                                                                          streamNamespacesService));
        // #290: cluster-formation bootstrap admin API key. On first leadership, if no admin key is yet
        // present in the KV store, register one cluster-wide and print the plaintext once. Self-healing
        // and idempotent (mirrors SystemStreamRegistrar); the leg uses the same clusterNode.apply
        // consensus path as the API-key routes and the same KV store the validator reads.
        // #980: the key is DERIVED from the cluster secret rather than randomised, so the bootstrap
        // CLI — which minted that secret six phases earlier — can authenticate its own quorum poll.
        // Registration is unchanged, which is what keeps the key enumerable, revocable and audited.
        var bootstrapAdminKeyRegistrar = BootstrapAdminKeyRegistrar.bootstrapAdminKeyRegistrar(BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore,
                                                                                                                                         isLeaderSupplier::getAsBoolean,
                                                                                                                                         clusterCommandApplier,
                                                                                                                                         config.clusterSecret()));

        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 bootstrapAdminKeyRegistrar::onLeaderChange));
        // #1529 part 1: the leader mints the cluster lineage/incarnation at genesis if absent. Same
        // self-healing, leader-armed shape as the bootstrap admin key; a restored value is left alone.
        var clusterIncarnationRegistrar = ClusterIncarnationRegistrar.clusterIncarnationRegistrar(ClusterIncarnationRegistrar.genesisLeg(() -> kvStore,
                                                                                                                                         clusterCommandApplier));

        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 clusterIncarnationRegistrar::onLeaderChange));
        // #1532: change-triggered, leader-only KV backup — only when [backup] is enabled with a path.
        // #1533 × #1617: the restore-blocked, dropped-checkpoints and forked conditions are also cluster events.
        var backupWarnings = BackupWarning.Sink.operatorWarnings(operatorWarningSink);
        var kvBackupService = createKvBackupService(config, kvStore, nodeCodec, backupWarnings);

        kvBackupService.onPresent(service -> routeKvBackup(allEntries, service));
        // #1533: the restore decision precedes every cluster-state seeder — on EVERY node, so a leader
        // without [backup] still commits DISABLED and opens the gate its backup-enabled peers hold closed.
        var backupRestoreCoordinator = BackupRestoreCoordinator.backupRestoreCoordinator(kvStore,
                                                                                         clusterCommandApplier,
                                                                                         restoreSource(config,
                                                                                                       kvBackupService),
                                                                                         backupWarnings);

        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 backupRestoreCoordinator::onLeaderChange));
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
                                                                             ENTITY_FORWARD_TIMEOUT,
                                                                             CommittedEntityStake.committedEntityStake(kvStore,
                                                                                                                       config.self()));

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
        // offset is acked back. #1230: a batch whose sender cannot be the committed owner at the batch's
        // epoch is neither applied nor acked (the same RAW committed record as the admission — not the #568
        // liveness-filtered view, which is for routing/backfill only). ReplicateAck flows into the active DefaultReplicationManager so it can
        // advance the watermark and resolve any awaitReplication(minAcks) promise. Routed on the same
        // partition message transport as the forward handler.
        var streamReplicationReceiveHandler = ReplicationReceiveHandler.replicationReceiveHandler(config.self(),
                                                                                                  streamPartitionManager::appendRecovered,
                                                                                                  streamPartitionManager::nextExpectedOffset,
                                                                                                  streamReplicationTransport,
                                                                                                  (streamName, partition) -> streamBackfillExecutor.execute(() -> streamPartitionBackfill.backfill(streamName,
                                                                                                                                                                                                   partition)),
                                                                                                  streamPartitionManager::syncReplicated,
                                                                                                  streamOwnershipViews.writeAuthority(),
                                                                                                  operatorWarningSink);
        // #1730 phase 2 (B7): a copy not yet compared with the committed owner of the current epoch acknowledges nothing.
        streamReplicationReceiveHandler.ackGate(streamPartitionManager::replicaVerified);
        // #2087: a replica serves a consumer read only up to the owner's reported visible position.
        streamReplicationReceiveHandler.commitSink(streamPartitionManager::commitAdvanced);
        allEntries.add(MessageRouter.Entry.route(ReplicationMessage.CommitAdvance.class,
                                                 streamReplicationReceiveHandler::onCommitAdvance));
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
                                                                                                                                                                            epochSources.generationEpoch(),
                                                                                                                                                                            hlcClock,
                                                                                                                                                                            (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                                                                                                                            partition),
                                                                                                                                                                                                                    StreamPartitionOwnershipValue.class),
                                                                                                                                                                            entityArcOwner,
                                                                                                                                                                            streamIsrInputs(clusterEventsControllerRef),
                                                                                                                                                                            () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                                                                                                                   LeaderValue.class)),
                                                                                            clusterCommandApplier,
                                                                                            ENTITY_RECONCILE_KICK,
                                                                                            EntityOwnershipReconciler.SETTLE_TICKS,
                                                                                            EntityOwnershipReconciler.FIRST_MINT_CEILING_TICKS,
                                                                                            ENTITY_OWNERSHIP_RECONCILE_INTERVAL,
                                                                                            System::nanoTime,
                                                                                            operatorWarningSink);
        // A committed registration REMOVAL is a hosting-set shrink, which is the one edge the leader's mint
        // must not wait a tick for: until it re-mints, the retracting node is still the committed owner of
        // arcs it can no longer serve. Every node sees the notification; the writer's own leader gate
        // decides who acts, so a follower's pass emits nothing.
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class,
                                                 entityOwnershipReconciler::onRegistrationRemoved));
        // #1302/#1330: every checkpoint tick reports this node's largest checkpoint lag — over partitions it
        // OWNS, measured from the committed checkpoint in KV — into the node metrics map, where the alert
        // threshold path (DashboardMetricsPublisher -> AlertManager.checkThreshold) evaluates it.
        var entityCheckpointDriver = EntityCheckpointDriver.entityCheckpointDriver(EntityCheckpointLagMetric.sinkFor(metricsCollector),
                                                                                   EntityCheckpointLagMetric.committedCheckpoints(kvStore));
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
                                                                                   streamTieredReader,
                                                                                   streamSegmentIndex,
                                                                                   streamSegmentSealer,
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
        var certRenewalScheduler = ManagementAssembly.certRenewal(new ManagementAssembly.CertRenewalInputs(config,
                                                                                                           clusterNode,
                                                                                                           operatorWarningSink,
                                                                                                           appHttpServer,
                                                                                                           managementServerRef));
        var startTimeMs = System.currentTimeMillis();
        var nodeLifecycle = NodeLifecycle.nodeLifecycle();
        var node = new aetherNode(workerMetadataChannel,
                                  config,
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
                                  streamPartitionManager,
                                  streamSegmentIndex,
                                  streamReadRouter,
                                  streamWriteRouter,
                                  consumerGroupCoordinator,
                                  consumerGroupRegistry,
                                  streamConsumerManager,
                                  Option.some(projectionNodeSupport),
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
                                  periodicTasks,
                                  kvBackupService,
                                  leaderTerm,
                                  streamReplicationShutdown,
                                  backupRestoreCoordinator::onNodeStopping,
                                  resourceProviderSetup.spiProvider(),
                                  replacementWiring.service(),
                                  upgradeWiring.service(),
                                  prefetchChannel);

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
        // #1052: self-ready (lifecycle ACTIVE, reported READY) waits for every DHT-backed storage
        // instance to be admitted by its post-formation encryption-marker check, which now retries while
        // the DHT cannot answer. Without this the node would report ready while its DHT tiers still refuse
        // every operation.
        var dhtAdmission = StorageFactory.dhtAdmission(storageSetups);
        BooleanSupplier nodeDraining = () -> drainProcedure.state() != DrainProcedure.DrainState.INACTIVE;

        nodeDeploymentManager.setSelfReadySignal(() -> markSubsystemsReadyOnceDhtAdmitted(dhtAdmission,
                                                                                          () -> signalReadyActivatingStorage(() -> signalReadyAndWakePrefetch(nodeLifecycle::signalReady,
                                                                                                                                                              storageSetups,
                                                                                                                                                              nodeDraining),
                                                                                                                             storageSetups,
                                                                                                                             nodeDraining),
                                                                                          nodeReportedStateHolder));
        // Activation level-heal: a cold-start `restart_all_nodes` can drop the single CAS-latched
        // ClusterStateNotification.ACTIVE edge while the router delegate is being rebuilt, leaving NDM
        // stuck in Dormant (self-ready/subsystemsReady never fire) so the node reports SYNCING forever.
        // This tick re-dispatches QuorumEstablished only while still Dormant AND consensus is live —
        // a dropped edge self-heals within one interval; a no-op on every healthy node thereafter.
        periodicTasks.defer(() -> SharedScheduler.scheduleAtFixedRate(() -> reconcileNodeActivation(() -> runtimeReady(clusterNode),
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
                                                      var managementServer = ManagementAssembly.managementServer(new ManagementAssembly.ManagementServerInputs(config,
                                                                                                                                                               kvStore,
                                                                                                                                                               clusterNode,
                                                                                                                                                               serializer,
                                                                                                                                                               deserializer,
                                                                                                                                                               membershipFsmRef,
                                                                                                                                                               resourceProviderSetup,
                                                                                                                                                               logLevelRegistry,
                                                                                                                                                               traceStore,
                                                                                                                                                               configRegistry,
                                                                                                                                                               scheduledTaskRegistry,
                                                                                                                                                               scheduledTaskStateRegistry,
                                                                                                                                                               drainCommandRegistry,
                                                                                                                                                               alertManager,
                                                                                                                                                               dynamicConfigManager,
                                                                                                                                                               operatorWarningSink,
                                                                                                                                                               sliceInvoker,
                                                                                                                                                               scheduledTaskManager,
                                                                                                                                                               serverBossGroup,
                                                                                                                                                               serverWorkerGroup,
                                                                                                                                                               managementServerRef,
                                                                                                                                                               entityCheckpointDriver,
                                                                                                                                                               node));

                                                      return new aetherNode(workerMetadataChannel,
                                                                            config,
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
                                                                            streamPartitionManager,
                                                                            streamSegmentIndex,
                                                                            streamReadRouter,
                                                                            streamWriteRouter,
                                                                            consumerGroupCoordinator,
                                                                            consumerGroupRegistry,
                                                                            streamConsumerManager,
                                                                            Option.some(projectionNodeSupport),
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
                                                                            periodicTasks,
                                                                            kvBackupService,
                                                                            leaderTerm,
                                                                            streamReplicationShutdown,
                                                                            backupRestoreCoordinator::onNodeStopping,
                                                                            resourceProviderSetup.spiProvider(),
                                                                            replacementWiring.service(),
                                                                            upgradeWiring.service(),
                                                                            prefetchChannel);
                                                  }

                                                  return node;
                                              });
    }

    TimeSpan PHASE_WATCH_INTERVAL = TimeSpan.timeSpan(1).seconds();
    /// Wave-1 item 3 (#245 baseline diagnostic) cadence for [#logMembershipBaselineTrace].
    TimeSpan MEMBERSHIP_BASELINE_TRACE_INTERVAL = TimeSpan.timeSpan(30).seconds();

    /// #1777 track 1: apply the cluster's committed DHT replication — `[replication]` for the DHT itself (W = CF,
    /// R = RF − CF + 1, re-placed through the catch-up gate when it changes) and `[cache]` for the cache namespace.
    /// No committed config means the built-in factors. A committed config that does not parse was refused at
    /// apply, so failing here is a defect: it is logged at ERROR and the node keeps what it had — still refusing,
    /// if nothing was resolved yet, rather than guessing a factor. FULL replication is a harness declaration and is
    /// kept as declared.
    ///
    /// `committed` is the configuration being applied — the one a commit notification carries, so its version is the
    /// version this node reports as applied (#1777 R1b), never a later one read back from the store.
    @Contract
    private static void resolveDhtReplication(Option<AetherValue.ClusterConfigValue> committed,
                                              DHTNode dhtNode,
                                              AetherNodeConfig config,
                                              AtomicReference<DHTConfig> cacheDhtConfig,
                                              DhtReplicationSettlement settlement) {
        var version = committed.map(AetherValue.ClusterConfigValue::configVersion).or(DHTNode.NO_CHANGE);

        ClusterReplication.defaults(committed)
                          .onSuccess(defaults -> applyDhtReplication(defaults,
                                                                     version,
                                                                     dhtNode,
                                                                     config.artifactRepo(),
                                                                     config.cache(),
                                                                     cacheDhtConfig))
                          .onSuccess(_ -> settlement.applied(version))
                          .onFailure(cause -> LOG.error("DHT replication not resolved from the committed cluster configuration: {}",
                                                        cause.message()));
    }

    /// `declared` and `declaredCache` supply the timeout and retry policy; their factors are replaced by the
    /// committed ones, except a declared FULL replication (the Ember harness), which has no placement to resolve.
    /// `version` is the committed configuration version that carries the factors (#1777 R1b).
    @Contract
    static void applyDhtReplication(ReplicationDefaultsConfig defaults,
                                    long version,
                                    DHTNode dhtNode,
                                    DHTConfig declared,
                                    DHTConfig declaredCache,
                                    AtomicReference<DHTConfig> cacheDhtConfig) {
        var resolved = declared.isFullReplication()
                       ? Result.success(declared)
                       : declared.withFactors(defaults.replicationFactor(), defaults.confirmationFactor());
        // #1777 track 3: the tombstone retention is cluster-wide, like the factors — every replica must agree
        // which tombstones have expired, since the anti-entropy digest leaves expired ones out
        dhtNode.resolveTombstoneRetention(defaults.tombstoneRetention());
        resolved.onSuccess(dht -> logIfReplicationChanged(dhtNode, dht))
                .onSuccess(dht -> dhtNode.resolveReplication(dht, version))
                .onFailure(cause -> LOG.error("DHT replication factors refused: {}",
                                              cause.message()));
        declaredCache.withFactors(defaults.cacheReplicationFactor(),
                                  defaults.cacheConfirmationFactor())
                     .onSuccess(cacheDhtConfig::set)
                     .onFailure(cause -> LOG.error("DHT cache replication factors refused: {}",
                                                   cause.message()));
    }

    @Contract
    private static void logIfReplicationChanged(DHTNode dhtNode, DHTConfig resolved) {
        var current = dhtNode.config();

        if (!dhtNode.replicationResolved() || current.replicationFactor() != resolved.replicationFactor() || current.writeQuorum() != resolved.writeQuorum()) {
            LOG.info("DHT replication: replication factor {}, write quorum {}, read quorum {}",
                     resolved.replicationFactor(),
                     resolved.writeQuorum(),
                     resolved.readQuorum());
        }
    }

    /// #1777 R1b: the workers a replication change waits for — every worker the membership tracks that has not been
    /// marked Dead in the leader's membership view. Any of them may write to the DHT. The view decides only WHEN a change
    /// settles; the replication-change fence on every put keeps a writer it misses safe (#1777 R1c).
    static Set<NodeId> dhtWritingWorkers(Option<MembershipFsm> membershipFsm) {
        return membershipFsm.map(fsm -> fsm.memberDescriptors()
                                           .entrySet()
                                           .stream()
                                           .filter(entry -> "worker".equalsIgnoreCase(entry.getValue().role()))
                                           .map(Map.Entry::getKey)
                                           .filter(fsm::isTrackedAndNotDead)
                                           .collect(Collectors.toSet()))
                            .or(Set.of());
    }

    /// #1777 R1b: whether the leader's membership view holds `member` `Dead`. Not a committed fact: a wrong verdict only
    /// hastens or delays a settle (#1777 R1c).
    static boolean committedDeparture(Option<MembershipFsm> membershipFsm, NodeId member) {
        return membershipFsm.map(fsm -> "Dead".equals(fsm.memberStates().get(member)))
                            .or(false);
    }

    /// `ClusterConfigKey` KV-commit fan-out. CTM keeps its config-changed notification, AND —
    /// H1 / #257 (cluster-topology-overhaul Wave 8 item 1, pulled forward) — the leader's
    /// reconciler is triggered with `trigger=CONFIG_CHANGE`: a committed config-driven target
    /// change (scale up/down, restore-to-N) must produce a reconcile pass within the debounce
    /// window instead of waiting for unrelated SWIM churn (live-confirmed: a 2-core deficit
    /// sat 10 minutes unhealed because no reconcile ever fired). `onConfigChange` is
    /// leader-gated and rides the reconciler's standard CAS-debounce machinery.
    ///
    /// Desired core capacity does not change voting authority. The installed voter configuration
    /// changes only after a consensus handoff has established its successor electorate.
    @Contract
    private static void onClusterConfigPut(ValuePut<AetherKey.ClusterConfigKey, AetherValue.ClusterConfigValue> put,
                                           ClusterTopologyManager clusterTopologyManager,
                                           LeaderReconciler leaderReconciler) {
        clusterTopologyManager.onClusterConfigChanged();
        // RFC-0017 stage 5 — every committed config change (scale, apply, restore) converges worker
        // topology through ONE trigger source. Leader-gated and serialized inside the CTM.
        clusterTopologyManager.reconcileWorkerTopology();
        leaderReconciler.onConfigChange();
    }

    /// The node reports ready: lifecycle first, then cross-node block prefetching wakes (it gossips hints and acts on
    /// them only from here on). Run from the same self-ready signal as the readiness state, so a node that is not ready
    /// neither tells peers what to fetch nor starts fetching for them. A node that has begun draining stays asleep: the
    /// ready signal can fire again after the drain began, and a leaving node must not start fetching for its peers.
    @Contract
    static void signalReadyAndWakePrefetch(Runnable signalReady,
                                           Map<String, StorageFactory.StorageSetup> storageSetups,
                                           BooleanSupplier draining) {
        signalReady.run();
        if (!draining.getAsBoolean()) {
            StorageFactory.activatePrefetch(storageSetups);
        }
    }

    /// B1 (membership v2 §7.5): the node's self-ready signal also marks subsystems ready for the
    /// node-reported readiness state (READY requires a live consensus-active level AND subsystems-ready).
    @Contract
    private static void markSubsystemsReady(Runnable signalReady, NodeReportedStateHolder holder) {
        signalReady.run();
        holder.onSubsystemsReady();
    }

    /// Per-node storage maintenance starts with the node's readiness: promotion and demotion of `artifacts` and
    /// `content` (see `StorageFactory#activateNodeMaintenance`), before the node reports ready. Reached only through
    /// [#markSubsystemsReadyOnceDhtAdmitted], so only after every DHT-backed instance is admitted. FER (degrade forward):
    /// a manager that cannot start is logged and the node still becomes ready, with that instance's tiers unmaintained;
    /// promotion is an optimization, not a dependency.
    @Contract
    static void signalReadyActivatingStorage(Runnable signalReady,
                                             Map<String, StorageFactory.StorageSetup> storageSetups,
                                             BooleanSupplier draining) {
        activateStorageUnlessDraining(storageSetups, draining);
        signalReady.run();
    }

    /// The ready signal fires on EVERY entry of the node-deployment state machine to Active, so it can fire again after
    /// the node began draining (a quorum-loss drain, then quorum comes back before the process exits). Drain is one-way and
    /// single-shot (`DrainProcedure`: INACTIVE -> DRAINING -> EXITED, nothing leads back), so a draining node's maintenance
    /// must stay off: re-activating it would resume promotion and demotion on a node that is leaving.
    @Contract
    private static void activateStorageUnlessDraining(Map<String, StorageFactory.StorageSetup> storageSetups,
                                                      BooleanSupplier draining) {
        if (draining.getAsBoolean()) {
            LOG.info("Node is draining: storage maintenance stays off");

            return;
        }

        StorageFactory.activateNodeMaintenance(storageSetups).onFailure(cause -> LOG.warn("Storage maintenance could not be activated: {}",
                                                                                          cause.message()));
    }

    /// #1052: the NDM self-ready signal, deferred until `dhtAdmission` succeeds -- every DHT-backed
    /// storage instance admitted by its post-formation encryption-marker check. Already admitted (or no
    /// DHT tier at all: an empty set resolves at once), it runs synchronously, exactly as before. Still
    /// pending, it runs when the last gate admits. A refusal never runs it: the node stays `JOINING`
    /// while `start()` fails on the same cause.
    @Contract
    private static void markSubsystemsReadyOnceDhtAdmitted(Promise<Unit> dhtAdmission,
                                                           Runnable signalReady,
                                                           NodeReportedStateHolder holder) {
        dhtAdmission.withSuccess(_ -> markSubsystemsReady(signalReady, holder));
    }

    /// B4/C-1 (membership v2 §7.5): nodes the leader observes in a given reported state — peers from
    /// the readiness view PLUS self when the local holder reports that state (the leader does not pong
    /// itself, so it is absent from the aggregated pong view; its own state is authoritative locally).
    static Set<NodeId> nodesReporting(ClusterSyncPongSignalFan fan,
                                      NodeReportedStateHolder selfHolder,
                                      NodeId self,
                                      NodeReportedState target) {
        return nodesReporting(fan.readinessSnapshot(), selfHolder, self, target);
    }

    static Set<NodeId> nodesReporting(Map<NodeId, NodeReportedState> states,
                                      NodeReportedStateHolder selfHolder,
                                      NodeId self,
                                      NodeReportedState target) {
        var matching = new HashSet<NodeId>();

        states.forEach((nodeId, state) -> addIfMatching(matching, nodeId, state, target));
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

    /// The committed generation epoch at local counter 0 (#1529): the cluster incarnation (dominant) and the
    /// leader term — the base every ownership writer mints its owner epochs from. A named seam so
    /// `ClusterIncarnationTest.GenerationEpochSeam` pins it (forcing the incarnation to 0 here left every
    /// module green). An epoch minted before the registrar commits the genesis incarnation carries 0, which
    /// ranks below every later mint.
    static Supplier<Epoch> generationEpoch(ObservedIncarnation incarnation, Supplier<Long> rabiaTermSupplier) {
        return () -> Epoch.epoch(incarnation.current(), rabiaTermSupplier.get(), 0L);
    }

    /// The node's incarnation-bearing epoch sources (#1529), built ONCE per node and shared by every consumer:
    /// the SWIM health detector, cluster sync, the governor authority, the QUIC connectivity reporter (all
    /// [#leaderEpoch]); the ownership writers and the bootstrap module ([#generationEpoch]); presence, worker
    /// metadata and stream cursors ([#incarnation]).
    ///
    /// None of them may read the incarnation through [ClusterIncarnation#current]: that goes through the
    /// synchronized `KVStore.getTyped`, whose monitor a snapshot restore holds for the whole install, and
    /// several of these suppliers are read inline on network threads — a SWIM hint on the SWIM UDP loop, a
    /// reconnect or an inbound attach on the QUIC loop, a routed request on its channel's loop (v1640 B1). They
    /// read the [ObservedIncarnation] mirror, which the committed key's notifications keep current.
    ///
    /// The mirror can only LAG the committed incarnation (until a commit's notification is dispatched, or a
    /// snapshot install's replay), never lead it, and a lagging incarnation mints a LOWER epoch. Every fence
    /// orders incarnation-first and refuses an older epoch, so a stale read is refused, never accepted in the
    /// newer incarnation's place: the cost of the lag is a retried write, not a wrong one.
    record EpochSources(ObservedIncarnation incarnation,
                        AtomicLong generationCounter,
                        Supplier<Epoch> leaderEpoch,
                        Supplier<Epoch> generationEpoch) {}

    /// Seeds the mirror from the store on the assembly thread, before consensus starts; its routes go live
    /// with the node's router (`routeEntries`), and no commit can land before that.
    static EpochSources epochSources(KVStore<AetherKey, AetherValue> kvStore, LeaderTerm leaderTerm) {
        var incarnation = ObservedIncarnation.observedIncarnation(ClusterIncarnation.current(kvStore));
        var generationCounter = new AtomicLong(0L);

        return new EpochSources(incarnation,
                                generationCounter,
                                () -> Epoch.epoch(incarnation.current(), leaderTerm.current(), generationCounter.get()),
                                generationEpoch(incarnation, leaderTerm::current));
    }

    private static RollbackConfig committedRollbackPolicy(Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader) {
        return clusterConfigReader.get()
                                  .map(AetherValue.ClusterConfigValue::tomlContent)
                                  .map(RollbackPolicyParser::fromClusterToml)
                                  .or(() -> Result.success(RollbackConfig.rollbackConfig()))
                                  .onFailure(cause -> LOG.error("Committed [rollback] policy unreadable, automatic rollback OFF: {}",
                                                                cause.message()))
                                  .or(RollbackConfig.rollbackConfig(false));
    }

    /// #1573: the ACTIVE instances of every deployed artifact version on nodes the membership FSM still counts,
    /// as the all-instances-failed detector needs them. An instance left in KV on a node membership has declared
    /// DEAD serves nothing and is not a host; a counted node that stopped reporting still is — and its stale
    /// metrics make the version undecidable. An unparsable artifact or node id is skipped, never guessed.
    private static Map<Artifact, Set<NodeId>> activeInstancesByArtifact(DeploymentMap deploymentMap,
                                                                        Option<MembershipFsm> membershipFsm) {
        return membershipFsm.map(fsm -> activeInstancesByArtifact(deploymentMap, fsm))
                            .or(Map.of());
    }

    private static Map<Artifact, Set<NodeId>> activeInstancesByArtifact(DeploymentMap deploymentMap,
                                                                        MembershipFsm membershipFsm) {
        var result = new HashMap<Artifact, Set<NodeId>>();

        deploymentMap.allDeployments()
                     .forEach(info -> Artifact.artifact(info.artifact()).onSuccess(artifact -> result.put(artifact,
                                                                                                          activeNodes(info,
                                                                                                                      membershipFsm))));

        return Map.copyOf(result);
    }

    private static Set<NodeId> activeNodes(DeploymentMap.SliceDeploymentInfo info, MembershipFsm membershipFsm) {
        return info.instances()
                   .stream()
                   .filter(instance -> instance.state() == SliceState.ACTIVE)
                   .map(instance -> new NodeId(instance.nodeId()))
                   .filter(membershipFsm::isCountedMember)
                   .collect(Collectors.toUnmodifiableSet());
    }

    static Set<NodeId> installedVoterIds(RabiaNode<KVCommand<AetherKey>> node) {
        return node.voterConfiguration()
                   .map(configuration -> Set.copyOf(configuration.members()))
                   .or(Set.of());
    }

    static Set<NodeId> configuredVoters(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .map(info -> configuredMember(info,
                                                   config.self(),
                                                   config.workerConfig().isPresent()))
                     .filter(info -> "core".equalsIgnoreCase(info.labels().getOrDefault(NodeInfo.LABEL_ROLE, "")))
                     .map(NodeInfo::id)
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// Genesis (#1526). A malformed `cluster.genesis_voters` fails boot loudly. A core (other than a
    /// single-node cluster) never installs a roster on its own: it defers genesis and its engine runs
    /// genesis view agreement over the discovered core membership, or over `cluster.genesis_voters`, until
    /// the agreement rules hold or an already formed electorate answers. A worker does not vote and keeps
    /// the plain wait-and-retry ([#retryGenesis]). No backup's voter configuration is consulted: a cold
    /// restart forms a fresh genesis and restores the data under it.
    private static Result<RabiaNode<KVCommand<AetherKey>>> initializeVoterConfiguration(RabiaNode<KVCommand<AetherKey>> node,
                                                                                        AetherNodeConfig config,
                                                                                        AtomicReference<Set<NodeId>> installedVoters,
                                                                                        AtomicReference<MembershipFsm> membershipFsmRef) {
        node.onVoterConfiguration(configuration -> installVoterConfiguration(node, installedVoters, configuration));

        return configuredGenesis(config).flatMap(configured -> initializeRoleVoters(node,
                                                                                    config,
                                                                                    configured,
                                                                                    membershipFsmRef))
                                .map(ignored -> node);
    }

    private static Result<Unit> initializeRoleVoters(RabiaNode<KVCommand<AetherKey>> node,
                                                     AetherNodeConfig config,
                                                     Option<VoterConfiguration> configured,
                                                     AtomicReference<MembershipFsm> membershipFsmRef) {
        if (configuredWorker(config)) {
            return initializeWorkerVoters(node, config, configured);
        }

        return initializeCoreVoters(node, config, configured, membershipFsmRef);
    }

    /// A roster of this node alone needs no agreement — there is no other member to report — so it is
    /// installed at assembly. Every other core runs genesis view agreement in its engine over the
    /// discovered core membership, or over `cluster.genesis_voters` when set.
    private static Result<Unit> initializeCoreVoters(RabiaNode<KVCommand<AetherKey>> node,
                                                     AetherNodeConfig config,
                                                     Option<VoterConfiguration> configured,
                                                     AtomicReference<MembershipFsm> membershipFsmRef) {
        var count = config.topology().clusterSize();

        return singleNodeGenesis(config, configured, count).map(node::initializeVoters)
                                .or(() -> node.deferGenesis(() -> discoveredCores(membershipFsmRef),
                                                            count,
                                                            configured.map(VoterConfiguration::roster),
                                                            expectedCores(config, configured)));
    }

    private static Set<NodeId> expectedCores(AetherNodeConfig config, Option<VoterConfiguration> configured) {
        return configured.map(roster -> Set.copyOf(roster.members()))
                         .or(() -> configuredVoters(config));
    }

    private static Option<VoterConfiguration> singleNodeGenesis(AetherNodeConfig config,
                                                                Option<VoterConfiguration> configured,
                                                                int count) {
        return configured.orElse(() -> completeRoster(List.copyOf(configuredVoters(config)),
                                                      count))
                         .filter(roster -> roster.members()
                                                 .equals(List.of(config.self())));
    }

    private static Result<Option<VoterConfiguration>> configuredGenesis(AetherNodeConfig config) {
        return config.configProvider()
                     .flatMap(provider -> provider.getString("cluster.genesis_voters"))
                     .map(AetherNode::parsedGenesis)
                     .or(Result.success(Option.none()));
    }

    private static Result<Option<VoterConfiguration>> parsedGenesis(String value) {
        return parseGenesisVoters(value).map(Option::some);
    }

    private static Result<Unit> initializeWorkerVoters(RabiaNode<KVCommand<AetherKey>> node,
                                                       AetherNodeConfig config,
                                                       Option<VoterConfiguration> configured) {
        return configured.orElse(() -> completeRoster(List.copyOf(configuredVoters(config)),
                                                      config.topology().clusterSize()))
                         .fold(node::deferGenesis, node::initializeVoters);
    }

    static Set<NodeId> discoveredCores(AtomicReference<MembershipFsm> membershipFsmRef) {
        return Option.option(membershipFsmRef.get())
                     .map(MembershipFsm::coreCountedMembers)
                     .or(Set.of());
    }

    /// Genesis wait-and-retry period for workers (#1526).
    static final TimeSpan GENESIS_RETRY_INTERVAL = TimeSpan.timeSpan(2).seconds();

    static Option<VoterConfiguration> completeRoster(List<NodeId> knownCores, int configuredCount) {
        return Option.some(knownCores)
                     .filter(cores -> cores.size() == configuredCount)
                     .flatMap(cores -> VoterConfiguration.voterConfiguration(0,
                                                                             List.copyOf(cores))
                                                         .option());
    }

    static Result<VoterConfiguration> parseGenesisVoters(String value) {
        var ids = java.util.Arrays.stream(value.split(",", -1)).map(String::strip).toList();

        if (ids.stream().anyMatch(String::isBlank)) {
            return VoterBootstrapError.MALFORMED_GENESIS_VOTERS.result();
        }

        return VoterConfiguration.voterConfiguration(0,
                                                     ids.stream().map(NodeId::new).toList());
    }

    private static void installVoterConfiguration(RabiaNode<KVCommand<AetherKey>> node,
                                                  AtomicReference<Set<NodeId>> installedVoters,
                                                  VoterConfiguration configuration) {
        installedVoters.set(Set.copyOf(configuration.members()));
        ((TopologyObserver) node.topologyManager()).installVoterConfiguration(configuration);
    }

    enum VoterBootstrapError implements Cause {
        MALFORMED_GENESIS_VOTERS("cluster.genesis_voters must list non-blank core node ids separated by commas"),
        GENESIS_ROSTER_INCOMPLETE("the discovered core membership does not yet name exactly the configured core count");
        private final String message;
        VoterBootstrapError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    static Supplier<Set<NodeId>> presenceMemberSupplier(Supplier<MembershipFsm> membershipFsm,
                                                        NodeId self,
                                                        Supplier<Set<NodeId>> installedVoters) {
        return () -> presenceMemberSupplier(membershipFsm, self).get()
                                           .stream()
                                           .filter(installedVoters.get()::contains)
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

    /// Configured seeds have a declared role even when the single-tier configuration omits labels.
    /// Discovered peers do not receive this default: unknown observations remain ineligible.
    static NodeInfo configuredMember(NodeInfo info, NodeId self, boolean worker) {
        var labels = new HashMap<>(info.labels());

        labels.putIfAbsent(NodeInfo.LABEL_ROLE,
                           info.id().equals(self) && worker
                           ? "worker"
                           : "core");

        return NodeInfo.nodeInfo(info.id(), info.address(), labels, info.resolvedAddress());
    }

    private static void publishSourceMetrics(RabiaNode<KVCommand<AetherKey>> cluster,
                                             Supplier<MembershipFsm> membership,
                                             ControlLoop control,
                                             CommunityMetricsSnapshot snapshot) {
        control.onCommunityMetricsSnapshot(snapshot);
        if (cluster.leaderManager().isLeader()) {
            return;
        }

        Option.option(membership.get()).onPresent(fsm -> sourceMetricsUplink(cluster, fsm).onPresent(target -> cluster.network()
                                                                                                                      .handleSend(new NetworkServiceMessage.Send(target,
                                                                                                                                                                 snapshot))));
    }

    private static Option<NodeId> sourceMetricsUplink(RabiaNode<KVCommand<AetherKey>> cluster,
                                                      MembershipFsm membership) {
        var peers = cluster.network().connectedPeers();

        return cluster.leaderManager()
                      .leader()
                      .filter(peers::contains)
                      .filter(node -> isCoreMember(membership, node))
                      .orElse(() -> Option.from(peers.stream()
                                                     .filter(node -> isCoreMember(membership, node))
                                                     .sorted()
                                                     .findFirst()));
    }

    /// Provisioned CORE observers may report provider readiness before consensus handoff.
    /// Workload allocation requires both membership readiness and installed voting authority.
    static Set<NodeId> installedCorePlacementMembers(Set<NodeId> counted, Set<NodeId> installed) {
        if (counted == MembershipFsm.MEMBERSHIP_NOT_WIRED) {
            return MembershipFsm.MEMBERSHIP_NOT_WIRED;
        }

        return counted.stream()
                      .filter(installed::contains)
                      .collect(Collectors.toSet());
    }

    static boolean isCoreMember(MembershipFsm membershipFsm, NodeId nodeId) {
        return membershipFsm.isTrackedAndNotDead(nodeId) && membershipFsm.memberDescriptor(nodeId)
                                                                         .map(MemberDescriptor::isCore)
                                                                         .or(false);
    }

    /// Promotion and demotion of this node's storage stop when the node starts draining, not only when it stops: a
    /// draining node is leaving, and a cycle that copies blocks into tiers about to be torn down gains nothing. FER: a
    /// manager that will not deactivate is logged and the drain goes on. Also run first thing in `shutdownStorage()`.
    /// Cross-node prefetching sleeps with them: a draining node neither gossips hints nor fetches for its peers.
    @Contract
    static void stopStorageMaintenance(Map<String, StorageFactory.StorageSetup> storageSetups) {
        StorageFactory.deactivatePrefetch(storageSetups);
        StorageFactory.deactivateNodeMaintenance(storageSetups).onFailure(cause -> LOG.warn("Storage maintenance could not be deactivated: {}",
                                                                                            cause.message()));
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

    /// #517: self is in its own topology by the time assembly runs — `TopologyObserver.topologyObserver`
    /// (via `RabiaNode.rabiaNode`, before `assembleNode`) refuses the config otherwise, pinned by
    /// `AetherNodeSelfAbsentFromTopologyBootTest`. The old `.orElse(new NodeAddress("", 0))` was a
    /// dead branch that would have advertised a placeholder silently if that ordering ever changed.
    static NodeAddress findSelfAddress(AetherNodeConfig config) {
        return Option.from(config.topology()
                                 .coreNodes()
                                 .stream()
                                 .filter(info -> info.id()
                                                     .equals(config.self()))
                                 .map(NodeInfo::address)
                                 .findFirst()).expect("self " + config.self()
                                                     + " absent from its own topology — refused by TopologyObserver.topologyObserver before assembly (#517)");
    }

    private static AetherValue.ProvisioningSource detectProvisioningSource() {
        var raw = Option.option(System.getenv("AETHER_PROVISIONED_BY"))
                        .filter(v -> !v.isBlank())
                        .map(String::trim)
                        .map(String::toLowerCase);

        return raw.map(AetherNode::provisioningSourceFrom)
                  .or(AetherValue.ProvisioningSource.MANUAL);
    }

    private static AetherValue.ProvisioningSource provisioningSourceFrom(String raw) {
        return switch (raw) {
            case "ctm" -> AetherValue.ProvisioningSource.CTM;
            case "manual" -> AetherValue.ProvisioningSource.MANUAL;
            // #964 S2: an unparseable config string is UNRECOGNISED, never the wire sentinel.
            default -> AetherValue.ProvisioningSource.UNRECOGNISED;
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

    /// The SWIM death hint a QUIC-side cause reports, tagged with the origin that decides how long
    /// SWIM believes it (#1061): `PEER_LEFT` describes one lost link, so a reconnect or a live link
    /// overrides it; `PING_TIMEOUT` describes a connected-but-silent peer, so a live link does not.
    static TransportObservation.PeerUnreachable unreachableHint(QuicTransportCause cause, NodeId peer) {
        return new TransportObservation.PeerUnreachable(peer, cause, hintOrigin(cause));
    }

    private static TransportObservation.HintOrigin hintOrigin(QuicTransportCause cause) {
        return switch (cause) {
            case PEER_LEFT -> TransportObservation.HintOrigin.LINK_LOST;
            case PING_TIMEOUT -> TransportObservation.HintOrigin.PEER_UNRESPONSIVE;
        };
    }

    /// The connectivity reporter and its `ObservedEpochSupplier` adapter, which the transport calls when it
    /// reports a peer connecting or leaving (#1529).
    record ConnectivityWiring(PeerConnectivityReporter reporter, QuicClusterNetwork.ObservedEpochSupplier epoch) {}

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

    /// #1533 — the ONE restore-commit hook. On a fresh cluster a component that loads cluster state once —
    /// at leader gain or on its task-group activation — does so BEFORE the restore lands, and sees an empty
    /// KV. A terminal restore decision re-drives each such component that has no put listener of its own
    /// for the keys it loaded: the bootstrap config seed (refused by the restore gate), the rollout manager
    /// (`DeploymentKey`) and the A/B test manager (`AbTestKey`). Components that listen for puts of their
    /// restored keys are covered by those listeners, because the restore's writes publish ordinary puts.
    @Contract
    static void onRestoreDecision(KVStoreNotification.ValuePut<AetherKey.BackupRestoreKey, AetherValue.BackupRestoreValue> put,
                                  BootstrapModule bootstrapModule,
                                  DeploymentManager deploymentManager,
                                  AbTestManager abTestManager) {
        if (put.cause().value().outcome().isTerminal()) {
            bootstrapModule.retryIfNeeded();
            deploymentManager.reloadRestoredState();
            abTestManager.reloadRestoredState();
        }
    }

    /// #1797: a committed `LeaderKey` naming this node raises the held term even without a leader-gain edge.
    @Contract
    static void onLeaderKeyCommit(KVStoreNotification.ValuePut<?, ?> notification, LeaderTerm leaderTerm) {
        if (notification.cause().key() instanceof LeaderKey) {
            leaderTerm.onLeaderKeyCommitted();
        }
    }

    private static void onLeaderChangeForPublisher(LeaderNotification.LeaderChange change,
                                                   LeaderTerm leaderTerm,
                                                   BootstrapModule bootstrap) {
        if (change.localNodeIsLeader()) {
            leaderTerm.onLeaderGained();
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
    /// #683: the transport sees the encryptor through [GossipKeyDivergenceGuard], which refuses the
    /// boot if gossip arrives under a key epoch this node does not hold and none has ever decrypted
    /// — the signature of a cluster that rotated its gossip key after this node's derived key was
    /// issued. The guard decorates for the TRANSPORT only; `encryptor` itself stays the rotation
    /// target, so an applied rotation is picked up through the delegate and disarms the guard.
    /// The guard exits through the node's `gossipKeyDivergedExit` (#1312): production passes
    /// `System.exit(EXIT_GOSSIP_KEY_DIVERGED)`, which mirrors `Main`'s other boot gates and runs the node's
    /// shutdown hooks — the guard is armed for minutes after boot, long enough for this node to have
    /// joined consensus over QUIC, and `System.exit` was measured to terminate cleanly from this thread
    /// ([GossipKeyDivergenceGuard]). A single-JVM host (Ember/Forge) passes a per-node stop, because a
    /// `System.exit` there would take every co-hosted node down.
    ///
    /// #1308: the join is announced only once SWIM has STARTED, and a failed start (typically the
    /// SWIM UDP port already bound) fails the node through `failNode`. A node without its SWIM
    /// listener neither answers peers' probes nor probes them, so it must not keep running as a
    /// cluster member. The caller passes the node's `jvmExit`: `Runtime.halt(2)` in production, a
    /// graceful per-node stop in single-JVM hosts (Ember/Forge), where `System.exit` would take every
    /// co-hosted node down. Skipping shutdown hooks is judged acceptable here because SWIM starts at
    /// transport-ready, before this node has joined or consensus has formed, so it has acknowledged
    /// no cluster write a hook would need to flush (design intent, not measured).
    ///
    /// `startOutcome` is the node's start outcome ([#formationUnlessSwimFails]): a failed start fails it
    /// FIRST, inline on the resolved start promise, and only then runs `failNode`. Ordered so because
    /// `failNode` may block (Ember awaits the node's stop) while formation can still resolve, and
    /// the outcome is first-settlement-wins.
    static Promise<Unit> startSwim(CoreSwimHealthDetector swimHealthDetector,
                                   ClusterNetwork network,
                                   RotatingGossipEncryptor encryptor,
                                   Runnable announceJoinTrigger,
                                   Runnable failNode,
                                   Runnable gossipKeyDivergedExit,
                                   Promise<Unit> startOutcome) {
        return startSwim(swimHealthDetector,
                         network,
                         encryptor,
                         announceJoinTrigger,
                         failNode,
                         gossipKeyDivergedExit,
                         System::nanoTime,
                         startOutcome);
    }

    /// The same, on the divergence gate's arming clock (`gateClock`), so a test can arm the gate without
    /// waiting out its delay.
    static Promise<Unit> startSwim(CoreSwimHealthDetector swimHealthDetector,
                                   ClusterNetwork network,
                                   RotatingGossipEncryptor encryptor,
                                   Runnable announceJoinTrigger,
                                   Runnable failNode,
                                   Runnable gossipKeyDivergedExit,
                                   LongSupplier gateClock,
                                   Promise<Unit> startOutcome) {
        var workerGroup = network.server().map(Server::workerGroup);
        var guarded = GossipKeyDivergenceGuard.gossipKeyDivergenceGuard(encryptor, gossipKeyDivergedExit, gateClock);
        // `startOutcome::fail` and the announcement are dependent steps (`withFailure`/`withSuccess`), so they have run
        // when `start` resolves for whoever awaits it. `failNode` may block (Ember awaits the node's own stop), so it stays
        // a detached handler, registered on the DERIVED promise: it can run only after `startOutcome` was failed.
        return swimHealthDetector.start(workerGroup, guarded)
                                 .withFailure(startOutcome::fail)
                                 .withSuccess(_ -> announceUnlessClosed(swimHealthDetector, announceJoinTrigger))
                                 .onFailure(cause -> refuseToRunWithoutSwim(swimHealthDetector.swimPort(),
                                                                            cause,
                                                                            failNode));
    }

    /// A start refused because `stop()` already closed the detector resolves as a no-op success (it must
    /// not run `failNode`: in a single-JVM host a stale one could stop a same-id relaunch). There is no
    /// protocol to announce for, so the announcement is skipped.
    ///
    /// A dependent step of the start: a trigger that throws must not fail the start it follows (that would run `failNode`),
    /// so a throw is logged instead.
    ///
    /// FER (degrade forward): SWIM is up, so a failed announcement is absorbed with a WARN rather than halting a node whose
    /// failure detector runs. Guarantee earned: a lost announcement does not stop the node. Mechanism: a single attempt, no retry
    /// here; the WARN is the only record of it.
    @Contract
    private static void announceUnlessClosed(CoreSwimHealthDetector swimHealthDetector, Runnable announceJoinTrigger) {
        if (!swimHealthDetector.isClosed()) {
            Result.lift(announceJoinTrigger::run).onFailure(cause -> LOG.warn("SWIM join announcement failed: {}",
                                                                              cause.message()));
        }
    }

    /// #1308: the node's start outcome, given consensus formation and the outcome promise that
    /// [#startSwim] fails inline when SWIM cannot start. Formation settles it either way — first
    /// settlement wins (`resolve` is compare-and-set), so a SWIM failure that has already landed is
    /// never overridden by a later formation success. A SWIM start SUCCESS settles nothing: the
    /// node has started only once formation resolves. A SWIM failure that lands after formation is
    /// moot for the outcome — in production `failNode` has already halted the JVM, in a single-JVM
    /// host it has already stopped this node.
    static Promise<Unit> formationUnlessSwimFails(Promise<Unit> formation, Promise<Unit> outcome) {
        formation.onResult(outcome::resolve);

        return outcome;
    }

    private static void refuseToRunWithoutSwim(int swimPort, Cause cause, Runnable failNode) {
        LOG.error("SWIM failed to start on UDP port {}: {} — this node cannot answer or send failure-detection "
                 + "probes, so it will not announce its join and is exiting instead of running without SWIM",
                  swimPort,
                  cause.message());
        failNode.run();
    }

    private static Set<NodeId> retirementCandidates(Set<NodeId> planned,
                                                    RabiaNode<KVCommand<AetherKey>> cluster,
                                                    Supplier<MembershipFsm> membership,
                                                    KVStore<AetherKey, AetherValue> store,
                                                    NodeReplacementIndex replacements,
                                                    Set<NodeId> ready) {
        var combined = new HashSet<>(planned);

        combined.addAll(excludedCoreNodes(cluster, membership, store, replacements, ready));

        return Set.copyOf(combined);
    }

    static Set<NodeId> excludedCoreNodes(RabiaNode<KVCommand<AetherKey>> cluster,
                                         Supplier<MembershipFsm> membership,
                                         KVStore<AetherKey, AetherValue> store,
                                         NodeReplacementIndex replacements,
                                         Set<NodeId> ready) {
        var desired = store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
                           .map(AetherValue.ClusterConfigValue::coreCount)
                           .or(0);

        return cluster.retirementSafeVoters()
                      .filter(voters -> voters.members()
                                              .size() == desired)
                      .flatMap(voters -> Option.option(membership.get()).map(fsm -> excludedCores(fsm.coreCountedMembers(),
                                                                                                  Set.copyOf(voters.members()),
                                                                                                  cluster.verifiedVoterHistoryIds(),
                                                                                                  ready,
                                                                                                  replacements.retirementProtected())))
                      .or(Set.of());
    }

    /// The counted cores outside the installed roster that may be retired — minus every node a live #1543
    /// replacement pairing protects, so a fresh +1 replacement is not reaped as surplus before it is voted in.
    static Set<NodeId> excludedCores(Collection<NodeId> coreCounted,
                                     Set<NodeId> installed,
                                     Set<NodeId> history,
                                     Set<NodeId> ready,
                                     Set<NodeId> replacementProtected) {
        return coreCounted.stream()
                          .filter(node -> !replacementProtected.contains(node))
                          .filter(node -> retirementEligibleCore(node, installed, history, ready))
                          .collect(Collectors.toUnmodifiableSet());
    }

    static Set<NodeId> readyCoreCandidates(Set<NodeId> ready, Supplier<MembershipFsm> membership) {
        return Option.option(membership.get())
                     .map(fsm -> ready.stream()
                                      .filter(node -> isCoreMember(fsm, node))
                                      .collect(Collectors.toUnmodifiableSet()))
                     .or(Set.of());
    }

    /// A certified electorate excludes both retired voters and candidates that never voted.
    /// Candidate exclusion is surplus evidence only while the complete installed roster is ready.
    static boolean retirementEligibleCore(NodeId node, Set<NodeId> installed, Set<NodeId> history, Set<NodeId> ready) {
        return ! installed.isEmpty()
               && !installed.contains(node)
               && (history.contains(node) || ready.containsAll(installed));
    }

    static Option<AetherValue.CommunityPlacementOperationValue> selfPlacementOperation(KVStore<AetherKey, AetherValue> store,
                                                                                       NodeId self) {
        return store.getTyped(AetherKey.ActivationDirectiveKey.activationDirectiveKey(self),
                              AetherValue.ActivationDirectiveValue.class)
                    .flatMap(directive -> store.getTyped(new AetherKey.CommunityPlacementOperationKey(directive.communityId()),
                                                         AetherValue.CommunityPlacementOperationValue.class));
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

    static boolean configuredWorker(AetherNodeConfig config) {
        return config.workerConfig()
                     .isPresent() || config.topology()
                                           .coreNodes()
                                           .stream()
                                           .filter(info -> info.id()
                                                               .equals(config.self()))
                                           .anyMatch(info -> Set.of("worker", "spot").contains(info.labels()
                                                                                                   .getOrDefault(NodeInfo.LABEL_ROLE,
                                                                                                                 "")
                                                                                                   .toLowerCase(java.util.Locale.ROOT)));
    }

    private static long resolveStreamMaxMemoryBytes() {
        return Option.option(System.getenv("STREAM_MAX_MEMORY_BYTES"))
                     .filter(s -> !s.isBlank())
                     .flatMap(s -> Result.lift(() -> Long.parseLong(s)).option())
                     .or(128 * 1024 * 1024L);
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
        // #887 E2/E3: every node derives its security overrides from the replicated blueprint. The
        // data was already here (AppBlueprintValue carries ExpandedBlueprint.securityOverrides() to
        // every node, durably); what was missing was a reader, so overrides reached only the node
        // that served POST /api/v1/blueprints and no other node enforced them.
        var securityOverrideSynchronizer = SecurityOverrideSynchronizer.securityOverrideSynchronizer(kvStore,
                                                                                                     appHttpServer::httpRoutePublisher);
        var kvRouterBuilder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                  .onPut(AetherKey.AppBlueprintKey.class,
                                                         clusterDeploymentManager::onAppBlueprintPut)
                                                  .onPut(AetherKey.AppBlueprintKey.class,
                                                         securityOverrideSynchronizer::onAppBlueprintPut)
                                                  .onRemove(AetherKey.AppBlueprintKey.class,
                                                            securityOverrideSynchronizer::onAppBlueprintRemove)
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
                                                  .onPut(AetherKey.SliceTargetKey.class,
                                                         nodeDeploymentManager::onSliceTargetPut)
                                                  .onPut(AetherKey.VersionRoutingKey.class,
                                                         nodeDeploymentManager::onVersionRoutingPut)
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
                                                  .onPut(AetherKey.ScheduledTaskPauseKey.class,
                                                         scheduledTaskRegistry::onScheduledTaskPausePut)
                                                  .onRemove(AetherKey.ScheduledTaskPauseKey.class,
                                                            scheduledTaskRegistry::onScheduledTaskPauseRemove)
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
                                                  .onPut(AetherKey.CommunityKey.class, eventAggregator::onCommunityPut)
                                                  .onPut(AetherKey.GovernorAnnouncementKey.class,
                                                         eventAggregator::onGovernorAnnouncementPut)
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
        // #381: push each applied config change to the registered slices' notifyConfigUpdate.
        dynamicConfigManager.onPresent(dcm -> dcm.onApplied(nodeDeploymentManager::onConfigChanged));
        kvRouterBuilder.onPut(AetherKey.ConsumerGroupKey.class, consumerGroupRegistry::onConsumerGroupPut);
        kvRouterBuilder.onRemove(AetherKey.ConsumerGroupKey.class, consumerGroupRegistry::onConsumerGroupRemove);
        // #345 1b: feed the per-ownership-domain epoch high-water table from committed EpochBearing puts.
        // No onRemove — the high-water is monotonic by definition, so ownership/governor removes are intentionally ignored.
        kvRouterBuilder.onPut(AetherKey.GovernorAnnouncementKey.class,
                              ownershipEpochHighWater::onGovernorAnnouncementPut);
        // #731 round 3: re-run the dead-worker sweep on every committed reannouncement, not only on
        // the one-shot 2s deferredTopologyRecheck timer (ClusterDeploymentState onEntry).
        kvRouterBuilder.onPut(AetherKey.GovernorAnnouncementKey.class,
                              clusterDeploymentManager::onGovernorAnnouncementPut);
        kvRouterBuilder.onPut(AetherKey.DhtPartitionOwnershipKey.class, ownershipEpochHighWater::onDhtOwnershipPut);
        kvRouterBuilder.onPut(AetherKey.StreamPartitionOwnershipKey.class,
                              ownershipEpochHighWater::onStreamPartitionOwnershipPut);
        // #1640: a new cluster-events owner re-sends every event waiting for redelivery at once.
        kvRouterBuilder.onPut(AetherKey.StreamPartitionOwnershipKey.class,
                              eventAggregator::onStreamPartitionOwnershipPut);
        entries.addAll(kvRouterBuilder.build().asRouteEntries());
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, nodeDeploymentManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, controlLoop::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, metricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                              deploymentMetricsScheduler::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, scheduledTaskManager::onQuorumStateChange));
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class, appHttpServer::onQuorumStateChange));
        // #887 E3: a node that just became ACTIVE restored state it never observed arriving.
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                              securityOverrideSynchronizer::onQuorumStateChange));
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
        // #1723: a per-node scheduled row whose node left for good while its newest fire was unknown is closed by the leader.
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class, scheduledTaskManager::onNodeRemoved));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              scheduledTaskManager::onNodeDecommissioned));
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
        // #731: the non-core leave channel, symmetric to the join route above — a departed
        // worker's REMOVED edge never reaches MembershipDecision either, so without this route
        // handleNodeRemoval is unreachable for a dead worker and its allocation-pool slot and KV
        // footprint (SliceNodeKey/NodeArtifactKey/NodeRoutesKey) linger forever.
        entries.add(MessageRouter.Entry.route(WorkerLeaveDecision.class, clusterDeploymentManager::onWorkerLeave));
        entries.add(MessageRouter.Entry.route(WorkerLeaveDecision.class,
                                              decision -> {
                                                  var node = decision.nodeId();

                                                  sliceInvoker.onNodeDeparture(node);
                                                  appHttpServer.onNodeDeparture(node);
                                                  httpRouteRegistry.evictNode(node);
                                                  deploymentMetricsCollector.onNodeDeparture(node);
                                                  controlLoop.onWorkerDeparture(node);
                                                  loadBalancerManager.onPresent(manager -> manager.onNodeDeparture(node));
                                              }));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class, sliceInvoker::onNodeRemoved));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,
                                              sliceInvoker::onNodeDecommissioned));
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
        // #689: the worker join channel reaches the CTM too, so a provisioned node's advertised role
        // is compared against its provisioning intent on whichever channel it joins.
        entries.add(MessageRouter.Entry.route(WorkerJoinDecision.class, clusterTopologyManager::onWorkerJoin));
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
        // #588: the pong roster (`allMetrics()`, the node list `/api/v1/cluster/status` serves) forgets a
        // core node on NodeRemoved above; a worker's death travels on WorkerLeaveDecision instead, and
        // without this route its last pong stayed in the roster forever as an UNKNOWN ghost.
        entries.add(MessageRouter.Entry.route(WorkerLeaveDecision.class,
                                              decision -> metricsCollector.removeNode(decision.nodeId())));
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
        // #926: resolve the node-health alert raised on the DEAD edge when the node comes back. Bound
        // to the SAME ungated handshake that sources NODE_JOINED, so recovery is exactly as reachable
        // as the failure it clears — a failure signal whose matching recovery signal is less reachable
        // leaves a permanently red surface, which trains an operator to ignore it.
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.PeerJoined.class,
                                              msg -> alertManager.clearNodeHealthAlert(msg.nodeId())));
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
        entries.add(MessageRouter.Entry.route(ObserverResumedNotification.class, eventAggregator::onObserverResumed));
        entries.add(MessageRouter.Entry.route(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown.class,
                                              eventAggregator::onSelfShutdown));
        entries.add(MessageRouter.Entry.route(DeploymentEvent.DeploymentFailed.class, abTestManager::onDeploymentFailed));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              eventAggregator::onSliceFailure));
        entries.add(MessageRouter.Entry.route(RollbackEvent.AutoRollbackExecuted.class, eventAggregator::onAutoRollback));
        entries.add(MessageRouter.Entry.route(SliceFailureEvent.AllInstancesFailed.class,
                                              alertManager::onAllInstancesFailed));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledUp.class, eventAggregator::onScaledUp));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaledDown.class, eventAggregator::onScaledDown));
        entries.add(MessageRouter.Entry.route(ScalingEvent.ScaleCapped.class, eventAggregator::onScaleCapped));
        entries.add(MessageRouter.Entry.route(ClusterDeploymentManager.ReconciliationAdjustment.class,
                                              eventAggregator::onReconciliationAdjustment));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                              eventAggregator::onConnectionEstablished));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionFailed.class,
                                              eventAggregator::onConnectionFailed));
        entries.add(MessageRouter.Entry.route(NetworkServiceMessage.OfflineFramesExpired.class,
                                              eventAggregator::onOfflineFramesExpired));
        entries.add(MessageRouter.Entry.route(OperationalEvent.AccessDenied.class, eventAggregator::onAccessDenied));
        entries.add(MessageRouter.Entry.route(OperationalEvent.NodeLifecycleChanged.class,
                                              eventAggregator::onNodeLifecycleChanged));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ConfigChanged.class, eventAggregator::onConfigChanged));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BlueprintDeployed.class,
                                              eventAggregator::onBlueprintDeployed));
        entries.add(MessageRouter.Entry.route(OperationalEvent.StreamFailoverRefused.class,
                                              eventAggregator::onStreamFailoverRefused));
        entries.add(MessageRouter.Entry.route(OperationalEvent.StreamFailoverResolved.class,
                                              eventAggregator::onStreamFailoverResolved));
        entries.add(MessageRouter.Entry.route(OperationalEvent.StreamIsrBelowMinimum.class,
                                              eventAggregator::onStreamIsrBelowMinimum));
        entries.add(MessageRouter.Entry.route(OperationalEvent.StreamIsrRestored.class,
                                              eventAggregator::onStreamIsrRestored));
        entries.add(MessageRouter.Entry.route(OperationalEvent.StreamLineageRestarted.class,
                                              eventAggregator::onStreamLineageRestarted));
        entries.add(MessageRouter.Entry.route(OperationalEvent.StreamConfigChangeNotApplied.class,
                                              eventAggregator::onStreamConfigChangeNotApplied));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ScheduledTaskOutcomeUnknown.class,
                                              eventAggregator::onScheduledTaskOutcomeUnknown));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ScheduledTaskOutcomeRestored.class,
                                              eventAggregator::onScheduledTaskOutcomeRestored));
        entries.add(MessageRouter.Entry.route(OperationalEvent.RoutePrefixCollision.class,
                                              eventAggregator::onRoutePrefixCollision));
        entries.add(MessageRouter.Entry.route(OperationalEvent.RoutePrefixCollisionCleared.class,
                                              eventAggregator::onRoutePrefixCollisionCleared));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ScheduledTaskFireHeld.class,
                                              eventAggregator::onScheduledTaskFireHeld));
        entries.add(MessageRouter.Entry.route(OperationalEvent.ScheduledTaskFireReleased.class,
                                              eventAggregator::onScheduledTaskFireReleased));
        entries.add(MessageRouter.Entry.route(OperationalEvent.BlueprintDeleted.class,
                                              eventAggregator::onBlueprintDeleted));
        entries.add(MessageRouter.Entry.route(OperationalEvent.DhtReplicationUnsettled.class,
                                              eventAggregator::onDhtReplicationUnsettled));
        entries.add(MessageRouter.Entry.route(OperationalEvent.DhtReplicationSettled.class,
                                              eventAggregator::onDhtReplicationSettled));
        entries.add(MessageRouter.Entry.route(OperationalEvent.DhtWriterStale.class, eventAggregator::onDhtWriterStale));
        entries.add(MessageRouter.Entry.route(OperationalEvent.DhtWriterStaleResolved.class,
                                              eventAggregator::onDhtWriterStaleResolved));
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeRequest.class, invocationHandler::onInvokeRequest));
        entries.add(MessageRouter.Entry.route(InvocationMessage.InvokeResponse.class, sliceInvoker::onInvokeResponse));
        entries.addAll(httpForwardRouteEntries(appHttpServer, managementServerRef));
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
        // #964: switch rather than `== MANAGEMENT ? management : app`. Every pipeline test in this
        // codebase asked "is it MANAGEMENT?", so an UNKNOWN decoded from a peer running a newer
        // Pipeline would have landed on the APP pipeline by default — a forwarded request routed into
        // a server it was not addressed to. UNKNOWN goes to NEITHER, and the next Pipeline constant is
        // now a compile error here instead of another silent fall-through onto app.
        switch (request.pipeline()) {
            case MANAGEMENT -> managementServer.onPresent(ms -> ms.onHttpForwardRequest(request));
            case APP -> appHttpServer.onHttpForwardRequest(request);
            case UNKNOWN -> LoggerFactory.getLogger(AetherNode.class).warn("Dropping forwarded HTTP request {} from {}: its target pipeline was" + " written by a node running a newer Pipeline and cannot be read" + " here (#964). It is NOT routed to the app pipeline.",
                                                                           request.requestId(),
                                                                           request.sender());
        }
    }

    /// The HTTP forward messages, each demuxed by its pipeline (#964). Public only so a test can pin this wiring
    /// without booting a node: a missing entry, or a demux that drops the app pipeline, fails no other test, it only
    /// makes forwards wait out their hop timeout (#2244 F1).
    static List<MessageRouter.Entry<?>> httpForwardRouteEntries(AppHttpServer appHttpServer,
                                                                AtomicReference<Option<ManagementServer>> managementServerRef) {
        return List.of(MessageRouter.Entry.route(HttpForwardMessage.HttpForwardRequest.class,
                                                 request -> demuxHttpForwardRequest(request,
                                                                                    appHttpServer,
                                                                                    managementServerRef.get())),
                       MessageRouter.Entry.route(HttpForwardMessage.HttpForwardResponse.class,
                                                 response -> demuxHttpForwardResponse(response,
                                                                                      appHttpServer,
                                                                                      managementServerRef.get())),
                       MessageRouter.Entry.route(HttpForwardMessage.HttpForwardNotApplied.class,
                                                 notApplied -> demuxHttpForwardNotApplied(notApplied, appHttpServer)));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void demuxHttpForwardResponse(HttpForwardMessage.HttpForwardResponse response,
                                                 AppHttpServer appHttpServer,
                                                 Option<ManagementServer> managementServer) {
        // #964: see demuxHttpForwardRequest — UNKNOWN must not default onto the app pipeline.
        switch (response.pipeline()) {
            case MANAGEMENT -> managementServer.onPresent(ms -> ms.onHttpForwardResponse(response));
            case APP -> appHttpServer.onHttpForwardResponse(response);
            case UNKNOWN -> LoggerFactory.getLogger(AetherNode.class).warn("Dropping forwarded HTTP response for {}: its target pipeline was" + " written by a node running a newer Pipeline and cannot be read" + " here (#964). It is NOT routed to the app pipeline.",
                                                                           response.correlationId());
        }
    }

    /// #2244 F1: only the app pipeline's receiver sends a not-applied answer; any other pipeline's is dropped, and
    /// that pending forward waits out its hop timeout as "may have been applied" (the conservative side).
    private static void demuxHttpForwardNotApplied(HttpForwardMessage.HttpForwardNotApplied notApplied,
                                                   AppHttpServer appHttpServer) {
        switch (notApplied.pipeline()) {
            case APP -> appHttpServer.onHttpForwardNotApplied(notApplied);
            case MANAGEMENT, UNKNOWN -> LoggerFactory.getLogger(AetherNode.class).warn("Dropping forward not-applied answer for {} on pipeline {}: only the app pipeline sends it",
                                                                                       notApplied.correlationId(),
                                                                                       notApplied.pipeline());
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

    record ResourceProviderSetup(ResourceProviderFacade facade,
                                 Option<DynamicConfigurationProvider> dynamicProvider,
                                 Option<ConfigurationProvider> nodeComposite,
                                 Option<SpiResourceProvider> spiProvider,
                                 Option<Fn1<Promise<String>, String>> secretResolver) {}

    /// A real composite (#1927): every configured repository is consulted in order, falling through only when one answered "absent".
    private static Repository compositeRepository(List<Repository> repositories) {
        return CompositeRepository.compositeRepository(repositories);
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

    private static StreamForwardTransport createStreamForwardTransport(ClusterNetwork network) {
        return new StreamForwardTransport() {
            @Contract
            @Override
            public void send(NodeId target, StreamForwardMessage message) {
                network.send(target, message);
            }

            @Contract
            @Override
            public void send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
                network.send(target, message, callerWait);
            }
        };
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

    static boolean runtimeReady(RabiaNode<KVCommand<AetherKey>> cluster) {
        return cluster.isActive() || cluster.isPassiveClientReady() || (cluster.isObserving() && !installedVoterIds(cluster).contains(cluster.self()));
    }

    static List<NodeInfo> metadataDirectory(MembershipFsm membership, Set<NodeId> peers) {
        return peers.stream()
                    .sorted()
                    .flatMap(peer -> membership.memberDescriptor(peer)
                                               .flatMap(descriptor -> descriptor.address()
                                                                                .map(address -> NodeInfo.nodeInfo(peer,
                                                                                                                  address,
                                                                                                                  Map.of(NodeInfo.LABEL_ROLE,
                                                                                                                         descriptor.role(),
                                                                                                                         NodeInfo.LABEL_SOURCE,
                                                                                                                         descriptor.source()))))
                                               .stream())
                    .toList();
    }
}
