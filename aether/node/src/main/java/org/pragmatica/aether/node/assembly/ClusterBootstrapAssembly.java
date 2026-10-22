// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.generation.BootstrapModule;
import org.pragmatica.aether.deployment.generation.SwimHintsRegistry;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.EpochSources;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.BootstrapAdminKeyLeg;
import org.pragmatica.aether.node.BootstrapAdminKeyRegistrar;
import org.pragmatica.aether.node.ClusterIncarnationRegistrar;
import org.pragmatica.aether.node.DhtReplicationSettlement;
import org.pragmatica.aether.node.LeaderTerm;
import org.pragmatica.aether.node.backup.BackupRestoreCoordinator;
import org.pragmatica.aether.node.backup.BackupWarning;
import org.pragmatica.aether.node.backup.GitBackupRepository;
import org.pragmatica.aether.node.backup.KvBackupService;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.worker.metrics.SpokesmanPingLoop;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.enabledBackup;
import static org.pragmatica.aether.node.AetherNode.resolveDhtReplication;
import static org.pragmatica.aether.node.AetherNode.wireMembershipDecisionTail;


/// The cluster bootstrap unit of the node assembly: the bootstrap module that seeds the cluster, the KV routers that retry
/// it, the spokesman ping loop (started at assembly), and the registrars of the bootstrap admin key and cluster
/// incarnation with the backup service and restore coordinator. Moved out of `AetherNode` without change; the root calls
/// the phases in the order it always ran them.
public sealed interface ClusterBootstrapAssembly {
    /// What `bootstrapModule` reads from the assembly built so far.
    record BootstrapModuleInputs(AetherNodeConfig config,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 LeaderTerm leaderTerm,
                                 AetherNode.EpochSources epochSources,
                                 HlcClock hlcClock,
                                 PeerObservationStore peerObservationStore,
                                 BooleanSupplier isLeaderSupplier,
                                 MembershipFsm membershipFsm) {}

    /// The configuration baseline, the SWIM hints registry and the bootstrap module.
    static BootstrapModule bootstrapModule(BootstrapModuleInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var leaderTerm = in.leaderTerm();
        var epochSources = in.epochSources();
        var hlcClock = in.hlcClock();
        var peerObservationStore = in.peerObservationStore();
        var isLeaderSupplier = in.isLeaderSupplier();
        var membershipFsm = in.membershipFsm();
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

        return bootstrapModule;
    }

    /// What `bootstrapRouting` reads from the assembly built so far.
    record BootstrapRoutingInputs(AetherNodeConfig config,
                                  KVStore<AetherKey, AetherValue> kvStore,
                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                  DHTNode dhtNode,
                                  LeaderTerm leaderTerm,
                                  AtomicReference<DHTConfig> cacheDhtConfig,
                                  DhtReplicationSettlement dhtSettlement,
                                  ClusterSyncCollector metricsCollector,
                                  Supplier<Long> rabiaTermSupplier,
                                  ClusterTopologyManager clusterTopologyManager,
                                  DeploymentManager deploymentManager,
                                  AbTestManager abTestManager,
                                  ArrayList<MessageRouter.Entry<?>> allEntries,
                                  LeaderReconciler leaderReconciler,
                                  BootstrapModule bootstrapModule) {}

    /// The routes that drive the bootstrap module, the health KV router, the spokesman ping loop (started here) and its KV router.
    static SpokesmanPingLoop bootstrapRouting(BootstrapRoutingInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var dhtNode = in.dhtNode();
        var leaderTerm = in.leaderTerm();
        var cacheDhtConfig = in.cacheDhtConfig();
        var dhtSettlement = in.dhtSettlement();
        var metricsCollector = in.metricsCollector();
        var rabiaTermSupplier = in.rabiaTermSupplier();
        var clusterTopologyManager = in.clusterTopologyManager();
        var deploymentManager = in.deploymentManager();
        var abTestManager = in.abTestManager();
        var allEntries = in.allEntries();
        var leaderReconciler = in.leaderReconciler();
        var bootstrapModule = in.bootstrapModule();

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

        return spokesmanPingLoop;
    }

    /// What `bootstrapRegistrars` reads from the assembly built so far.
    record BootstrapRegistrarsInputs(AetherNodeConfig config,
                                     KVStore<AetherKey, AetherValue> kvStore,
                                     SliceCodec nodeCodec,
                                     BooleanSupplier isLeaderSupplier,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> clusterCommandApplier,
                                     OperatorWarningSink operatorWarningSink,
                                     ArrayList<MessageRouter.Entry<?>> allEntries) {}

    /// What `bootstrapRegistrars` hands back for the rest of the assembly.
    record BootstrapRegistrarsOutputs(Option<KvBackupService> kvBackupService,
                                      BackupRestoreCoordinator backupRestoreCoordinator) {}

    /// The bootstrap admin key and cluster incarnation registrars, the backup service and the restore coordinator.
    static BootstrapRegistrarsOutputs bootstrapRegistrars(BootstrapRegistrarsInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var nodeCodec = in.nodeCodec();
        var isLeaderSupplier = in.isLeaderSupplier();
        var clusterCommandApplier = in.clusterCommandApplier();
        var operatorWarningSink = in.operatorWarningSink();
        var allEntries = in.allEntries();
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

        return new BootstrapRegistrarsOutputs(kvBackupService, backupRestoreCoordinator);
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

    @SuppressWarnings("unchecked")
    private static void routeKvBackup(List<MessageRouter.Entry<?>> allEntries, KvBackupService service) {
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, service::onValuePut));
        allEntries.add(MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, service::onValueRemove));
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, service::onLeaderChange));
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

    record unused() implements ClusterBootstrapAssembly {}
}
