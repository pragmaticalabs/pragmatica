// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.cluster.CapacityView;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.deployment.cluster.CommunityRetirementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.EpochSources;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.DhtReplicationSettlement;
import org.pragmatica.aether.node.DhtWriterStaleWatch;
import org.pragmatica.aether.node.GossipKeyRotationHandler;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.governor.CommunityMembershipFilter;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.cluster.replica.ReplicaFeedClient;
import org.pragmatica.cluster.replica.ReplicaServeGate;
import org.pragmatica.aether.worker.governor.GovernorAuthority;
import org.pragmatica.aether.worker.governor.GovernorAuthorityClient;
import org.pragmatica.aether.worker.governor.GovernorAuthorityMessage;
import org.pragmatica.aether.worker.health.CommunityHealthIndex;
import org.pragmatica.aether.worker.health.CommunityHealthRuntime;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.aether.worker.health.GovernorCandidateHealth;
import org.pragmatica.aether.worker.health.WorkerAdmission;
import org.pragmatica.aether.worker.metadata.WorkerMetadataChannel;
import org.pragmatica.aether.worker.metadata.WorkerMetadataMessage;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.cluster.metrics.ClusterSyncMessage;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;

import org.slf4j.Logger;

import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.applyDhtReplication;
import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.installedVoterIds;
import static org.pragmatica.aether.node.AetherNode.isCoreMember;
import static org.pragmatica.aether.node.AetherNode.metadataDirectory;
import static org.pragmatica.aether.node.AetherNode.nodesReporting;
import static org.pragmatica.aether.node.AetherNode.reconcileNodeActivation;
import static org.pragmatica.aether.node.AetherNode.runtimeReady;


/// The worker hierarchy unit of the node assembly: the worker switch of the cluster node, the worker activation router and
/// governor authority client, the worker metadata channel with the community directory, health, admission and recovery,
/// and the community reachability that feeds the deployment manager. Moved out of `AetherNode` without change; the root
/// calls the phases in the order it always ran them.
public sealed interface WorkerHierarchyAssembly {
    /// What `workerSwitch` reads from the assembly built so far.
    record WorkerSwitchInputs(AetherNodeConfig config,
                              RabiaNode<KVCommand<AetherKey>> clusterNode,
                              SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                              Set<NodeId> corePeerIds) {}

    /// The forwarding cluster node and, on a worker, the switch of the cluster node to worker mode.
    static ForwardingClusterNode<KVCommand<AetherKey>> workerSwitch(WorkerSwitchInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var switchableCluster = in.switchableCluster();
        var corePeerIds = in.corePeerIds();
        var forwardingClusterNode = ForwardingClusterNode.forwardingClusterNode(clusterNode,
                                                                                clusterNode.network(),
                                                                                corePeerIds);
        // Worker role is immutable: even the first metadata cut's LOAD callbacks must forward.
        if (configuredWorker(config)) {
            switchableCluster.switchTo(forwardingClusterNode);
        }

        return forwardingClusterNode;
    }

    /// What `workerActivation` reads from the assembly built so far.
    record WorkerActivationInputs(AetherNodeConfig config,
                                  MessageRouter.DelegateRouter delegateRouter,
                                  KVStore<AetherKey, AetherValue> kvStore,
                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                  AtomicReference<MembershipFsm> membershipFsmRef,
                                  SliceStore sliceStore,
                                  SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                  ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                  CommunityRetirementIndex communityRetirements,
                                  NodeReplacementIndex nodeReplacements,
                                  UpgradeRunIndex upgradeRuns,
                                  ClusterDeploymentManager clusterDeploymentManager,
                                  AtomicReference<BooleanSupplier> workerProjectionFreshRef,
                                  AtomicReference<CoreSwimHealthDetector> swimHealthDetectorHolder,
                                  SliceInvoker sliceInvoker,
                                  List<MessageRouter.Entry<?>> aetherEntries,
                                  Logger growthLog,
                                  NodeId selfId,
                                  GossipKeyRotationHandler gossipKeyRotationHandler) {}

    /// What `workerActivation` hands back for the rest of the assembly.
    record WorkerActivationOutputs(AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                                   CommunityMemberDirectory communityDirectory,
                                   KVNotificationRouter<AetherKey, AetherValue> activationKvRouter) {}

    /// The governor announcer holder, the community directory, the governor authority client and the router that applies activation directives.
    static WorkerActivationOutputs workerActivation(WorkerActivationInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var membershipFsmRef = in.membershipFsmRef();
        var sliceStore = in.sliceStore();
        var switchableCluster = in.switchableCluster();
        var forwardingClusterNode = in.forwardingClusterNode();
        var communityRetirements = in.communityRetirements();
        var nodeReplacements = in.nodeReplacements();
        var upgradeRuns = in.upgradeRuns();
        var clusterDeploymentManager = in.clusterDeploymentManager();
        var workerProjectionFreshRef = in.workerProjectionFreshRef();
        var swimHealthDetectorHolder = in.swimHealthDetectorHolder();
        var sliceInvoker = in.sliceInvoker();
        var aetherEntries = in.aetherEntries();
        var growthLog = in.growthLog();
        var selfId = in.selfId();
        var gossipKeyRotationHandler = in.gossipKeyRotationHandler();
        // Worker-mode activation needs the SWIM detector to feed the governor announcer
        // (GAP 2). The detector is a local declared further down (after the SWIM config is
        // built), but this registration lambda is constructed here — so capture it through a
        // holder seeded immediately after the detector is created. The ActivationDirectiveKey
        // ValuePut handler only fires long after seeding, so the holder is always populated by
        // read time. A holder (not a reorder) keeps the router-build / replay-burst ordering
        // documented below intact.
        // (`swimHealthDetectorHolder` is declared earlier, beside the CTM wiring that also reads it.)
        // #642: worker-mode subsystems are created lazily, when a WORKER activation directive arrives —
        // long after assembly — so the announcer cannot be a plain local. The holder is how stop()
        // reaches it to cancel the re-announce tick; empty on a node that never became a worker.
        var governorAnnouncerHolder = new AtomicReference<GovernorAnnouncer>();
        var communityDirectory = org.pragmatica.aether.worker.health.CommunityMemberDirectory.communityMemberDirectory();
        var governorAuthorityClient = GovernorAuthorityClient.governorAuthorityClient(() -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                             LeaderValue.class)
                                                                                                   .map(LeaderValue::leader),
                                                                                      (target, message) -> clusterNode.network()
                                                                                                                      .send(target,
                                                                                                                            message));

        aetherEntries.add(MessageRouter.Entry.route(GovernorAuthorityMessage.Response.class,
                                                    governorAuthorityClient::onResponse));
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
                                                                                                                                                                                communityDirectory,
                                                                                                                                                                                governorAnnouncerHolder,
                                                                                                                                                                                governorAuthorityClient,
                                                                                                                                                                                () -> runtimeReady(clusterNode) && workerProjectionFreshRef.get()
                                                                                                                                                                                                                                           .getAsBoolean(),
                                                                                                                                                                                growthLog))
                                                     .onPut(AetherKey.CommunityPlacementOperationKey.class,
                                                            (ValuePut<AetherKey.CommunityPlacementOperationKey, AetherValue.CommunityPlacementOperationValue> put) -> communityRetirements.put(put.cause()
                                                                                                                                                                                                  .value()))
                                                     .onRemove(AetherKey.CommunityPlacementOperationKey.class,
                                                               (ValueRemove<AetherKey.CommunityPlacementOperationKey, AetherValue.CommunityPlacementOperationValue> remove) -> communityRetirements.remove(remove.cause()
                                                                                                                                                                                                                 .key()
                                                                                                                                                                                                                 .communityId()))
                                                     .onPut(AetherKey.NodeReplacementKey.class,
                                                            (ValuePut<AetherKey.NodeReplacementKey, AetherValue.NodeReplacementValue> put) -> nodeReplacements.put(put.cause()
                                                                                                                                                                      .key(),
                                                                                                                                                                   put.cause()
                                                                                                                                                                      .value()))
                                                     .onRemove(AetherKey.NodeReplacementKey.class,
                                                               (ValueRemove<AetherKey.NodeReplacementKey, AetherValue.NodeReplacementValue> remove) -> nodeReplacements.remove(remove.cause()
                                                                                                                                                                                     .key()))
                                                     .onPut(AetherKey.UpgradeRunKey.class,
                                                            (ValuePut<AetherKey.UpgradeRunKey, AetherValue.UpgradeRunValue> put) -> upgradeRuns.put(put.cause()
                                                                                                                                                       .value()))
                                                     .onRemove(AetherKey.UpgradeRunKey.class,
                                                               (ValueRemove<AetherKey.UpgradeRunKey, AetherValue.UpgradeRunValue>_) -> upgradeRuns.remove())
                                                     .onPut(AetherKey.NodePlacementKey.class,
                                                            (ValuePut<AetherKey.NodePlacementKey, AetherValue.NodePlacementValue> put) -> retryPlacedWorker(put,
                                                                                                                                                            membershipFsmRef::get,
                                                                                                                                                            clusterDeploymentManager))
                                                     .onPut(AetherKey.GossipKeyRotationKey.class,
                                                            gossipKeyRotationHandler::onGossipKeyRotationPut)
                                                     .build();

        return new WorkerActivationOutputs(governorAnnouncerHolder, communityDirectory, activationKvRouter);
    }

    /// What `workerMetadata` reads from the assembly built so far.
    record WorkerMetadataInputs(AetherNodeConfig config,
                                KVStore<AetherKey, AetherValue> kvStore,
                                RabiaNode<KVCommand<AetherKey>> clusterNode,
                                SliceCodec nodeCodec,
                                DHTNode dhtNode,
                                AetherNode.EpochSources epochSources,
                                HlcClock hlcClock,
                                long bootToken,
                                AtomicReference<DHTConfig> cacheDhtConfig,
                                SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                DhtReplicationSettlement dhtSettlement,
                                DhtWriterStaleWatch dhtWriterStaleWatch,
                                ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                AtomicReference<Supplier<Set<NodeId>>> cdmReadyNodesRef,
                                ClusterDeploymentManager clusterDeploymentManager,
                                ClusterSyncCollector metricsCollector,
                                AtomicReference<BooleanSupplier> workerProjectionFreshRef,
                                NodeReportedStateHolder nodeReportedStateHolder,
                                Supplier<Epoch> leaderEpochSupplier,
                                ClusterEventAggregator eventAggregator,
                                ControlLoop controlLoop,
                                NodeDeploymentManager nodeDeploymentManager,
                                WorkerEndpointDirectory workerEndpointDirectory,
                                AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                                CommunityMemberDirectory communityDirectory,
                                ArrayList<MessageRouter.Entry<?>> allEntries,
                                CoreSwimHealthDetector swimHealthDetector,
                                MembershipFsm membershipFsm,
                                Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `workerMetadata` hands back for the rest of the assembly.
    record WorkerMetadataOutputs(Supplier<Set<NodeId>> routingCoreIds,
                                 WorkerMetadataChannel workerMetadataChannel,
                                 Function<String, Option<AetherValue.GovernorAnnouncementValue>> governorLookup,
                                 CommunityHealthIndex communityHealth,
                                 GovernorCandidateHealth candidateHealth,
                                 WorkerAdmission workerAdmission,
                                 CommunityHealthRuntime communityHealthRuntime,
                                 Option<org.pragmatica.cluster.replica.ReplicaFeedClient<AetherKey, AetherValue>> replicaClient) {}

    /// The worker metadata scope and channel, the community directory, health, admission, authority, runtime and recovery.
    static WorkerMetadataOutputs workerMetadata(WorkerMetadataInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var nodeCodec = in.nodeCodec();
        var dhtNode = in.dhtNode();
        var epochSources = in.epochSources();
        var hlcClock = in.hlcClock();
        var bootToken = in.bootToken();
        var cacheDhtConfig = in.cacheDhtConfig();
        var switchableCluster = in.switchableCluster();
        var dhtSettlement = in.dhtSettlement();
        var dhtWriterStaleWatch = in.dhtWriterStaleWatch();
        var forwardingClusterNode = in.forwardingClusterNode();
        var cdmReadyNodesRef = in.cdmReadyNodesRef();
        var clusterDeploymentManager = in.clusterDeploymentManager();
        var metricsCollector = in.metricsCollector();
        var workerProjectionFreshRef = in.workerProjectionFreshRef();
        var nodeReportedStateHolder = in.nodeReportedStateHolder();
        var leaderEpochSupplier = in.leaderEpochSupplier();
        var eventAggregator = in.eventAggregator();
        var controlLoop = in.controlLoop();
        var nodeDeploymentManager = in.nodeDeploymentManager();
        var workerEndpointDirectory = in.workerEndpointDirectory();
        var governorAnnouncerHolder = in.governorAnnouncerHolder();
        var communityDirectory = in.communityDirectory();
        var allEntries = in.allEntries();
        var swimHealthDetector = in.swimHealthDetector();
        var membershipFsm = in.membershipFsm();
        var defer = in.defer();

        allEntries.add(MessageRouter.Entry.route(CommunityMetricsSnapshot.class,
                                                 snapshot -> receiveSourceMetrics(clusterNode,
                                                                                  membershipFsm,
                                                                                  controlLoop,
                                                                                  snapshot)));
        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.metrics.SourceMetricsBatch.class,
                                                 batch -> receiveSourceMetricsBatch(clusterNode,
                                                                                    membershipFsm,
                                                                                    controlLoop,
                                                                                    batch)));
        var metadataPeerScope = configuredWorker(config)
                                ? Option.some(org.pragmatica.aether.node.health.WorkerPeerScope.workerPeerScope(config.self(),
                                                                                                                () -> workerBootstrapCores(config.self(),
                                                                                                                                           config.topology()
                                                                                                                                                 .coreNodes()),
                                                                                                                membershipFsm,
                                                                                                                swimHealthDetector))
                                : Option.<org.pragmatica.aether.node.health.WorkerPeerScope> none();
        Supplier<Set<NodeId>> routingCoreIds = () -> metadataPeerScope.map(org.pragmatica.aether.node.health.WorkerPeerScope::routingCoreIds)
                                                                      .or(() -> installedVoterIds(clusterNode));
        var metadataFailureReporter = org.pragmatica.aether.worker.metadata.WorkerMetadataFailureReporter.workerMetadataFailureReporter(org.pragmatica.lang.utils.TimeSource.system(),
                                                                                                                                        TimeSpan.timeSpan(30).seconds(),
                                                                                                                                        rejection -> {
                                                                                                                                            LOG.warn("Worker metadata rejected for {}: {} ({} additional failures suppressed)",
                                                                                                                                                     rejection.worker(),
                                                                                                                                                     rejection.reason(),
                                                                                                                                                     rejection.suppressed());
                                                                                                                                            eventAggregator.emitLocal(new ClusterEvent.NodeLifecycleChanged(hlcClock.now(),
                                                                                                                                                                                                            ClusterEvent.Severity.WARNING,
                                                                                                                                                                                                            "Worker metadata unavailable",
                                                                                                                                                                                                            Map.of("reporter",
                                                                                                                                                                                                                   config.self()
                                                                                                                                                                                                                         .id(),
                                                                                                                                                                                                                   "worker",
                                                                                                                                                                                                                   rejection.worker()
                                                                                                                                                                                                                            .id(),
                                                                                                                                                                                                                   "reason",
                                                                                                                                                                                                                   rejection.reason(),
                                                                                                                                                                                                                   "suppressed",
                                                                                                                                                                                                                   Long.toString(rejection.suppressed()))));
                                                                                                                                        });
        // W-OBS W3: the three consumers a worker's cluster directory feeds, named once so the projection channel and the (DARK) replica client install into the same ones.
        Function<List<NodeInfo>, Result<Unit>> installPeers = peers -> metadataPeerScope.map(scope -> clusterNode.installPassiveCoreDirectory(peers.stream()
                                                                                                                                                   .filter(peer -> "core".equalsIgnoreCase(peer.labels()
                                                                                                                                                                                               .getOrDefault("role",
                                                                                                                                                                                                             "")))
                                                                                                                                                   .map(NodeInfo::id)
                                                                                                                                                   .distinct()
                                                                                                                                                   .sorted()
                                                                                                                                                   .toList())
                                                                                                                 .onSuccess(_ -> {
                                                                                                                                scope.installDirectory(peers);
                                                                                                                                forwardingClusterNode.updateCorePeers(scope.routingCoreIds());
                                                                                                                                installWorkerDhtRing(dhtNode,
                                                                                                                                                     scope.routingCoreIds());
                                                                                                                            }))
                                                                                        .or(Result.success(Unit.unit()));
        Consumer<List<NodeInfo>> installEndpoints = workerEndpointDirectory::install;
        Consumer<org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.DhtReplication> applyDht = replication -> applyWorkerDhtReplication(replication,
                                                                                                                                                 dhtNode,
                                                                                                                                                 config.artifactRepo(),
                                                                                                                                                 config.cache(),
                                                                                                                                                 cacheDhtConfig,
                                                                                                                                                 dhtSettlement);
        Runnable workerReady = () -> becomeForwarding(switchableCluster,
                                                      forwardingClusterNode,
                                                      clusterNode,
                                                      nodeDeploymentManager);
        var workerMetadataChannel = org.pragmatica.aether.worker.metadata.WorkerMetadataChannel.workerMetadataChannel(config.self(),
                                                                                                                      configuredWorker(config),
                                                                                                                      kvStore,
                                                                                                                      nodeCodec,
                                                                                                                      clusterNode.network()::send,
                                                                                                                      () -> metadataServingReady(configuredWorker(config),
                                                                                                                                                 clusterNode),
                                                                                                                      node -> membershipFsm.isTrackedAndNotDead(node) && membershipFsm.memberDescriptor(node)
                                                                                                                                                                                      .map(descriptor -> "worker".equalsIgnoreCase(descriptor.role()) || "spot".equalsIgnoreCase(descriptor.role()))
                                                                                                                                                                                      .or(false),
                                                                                                                      node -> isCoreMember(membershipFsm,
                                                                                                                                           node) && clusterNode.network()
                                                                                                                                                               .connectedPeers()
                                                                                                                                                               .contains(node),
                                                                                                                      routingCoreIds,
                                                                                                                      peers -> metadataDirectory(membershipFsm,
                                                                                                                                                 peers),
                                                                                                                      installPeers,
                                                                                                                      installEndpoints,
                                                                                                                      () -> Unit.unit(),
                                                                                                                      message -> metadataFailureReporter.report(config.self(),
                                                                                                                                                                message),
                                                                                                                      metadataFailureReporter::report,
                                                                                                                      org.pragmatica.aether.worker.metadata.WorkerMetadataLimits.DEFAULT,
                                                                                                                      epochSources.incarnation()::current,
                                                                                                                      () -> workerDhtReplication(kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                                                                                                  AetherValue.ClusterConfigValue.class),
                                                                                                                                                 kvStore.getTyped(AetherKey.DhtReplicationChangeKey.dhtReplicationChangeKey(),
                                                                                                                                                                  AetherValue.DhtReplicationChangeValue.class)),
                                                                                                                      applyDht
                                                                                                                      // #1777 track 1 (CTO ruling B): a worker never
                                                                                                                      // restores consensus state and is never served the
                                                                                                                      // cluster TOML, so the core derives the DHT
                                                                                                                      // replication into every projection, and the worker
                                                                                                                      // applies it on every install — live changes included
                                                                                                                     );
        // W-OBS W4: a worker's freshness gate and engine readiness come from the replica client (ReplicaReadiness, I-W6); the projection channel
        // above no longer polls or drives either, and goes in W5.
        var started = ReplicaClientAssembly.replicaClient(configuredWorker(config),
                                                          new ReplicaClientAssembly.Inputs(config.self(),
                                                                                           kvStore,
                                                                                           clusterNode,
                                                                                           metricsCollector::heldDirectory,
                                                                                           routingCoreIds,
                                                                                           new ReplicaFeedClient.GovernorArm(() -> Option.option(governorAnnouncerHolder.get())
                                                                                                                                         .map(GovernorAnnouncer::isGovernor)
                                                                                                                                         .or(false),
                                                                                                                             () -> ReplicaFeedAssembly.announcedGovernor(config.self(),
                                                                                                                                                                         communityDirectory::assignment,
                                                                                                                                                                         community -> kvStore.getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(community),
                                                                                                                                                                                                       AetherValue.GovernorAnnouncementValue.class),
                                                                                                                                                                         governor -> clusterNode.network()
                                                                                                                                                                                                .connectedPeers()
                                                                                                                                                                                                .contains(governor))),
                                                                                           new ReplicaClientAssembly.DirectoryInstall(installPeers,
                                                                                                                                      installEndpoints,
                                                                                                                                      applyDht),
                                                                                           workerReady,
                                                                                           workerProjectionFreshRef,
                                                                                           allEntries,
                                                                                           defer));

        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.ManifestRequest.class,
                                                 workerMetadataChannel::onManifestRequest));
        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.Manifest.class,
                                                 workerMetadataChannel::onManifest));
        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.ChunkRequest.class,
                                                 workerMetadataChannel::onChunkRequest));
        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.metadata.WorkerMetadataMessage.Chunk.class,
                                                 workerMetadataChannel::onChunk));
        allEntries.add(MessageRouter.Entry.route(ValuePut.class, workerMetadataChannel::onValuePut));
        allEntries.add(MessageRouter.Entry.route(ValueRemove.class, workerMetadataChannel::onValueRemove));
        clusterNode.onStateRestored(workerMetadataChannel::onStateRestored);
        // W-OBS W2/W4: the replica feed server (subscribe and snapshot routes, drain and heartbeat ticks). A core serves workers; a worker serves
        // only as its community's governor, to that community's members, once its serve gate opens (I-W5).
        var replicaClient = started.map(ReplicaClientAssembly.Started::client);

        ReplicaFeedAssembly.replicaFeed(config.self(),
                                        kvStore,
                                        clusterNode.network()::send,
                                        () -> ReplicaFeedAssembly.servesReplicaFeed(configuredWorker(config),
                                                                                    clusterNode.isActive(),
                                                                                    clusterNode.isPendingCatchUp(),
                                                                                    started.map(ReplicaClientAssembly.Started::serveGate)
                                                                                           .map(ReplicaServeGate::mayServeMembers)
                                                                                           .or(false)),
                                        peer -> ReplicaFeedAssembly.mayFeed(configuredWorker(config),
                                                                            membershipFsm.isTrackedAndNotDead(peer),
                                                                            communityDirectory.assignment(config.self()),
                                                                            communityDirectory.assignment(peer)),
                                        peer -> clusterNode.network()
                                                           .connectedPeers()
                                                           .contains(peer),
                                        allEntries,
                                        defer);
        // #1777 R1b: this tick retries this node's DHT replication report until it is filed (a no-op once it is)
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> {
                                                                   dhtSettlement.report();
                                                                   dhtWriterStaleWatch.tick();
                                                               },
                                                               TimeSpan.timeSpan(100).millis()));
        communityDirectory.restore(kvStore.snapshot());
        // #1717: one-plane worker eviction only where an evidence source exists to veto it (a core)
        membershipFsm.setOnePlaneWorkerEviction(!configuredWorker(config));
        membershipFsm.setJoinGraceReapEligibility(node -> configuredWorker(config) || (communityDirectory.assignment(node)
                                                                                                         .isEmpty() && !workerAdmissionAllowed(node,
                                                                                                                                               membershipFsm,
                                                                                                                                               kvStore,
                                                                                                                                               config)));
        // The candidate index replaces a per-edge KV scan (#1840), so a committed directive change must refresh
        // the nomination view itself: SWIM edges alone would leave it stale until the next edge.
        Runnable refreshCommunityMembership = () -> Option.option(governorAnnouncerHolder.get()).onPresent(announcer -> announceCommunityMembership(announcer,
                                                                                                                                                    swimHealthDetector,
                                                                                                                                                    communityDirectory,
                                                                                                                                                    announcer.communityId()));
        var communityDirectoryRouter = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                           .onPut(AetherKey.ActivationDirectiveKey.class,
                                                                  (ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put) -> {
                                                                      communityDirectory.put(put.cause().key().nodeId(),
                                                                                             put.cause().value());
                                                                      refreshCommunityMembership.run();
                                                                  })
                                                           .onRemove(AetherKey.ActivationDirectiveKey.class,
                                                                     (ValueRemove<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> remove) -> {
                                                                         communityDirectory.remove(remove.cause()
                                                                                                         .key()
                                                                                                         .nodeId());
                                                                         refreshCommunityMembership.run();
                                                                     })
                                                           .build();

        allEntries.addAll(communityDirectoryRouter.asRouteEntries());
        clusterNode.onStateRestored(() -> {
            communityDirectory.restore(kvStore.snapshot());
            refreshCommunityMembership.run();
        });
        Function<String, Option<AetherValue.GovernorAnnouncementValue>> governorLookup = community -> kvStore.getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(community),
                                                                                                                       AetherValue.GovernorAnnouncementValue.class);
        var communityHealth = org.pragmatica.aether.worker.health.CommunityHealthIndex.communityHealthIndex(config.self(),
                                                                                                            governorLookup,
                                                                                                            communityDirectory::assignment,
                                                                                                            System::nanoTime,
                                                                                                            config.timeouts()
                                                                                                                  .cluster()
                                                                                                                  .communityAbsence(),
                                                                                                            10_000);
        var candidateHealth = org.pragmatica.aether.worker.health.GovernorCandidateHealth.governorCandidateHealth(communityDirectory::assignment,
                                                                                                                  System::nanoTime,
                                                                                                                  config.timeouts()
                                                                                                                        .cluster()
                                                                                                                        .communityAbsence(),
                                                                                                                  128);
        var workerAdmission = org.pragmatica.aether.worker.health.WorkerAdmission.workerAdmission(peer -> clusterNode.leaderManager()
                                                                                                                     .isLeader()
                                                                                                          && clusterNode.isActive()
                                                                                                          && communityDirectory.assignment(peer)
                                                                                                                               .isEmpty()
                                                                                                          && workerAdmissionAllowed(peer,
                                                                                                                                    membershipFsm,
                                                                                                                                    kvStore,
                                                                                                                                    config),
                                                                                                  peer -> sendObservationProbe(clusterNode,
                                                                                                                               peer),
                                                                                                  (peer, incarnation) -> membershipFsm.memberDescriptor(peer)
                                                                                                                                      .onPresent(descriptor -> {
                                                                                                                                                     membershipFsm.onWorkerAdmissionHealthy(peer,
                                                                                                                                                                                            incarnation,
                                                                                                                                                                                            descriptor);
                                                                                                                                                     if (membershipFsm.countedMembers()
                                                                                                                                                                      .contains(peer)) {
                                                                                                                                                         clusterDeploymentManager.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(peer,
                                                                                                                                                                                                                                     descriptor.role(),
                                                                                                                                                                                                                                     hlcClock.now()));
                                                                                                                                                     }
                                                                                                                                                 }),
                                                                                                  System::nanoTime,
                                                                                                  config.timeouts()
                                                                                                        .cluster()
                                                                                                        .communityAbsence(),
                                                                                                  128);

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> workerAdmission.poll(membershipFsm.memberStates()
                                                                                                       .keySet()
                                                                                                       .stream()
                                                                                                       .sorted()
                                                                                                       .toList()),
                                                               config.timeouts().cluster().pingInterval()));
        var communityHealthReporter = org.pragmatica.aether.worker.health.CommunityHealthReporter.communityHealthReporter(config.self(),
                                                                                                                          governorLookup,
                                                                                                                          communityDirectory::assignment,
                                                                                                                          peer -> routingCoreIds.get()
                                                                                                                                                .contains(peer),
                                                                                                                          System::nanoTime,
                                                                                                                          config.timeouts()
                                                                                                                                .cluster()
                                                                                                                                .communityAbsence());

        metricsCollector.addPongListener(pong -> {
            workerAdmission.recordPong(pong.sender(), pong.lifecycleState(), pong.incarnation(), pong.observation());
            candidateHealth.recordPong(pong.sender(), pong.lifecycleState(), pong.observation());
            communityHealthReporter.recordPong(pong.sender(),
                                               pong.lifecycleState(),
                                               pong.incarnation(),
                                               pong.observation());
        });
        var governorAuthority = GovernorAuthority.governorAuthority(config.self(),
                                                                    clusterNode,
                                                                    kvStore,
                                                                    () -> clusterNode.leaderManager()
                                                                                     .isLeader() && clusterNode.isActive(),
                                                                    communityDirectory::members,
                                                                    peer -> candidateHealth.isEligible(peer) || communityHealth.isReady(peer),
                                                                    leaderEpochSupplier,
                                                                    hlcClock);

        allEntries.add(MessageRouter.Entry.route(GovernorAuthorityMessage.Request.class,
                                                 request -> {
                                                     if (clusterNode.leaderManager()
                                                                    .isLeader() && candidateHealth.request(request.sender(),
                                                                                                           request.communityId())) {
                                                         sendObservationProbe(clusterNode, request.sender());
                                                     }

                                                     governorAuthority.handle(request)
                                                                      .onSuccess(response -> clusterNode.network()
                                                                                                        .send(request.sender(),
                                                                                                              response))
                                                                      .onFailure(cause -> {
                                                                                     LOG.debug("Governor authority request refused: {}",
                                                                                               cause.message());
                                                                                     clusterNode.network()
                                                                                                .send(request.sender(),
                                                                                                      new GovernorAuthorityMessage.Response(config.self(),
                                                                                                                                            request.communityId(),
                                                                                                                                            request.requestId(),
                                                                                                                                            Option.none()));
                                                                                 });
                                                 }));
        swimHealthDetector.addObservationListener(observation -> Option.option(governorAnnouncerHolder.get()).onPresent(announcer -> announceCommunityMembership(announcer,
                                                                                                                                                                 swimHealthDetector,
                                                                                                                                                                 communityDirectory,
                                                                                                                                                                 announcer.communityId())));
        var communityHealthRuntime = new org.pragmatica.aether.worker.health.CommunityHealthRuntime(config.self(),
                                                                                                    communityDirectory,
                                                                                                    communityHealth,
                                                                                                    communityHealthReporter,
                                                                                                    governorLookup,
                                                                                                    () -> installedVoterIds(clusterNode).contains(config.self()),
                                                                                                    () -> nodeReportedStateHolder.current()
                                                                                                                                 .name(),
                                                                                                    () -> bootToken,
                                                                                                    (peer, message) -> clusterNode.network()
                                                                                                                                  .send(peer,
                                                                                                                                        message),
                                                                                                    evidence -> recordGovernorEvidence(membershipFsm,
                                                                                                                                       kvStore,
                                                                                                                                       evidence));

        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.health.CommunityHealthMessage.Request.class,
                                                 communityHealthRuntime::onRequest));
        allEntries.add(MessageRouter.Entry.route(org.pragmatica.aether.worker.health.CommunityHealthMessage.Report.class,
                                                 communityHealthRuntime::onReport));
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(communityHealthRuntime::poll,
                                                               config.timeouts().cluster().pingInterval(),
                                                               config.timeouts().cluster().pingInterval()));
        metricsCollector.setCommunityReadinessSupplier(() -> communityHealth.readyMembers()
                                                                            .stream()
                                                                            .collect(java.util.stream.Collectors.toUnmodifiableMap(node -> node,
                                                                                                                                   _ -> NodeReportedState.READY)));
        cdmReadyNodesRef.set(() -> nodesReporting(metricsCollector.reportedStates(),
                                                  nodeReportedStateHolder,
                                                  config.self(),
                                                  NodeReportedState.READY));
        var governorRecovery = org.pragmatica.aether.worker.health.GovernorRecovery.governorRecovery(communityDirectory,
                                                                                                     communityHealth,
                                                                                                     candidateHealth,
                                                                                                     governorLookup,
                                                                                                     governorAuthority,
                                                                                                     () -> clusterNode.leaderManager()
                                                                                                                      .isLeader() && clusterNode.isActive(),
                                                                                                     peer -> membershipFsm.memberDescriptor(peer)
                                                                                                                          .flatMap(MemberDescriptor::address)
                                                                                                                          .map(address -> address.asString()),
                                                                                                     peer -> sendObservationProbe(clusterNode,
                                                                                                                                  peer),
                                                                                                     System::nanoTime,
                                                                                                     config.timeouts()
                                                                                                           .cluster()
                                                                                                           .communityAbsence(),
                                                                                                     config.timeouts()
                                                                                                           .cluster()
                                                                                                           .pingInterval());

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(governorRecovery::poll,
                                                               config.timeouts().cluster().pingInterval()));

        return new WorkerMetadataOutputs(routingCoreIds,
                                         workerMetadataChannel,
                                         governorLookup,
                                         communityHealth,
                                         candidateHealth,
                                         workerAdmission,
                                         communityHealthRuntime,
                                         replicaClient);
    }

    /// A worker that is ready to serve: forward writes to the cores and reconcile what this node should run.
    private static void becomeForwarding(SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                                         ForwardingClusterNode<KVCommand<AetherKey>> forwardingClusterNode,
                                         RabiaNode<KVCommand<AetherKey>> clusterNode,
                                         NodeDeploymentManager nodeDeploymentManager) {
        switchableCluster.switchTo(forwardingClusterNode);
        reconcileNodeActivation(() -> runtimeReady(clusterNode), nodeDeploymentManager);
    }

    /// What `communityReachability` reads from the assembly built so far.
    record CommunityReachabilityInputs(AetherNodeConfig config,
                                       ClusterDeploymentManager clusterDeploymentManager,
                                       ClusterSyncCollector metricsCollector,
                                       CommunityMemberDirectory communityDirectory,
                                       ArrayList<MessageRouter.Entry<?>> allEntries,
                                       MembershipFsm membershipFsm,
                                       CommunityHealthIndex communityHealth,
                                       CommunityHealthRuntime communityHealthRuntime) {}

    /// The community reachability that feeds liveness into the cluster deployment manager.
    static Unit communityReachability(CommunityReachabilityInputs in) {
        var config = in.config();
        var clusterDeploymentManager = in.clusterDeploymentManager();
        var metricsCollector = in.metricsCollector();
        var communityDirectory = in.communityDirectory();
        var allEntries = in.allEntries();
        var membershipFsm = in.membershipFsm();
        var communityHealth = in.communityHealth();
        var communityHealthRuntime = in.communityHealthRuntime();
        // Assigned workers use challenge-bound governor evidence. Report expiry blocks new
        // placement; it never synthesizes individual member death or provider termination.
        var communityReachability = org.pragmatica.aether.deployment.cluster.fsm.CommunityReachability.communityReachability(config.timeouts()
                                                                                                                                   .cluster()
                                                                                                                                   .communityAbsence(),
                                                                                                                             metricsCollector::sinceLastPong,
                                                                                                                             System::nanoTime);

        clusterDeploymentManager.setCommunityLiveness(node -> communityDirectory.assignment(node)
                                                                                .fold(() -> communityReachability.isAbsent(node),
                                                                                      _ -> !communityHealth.isReachable(node)));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> beginCommunityObservation(change, communityReachability)));
        // Core/local membership and fresh governor evidence remain distinct inputs. A stale
        // worker FSM entry cannot override expiry of the community report.
        clusterDeploymentManager.setLocalAliveMembersSupplier(() -> communityHealthRuntime.aliveNodes(membershipFsm.dhtRoutableMembers()));

        return Unit.unit();
    }

    /// #1777 track 1 (CTO ruling B), core side: the DHT replication a worker's projection carries, derived from this
    /// core's committed `[replication]` and `[cache]` sections — the narrow subset, never the TOML itself (#1390) — and,
    /// for CTO ruling R1b, the committed configuration version and the latest replication change: its floor while it is
    /// unsettled, so a worker keeps the transitional quorums exactly as long as the cores do.
    static Result<WorkerMetadataMessage.DhtReplication> workerDhtReplication(Option<AetherValue.ClusterConfigValue> committed,
                                                                             Option<AetherValue.DhtReplicationChangeValue> change) {
        var configVersion = committed.map(AetherValue.ClusterConfigValue::configVersion).or(DHTNode.NO_CHANGE);

        return ClusterReplication.defaults(committed).map(defaults -> new WorkerMetadataMessage.DhtReplication(defaults.replicationFactor(),
                                                                                                               defaults.confirmationFactor(),
                                                                                                               defaults.cacheReplicationFactor(),
                                                                                                               defaults.cacheConfirmationFactor(),
                                                                                                               configVersion,
                                                                                                               change.map(AetherValue.DhtReplicationChangeValue::version)
                                                                                                                     .or(DHTNode.NO_CHANGE),
                                                                                                               change.map(AetherValue.DhtReplicationChangeValue::floorWriteQuorum)
                                                                                                                     .or(0),
                                                                                                               change.map(AetherValue.DhtReplicationChangeValue::floorReadQuorum)
                                                                                                                     .or(0),
                                                                                                               change.map(AetherValue.DhtReplicationChangeValue::settled)
                                                                                                                     .or(true)));
    }

    /// #1777 track 1 (CTO ruling B), worker side: apply a projection's DHT replication exactly as a core applies its
    /// committed configuration — the same resolution and, on a change, the same catch-up diff — then the committed
    /// replication change it carries (CTO ruling R1b), and report the applied version through the settlement.
    @Contract
    static void applyWorkerDhtReplication(WorkerMetadataMessage.DhtReplication replication,
                                          DHTNode dhtNode,
                                          DHTConfig declared,
                                          DHTConfig declaredCache,
                                          AtomicReference<DHTConfig> cacheDhtConfig,
                                          DhtReplicationSettlement settlement) {
        applyDhtReplication(new ReplicationDefaultsConfig(replication.replicationFactor(),
                                                          replication.confirmationFactor(),
                                                          ReplicationDefaultsConfig.BUILT_IN.clusterEventsConfirmationFactor(),
                                                          replication.cacheReplicationFactor(),
                                                          replication.cacheConfirmationFactor(),

        // a worker is never a ring member and holds no DHT data, so it
        // keeps no tombstones and the retention is inert there (#1777)
        ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION),
                            replication.configVersion(),
                            dhtNode,
                            declared,
                            declaredCache,
                            cacheDhtConfig);
        if (replication.changeVersion() != DHTNode.NO_CHANGE) {
            DhtReplicationSettlement.applyCommitted(dhtNode, declared, workerChange(replication));
        }
        // a worker holds no partitions and receives no DHT writes; its fence is what its projection says
        dhtNode.confirmReplicationFence();
        settlement.applied(replication.configVersion());
    }

    /// The committed change a worker projection carries, as the record a core reads. A worker holds no partitions, so
    /// the writers-switched stage means nothing to it: unsettled is all it needs.
    private static AetherValue.DhtReplicationChangeValue workerChange(WorkerMetadataMessage.DhtReplication replication) {
        return new AetherValue.DhtReplicationChangeValue(replication.changeVersion(),
                                                         replication.replicationFactor(),
                                                         replication.confirmationFactor(),
                                                         replication.floorWriteQuorum(),
                                                         replication.floorReadQuorum(),
                                                         replication.replicationFactor(),
                                                         replication.changeSettled()
                                                         ? AetherValue.DhtReplicationStage.SETTLED
                                                         : AetherValue.DhtReplicationStage.APPLYING,
                                                         0L,
                                                         false);
    }

    private static void beginCommunityObservation(LeaderNotification.LeaderChange change,
                                                  org.pragmatica.aether.deployment.cluster.fsm.CommunityReachability reachability) {
        if (change.localNodeIsLeader()) {
            reachability.beginLeadership();
        }
    }

    private static void receiveSourceMetrics(RabiaNode<KVCommand<AetherKey>> cluster,
                                             MembershipFsm membership,
                                             ControlLoop control,
                                             CommunityMetricsSnapshot snapshot) {
        control.onCommunityMetricsSnapshot(snapshot);
        if (cluster.leaderManager().isLeader()) {
            return;
        }

        cluster.leaderManager()
               .leader()
               .filter(node -> isCoreMember(membership, node))
               .onPresent(leader -> cluster.network()
                                           .send(leader,
                                                 new org.pragmatica.aether.worker.metrics.SourceMetricsBatch(cluster.self(),
                                                                                                             List.of(snapshot))));
    }

    private static void receiveSourceMetricsBatch(RabiaNode<KVCommand<AetherKey>> cluster,
                                                  MembershipFsm membership,
                                                  ControlLoop control,
                                                  org.pragmatica.aether.worker.metrics.SourceMetricsBatch batch) {
        if (isCoreMember(membership, batch.sender())) {
            batch.snapshots().forEach(control::onCommunityMetricsSnapshot);
        }
    }

    private static void recordGovernorEvidence(MembershipFsm membership,
                                               KVStore<AetherKey, AetherValue> store,
                                               org.pragmatica.aether.worker.health.CommunityHealthIndex.GovernorEvidence evidence) {
        var member = evidence.member();

        store.getTyped(new AetherKey.ActivationDirectiveKey(member.node()),
                       AetherValue.ActivationDirectiveValue.class)
             .filter(value -> value.communityId()
                                   .equals(evidence.community()))
             .onPresent(value -> membership.onGovernorHealthy(member.node(),
                                                              evidence.community(),
                                                              evidence.governor(),
                                                              evidence.governorTerm(),
                                                              member.incarnation(),
                                                              new org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor(Option.none(),
                                                                                                                                   value.role(),
                                                                                                                                   store.getTyped(new AetherKey.NodePlacementKey(member.node()),
                                                                                                                                                  AetherValue.NodePlacementValue.class)
                                                                                                                                        .map(AetherValue.NodePlacementValue::sourceName)
                                                                                                                                        .or("")),
                                                              java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(evidence.observedAgoNanos())));
    }

    private static void sendObservationProbe(RabiaNode<KVCommand<AetherKey>> clusterNode, NodeId peer) {
        clusterNode.network()
                   .send(peer,
                         new org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing(clusterNode.self(),
                                                                                               Map.of(),
                                                                                               0,
                                                                                               0,
                                                                                               0,
                                                                                               0,
                                                                                               Set.of(),
                                                                                               Set.of(),
                                                                                               Map.of(),
                                                                                               Set.of(),
                                                                                               false,
                                                                                               false));
    }

    @SuppressWarnings({"JBCT-RET-01"})
    static void handleActivationDirective(ValuePut<AetherKey.ActivationDirectiveKey, AetherValue.ActivationDirectiveValue> put,
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
                                          org.pragmatica.aether.worker.health.CommunityMemberDirectory communityDirectory,
                                          AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                                          GovernorAuthorityClient governorAuthorityClient,
                                          java.util.function.BooleanSupplier ready,
                                          Logger growthLog) {
        if (!put.cause().key().nodeId().equals(selfId)) {
            return;
        }

        var role = put.cause().value().role();
        var communityId = put.cause().value().communityId();

        if (!activationRoleMatches(configuredWorker(config), role)) {
            growthLog.warn("Rejected activation role {} for immutable configured node type", role);

            return;
        }

        if (AetherValue.ActivationDirectiveValue.CORE.equals(role)) {
            growthLog.info("Received core activation directive from CDM");
            clusterNode.authorizeActivation();
        } else if (AetherValue.ActivationDirectiveValue.WORKER.equals(role)) {
            growthLog.info("Received worker activation directive from CDM");
            swimHealthDetectorSupplier.get().markAssigned();
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
                               communityDirectory,
                               governorAnnouncerHolder,
                               governorAuthorityClient,
                               ready,
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
                                           org.pragmatica.aether.worker.health.CommunityMemberDirectory communityDirectory,
                                           AtomicReference<GovernorAnnouncer> governorAnnouncerHolder,
                                           GovernorAuthorityClient governorAuthorityClient,
                                           java.util.function.BooleanSupplier ready,
                                           Logger log) {
        synchronized (governorAnnouncerHolder) {
            var previous = Option.option(governorAnnouncerHolder.get());

            if (previous.filter(announcer -> announcer.communityId()
                                                      .equals(communityId)).isPresent()) {
                return;
            }

            previous.onPresent(GovernorAnnouncer::stop);
            switchableCluster.switchTo(forwardingClusterNode);
            log.info("Worker {} switched to forwarding mode", selfId.id());
            var workerTcpAddress = resolveSelfTcpAddress(config);
            // Workers nominate through the core authority service. Only a committed grant
            // activates governor responsibilities; workers never write authority directly.
            var governorAnnouncer = GovernorAnnouncer.governorAnnouncer(selfId,
                                                                        () -> communityId,
                                                                        () -> workerTcpAddress,
                                                                        () -> kvStore.getTyped(AetherKey.GovernorAnnouncementKey.forCommunity(communityId),
                                                                                               AetherValue.GovernorAnnouncementValue.class),
                                                                        () -> ready.getAsBoolean() && eligibleGovernor(kvStore,
                                                                                                                       selfId,
                                                                                                                       communityId),
                                                                        governorAuthorityClient::request);

            governorAnnouncer.start();
            // #642: publish the handle so AetherNode.stop() can cancel the re-announce tick. Without it a
            // stopped worker kept writing GovernorAnnouncementKey through consensus on its own behalf.
            governorAnnouncerHolder.set(governorAnnouncer);
            // Each SWIM edge refreshes the nomination view using committed community
            // assignments. Provider source labels do not define community membership.
            announceCommunityMembership(governorAnnouncer, swimHealthDetector, communityDirectory, communityId);
            log.info("Worker {} subsystems created, ready for SWIM-based community formation", selfId.id());
        }
    }

    @SuppressWarnings({"JBCT-RET-01"})
    private static void announceCommunityMembership(GovernorAnnouncer governorAnnouncer,
                                                    CoreSwimHealthDetector swimHealthDetector,
                                                    org.pragmatica.aether.worker.health.CommunityMemberDirectory communityDirectory,
                                                    String communityId) {
        governorAnnouncer.onMembershipChange(CommunityMembershipFilter.communityAliveMembers(swimHealthDetector.aliveMembers(),
                                                                                             communityDirectory,
                                                                                             communityId));
    }

    private static void retryPlacedWorker(ValuePut<AetherKey.NodePlacementKey, AetherValue.NodePlacementValue> put,
                                          Supplier<MembershipFsm> membership,
                                          ClusterDeploymentManager deployment) {
        var node = put.cause().key().nodeId();

        Option.option(membership.get())
              .filter(fsm -> fsm.isTrackedAndNotDead(node))
              .flatMap(fsm -> fsm.memberDescriptor(node))
              .filter(descriptor -> "worker".equalsIgnoreCase(descriptor.role()) || "spot".equalsIgnoreCase(descriptor.role()))
              .onPresent(_ -> deployment.onNodePlacementPut(put));
    }

    private static boolean eligibleGovernor(KVStore<AetherKey, AetherValue> store, NodeId self, String community) {
        return store.getTyped(AetherKey.ActivationDirectiveKey.activationDirectiveKey(self),
                              AetherValue.ActivationDirectiveValue.class)
                    .filter(value -> AetherValue.ActivationDirectiveValue.WORKER.equals(value.role()) && community.equals(value.communityId()))
                    .isPresent()
               && store.getTyped(AetherKey.CommunityKey.communityKey(community),
                                 AetherValue.CommunityValue.class)
                       .filter(value -> value.state() != org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING && value.state() != org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED)
                       .isPresent();
    }

    static boolean activationRoleMatches(boolean worker, String role) {
        return worker
               ? AetherValue.ActivationDirectiveValue.WORKER.equals(role)
               : AetherValue.ActivationDirectiveValue.CORE.equals(role);
    }

    static boolean workerAdmissionAllowed(NodeId peer,
                                          MembershipFsm membership,
                                          KVStore<AetherKey, AetherValue> store,
                                          AetherNodeConfig config) {
        return membership.memberDescriptor(peer)
                         .filter(descriptor -> "worker".equalsIgnoreCase(descriptor.role()) || "spot".equalsIgnoreCase(descriptor.role()))
                         .filter(descriptor -> config.environment()
                                                     .map(environment -> environment.isLocalWorkerAdmissionAuthorized(peer.id()))
                                                     .or(false) || CapacityView.overStore(store)
                                                                               .reservation(peer)
                                                                               .filter(value -> value.intendedRole()
                                                                                                     .equalsIgnoreCase(descriptor.role()) && (value.phase() == AetherValue.CapacityReservationPhase.DISPATCHED || value.phase() == AetherValue.CapacityReservationPhase.OBSERVED))
                                                                               .isPresent())
                         .isPresent();
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

    /// Item 4 (activation): map the authoritative MembershipFsm desired-connection set into the transport's
    /// `Collection<NodeInfo>` dial contract. Each `PeerTarget` already carries the FSM-resolved dial address
    /// (`MemberDescriptor.fromNodeInfo` stored `NodeInfo.resolvedAddress()`), so `target.address()` is the
    /// exact address the transport must dial. The per-target descriptor supplies the role/source labels.
    static Set<NodeId> workerBootstrapCores(NodeId self, List<NodeInfo> configuredSeeds) {
        return configuredSeeds.stream()
                              .filter(peer -> !peer.id()
                                                   .equals(self))
                              .filter(peer -> "core".equalsIgnoreCase(peer.labels()
                                                                          .getOrDefault(NodeInfo.LABEL_ROLE, "core")))
                              .map(NodeInfo::id)
                              .collect(Collectors.toUnmodifiableSet());
    }

    /// Serving metadata requires active consensus participation, unlike staged-core execution readiness.
    static boolean metadataServingReady(boolean worker, RabiaNode<KVCommand<AetherKey>> cluster) {
        return ! worker && cluster.isActive();
    }

    private static void installWorkerDhtRing(DHTNode dhtNode, Set<NodeId> cores) {
        dhtNode.ring()
               .nodes()
               .stream()
               .filter(node -> !cores.contains(node))
               .toList()
               .forEach(dhtNode.ring()::removeNode);
        cores.forEach(dhtNode.ring()::addNode);
    }

    record unused() implements WorkerHierarchyAssembly {}
}
