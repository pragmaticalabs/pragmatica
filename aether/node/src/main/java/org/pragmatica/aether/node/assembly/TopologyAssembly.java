// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.CapacityControlledLifecycle;
import org.pragmatica.aether.deployment.cluster.CapacityView;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler;
import org.pragmatica.aether.deployment.cluster.CommunityRetirementIndex;
import org.pragmatica.aether.deployment.cluster.CoreVoterReconciler;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.MembershipLiveness;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.deployment.cluster.SourceComputeRegistry;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.deployment.membership.phase.ClusterPhaseView;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.node.AdvertisedVersion;
import org.pragmatica.aether.node.AetherNode.VoterBootstrapError;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.CoreAdmission;
import org.pragmatica.aether.node.FailedVoterEvidence;
import org.pragmatica.aether.node.LeaderTerm;
import org.pragmatica.aether.node.NodeReplacementWiring;
import org.pragmatica.aether.node.UpgradeRunWiring;
import org.pragmatica.aether.node.assembly.DrainAssembly;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.worker.governor.CommunityDrainCoordinator;
import org.pragmatica.aether.worker.governor.CommunityPlacementMessage;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Lazy;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.CONFIG_PATH_PROPERTY;
import static org.pragmatica.aether.node.AetherNode.GENESIS_RETRY_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.PHASE_WATCH_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.completeRoster;
import static org.pragmatica.aether.node.AetherNode.configuredVoters;
import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.discoveredCores;
import static org.pragmatica.aether.node.AetherNode.excludedCoreNodes;
import static org.pragmatica.aether.node.AetherNode.installedVoterIds;
import static org.pragmatica.aether.node.AetherNode.readyCoreCandidates;
import static org.pragmatica.aether.node.AetherNode.retirementEligibleCore;
import static org.pragmatica.aether.node.AetherNode.swimAliveForCoConfirmation;
import static org.pragmatica.aether.node.assembly.DrainAssembly.requestDrainThroughFsm;


/// The topology unit of the node assembly: the node source lifecycle, the community-retirement, node-replacement and
/// upgrade-run indexes and their restore hooks, the cluster topology manager with its core admission and voter
/// reconciler, the community placement reconciler and its drain routes, and the leader reconciler with the replacement
/// and upgrade wiring. Moved out of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface TopologyAssembly {
    /// What `sourceLifecycle` reads from the assembly built so far.
    record SourceLifecycleInputs(AetherNodeConfig config,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 AtomicReference<MembershipFsm> membershipFsmRef) {}

    /// The node source lifecycle manager and the capacity-controlled lifecycle over it.
    static CapacityControlledLifecycle sourceLifecycle(SourceLifecycleInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var membershipFsmRef = in.membershipFsmRef();
        var sourceLifecycle = sourceLifecycleManager(config.environment(),
                                                     config.configProvider(),
                                                     config.clusterName(),
                                                     config.autoHeal().maxNodes(),
                                                     () -> kvStore.get(AetherKey.ClusterConfigKey.CURRENT)
                                                                  .filter(value -> value instanceof AetherValue.ClusterConfigValue)
                                                                  .map(value -> (AetherValue.ClusterConfigValue) value),
                                                     node -> computeSource(node,
                                                                           id -> kvStore.get(new AetherKey.NodePlacementKey(id))
                                                                                        .filter(value -> value instanceof AetherValue.NodePlacementValue)
                                                                                        .map(value -> (AetherValue.NodePlacementValue) value),
                                                                           id -> Option.option(membershipFsmRef.get()).flatMap(fsm -> fsm.memberDescriptor(id))));
        var lifecycleManager = org.pragmatica.aether.deployment.cluster.CapacityControlledLifecycle.capacityControlledLifecycle(sourceLifecycle,
                                                                                                                                config.self(),
                                                                                                                                kvStore,
                                                                                                                                commands -> clusterNode.apply(commands),
                                                                                                                                clusterNode.leaderManager()::isLeader,
                                                                                                                                () -> config.autoHeal()
                                                                                                                                            .maxNodes()
                                                                                                                                            .or(Integer.MAX_VALUE));

        return lifecycleManager;
    }

    /// What `retirementIndexes` reads from the assembly built so far.
    record RetirementIndexesInputs(KVStore<AetherKey, AetherValue> kvStore,
                                   RabiaNode<KVCommand<AetherKey>> clusterNode) {}

    /// What `retirementIndexes` hands back for the rest of the assembly.
    record RetirementIndexesOutputs(CommunityRetirementIndex communityRetirements,
                                    NodeReplacementIndex nodeReplacements,
                                    UpgradeRunIndex upgradeRuns) {}

    /// The retirement, replacement and upgrade-run indexes, restored from the KV store now and after every state restore.
    static RetirementIndexesOutputs retirementIndexes(RetirementIndexesInputs in) {
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        // Membership-v2: CDM DRAINING set sourced from the real node-authoritative
        // NodeReportedState.DRAINING (metrics pong) — late-bound like the READY ref, since the
        // pong fan + self-state holder are constructed further below.
        var communityRetirements = CommunityRetirementIndex.communityRetirementIndex();
        var nodeReplacements = NodeReplacementIndex.nodeReplacementIndex();
        var upgradeRuns = UpgradeRunIndex.upgradeRunIndex();
        // Snapshot replay emits only changed KV entries. Leadership must also be refreshed when
        // an unchanged committed LeaderValue survives a voter handoff that cleared local leadership.
        clusterNode.onStateRestored(() -> refreshCommittedLeader(kvStore, clusterNode.leaderManager()));
        clusterNode.onStateRestored(() -> restoreRetirementIndex(kvStore, communityRetirements));
        restoreRetirementIndex(kvStore, communityRetirements);
        clusterNode.onStateRestored(() -> nodeReplacements.restore(kvStore.snapshot()));
        nodeReplacements.restore(kvStore.snapshot());
        clusterNode.onStateRestored(() -> upgradeRuns.restore(kvStore.snapshot()));
        upgradeRuns.restore(kvStore.snapshot());

        return new RetirementIndexesOutputs(communityRetirements, nodeReplacements, upgradeRuns);
    }

    /// What `topologyManager` reads from the assembly built so far.
    record TopologyManagerInputs(AetherNodeConfig config,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 AtomicReference<MembershipFsm> membershipFsmRef,
                                 CapacityControlledLifecycle lifecycleManager,
                                 DeploymentMap deploymentMap,
                                 Supplier<Set<NodeId>> stableCdmReadyNodesSupplier,
                                 NodeReplacementIndex nodeReplacements,
                                 ClusterSyncCollector metricsCollector,
                                 BooleanSupplier isLeaderSupplier,
                                 DrainCommandRegistry drainCommandRegistry,
                                 Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader,
                                 Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                                 GenerationSnapshotSource leaderAwareSnapshotSource,
                                 Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `topologyManager` hands back for the rest of the assembly.
    record TopologyManagerOutputs(Supplier<AetherValue.ClusterPhase> effectivePhaseSupplier,
                                  AtomicReference<CoreSwimHealthDetector> swimHealthDetectorHolder,
                                  AtomicReference<LeaderReconciler> leaderReconcilerRef,
                                  IntSupplier configuredCoreCountSupplier,
                                  ClusterTopologyManager clusterTopologyManager,
                                  CoreAdmission coreAdmission) {}

    /// The cluster phase view, the topology manager with its setters, core admission, the core voter reconciler and the phase watcher, all deferred until start.
    static TopologyManagerOutputs topologyManager(TopologyManagerInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var membershipFsmRef = in.membershipFsmRef();
        var lifecycleManager = in.lifecycleManager();
        var deploymentMap = in.deploymentMap();
        var stableCdmReadyNodesSupplier = in.stableCdmReadyNodesSupplier();
        var nodeReplacements = in.nodeReplacements();
        var metricsCollector = in.metricsCollector();
        var isLeaderSupplier = in.isLeaderSupplier();
        var drainCommandRegistry = in.drainCommandRegistry();
        var clusterConfigReader = in.clusterConfigReader();
        var clusterCommandApplier = in.clusterCommandApplier();
        var leaderAwareSnapshotSource = in.leaderAwareSnapshotSource();
        var defer = in.defer();
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
        var resolvedLocalConfig = Lazy.lazy(TopologyAssembly::parseOwnResolvedConfig);
        // #1050 / #1062 — the CTM consults membership and liveness evidence before every irreversible reap. The
        // SWIM detector and the LeaderReconciler are built further below, so both are reached through holders
        // declared HERE (read per call; empty until published, which every reap gate treats as fail-closed).
        // The configured core count comes out of the same seam and is handed to the LeaderReconciler and the
        // QuorumLossDetector below, so the drain decision and its reap read ONE count supplier.
        var swimHealthDetectorHolder = new AtomicReference<CoreSwimHealthDetector>();
        var leaderReconcilerRef = new AtomicReference<LeaderReconciler>();
        var ctmLiveness = drainGraceLiveness(membershipFsmRef::get,
                                             clusterConfigReader,
                                             config.topology().coreNodes().size(),
                                             swimHealthDetectorHolder::get,
                                             peer -> clusterNode.network()
                                                                .connectedPeers()
                                                                .contains(peer),
                                             leaderReconcilerRef::get,
                                             metricsCollector::retainedDispatchedNodes);
        IntSupplier configuredCoreCountSupplier = ctmLiveness.configuredCoreCount();
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
                                                                                   resolvedLocalConfig::get,
                                                                                   () -> kvStore.getTyped(AetherKey.AutoHealStateKey.SINGLETON,
                                                                                                          AutoHealStateValue.class),
                                                                                   ctmLiveness);

        clusterTopologyManager.setHierarchyStateWriter(org.pragmatica.aether.deployment.cluster.HierarchyStateWriter.hierarchyStateWriter(() -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                                                                                 LeaderValue.class)
                                                                                                                                                       .filter(value -> value.leader()
                                                                                                                                                                             .equals(config.self())
                                                                                                                                                                        && clusterNode.leaderManager()
                                                                                                                                                                                      .isLeader()
                                                                                                                                                                        && clusterNode.isActive()),
                                                                                                                                          kvStore::get,
                                                                                                                                          clusterCommandApplier));
        clusterTopologyManager.setGenesisVoters(() -> clusterNode.genesisVoters()
                                                                 .map(VoterConfiguration::members)
                                                                 .or(List.of()));
        clusterTopologyManager.setNodeReplacements(nodeReplacements);
        clusterTopologyManager.setRetirementRefusal(node -> retirementRefusal(clusterNode,
                                                                              membershipFsmRef::get,
                                                                              deploymentMap,
                                                                              readyCoreCandidates(stableCdmReadyNodesSupplier.get(),
                                                                                                  membershipFsmRef::get),
                                                                              node));
        // #1526: configured cores are bootstrap identities and keep core admission intent after a fresh
        // genesis that did not include them (a cold restart forms genesis from the cores present), so a
        // configured core that comes back late is admitted and voted in through a Rabia §4 add.
        var configuredCoreIds = configuredVoters(config);
        var verifiedVoterIdentities = new AtomicReference<>(bootstrapIdentities(configuredCoreIds,
                                                                                clusterNode.verifiedVoterHistoryIds()));

        clusterNode.onVoterConfiguration(_ -> verifiedVoterIdentities.set(bootstrapIdentities(configuredCoreIds,
                                                                                              clusterNode.verifiedVoterHistoryIds())));
        var coreAdmission = CoreAdmission.coreAdmission(membershipFsmRef::get,
                                                        verifiedVoterIdentities::get,
                                                        CapacityView.overStore(kvStore)::reservation,
                                                        node -> config.environment()
                                                                      .map(environment -> environment.isLocalCoreAdmissionAuthorized(node.id()))
                                                                      .or(false));
        var coreVoterReconciler = CoreVoterReconciler.coreVoterReconciler(config.self(),
                                                                          isLeaderSupplier,
                                                                          clusterNode::voterConfiguration,
                                                                          clusterNode::retirementSafeVoters,
                                                                          configuredCoreCountSupplier,
                                                                          () -> readyCoreCandidates(stableCdmReadyNodesSupplier.get(),
                                                                                                    membershipFsmRef::get).stream()
                                                                                                   .filter(coreAdmission::isAllowed)
                                                                                                   .collect(Collectors.toUnmodifiableSet()),
                                                                          nodeReplacements::voterSwaps,
                                                                          FailedVoterEvidence.forNode(metricsCollector,
                                                                                                      membershipFsmRef::get),
                                                                          clusterNode::reconfigure);

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> coreVoterReconciler.reconcile()
                                                                                        .flatMap(_ -> retireExcludedCores(clusterNode,
                                                                                                                          membershipFsmRef::get,
                                                                                                                          kvStore,
                                                                                                                          nodeReplacements,
                                                                                                                          deploymentMap,
                                                                                                                          clusterTopologyManager,
                                                                                                                          readyCoreCandidates(stableCdmReadyNodesSupplier.get(),
                                                                                                                                              membershipFsmRef::get)))
                                                                                        .onFailure(cause -> LOG.warn("Core voter reconciliation: {}",
                                                                                                                     cause.message())),
                                                               TimeSpan.timeSpan(1).seconds()));
        // E2 Phase 2b (2026-05-28): OrphanSelfDrainChecker deleted; NTT (§6) drives departure
        // detection and the §8 unified drain handles surplus dissolution. Membership v2 finale:
        // the leader-pinned `LifecycleReconciler` (and the FSM it wrote through) are gone — the
        // NTT-side `LeaderReconciler` (wired below) is the sole provisioning driver.
        defer.accept(phaseChangeWatcherArmer(effectivePhaseSupplier, clusterTopologyManager));
        if (configuredWorker(config)) {
            defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> retryGenesis(clusterNode::isGenesisPending,
                                                                                      clusterNode::initializeVoters,
                                                                                      () -> discoveredCores(membershipFsmRef),
                                                                                      config.topology().clusterSize()),
                                                                   GENESIS_RETRY_INTERVAL));
        }

        return new TopologyManagerOutputs(effectivePhaseSupplier,
                                          swimHealthDetectorHolder,
                                          leaderReconcilerRef,
                                          configuredCoreCountSupplier,
                                          clusterTopologyManager,
                                          coreAdmission);
    }

    /// What `placementReconciler` reads from the assembly built so far.
    record PlacementReconcilerInputs(AetherNodeConfig config,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     RabiaNode<KVCommand<AetherKey>> clusterNode,
                                     HlcClock hlcClock,
                                     AtomicReference<MembershipFsm> membershipFsmRef,
                                     CapacityControlledLifecycle lifecycleManager,
                                     DeploymentMap deploymentMap,
                                     Supplier<Set<NodeId>> stableCdmReadyNodesSupplier,
                                     Supplier<Set<NodeId>> stableCdmDrainingNodesSupplier,
                                     BooleanSupplier isLeaderSupplier,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                                     DrainAssembly.Infrastructure drain,
                                     ClusterTopologyManager clusterTopologyManager,
                                     ClusterEventAggregator eventAggregator,
                                     OperatorWarningSink operatorWarningSink) {}

    /// The community placement reconciler, installed into the topology manager.
    static CommunityPlacementReconciler placementReconciler(PlacementReconcilerInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var hlcClock = in.hlcClock();
        var membershipFsmRef = in.membershipFsmRef();
        var lifecycleManager = in.lifecycleManager();
        var deploymentMap = in.deploymentMap();
        var stableCdmReadyNodesSupplier = in.stableCdmReadyNodesSupplier();
        var stableCdmDrainingNodesSupplier = in.stableCdmDrainingNodesSupplier();
        var isLeaderSupplier = in.isLeaderSupplier();
        var clusterCommandApplier = in.clusterCommandApplier();
        var drain = in.drain();
        var clusterTopologyManager = in.clusterTopologyManager();
        var eventAggregator = in.eventAggregator();
        var operatorWarningSink = in.operatorWarningSink();
        var placementReconciler = org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.communityPlacementReconciler(config.self(),
                                                                                                                                     kvStore,
                                                                                                                                     clusterCommandApplier,
                                                                                                                                     isLeaderSupplier,
                                                                                                                                     node -> stableCdmReadyNodesSupplier.get()
                                                                                                                                                                        .contains(node) && !stableCdmDrainingNodesSupplier.get()
                                                                                                                                                                                                                          .contains(node),
                                                                                                                                     () -> Option.option(membershipFsmRef.get())
                                                                                                                                                 .map(MembershipFsm::countedMembers)
                                                                                                                                                 .or(Set.of()),
                                                                                                                                     () -> config.autoHeal()
                                                                                                                                                 .maxNodes()
                                                                                                                                                 .or(Integer.MAX_VALUE),
                                                                                                                                     new org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.Actuator() {
                                                                                                                                         @Override
                                                                                                                                         public Result<String> sourceBinding(SourceName source) {
                                                                                                                                             return lifecycleManager.sourceBinding(source);
                                                                                                                                         }

                                                                                                                                         @Override
                                                                                                                                         public Promise<Unit> create(AetherValue.CommunityPlacementOperationValue operation) {
                                                                                                                                             return clusterTopologyManager.provisionPlacementNode(operation);
                                                                                                                                         }

                                                                                                                                         @Override
                                                                                                                                         public Promise<Boolean> retirementSafe(AetherValue.CommunityPlacementOperationValue operation) {
                                                                                                                                             return Promise.success(operation.previousNode()
                                                                                                                                                                             .map(node -> deploymentMap.byNode(node)
                                                                                                                                                                                                       .isEmpty())
                                                                                                                                                                             .or(true));
                                                                                                                                         }

                                                                                                                                         @Override
                                                                                                                                         public Promise<Unit> drain(AetherValue.CommunityPlacementOperationValue operation) {
                                                                                                                                             operation.previousNode()
                                                                                                                                                      .onPresent(node -> clusterNode.network()
                                                                                                                                                                                    .send(node,
                                                                                                                                                                                          new CommunityPlacementMessage.DrainRequested(config.self(),
                                                                                                                                                                                                                                       operation.operationId())));

                                                                                                                                             return Promise.unitPromise();
                                                                                                                                         }

                                                                                                                                         @Override
                                                                                                                                         public Promise<Unit> terminate(AetherValue.CommunityPlacementOperationValue operation) {
                                                                                                                                             return operation.previousNode()
                                                                                                                                                             .fold(Promise::unitPromise,
                                                                                                                                                                   node -> lifecycleManager.terminateNode(node,
                                                                                                                                                                                                          SourceName.sourceNameOrDefault(operation.previousSource())));
                                                                                                                                         }

                                                                                                                                         @Override
                                                                                                                                         public Promise<Boolean> previousInstanceExists(AetherValue.CommunityPlacementOperationValue operation) {
                                                                                                                                             return operation.previousNode()
                                                                                                                                                             .fold(() -> Promise.success(false),
                                                                                                                                                                   node -> lifecycleManager.instancesForNode(node,
                                                                                                                                                                                                             SourceName.sourceNameOrDefault(operation.previousSource()))
                                                                                                                                                                                           .map(instances -> !instances.isEmpty()));
                                                                                                                                         }
                                                                                                                                     },
                                                                                                                                     operation -> eventAggregator.emitAsLeader(new ClusterEvent.NodeLifecycleChanged(hlcClock.now(),
                                                                                                                                                                                                                     ClusterEvent.Severity.WARNING,
                                                                                                                                                                                                                     "Community placement requires operator attention: " + operation.detail(),
                                                                                                                                                                                                                     Map.of("community",
                                                                                                                                                                                                                            operation.communityId(),
                                                                                                                                                                                                                            "operation",
                                                                                                                                                                                                                            operation.operationId(),
                                                                                                                                                                                                                            "phase",
                                                                                                                                                                                                                            operation.phase()
                                                                                                                                                                                                                                     .name(),
                                                                                                                                                                                                                            "targetNode",
                                                                                                                                                                                                                            operation.targetNode()
                                                                                                                                                                                                                                     .id()))),
                                                                                                                                     (node, cause) -> eventAggregator.emitAsLeader(new ClusterEvent.NodeLifecycleChanged(hlcClock.now(),
                                                                                                                                                                                                                         ClusterEvent.Severity.WARNING,
                                                                                                                                                                                                                         "Worker capacity reduction requires operator attention: " + cause.message(),
                                                                                                                                                                                                                         Map.of("node",
                                                                                                                                                                                                                                node.id(),
                                                                                                                                                                                                                                "operation",
                                                                                                                                                                                                                                "implicit-worker-retirement"))),
                                                                                                                                     TimeSpan.timeSpan(45).seconds());

        placementReconciler.operatorWarnings(operatorWarningSink);
        clusterTopologyManager.installCommunityPlacement(placementReconciler);

        return placementReconciler;
    }

    /// What `placementDrainRoutes` reads from the assembly built so far.
    record PlacementDrainRoutesInputs(AetherNodeConfig config,
                                      RabiaNode<KVCommand<AetherKey>> clusterNode,
                                      CommunityDrainCoordinator communityDrain,
                                      CommunityPlacementReconciler placementReconciler,
                                      List<MessageRouter.Entry<?>> aetherEntries) {}

    /// The community placement drain routes.
    static Unit placementDrainRoutes(PlacementDrainRoutesInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var communityDrain = in.communityDrain();
        var placementReconciler = in.placementReconciler();
        var aetherEntries = in.aetherEntries();

        aetherEntries.add(MessageRouter.Entry.route(CommunityPlacementMessage.DrainCompleted.class,
                                                    response -> placementReconciler.onDrainCompleted(response.sender(),
                                                                                                     response.operationId())
                                                                                   .onSuccess(accepted -> clusterNode.network()
                                                                                                                     .send(response.sender(),
                                                                                                                           new CommunityPlacementMessage.DrainAccepted(config.self(),
                                                                                                                                                                       response.operationId(),
                                                                                                                                                                       accepted)))));
        aetherEntries.add(MessageRouter.Entry.route(CommunityPlacementMessage.DrainRequested.class,
                                                    communityDrain::onRequest));
        aetherEntries.add(MessageRouter.Entry.route(CommunityPlacementMessage.DrainAccepted.class,
                                                    communityDrain::onAccepted));

        return Unit.unit();
    }

    /// What `leaderReconciler` reads from the assembly built so far.
    record LeaderReconcilerInputs(AetherNodeConfig config,
                                  KVStore<AetherKey, AetherValue> kvStore,
                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                  DHTNode dhtNode,
                                  LeaderTerm leaderTerm,
                                  AtomicReference<MembershipFsm> membershipFsmRef,
                                  CapacityControlledLifecycle lifecycleManager,
                                  Supplier<Set<NodeId>> stableCdmReadyNodesSupplier,
                                  NodeReplacementIndex nodeReplacements,
                                  UpgradeRunIndex upgradeRuns,
                                  ClusterSyncCollector metricsCollector,
                                  BooleanSupplier isLeaderSupplier,
                                  ClusterSyncScheduler metricsScheduler,
                                  AtomicReference<LeaderReconciler> leaderReconcilerRef,
                                  IntSupplier configuredCoreCountSupplier,
                                  ClusterTopologyManager clusterTopologyManager,
                                  CoreAdmission coreAdmission,
                                  BooleanSupplier clusterEventsOwnerCheck,
                                  OperatorWarningSink operatorWarningSink,
                                  CommunityPlacementReconciler placementReconciler,
                                  AtomicReference<Option<ManagementServer>> managementServerRef,
                                  MembershipConfig membershipConfig,
                                  PresenceSampler presenceSampler,
                                  Supplier<Option<ClusterName>> clusterNameSupplier,
                                  MembershipFsm membershipFsm,
                                  Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `leaderReconciler` hands back for the rest of the assembly.
    record LeaderReconcilerOutputs(LeaderReconciler leaderReconciler,
                                   NodeReplacementWiring.Wiring replacementWiring,
                                   UpgradeRunWiring.Wiring upgradeWiring) {}

    /// The leader reconciler with its suppliers, the replacement and upgrade-run wiring and their deferred ticks.
    static LeaderReconcilerOutputs leaderReconciler(LeaderReconcilerInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var dhtNode = in.dhtNode();
        var leaderTerm = in.leaderTerm();
        var membershipFsmRef = in.membershipFsmRef();
        var lifecycleManager = in.lifecycleManager();
        var stableCdmReadyNodesSupplier = in.stableCdmReadyNodesSupplier();
        var nodeReplacements = in.nodeReplacements();
        var upgradeRuns = in.upgradeRuns();
        var metricsCollector = in.metricsCollector();
        var isLeaderSupplier = in.isLeaderSupplier();
        var metricsScheduler = in.metricsScheduler();
        var leaderReconcilerRef = in.leaderReconcilerRef();
        var configuredCoreCountSupplier = in.configuredCoreCountSupplier();
        var clusterTopologyManager = in.clusterTopologyManager();
        var coreAdmission = in.coreAdmission();
        var clusterEventsOwnerCheck = in.clusterEventsOwnerCheck();
        var operatorWarningSink = in.operatorWarningSink();
        var placementReconciler = in.placementReconciler();
        var managementServerRef = in.managementServerRef();
        var membershipConfig = in.membershipConfig();
        var presenceSampler = in.presenceSampler();
        var clusterNameSupplier = in.clusterNameSupplier();
        var membershipFsm = in.membershipFsm();
        var defer = in.defer();
        var leaderReconciler = LeaderReconciler.leaderReconciler(membershipConfig,
                                                                 presenceSampler,
                                                                 membershipFsm,
                                                                 configuredCoreCountSupplier,
                                                                 leaderTerm::committedTerm,
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
        // #1404 — gossip can be lost with the leader that sent it; the committed capacity reservations are the durable
        // record of what was requested, and count toward core capacity on the terms LeaderReconciler documents.
        leaderReconciler.setDurableProvisioningSupplier(() -> pendingCoreAllocations(lifecycleManager.allocatedNodes("core"),
                                                                                     installedVoterIds(clusterNode)));
        leaderReconciler.setInstalledVotersSupplier(() -> installedVoterIds(clusterNode));
        // Drain-victim slice-owner demotion (Approach 3, #1488): wire the authoritative KV-Store-backed
        // active-slice-ownership predicate so the reconciler drains a node currently serving / hosting
        // slices only after every eligible non-owner, and the minAvailable guard so it never drains an
        // owner whose removal would drop a hosted slice below minAvailable. KV-derived (leader-agnostic,
        // reconstructible from cluster state) and consulted through narrow JDK functional seams so the
        // membership-layer reconciler keeps no hard dependency on the deployment FSM.
        leaderReconciler.setOwnsActiveSlices(SliceOwnershipQuery.ownsActiveSlices(kvStore));
        leaderReconciler.setSliceDrainGuard(SliceOwnershipQuery.minAvailableDrainGuard(kvStore));
        // #1543: the pairings are capacity on purpose for the two reconcilers that would otherwise read them as excess or deficit.
        NodeReplacementWiring.connectReconcilers(nodeReplacements,
                                                 leaderReconciler::setSurgeReplacements,
                                                 placementReconciler::protectReplacements);
        // #1543 E (design section 4): a replacement's operator events are derived from the COMMITTED transition on every node and
        // raised only by the owner of the cluster-events partition. The leader that raises "started" is not the leader that
        // commits "completed" when the leader is the node being replaced; the owner is the same node for both, which is what
        // lets the aggregator pair the recovery with the event it closes.
        nodeReplacements.onTransition(NodeReplacementWiring.announcer(clusterEventsOwnerCheck, operatorWarningSink));
        // #1543 E: the replacement reconciler (leader-driven, resumes from the committed records) and its service.
        var replacementWiring = NodeReplacementWiring.wire(new NodeReplacementWiring.Inputs(config.self(),
                                                                                            isLeaderSupplier,
                                                                                            kvStore,
                                                                                            commands -> clusterNode.apply(commands),
                                                                                            nodeReplacements,
                                                                                            membershipFsmRef::get,
                                                                                            clusterNode::voterConfiguration,
                                                                                            clusterNode::retirementSafeVoters,
                                                                                            () -> readyCoreCandidates(stableCdmReadyNodesSupplier.get(),
                                                                                                                      membershipFsmRef::get).stream()
                                                                                                                     .filter(coreAdmission::isAllowed)
                                                                                                                     .collect(Collectors.toUnmodifiableSet()),
                                                                                            stableCdmReadyNodesSupplier,
                                                                                            id -> AdvertisedVersion.of(id,
                                                                                                                       clusterNode.topologyManager(),
                                                                                                                       Option.option(membershipFsmRef.get())),
                                                                                            clusterTopologyManager,
                                                                                            id -> managementServerRef.get()
                                                                                                                     .fold(() -> Promise.success(NodeReplacementWiring.DrainOutcome.pending("management server not ready")),
                                                                                                                           server -> NodeReplacementWiring.drainOutcomeOf(server.admitReplacementDrain(id))),
                                                                                            id -> managementServerRef.get()
                                                                                                                     .map(server -> server.replacementDrainUnderWay(id))
                                                                                                                     .or(false),
                                                                                            () -> ProvisionContext.coreNodeNamePrefix(clusterNameSupplier.get()),
                                                                                            id -> dhtNode.ring()
                                                                                                         .nodes()
                                                                                                         .contains(id),
                                                                                            () -> clusterNode.genesisVoters()
                                                                                                             .map(voters -> Set.copyOf(voters.members()))
                                                                                                             .or(Set.of()),
                                                                                            () -> config.autoHeal()
                                                                                                        .maxNodes()
                                                                                                        .or(Integer.MAX_VALUE),
                                                                                            operatorWarningSink,
                                                                                            System::currentTimeMillis,
                                                                                            NodeReplacementPlanner.Timings.defaults()));

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> replacementWiring.reconciler()
                                                                                      .reconcile()
                                                                                      .onFailure(cause -> LOG.warn("Node replacement reconciliation: {}",
                                                                                                                   cause.message())),
                                                               TimeSpan.timeSpan(1).seconds()));
        // #1543 F: the rolling-upgrade run rides the replacement service (one replacement at a time, never around it); its events are
        // raised by the cluster-events owner for the same reason the replacement's are.
        upgradeRuns.onTransition(UpgradeRunWiring.announcer(clusterEventsOwnerCheck, operatorWarningSink));
        var upgradeWiring = UpgradeRunWiring.wire(new UpgradeRunWiring.Inputs(config.self(),
                                                                              isLeaderSupplier,
                                                                              kvStore,
                                                                              commands -> clusterNode.apply(commands),
                                                                              upgradeRuns,
                                                                              membershipFsmRef::get,
                                                                              id -> AdvertisedVersion.of(id,
                                                                                                         clusterNode.topologyManager(),
                                                                                                         Option.option(membershipFsmRef.get())),
                                                                              replacementWiring.service(),
                                                                              System::currentTimeMillis));

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> upgradeWiring.reconciler()
                                                                                  .reconcile()
                                                                                  .onFailure(cause -> LOG.warn("Upgrade run reconciliation: {}",
                                                                                                               cause.message())),
                                                               TimeSpan.timeSpan(1).seconds()));

        return new LeaderReconcilerOutputs(leaderReconciler, replacementWiring, upgradeWiring);
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

    static Set<NodeId> bootstrapIdentities(Set<NodeId> configuredCores, Set<NodeId> voterHistory) {
        return Stream.concat(configuredCores.stream(),
                             voterHistory.stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// One worker genesis wait-and-retry tick. A no-op once genesis is resolved. While it is pending, the
    /// discovered core membership completes it as soon as it names exactly the configured core count.
    ///
    /// FER (degrade forward): a failed tick is absorbed — the worker stays genesis-pending, and the next
    /// tick retries. The logged line is the operator surface for the wait.
    static Unit retryGenesis(BooleanSupplier genesisPending,
                             Function<VoterConfiguration, Result<Unit>> initializeVoters,
                             Supplier<Set<NodeId>> discoveredCores,
                             int configuredCount) {
        if (!genesisPending.getAsBoolean()) {
            return Unit.unit();
        }

        var discovered = discoveredCores.get();

        return completeRoster(List.copyOf(discovered),
                              configuredCount).toResult(VoterBootstrapError.GENESIS_ROSTER_INCOMPLETE)
                             .flatMap(initializeVoters::apply)
                             .onFailure(cause -> LOG.info("Genesis voter roster pending ({} of {} cores discovered: {}): {}",
                                                          discovered.size(),
                                                          configuredCount,
                                                          discovered,
                                                          cause.message()))
                             .or(Unit.unit());
    }

    /// #1050 / #1062 — the CTM's membership and liveness evidence as ONE named seam, the sibling of
    /// [#presenceMemberSupplier]. Every projection is read live, per call:
    /// - counted — the FSM's role-scoped MEMBER + SUSPECT set, the `LeaderReconciler`'s own drain denominator;
    /// - tracked — the FSM's not-DEAD set (OBSERVED + MEMBER + SUSPECT + DEPARTING);
    /// - swimAlive — raw SWIM HEALTHY/SUSPECTED;
    /// - transportConnected — the leader's own cluster-transport view;
    /// - in-flight — this leader's reconciler in-flight keys plus the set retained from the previous leader's
    ///   pings;
    /// - configured — the committed `ClusterConfigValue.coreCount`, else the bootstrap topology size;
    /// - advertisedRole (#689) — the FSM's `memberDescriptor(id).role()`, the self-asserted role the projector
    ///   classified the node's join by; `none()` for an untracked id or before the FSM is published.
    ///
    /// Before the FSM, detector or reconciler is published, its projection reads empty or false, which every
    /// reap gate treats as not quorum-safe (fail-closed). `DrainGraceLivenessSeamTest` pins this method.
    static MembershipLiveness drainGraceLiveness(Supplier<MembershipFsm> membershipFsm,
                                                 Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader,
                                                 int topologyCoreNodes,
                                                 Supplier<CoreSwimHealthDetector> swimHealthDetector,
                                                 Predicate<NodeId> transportConnected,
                                                 Supplier<LeaderReconciler> leaderReconciler,
                                                 Supplier<Set<NodeId>> retainedDispatched) {
        return MembershipLiveness.membershipLiveness(() -> fsmProjection(membershipFsm.get(),
                                                                         MembershipFsm::coreCountedMembers),
                                                     () -> fsmProjection(membershipFsm.get(),
                                                                         MembershipFsm::broadcastEligibleMembers),
                                                     nodeId -> swimAliveIfPublished(swimHealthDetector.get(), nodeId),
                                                     transportConnected,
                                                     () -> inFlightProvisioning(leaderReconciler.get(),
                                                                                retainedDispatched.get()),
                                                     () -> configuredCoreCount(clusterConfigReader.get(),
                                                                               topologyCoreNodes),
                                                     nodeId -> advertisedRole(membershipFsm.get(), nodeId));
    }

    private static Option<String> advertisedRole(MembershipFsm membershipFsm, NodeId nodeId) {
        return Option.option(membershipFsm)
                     .flatMap(fsm -> fsm.memberDescriptor(nodeId))
                     .map(MemberDescriptor::role);
    }

    private static Set<NodeId> fsmProjection(MembershipFsm membershipFsm,
                                             Function<MembershipFsm, Set<NodeId>> projection) {
        return Option.option(membershipFsm)
                     .map(projection::apply)
                     .or(Set.of());
    }

    private static boolean swimAliveIfPublished(CoreSwimHealthDetector swimHealthDetector, NodeId nodeId) {
        return Option.option(swimHealthDetector)
                     .map(detector -> swimAliveForCoConfirmation(detector::healthOf, nodeId))
                     .or(false);
    }

    private static Set<NodeId> inFlightProvisioning(LeaderReconciler leaderReconciler, Set<NodeId> retainedDispatched) {
        return Stream.concat(retainedDispatched.stream(),
                             Option.option(leaderReconciler)
                                   .map(LeaderReconciler::inFlightProvisioningKeys)
                                   .or(Set.of())
                                   .stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static int configuredCoreCount(Option<AetherValue.ClusterConfigValue> clusterConfig,
                                           int topologyCoreNodes) {
        return clusterConfig.map(AetherValue.ClusterConfigValue::coreCount)
                            .or(topologyCoreNodes);
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
                     .flatMap(TopologyAssembly::parseConfigFile);
    }

    private static Option<TomlDocument> parseConfigFile(Path path) {
        return TomlParser.parseFile(path)
                         .onFailure(cause -> LOG.warn("#336: failed to parse own resolved config at {} for CTM placeholder resolution: {}",
                                                      path,
                                                      cause.message()))
                         .option();
    }

    private static Promise<Unit> retireExcludedCores(RabiaNode<KVCommand<AetherKey>> cluster,
                                                     Supplier<MembershipFsm> membership,
                                                     KVStore<AetherKey, AetherValue> store,
                                                     NodeReplacementIndex replacements,
                                                     DeploymentMap deployments,
                                                     ClusterTopologyManager topology,
                                                     Set<NodeId> ready) {
        if (!cluster.leaderManager().isLeader()) {
            return Promise.unitPromise();
        }

        var work = Promise.unitPromise();

        for (var node : excludedCoreNodes(cluster, membership, store, replacements, ready)) {
            if (deployments.byNode(node).isEmpty()) {
                work = work.flatMap(_ -> topology.drainNode(node, DrainReason.OVERPROVISION_SCALE_DOWN));
            }
        }

        return work;
    }

    private static void restoreRetirementIndex(KVStore<AetherKey, AetherValue> store, CommunityRetirementIndex index) {
        restoreRetirementIndex(store.snapshot(), index);
    }

    static Unit restoreRetirementIndex(Map<?, ?> snapshot, CommunityRetirementIndex index) {
        return index.restore(snapshot.values()
                                     .stream()
                                     .filter(AetherValue.CommunityPlacementOperationValue.class::isInstance)
                                     .map(AetherValue.CommunityPlacementOperationValue.class::cast)
                                     .toList());
    }

    /// The committed core allocations that still stand for capacity: those not yet in the installed electorate (an installed voter is
    /// counted as a member, departed or not), each as how the ledger stands behind it.
    static Map<NodeId, LeaderReconciler.DurableAllocation> pendingCoreAllocations(Map<NodeId, AetherValue.CapacityReservationPhase> allocated,
                                                                                  Set<NodeId> installed) {
        return allocated.entrySet()
                        .stream()
                        .filter(entry -> !installed.contains(entry.getKey()))
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                              entry -> durableAllocation(entry.getValue())));
    }

    private static LeaderReconciler.DurableAllocation durableAllocation(AetherValue.CapacityReservationPhase phase) {
        return phase == AetherValue.CapacityReservationPhase.OBSERVED
               ? LeaderReconciler.DurableAllocation.CONFIRMED
               : LeaderReconciler.DurableAllocation.UNCERTAIN;
    }

    /// #1804: `none()` when `node` may be retired now, else the reason it may not — the reaper logs that reason.
    private static Option<String> retirementRefusal(RabiaNode<KVCommand<AetherKey>> cluster,
                                                    Supplier<MembershipFsm> membership,
                                                    DeploymentMap deploymentMap,
                                                    Set<NodeId> ready,
                                                    NodeId node) {
        return Option.option(membership.get()).fold(() -> Option.some("membership view not available"),
                                                    fsm -> retirementRefusal(fsm.memberDescriptor(node),
                                                                             cluster.retirementSafeVoters()
                                                                                    .map(voters -> Set.copyOf(voters.members())),
                                                                             cluster.verifiedVoterHistoryIds(),
                                                                             ready,
                                                                             deploymentMap.byNode(node).isEmpty(),
                                                                             node));
    }

    /// A tracked worker or spot is retirable. Everything else is judged by the core rule: a tracked core, and a node
    /// the membership does not track at all — a dead boot a new leader never saw join, which its activation replay
    /// selects precisely BECAUSE it is untracked, so requiring a descriptor made that sweep unable to terminate
    /// anything it selected. An absent descriptor is not a refusal; being a current voter is.
    static Option<String> retirementRefusal(Option<MemberDescriptor> descriptor,
                                            Option<Set<NodeId>> installedVoters,
                                            Set<NodeId> history,
                                            Set<NodeId> ready,
                                            boolean deploymentsEmpty,
                                            NodeId node) {
        return descriptor.filter(known -> !known.isCore())
                         .fold(() -> coreRetirementRefusal(installedVoters, history, ready, deploymentsEmpty, node),
                               known -> workerRetirementRefusal(known.role()));
    }

    private static Option<String> workerRetirementRefusal(String role) {
        return "worker".equalsIgnoreCase(role) || "spot".equalsIgnoreCase(role)
               ? Option.none()
               : Option.some("role '" + role + "' is not retirable");
    }

    private static Option<String> coreRetirementRefusal(Option<Set<NodeId>> installedVoters,
                                                        Set<NodeId> history,
                                                        Set<NodeId> ready,
                                                        boolean deploymentsEmpty,
                                                        NodeId node) {
        return installedVoters.fold(() -> Option.some("no certified voter electorate"),
                                    installed -> coreRetirementRefusal(installed, history, ready, deploymentsEmpty, node));
    }

    private static Option<String> coreRetirementRefusal(Set<NodeId> installed,
                                                        Set<NodeId> history,
                                                        Set<NodeId> ready,
                                                        boolean deploymentsEmpty,
                                                        NodeId node) {
        if (!retirementEligibleCore(node, installed, history, ready)) {
            return Option.some(ineligibleCoreReason(installed, ready, node));
        }

        return deploymentsEmpty
               ? Option.none()
               : Option.some("still hosts deployments");
    }

    private static String ineligibleCoreReason(Set<NodeId> installed, Set<NodeId> ready, NodeId node) {
        if (installed.isEmpty()) {
            return "installed voter set is empty";
        }

        return installed.contains(node)
               ? "still an installed voter"
               : "never a verified voter and the installed voter roster is not fully ready";
    }

    static Unit refreshCommittedLeader(KVStore<?, ?> kvStore, LeaderManager leaderManager) {
        kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
               .onPresent(value -> leaderManager.onLeaderCommitted(value.leader(),
                                                                   value.viewSequence()));

        return Unit.unit();
    }

    /// Cloud routing always uses committed sources. Only explicitly local integrations can
    /// provision before source config exists, which keeps Forge usable without cloud fallback.
    static NodeLifecycleManager sourceLifecycleManager(Option<EnvironmentIntegration> environment,
                                                       Option<ConfigurationProvider> protectedConfig,
                                                       Option<ClusterName> clusterName,
                                                       Option<Integer> maxNodes,
                                                       Supplier<Option<AetherValue.ClusterConfigValue>> configuration,
                                                       Function<NodeId, Result<SourceName>> sourceForNode) {
        var registry = SourceComputeRegistry.sourceComputeRegistry(configuration,
                                                                   environment.flatMap(EnvironmentIntegration::localCompute),
                                                                   protectedConfig);

        return NodeLifecycleManager.nodeLifecycleManager(registry, sourceForNode, clusterName, maxNodes);
    }

    static Result<SourceName> computeSource(NodeId node,
                                            Function<NodeId, Option<AetherValue.NodePlacementValue>> placements,
                                            Function<NodeId, Option<MemberDescriptor>> members) {
        return placements.apply(node)
                         .map(AetherValue.NodePlacementValue::sourceName)
                         .orElse(() -> members.apply(node)
                                              .filter(member -> member.isCore() || "worker".equalsIgnoreCase(member.role()))
                                              .map(MemberDescriptor::source))
                         .toResult(EnvironmentError.operationNotSupported("No authoritative compute source for node " + node.id()))
                         .flatMap(SourceName::sourceName);
    }

    record unused() implements TopologyAssembly {}
}
