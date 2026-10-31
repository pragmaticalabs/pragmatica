// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.NodeDepartureNotifier;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.deployment.cluster.IdentityRegistrar;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaProjector;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.deployment.membership.fsm.PeerTarget;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.ClusterSyncScheduler;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.AetherNetworkInboundPolicy;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.ConnectivityWiring;
import org.pragmatica.aether.node.AetherNode.EpochSources;
import org.pragmatica.aether.node.AetherNode.QuicTransportCause;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.CoreAdmission;
import org.pragmatica.aether.node.DhtReplicationSettlement;
import org.pragmatica.aether.node.IdentityLedgerWiring;
import org.pragmatica.aether.node.IdentityRecordPersister;
import org.pragmatica.aether.node.ObservedIncarnation;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.aether.node.journal.TransitionJournal;
import org.pragmatica.aether.node.prefetch.StoragePrefetchChannel;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.aether.worker.health.GovernorCandidateHealth;
import org.pragmatica.aether.worker.health.WorkerAdmission;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.quic.PeerConnectivityReporter;
import org.pragmatica.consensus.net.quic.PeerTransitionRecord;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicPeerStateListener;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.TransportObservation;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.MEMBERSHIP_BASELINE_TRACE_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.PENDING_CORE_ISOLATION_GRACE;
import static org.pragmatica.aether.node.AetherNode.configuredMember;
import static org.pragmatica.aether.node.AetherNode.configuredVoters;
import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.installedVoterIds;
import static org.pragmatica.aether.node.AetherNode.isCoreMember;
import static org.pragmatica.aether.node.AetherNode.metadataDirectory;
import static org.pragmatica.aether.node.AetherNode.reconcileReplicaSet;
import static org.pragmatica.aether.node.AetherNode.unreachableHint;
import static org.pragmatica.aether.node.assembly.DrainAssembly.clearOperatorDrainOnDeath;


/// The membership unit of the node assembly: the presence sampler, the quorum-loss detector and the membership state
/// machine, its transition journal, delta projector and descriptor seed, the SWIM observation listeners that feed it,
/// and the transport taps, network gates, departure notifier, death edges and hierarchy peer policy around it. Moved out
/// of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface MembershipAssembly {
    /// What `membershipFsm` reads from the assembly built so far.
    record MembershipFsmInputs(AetherNodeConfig config,
                               RabiaNode<KVCommand<AetherKey>> clusterNode,
                               AtomicReference<MembershipFsm> membershipFsmRef,
                               ClusterSyncCollector metricsCollector,
                               Supplier<Option<AetherValue.ClusterConfigValue>> clusterConfigReader,
                               AtomicReference<LeaderReconciler> leaderReconcilerRef,
                               ControlLoop controlLoop,
                               AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                               CoreSwimHealthDetector swimHealthDetector) {}

    /// What `membershipFsm` hands back for the rest of the assembly.
    record MembershipFsmOutputs(MembershipConfig membershipConfig,
                                PresenceSampler presenceSampler,
                                QuorumLossDetector quorumLossDetector,
                                Supplier<Option<ClusterName>> clusterNameSupplier,
                                MembershipFsm membershipFsm) {}

    /// The membership configuration, the presence sampler (started at assembly), the quorum-loss detector and the membership state machine.
    static MembershipFsmOutputs membershipFsm(MembershipFsmInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var membershipFsmRef = in.membershipFsmRef();
        var metricsCollector = in.metricsCollector();
        var clusterConfigReader = in.clusterConfigReader();
        var leaderReconcilerRef = in.leaderReconcilerRef();
        var controlLoop = in.controlLoop();
        var quorumLossDetectorRef = in.quorumLossDetectorRef();
        var swimHealthDetector = in.swimHealthDetector();
        // ---------------------------------------------------------------------
        // Membership v2 — NTT wiring (spec §6, §7.4). E2 Phase 2a (2026-05-28) made the
        // observation unconditional: NTT + QuorumLossDetector + LeaderReconciler are
        // constructed and listeners registered on every node. The migration-ramp
        // observation-flag and DivergenceLogger are gone.
        var membershipConfig = config.membership().or(MembershipConfig::membershipConfig);
        Runnable nttReconcileTrigger = () -> onNttReconcile(quorumLossDetectorRef, leaderReconcilerRef);
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
        var quorumLossDetector = QuorumLossDetector.quorumLossDetector(membershipConfig,
                                                                       () -> installedVoterIds(clusterNode).size(),
                                                                       () -> derivedMemberCount(Option.option(membershipFsmRef.get()),
                                                                                                config.self(),
                                                                                                clusterNode.voterConfiguration()));

        quorumLossDetectorRef.set(quorumLossDetector);
        Supplier<Option<ClusterName>> clusterNameSupplier = () -> clusterConfigReader.get()
                                                                                     .map(AetherValue.ClusterConfigValue::clusterName)
                                                                                     .flatMap(ClusterName::maybeClusterName)
                                                                                     .orElse(config::clusterName);
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
        metricsCollector.setMetricsProducerEligibility(membershipFsm::isTrackedAndNotDead);
        controlLoop.setMetricsProducerEligibility(membershipFsm::isTrackedAndNotDead);

        return new MembershipFsmOutputs(membershipConfig,
                                        presenceSampler,
                                        quorumLossDetector,
                                        clusterNameSupplier,
                                        membershipFsm);
    }

    /// What `membershipJournal` reads from the assembly built so far.
    record MembershipJournalInputs(AetherNodeConfig config,
                                   MessageRouter.DelegateRouter delegateRouter,
                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                   HlcClock hlcClock,
                                   GenerationSnapshotSource snapshotSource,
                                   ClusterSyncCollector metricsCollector,
                                   DrainCommandRegistry drainCommandRegistry,
                                   ClusterSyncScheduler metricsScheduler,
                                   CoreAdmission coreAdmission,
                                   AlertManager alertManager,
                                   AtomicReference<ReplicaSetController> clusterEventsControllerRef,
                                   AtomicReference<Option<ManagementServer>> managementServerRef,
                                   AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                   ArrayList<MessageRouter.Entry<?>> allEntries,
                                   PresenceSampler presenceSampler,
                                   MembershipFsm membershipFsm) {}

    /// What `membershipJournal` hands back for the rest of the assembly.
    record MembershipJournalOutputs(TransitionJournal transitionJournal,
                                    TopologyObserver topologyObserver,
                                    MembershipDeltaProjector membershipDeltaProjector,
                                    Set<NodeId> configuredTransferPeers,
                                    Predicate<NodeId> transferPeer,
                                    AtomicLong dialingSinceNanos) {}

    /// The transition journal and delta projector, the descriptor seed, the ping authority and the inbound network policy.
    static MembershipJournalOutputs membershipJournal(MembershipJournalInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var clusterNode = in.clusterNode();
        var hlcClock = in.hlcClock();
        var snapshotSource = in.snapshotSource();
        var metricsCollector = in.metricsCollector();
        var drainCommandRegistry = in.drainCommandRegistry();
        var metricsScheduler = in.metricsScheduler();
        var coreAdmission = in.coreAdmission();
        var alertManager = in.alertManager();
        var clusterEventsControllerRef = in.clusterEventsControllerRef();
        var managementServerRef = in.managementServerRef();
        var quorumLossDetectorRef = in.quorumLossDetectorRef();
        var allEntries = in.allEntries();
        var presenceSampler = in.presenceSampler();
        var membershipFsm = in.membershipFsm();
        // Wave-1 Enrichment A (cluster-topology-overhaul spec): per-node TRANSITION JOURNAL —
        // bounded per-layer ring buffer recording EVERY MembershipFsm transition and EVERY
        // PeerState transition, dumpable via GET /api/cluster/journal. Diagnostic-only and
        // permanent. The FSM listener is installed BEFORE the boot seed below so the seed's
        // OBSERVED→MEMBER promotions are the journal's first entries. The PEER-layer listener
        // and the §6.4 boot future-history feed are wired further down, once clusterNetworkRef
        // is in scope.
        var transitionJournal = TransitionJournal.transitionJournal();

        membershipFsm.onTransition(record -> {
            // #926 round 2: feed the alert manager the transition CAUSE so it can tell an announced
            // departure (graceful shutdown — i.e. every rolling restart — or an operator drain) from a
            // crash. The DEAD edge alone cannot: graceful and abrupt departures both arrive there
            // through the same `Stopped` transition. Folded into the EXISTING listener because
            // `onTransition` is a single-listener setter — registering a second one would silently
            // replace the transition journal.
            alertManager.noteMembershipTransition(record.nodeId(), record.cause());
            onFsmTransition(transitionJournal, quorumLossDetectorRef, record);
            reconcileReplicaSetOnCountedBoundary(clusterEventsControllerRef, record);
            // #1720: a floor-refused operator drain target that has now reached DEAD closes its refusal on this edge.
            // Here and not on the DEAD-edge listener, which a wiring pin keeps to the unconditional callback.
            closeSliceFloorRefusalOnDeath(managementServerRef, record);
            // #2063: the drain episode ended (node died, was refuted back to MEMBER, or rejoined); its operator drain entry must not outlive it.
            clearOperatorDrainOnDeath(drainCommandRegistry, record);
        });
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
        seedConfiguredDescriptors(config.topology().coreNodes(),
                                  config.self(),
                                  config.workerConfig().isPresent(),
                                  membershipFsm);
        metricsCollector.setPingAuthority(node -> isCoreMember(membershipFsm, node),
                                          node -> clusterNode.leaderManager()
                                                             .leader()
                                                             .map(node::equals)
                                                             .or(false));
        metricsScheduler.setMetricsRecipient(node -> isCoreMember(membershipFsm, node));
        topologyObserver.setConsensusMembership(coreAdmission::isAllowed);
        var configuredTransferPeers = configuredVoters(config);
        var transferPeer = transferPeers(configuredTransferPeers, () -> installedVoterIds(clusterNode));
        var dialingSinceNanos = new AtomicLong();

        topologyObserver.setStateTransferMembership(peer -> !peer.equals(config.self()) && (coreAdmission.isAllowed(peer) || configuredTransferPeers.contains(peer)));
        clusterNode.network()
                   .setInboundMessagePolicy((peer, message) -> AetherNetworkInboundPolicy.isAllowed(peer,
                                                                                                    message,
                                                                                                    !configuredWorker(config),
                                                                                                    coreAdmission::isAllowed,
                                                                                                    topologyObserver::isStateTransferPeer));
        membershipFsm.seed(config.topology().coreNodes().stream().map(NodeInfo::id).collect(Collectors.toSet()));
        // Route NTT's down-hysteresis crossing edge into the FSM (post-construction installer — the FSM
        // exists only now, AFTER presenceSampler). A sustained-absence SUSPECT member is then bounded by the FSM
        // (SUSPECT→DEPARTING per spec §3.3 / invariant I4) so a genuinely-gone node still departs the
        // count exactly once, rather than NTT independently removing it from its own set.
        presenceSampler.onDownHysteresisCrossing(membershipFsm::onDownHysteresisMet);

        return new MembershipJournalOutputs(transitionJournal,
                                            topologyObserver,
                                            membershipDeltaProjector,
                                            configuredTransferPeers,
                                            transferPeer,
                                            dialingSinceNanos);
    }

    /// What `swimObservations` reads from the assembly built so far.
    record SwimObservationsInputs(ClusterTopologyManager clusterTopologyManager,
                                  CoreSwimHealthDetector swimHealthDetector,
                                  PresenceSampler presenceSampler,
                                  MembershipFsm membershipFsm,
                                  LeaderReconciler leaderReconciler) {}

    /// The SWIM observation listeners that feed the presence sampler and the membership state machine.
    static Unit swimObservations(SwimObservationsInputs in) {
        var clusterTopologyManager = in.clusterTopologyManager();
        var swimHealthDetector = in.swimHealthDetector();
        var presenceSampler = in.presenceSampler();
        var membershipFsm = in.membershipFsm();
        var leaderReconciler = in.leaderReconciler();

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
        // #1050 (verify-1057-r2 S1): the FAULTY edge is the death evidence that re-arms a departed-node reap the CTM
        // abandoned while SWIM still reported the node SUSPECTED. Pinned by `SwimFaultyToCtmRoutingTest`.
        swimHealthDetector.addObservationListener(obs -> routeSwimFaultyToCtm(obs, clusterTopologyManager));

        return Unit.unit();
    }

    /// What `membershipEdges` reads from the assembly built so far.
    record MembershipEdgesInputs(AetherNodeConfig config,
                                 MessageRouter.DelegateRouter delegateRouter,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                 Serializer serializer,
                                 Deserializer deserializer,
                                 Map<String, StorageFactory.StorageSetup> storageSetups,
                                 DHTTopologyListener dhtTopologyListener,
                                 DhtReplicationSettlement dhtSettlement,
                                 ClusterSyncCollector metricsCollector,
                                 DrainCommandRegistry drainCommandRegistry,
                                 ClusterSyncPongSignalFan pongSignalFan,
                                 ClusterSyncScheduler metricsScheduler,
                                 AtomicReference<LeaderReconciler> leaderReconcilerRef,
                                 AlertManager alertManager,
                                 ClusterEventAggregator eventAggregator,
                                 OperatorWarningSink operatorWarningSink,
                                 WorkerEndpointDirectory workerEndpointDirectory,
                                 AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                 CommunityMemberDirectory communityDirectory,
                                 ArrayList<MessageRouter.Entry<?>> allEntries,
                                 CoreSwimHealthDetector swimHealthDetector,
                                 PresenceSampler presenceSampler,
                                 MembershipFsm membershipFsm,
                                 Supplier<Set<NodeId>> routingCoreIds,
                                 Function<String, Option<AetherValue.GovernorAnnouncementValue>> governorLookup,
                                 GovernorCandidateHealth candidateHealth,
                                 WorkerAdmission workerAdmission,
                                 TransitionJournal transitionJournal,
                                 TopologyObserver topologyObserver,
                                 MembershipDeltaProjector membershipDeltaProjector,
                                 Set<NodeId> configuredTransferPeers,
                                 Predicate<NodeId> transferPeer,
                                 AtomicLong dialingSinceNanos,
                                 LeaderReconciler leaderReconciler,
                                 Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `membershipEdges` hands back for the rest of the assembly.
    record MembershipEdgesOutputs(Consumer<NodeId> nttConnectTap,
                                  Consumer<NodeId> nttDisconnectTap,
                                  StoragePrefetchChannel prefetchChannel) {}

    /// The transport taps, network gates, departure notifier, death edges, hierarchy peer policy, connection initiator and journal feeds.
    static MembershipEdgesOutputs membershipEdges(MembershipEdgesInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var storageSetups = in.storageSetups();
        var dhtTopologyListener = in.dhtTopologyListener();
        var dhtSettlement = in.dhtSettlement();
        var metricsCollector = in.metricsCollector();
        var drainCommandRegistry = in.drainCommandRegistry();
        var pongSignalFan = in.pongSignalFan();
        var metricsScheduler = in.metricsScheduler();
        var leaderReconcilerRef = in.leaderReconcilerRef();
        var alertManager = in.alertManager();
        var eventAggregator = in.eventAggregator();
        var operatorWarningSink = in.operatorWarningSink();
        var workerEndpointDirectory = in.workerEndpointDirectory();
        var quorumLossDetectorRef = in.quorumLossDetectorRef();
        var communityDirectory = in.communityDirectory();
        var allEntries = in.allEntries();
        var swimHealthDetector = in.swimHealthDetector();
        var identityPersister = attachIdentityLedger(config, swimHealthDetector, operatorWarningSink);

        wireIdentityRecord(config, kvStore, clusterNode, swimHealthDetector, identityPersister, allEntries);
        var presenceSampler = in.presenceSampler();
        var membershipFsm = in.membershipFsm();
        var routingCoreIds = in.routingCoreIds();
        var governorLookup = in.governorLookup();
        var candidateHealth = in.candidateHealth();
        var workerAdmission = in.workerAdmission();
        var transitionJournal = in.transitionJournal();
        var topologyObserver = in.topologyObserver();
        var membershipDeltaProjector = in.membershipDeltaProjector();
        var configuredTransferPeers = in.configuredTransferPeers();
        var transferPeer = in.transferPeer();
        var dialingSinceNanos = in.dialingSinceNanos();
        var leaderReconciler = in.leaderReconciler();
        var defer = in.defer();
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
        // Storage prefetch hints ride SWIM's application gossip: once per probe round the hottest blocks of one
        // instance go to one random peer in a datagram of their own, and arriving hints reach the prefetch manager
        // of the instance they name. The managers stay dormant until the node reports ready.
        var prefetchChannel = StoragePrefetchChannel.storagePrefetchChannel(StorageFactory.prefetchManagers(storageSetups),
                                                                            serializer,
                                                                            deserializer);

        swimHealthDetector.registerGossipChannel(StoragePrefetchChannel.CHANNEL, prefetchChannel);
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
        var departureNotifier = NodeDepartureNotifier.nodeDepartureNotifier(eventAggregator,
                                                                            alertManager,
                                                                            config.self(),
                                                                            operatorWarningSink);

        membershipFsm.onConfirmedDeparture(departed -> {
            onMembershipDeath(departed, dropDeadPeerLink, quorumLossDetectorRef, leaderReconcilerRef);
        });
        // #1777 R1b: the pong cadence the leader already receives is what notices that time passed (the overdue bound)
        // and that a member's departure is now committed (it is no longer waited for, and its report is dropped); no timer
        // of its own, and no second consumer on the single-slot DEAD edge above
        metricsCollector.addPongListener(_ -> dhtSettlement.evaluate());
        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 change -> onDhtLeaderChange(change, kvStore, dhtSettlement)));
        // #210: emit the user-facing NODE_FAILED from this ungated DEAD edge — the SAME confirmed-
        // death signal that drives auto-heal above — instead of the quorum-gated
        // MembershipDecision.NodeRemoved, which the MembershipDeltaProjector drops during the
        // post-kill re-election window so the event never reached /api/events on cloud.
        // #926: NO LONGER leader-gated inside the aggregator. It was, and a cluster that cannot
        // elect a leader therefore could not emit the events saying it was broken — measured at
        // 8 SWIM-confirmed deaths and 0 NodeFailed events over ten days. Now emitted on every
        // node that confirms the death (see ClusterEventAggregator.onConfirmedDeparture for the
        // at-least-once-per-observer contract and why a dedup token is the wrong fix).
        // #926 round 2: both surfaces go through ONE named unit. Written as two statements here,
        // a probe deleted the alert call and all 1217 tests stayed green — the call site was
        // deletable with no signal. NodeDepartureNotifier makes the pair testable as a pair.
        // #1835: the pair rides ONLY the reachable-death edge — the FSM's `everReachable` latch, read at
        // the DEAD edge. A configured core this node never observed reachable (slow-booting JVM seed,
        // phantom seed) never joined, so it did not fail: it gets the WARNING-level `node-never-joined`
        // operator warning instead. `onMembershipDeath` above stays UNCONDITIONAL (auto-heal, link drop,
        // quorum nudge) — only the user-facing verdict is identity-gated.
        membershipFsm.onReachableDeath(departureNotifier::onConfirmedDeparture);
        membershipFsm.onNeverReachableDeath(departureNotifier::onNeverJoined);
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
        // #588: the two rosters the cluster-status routes read admit a peer BEFORE the FSM promotes
        // it — `topologyObserver` on the SWIM discovery edge, `metricsCollector` on the first ping or
        // pong carrying it — so a member that dies without ever reaching MEMBER (the join-grace reap
        // above, or any other OBSERVED→DEAD path) emits no REMOVED delta and stayed in both forever
        // as an UNKNOWN ghost. Prune on the never-joined death edge exactly as the projector's
        // REMOVED arm prunes on the joined one — same two calls, both idempotent on an absent node.
        // This does NOT touch the join-grace window itself: the window exists to protect a booted-
        // but-not-yet-healthy joiner from being reaped mid-join (`joinGraceReapDeferred` re-arms
        // while the transport link is live), and shortening or bypassing it would trade this ghost
        // for killed legitimate joiners. The reap's TIMING is unchanged; only its fan-out grows.
        membershipFsm.onNeverJoinedDeath(departed -> {
            topologyObserver.pruneDeparted(departed);
            metricsCollector.removeNode(departed);
        });
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
        membershipFsm.onEnteredDeparting(node -> {
            if (!configuredWorker(config)) {
                dhtTopologyListener.onNodeDeparting(node);
            }
        });
        // seed-500 part 2 (symmetry): re-add to the DHT ring on the DEPARTING→MEMBER recovery edge.
        // A drainer that refutes its drain via a strictly-newer incarnation (recoverFromDepartingIfNewer)
        // recovers DEPARTING→MEMBER, but because it was already JOINED the FSM suppresses the Wave-4
        // JOINED delta → no MembershipDecision.NodeJoined → dhtTopologyListener::onNodeJoined never
        // re-adds it → it stays pruned (the onNodeDeparting prune above) → under-replication. Wire the
        // recovery edge to the symmetric ring RE-ADD, idempotent with onNodeJoined (ConsistentHashRing.addNode
        // no-ops a node already present), so a concurrent NodeJoined is harmless.
        membershipFsm.onDepartingRecovery(node -> {
            if (!configuredWorker(config)) {
                dhtTopologyListener.onNodeRecovered(node);
            }
        });
        var hierarchyPeerPolicy = org.pragmatica.aether.node.health.HierarchyPeerPolicy.hierarchyPeerPolicy(config.self(),
                                                                                                            configuredWorker(config));
        Runnable refreshHierarchy = () -> refreshHierarchyPeerPolicy(hierarchyPeerPolicy,
                                                                     routingCoreIds,
                                                                     communityDirectory,
                                                                     governorLookup,
                                                                     clusterNode,
                                                                     membershipFsm,
                                                                     swimHealthDetector);

        refreshHierarchy.run();
        metricsScheduler.setPingTargetEligibility(peer -> hierarchyPeerPolicy.shouldPing(peer) || drainCommandRegistry.isDrainRequested(peer) || (!configuredWorker(config) && (workerAdmission.isPending(peer) || candidateHealth.isPending(peer))));
        metricsScheduler.setPeerMetricsForwarding(hierarchyPeerPolicy::forwardsPeerMetrics);
        // W-OBS W2: the leader's cluster directory content, and the governor lookup of the pong path's sender gate (I-W14).
        metricsScheduler.setDirectorySources(() -> ReplicaFeedAssembly.directoryNodes(membershipFsm),
                                             () -> ReplicaFeedAssembly.directoryDhtReplication(kvStore));
        metricsCollector.setDirectoryGovernor(sender -> ReplicaFeedAssembly.isOwnGovernor(config.self(),
                                                                                          sender,
                                                                                          communityDirectory::assignment,
                                                                                          governorLookup));
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> {
                                                                   refreshHierarchy.run();
                                                                   metricsScheduler.publishObservationsNow();
                                                               },
                                                               config.timeouts().cluster().pingInterval()));
        clusterNetworkRef.setConnectionInitiator((_, peer) -> hierarchyPeerPolicy.initiatesCoreBootstrap(bootstrapInitiator(clusterNode,
                                                                                                                            config.self(),
                                                                                                                            configuredTransferPeers,
                                                                                                                            dialingSinceNanos),
                                                                                                         transferPeer.test(peer)) || hierarchyPeerPolicy.isConnectionInitiator(peer,
                                                                                                                                                                               transferPeer.test(peer) || routingCoreIds.get()
                                                                                                                                                                                                                        .contains(peer) || membershipFsm.memberDescriptor(peer)
                                                                                                                                                                                                                                                        .map(MemberDescriptor::isCore)
                                                                                                                                                                                                                                                        .or(false),
                                                                                                                                                                               membershipFsm.memberDescriptor(peer)
                                                                                                                                                                                            .map(MembershipAssembly::isWorkerDescriptor)
                                                                                                                                                                                            .or(false),
                                                                                                                                                                               workerEndpointDirectory.entries()
                                                                                                                                                                                                      .get()
                                                                                                                                                                                                      .containsKey(peer)));
        clusterNetworkRef.setDesiredConnections(() -> desiredDialTargets(membershipFsm,
                                                                         workerEndpointDirectory,
                                                                         peer -> hierarchyPeerPolicy.shouldConnect(peer) || (configuredWorker(config) && routingCoreIds.get()
                                                                                                                                                                       .contains(peer)) || drainCommandRegistry.isDrainRequested(peer) || (!configuredWorker(config) && (workerAdmission.isPending(peer) || candidateHealth.isPending(peer)))));
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
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(() -> logMembershipBaselineTrace(membershipDeltaProjector,
                                                                                                membershipFsm,
                                                                                                presenceSampler),
                                                               MEMBERSHIP_BASELINE_TRACE_INTERVAL));
        var topologyForSwim = clusterNode.topologyManager();
        // P1 fix: prefer the transport-supplied `NodeInfo` (QUIC/Netty Hello handshake)
        // over the static-topology lookup. CTM-replaced or topology-forgotten peers are
        // absent from `topologyForSwim`, so the legacy NodeId-only path produced a SWIM
        // `PeerConnected(id, none())` that the FSM's `resolveSwimAddress` could not
        // resolve — leaving the peer permanently UNKNOWN and rejected by the gate.
        // Wave 2 role-reach (cluster-topology-overhaul): the QUIC transport supplies the
        // Hello NodeInfo for every peer that is topology-unknown OR presented labels, so feed it to
        // the membership FSM's descriptor FIRST — every node a worker dials (workers dial all
        // cores, always including the leader) learns the worker's self-asserted role/source on the
        // first handshake, independent of SWIM seed lists or the labels carried by gossip.
        // The descriptor upsert is blank-downgrade-guarded, so a label-less Hello never
        // erases a known role. Runs on the router dispatch thread like the SWIM feed below;
        // `onMemberDescriptor` is thread-safe (concurrent map + per-member monitor).
        allEntries.add(MessageRouter.Entry.route(NetworkServiceMessage.ConnectionEstablished.class,
                                                 connection -> routeConnectionEstablished(connection,
                                                                                          topologyForSwim::get,
                                                                                          membershipFsm::onMemberDescriptor,
                                                                                          swimHealthDetector::onNodeConnected,
                                                                                          swimHealthDetector::onNodeConnected)));

        return new MembershipEdgesOutputs(nttConnectTap, nttDisconnectTap, prefetchChannel);
    }

    /// What `quicReporters` reads from the assembly built so far.
    record QuicReportersInputs(RabiaNode<KVCommand<AetherKey>> clusterNode,
                               AetherNode.EpochSources epochSources,
                               PeerObservationStore peerObservationStore,
                               Supplier<Epoch> leaderEpochSupplier,
                               ClusterSyncScheduler metricsScheduler,
                               CoreSwimHealthDetector swimHealthDetector,
                               MembershipFsm membershipFsm,
                               Consumer<NodeId> nttConnectTap,
                               Consumer<NodeId> nttDisconnectTap) {}

    /// The QUIC connectivity reporter, the peer state listener and the broadcast membership view.
    static Unit quicReporters(QuicReportersInputs in) {
        var clusterNode = in.clusterNode();
        var epochSources = in.epochSources();
        var peerObservationStore = in.peerObservationStore();
        var leaderEpochSupplier = in.leaderEpochSupplier();
        var metricsScheduler = in.metricsScheduler();
        var swimHealthDetector = in.swimHealthDetector();
        var membershipFsm = in.membershipFsm();
        var nttConnectTap = in.nttConnectTap();
        var nttDisconnectTap = in.nttDisconnectTap();

        attachQuicConnectivityReporter(clusterNode.network(),
                                       connectivityWiring(peerObservationStore,
                                                          epochSources.incarnation(),
                                                          leaderEpochSupplier,
                                                          nttConnectTap,
                                                          nttDisconnectTap));
        attachQuicPeerStateListener(clusterNode.network(), swimHealthDetector, metricsScheduler::onLinkEstablished);
        attachBroadcastMembershipView(clusterNode.network(), membershipFsm);

        return Unit.unit();
    }

    /// NTT reconcile fan-out (membership v2). Fired once per stable presence-sensor membership
    /// transition AND (Wave 7) once per FSM death edge — both gated upstream so an already-handled
    /// re-fire is a no-op. Re-evaluates the quorum-loss detector (whose member count is derived from
    /// current state, see [`#derivedMemberCount`]) and nudges the leader reconciler.
    @Contract
    static void onNttReconcile(AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                               AtomicReference<LeaderReconciler> leaderReconcilerRef) {
        Option.option(quorumLossDetectorRef.get()).onPresent(QuorumLossDetector::reevaluate);
        Option.option(leaderReconcilerRef.get()).onPresent(LeaderReconciler::onTopologyUnhealthy);
    }

    /// #2429: the node's identity ledger is attached to the shared boot-token registry and loaded here, at assembly, before the
    /// transport starts and admission can consult it. A worker holds no ledger (it keeps per-observer admission).
    static Option<IdentityRecordPersister> attachIdentityLedger(AetherNodeConfig config,
                                                                CoreSwimHealthDetector swimHealthDetector,
                                                                OperatorWarningSink operatorWarningSink) {
        return configuredWorker(config)
               ? Option.none()
               : swimHealthDetector.bootTokens()
                                   .map(tokens -> IdentityLedgerWiring.wirePersisting(config,
                                                                                      tokens,
                                                                                      operatorWarningSink));
    }

    /// ID-1b: the identity record's two edges. Every core's apply of a committed retirement reaches its own ledger before the callback returns
    /// (`persister`), and the leader's admission signal feeds the shadow writer (`registrar`; BootTokens stays the decider). A worker holds
    /// neither: it keeps the per-observer path.
    static Unit wireIdentityRecord(AetherNodeConfig config,
                                   KVStore<AetherKey, AetherValue> kvStore,
                                   RabiaNode<KVCommand<AetherKey>> clusterNode,
                                   CoreSwimHealthDetector swimHealthDetector,
                                   Option<IdentityRecordPersister> persister,
                                   List<MessageRouter.Entry<?>> allEntries) {
        var registrar = swimHealthDetector.bootTokens()
                                          .filter(_ -> !configuredWorker(config))
                                          .map(tokens -> IdentityRegistrar.identityRegistrar(identityWriter(config,
                                                                                                            kvStore,
                                                                                                            clusterNode),
                                                                                             tokens::tokenOf,
                                                                                             System::currentTimeMillis));

        allEntries.addAll(identityRecordEntries(persister,
                                                registrar,
                                                () -> clusterNode.leaderManager()
                                                                 .isLeader() && clusterNode.isActive()));

        return Unit.unit();
    }

    /// The routes of the identity record: the apply listener on `NodeIdentityKey` (every core) and the admission signal `NodeJoined`
    /// (the leader records it; the signal arrives on every core).
    static List<MessageRouter.Entry<?>> identityRecordEntries(Option<IdentityRecordPersister> persister,
                                                              Option<IdentityRegistrar> registrar,
                                                              BooleanSupplier activeLeader) {
        var entries = new ArrayList<MessageRouter.Entry<?>>();

        persister.onPresent(applied -> entries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                                                          .onPut(AetherKey.NodeIdentityKey.class,
                                                                                 (KVStoreNotification.ValuePut<AetherKey.NodeIdentityKey, AetherValue.NodeIdentityValue> put) -> applied.onPut(put.cause()
                                                                                                                                                                                                  .key(),
                                                                                                                                                                                               put.cause()
                                                                                                                                                                                                  .value()))
                                                                          .build()
                                                                          .asRouteEntries()));
        registrar.onPresent(recording -> entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                                                               joined -> admitIfLeader(activeLeader,
                                                                                                       recording,
                                                                                                       joined))));

        return List.copyOf(entries);
    }

    private static HierarchyStateWriter identityWriter(AetherNodeConfig config,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       RabiaNode<KVCommand<AetherKey>> clusterNode) {
        return HierarchyStateWriter.hierarchyStateWriter(() -> kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                                                                      .filter(value -> value.leader()
                                                                                            .equals(config.self())
                                                                                       && clusterNode.leaderManager()
                                                                                                     .isLeader()
                                                                                       && clusterNode.isActive()),
                                                         kvStore::get,
                                                         commands -> clusterNode.apply(commands));
    }

    /// The admission signal is delivered on every core; only the leader records it.
    @Contract
    private static void admitIfLeader(BooleanSupplier activeLeader,
                                      IdentityRegistrar registrar,
                                      MembershipDecision.NodeJoined joined) {
        if (activeLeader.getAsBoolean()) {
            registrar.onAdmitted(joined.nodeId());
        }
    }

    /// The quorum-loss detector's member count, DERIVED FROM CURRENT STATE each time the detector evaluates it
    /// (#1853). It used to be PUSHED: `propagateMemberCount` computed it from a `voters` snapshot the trigger
    /// passed in and stored the result in the detector. Its inputs are the FSM's strict core members, their
    /// observed reachability and the installed voter set, and the push had triggers for the first two only —
    /// a presence-sampler edge that landed before genesis installed the voters computed against an empty voter
    /// set, stored 0, and nothing ever recomputed it (rc4 cloud run 9: quorum=false on 4 of 5 nodes for 17
    /// minutes with consensus active; and, since the detector's arm latch only sets on an evaluation that sees a
    /// quorate count, no self-fence on a later real quorum loss). A supplier cannot be stale: every read walks
    /// the live inputs. The edge-detecting half of the detector (arm latch, below-threshold window) is fed by
    /// [`#wireQuorumCountTriggers`] plus the presence-sampler, Member-boundary and death triggers.
    ///
    /// Wave 2 / W1 of the cluster-topology-overhaul spec (adopting membership-fsm-unification §6): the
    /// numerator is the FSM's STRICT CORE count — state exactly MEMBER, role=core; SUSPECT excluded, workers
    /// excluded. A worker must never hold the quorum-loss detector above threshold (1 core + 2 workers is NOT a
    /// quorum of a 3-core config — Rabia has no voter majority). The strict MEMBER-only numerator is safe against
    /// premature self-drain on a transient SUSPECT because the detector only fires after the
    /// `quorumLossDrainThreshold` window elapses below threshold — a flap that recovers within the window
    /// cancels the pending intent. Wave 7 (parallel-view collapse): the legacy pre-FSM fallback to the
    /// sampler's role-blind `currentMemberCount` is GONE; before the FSM is published the count is `0`, exactly
    /// what the push left in place when it skipped that window.
    ///
    /// #557: the count is [`MembershipFsm#strictCoreObservedMemberCount`] narrowed to the voters, i.e. the
    /// strict core count narrowed to OBSERVED reachability. The plain strict count is seed-derived — every
    /// configured core is MEMBER from wiring time — which armed the detector's arm-after-first-quorum latch on
    /// configuration alone, before the cluster had formed. That latch is specified as "has this cluster ever
    /// been quorate"; only the observed count means it.
    ///
    /// No installed voter configuration yields `0` without walking the members: nobody is a voter yet.
    /// The configuration is read ONCE per evaluation, so every member is tested against the same electorate.
    ///
    /// **Cost, the read path vs the push it replaces (#929).** The push walked every member twice and built two
    /// sets per trigger ([`MembershipFsm#coreObservedMembers`], [`MembershipFsm#strictCoreMembers`]). This is
    /// one allocation-free pass ([`MembershipFsm#strictCoreObservedVoterCount`]) taking each member's monitor
    /// briefly and one at a time, so it is cheaper per evaluation than the old trigger, and it adds no
    /// lock-order edge: the only new evaluation sites are the detector's own firing checks (which already hold
    /// the detector's monitor while the co-confirmation supplier walks the same member map) and the management
    /// reads, which hold no lock. Evaluations per second are the old trigger rate plus health/status polls.
    /// [unverified: no microbenchmark was run; the claim is the pass count and allocation, not a timing]
    static int derivedMemberCount(Option<MembershipFsm> membershipFsm, NodeId self, Option<VoterConfiguration> voters) {
        return Option.all(membershipFsm, voters)
                     .map((fsm, electorate) -> fsm.strictCoreObservedVoterCount(self, electorate::contains))
                     .or(0);
    }

    /// True iff this transition crosses the exact-`Member` boundary — i.e. the strict core member
    /// count ([`MembershipFsm#strictCoreMemberCount`], the quorum-loss numerator) changes. Feeding the
    /// detector on this edge (NOT only on the 15s presence down-hysteresis / DEAD edge) is what lets a
    /// node recognize quorum loss promptly when several cores enter SUSPECT at once — the design's own
    /// safety argument (derivedMemberCount javadoc) already assumes this feed; the splitTimeout window
    /// + refutation-cancel + Fix-C co-confirmation guard against a transient SUSPECT flap.
    private static boolean crossesMemberBoundary(MembershipTransitionRecord record) {
        return "Member".equals(record.fromState()) != "Member".equals(record.toState());
    }

    /// FSM death-edge fan-out (Wave 7, cluster-topology-overhaul): invoked once per fresh edge into
    /// DEAD via [`MembershipFsm#onConfirmedDeparture`]. Drops the dead peer's QUIC link promptly
    /// (`departurePermanent`, idempotent) and runs the same heal/quorum nudge the deleted
    /// FSM→PresenceSampler eviction used to trigger through the sampler's reconcile callback —
    /// behaviour parity with the sampler removed from the authority loop.
    @Contract
    static void onMembershipDeath(NodeId departed,
                                  Consumer<NodeId> dropDeadPeerLink,
                                  AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                  AtomicReference<LeaderReconciler> leaderReconcilerRef) {
        dropDeadPeerLink.accept(departed);
        onNttReconcile(quorumLossDetectorRef, leaderReconcilerRef);
    }

    /// True iff this transition moves a member across the placement boundary — counted (MEMBER or SUSPECT) to
    /// not counted, or back. Most such edges are followed by a membership decision, but two are not: MEMBER →
    /// DEPARTING (a graceful drain, which drops the member from placement with no removal until it terminalizes)
    /// and DEPARTING → MEMBER (a withdrawn drain, which returns it). Without this edge a pass that ran during
    /// the drain leaves the withdrawn member out of every replica set until some unrelated membership change.
    static boolean crossesCountedBoundary(MembershipTransitionRecord record) {
        return isCounted(record.fromState()) != isCounted(record.toState());
    }

    private static boolean isCounted(String state) {
        return "Member".equals(state) || "Suspect".equals(state);
    }

    @Contract
    private static void closeSliceFloorRefusalOnDeath(AtomicReference<Option<ManagementServer>> managementServerRef,
                                                      MembershipTransitionRecord record) {
        if ("Dead".equals(record.toState())) {
            managementServerRef.get().onPresent(server -> server.onMemberDeparted(record.nodeId()));
        }
    }

    @Contract
    static void reconcileReplicaSetOnCountedBoundary(AtomicReference<ReplicaSetController> controllerRef,
                                                     MembershipTransitionRecord record) {
        if (crossesCountedBoundary(record)) {
            reconcileReplicaSet(controllerRef);
        }
    }

    /// Single FSM-transition fan-out installed at the central transition chokepoint
    /// ([`MembershipFsm#onTransition`], fired once per ACTUAL per-member state change at the FSM's
    /// central dispatch chokepoint). Records the transition into the per-node journal
    /// (Wave-1 Enrichment A) AND — when the transition crosses the exact-`Member` boundary — re-feeds
    /// the strict-core count to the quorum-loss detector. This prompt re-feed (NOT only the 15s
    /// presence down-hysteresis `onNttReconcile` / DEAD `onMembershipDeath` paths) is what arms the
    /// self-drain window when several cores enter SUSPECT at once, and CANCELS it on a SUSPECT→MEMBER
    /// refutation.
    ///
    /// #929: the detector's count derivation (`derivedMemberCount`) walks EVERY member calling the `synchronized`
    /// `MemberTracking.isStrictCoreMember`. Until #929 this listener ran while the FSM held the
    /// DISPATCHING member's monitor, so this method held one member's monitor and requested all the
    /// others' — in `ConcurrentHashMap` iteration order. Two of this node's event loops (SWIM
    /// `onSwimSuspect`, QUIC `onLivenessGone`) dispatching on two different members deadlocked AB/BA,
    /// wedging both loops. The FSM now publishes with the per-member monitor RELEASED (serialised by
    /// `MemberTracking.transitionGuard`), which is what makes this member walk safe. The old
    /// "safe-by-precedent" note here cited `onMembershipDeath` driving the same chain from inside
    /// that monitor — that was the same defect, not a precedent for it.
    @Contract
    static void onFsmTransition(TransitionJournal journal,
                                AtomicReference<QuorumLossDetector> quorumLossDetectorRef,
                                MembershipTransitionRecord record) {
        appendFsmTransition(journal, record);
        if (crossesMemberBoundary(record)) {
            Option.option(quorumLossDetectorRef.get()).onPresent(QuorumLossDetector::reevaluate);
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

    /// #1777 R1b: a new leader takes over the replication change where the last one left it — it commits the change the
    /// current configuration carries if its predecessor had not, and advances what the reports already allow.
    @Contract
    private static void onDhtLeaderChange(LeaderNotification.LeaderChange change,
                                          KVStore<AetherKey, AetherValue> kvStore,
                                          DhtReplicationSettlement settlement) {
        if (!change.localNodeIsLeader()) {
            return;
        }

        kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
               .onPresent(settlement::onConfigCommitted);
        settlement.evaluate();
    }

    /// Whether this core must dial its configured transfer peers regardless of the single-dialer order.
    ///
    /// Staged (#1390): an installed configuration that does not include this node. Formed cores keep
    /// an unadmitted core outside their SWIM scope and never dial it — core SWIM membership is bounded
    /// to known cores plus governors, and a claimed CORE role alone must not widen it — so the staged
    /// core has to initiate.
    ///
    /// Genesis pending (no configuration yet, #1554): a cold-start candidate and a core joining a formed
    /// cluster look the same locally. Cold-start candidates reach each other through designated dials
    /// within a fraction of a second; if they also bypassed the order, every pair dialed both ways and
    /// the superseded duplicate could lose its FORWARD lane (#1578). So a pending core initiates only
    /// once it is isolated: no connection to any configured core after the grace window. That is the
    /// joiner the formed seeds cannot see. The window starts at the transport's first dial decision,
    /// not at construction: a node built long before it starts would otherwise start out "isolated".
    /// Feed 1 of a replacement's dial targets (#1200): the FSM descriptors of the cores it was configured with (`--peers`), so
    /// `desiredConnections()` carries their dial addresses from the first moment, before any gossip. Pinned by `ReplacementDialMechanismTest`.
    static Unit seedConfiguredDescriptors(java.util.List<NodeInfo> coreNodes,
                                          NodeId self,
                                          boolean worker,
                                          MembershipFsm membershipFsm) {
        coreNodes.stream().map(info -> configuredMember(info, self, worker)).forEach(membershipFsm::onMemberDescriptor);

        return Unit.unit();
    }

    static boolean bootstrapInitiator(RabiaNode<KVCommand<AetherKey>> node,
                                      NodeId self,
                                      Set<NodeId> configuredPeers,
                                      AtomicLong dialingSinceNanos) {
        return node.voterConfiguration()
                   .map(configuration -> !configuration.contains(self))
                   .or(() -> isolatedPendingCore(node,
                                                 configuredPeers,
                                                 dialingSince(dialingSinceNanos)));
    }

    /// The peers a bootstrapping core initiates toward regardless of the single-dialer order: the cores it was
    /// configured with at mint time AND the committed electorate it learns after joining (#1803). Mint-time
    /// peers alone are frozen at mint — a replacement minted before the current leader existed would otherwise
    /// wait for a leader that does not know it to dial first.
    static Predicate<NodeId> transferPeers(Set<NodeId> configured, Supplier<Set<NodeId>> electorate) {
        return peer -> configured.contains(peer) || electorate.get()
                                                              .contains(peer);
    }

    private static long dialingSince(AtomicLong dialingSinceNanos) {
        var _ = dialingSinceNanos.compareAndSet(0L, System.nanoTime());

        return dialingSinceNanos.get();
    }

    static boolean isolatedPendingCore(RabiaNode<KVCommand<AetherKey>> node,
                                       Set<NodeId> configuredPeers,
                                       long dialingSinceNanos) {
        return System.nanoTime() - dialingSinceNanos >= PENDING_CORE_ISOLATION_GRACE.nanos() && node.network()
                                                                                                    .connectedPeers()
                                                                                                    .stream()
                                                                                                    .noneMatch(configuredPeers::contains);
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

    private static void attachQuicPeerStateListener(ClusterNetwork network,
                                                    CoreSwimHealthDetector swimDetector,
                                                    Consumer<NodeId> linkEstablished) {
        LOG.debug("attachQuicPeerStateListener: network class={}",
                  Option.option(network).map(n -> n.getClass()
                                                   .getName()).or("null"));
        if (! (network instanceof QuicClusterNetwork quicNetwork)) {
            LOG.warn("attachQuicPeerStateListener: network is NOT QuicClusterNetwork — skipping listener attachment");

            return;
        }

        quicNetwork.setPeerStateListener(quicPeerStateListener(swimDetector::recordTransportHint, linkEstablished));
        quicNetwork.connectedPeers()
                   .forEach(peer -> {
                                LOG.debug("QuicPeerState: catch-up recordTransportHint(reachable) for already-connected peer {}",
                                          peer);
                                swimDetector.recordTransportHint(new TransportObservation.PeerReachable(peer));
                            });
    }

    /// The QUIC peer-state listener feeding SWIM transport hints and ClusterSync link epochs (#1061).
    /// Package-private so a test pins the observation each callback produces, not only the
    /// cause→origin mapping. A join or reconnect first starts a new ClusterSync missed-pong epoch
    /// for the peer (R-a: misses counted against the previous link are discarded) and then sends
    /// `PeerReachable`; a departure sends a `PEER_LEFT` hint, which is `LINK_LOST`; a dial answered by
    /// another identity sends `IdentityRefuted` for the dialed identity only (#1830).
    static QuicPeerStateListener quicPeerStateListener(Consumer<TransportObservation> swimHints,
                                                       Consumer<NodeId> linkEstablished) {
        return new QuicPeerStateListener() {
            @Override
            @Contract
            public void onPeerJoined(NodeId nodeId) {
                reportLinkEstablished("onPeerJoined", nodeId, swimHints, linkEstablished);
            }

            @Override
            @Contract
            public void onPeerReconnected(NodeId nodeId) {
                reportLinkEstablished("onPeerReconnected", nodeId, swimHints, linkEstablished);
            }

            @Override
            @Contract
            public void onPeerLeft(NodeId nodeId) {
                LOG.debug("QuicPeerState: onPeerLeft({}) — recordTransportHint(unreachable)", nodeId);
                swimHints.accept(unreachableHint(QuicTransportCause.PEER_LEFT, nodeId));
            }

            @Override
            @Contract
            public void onPeerIdentityRefuted(NodeId dialed, NodeId claimant) {
                LOG.debug("QuicPeerState: onPeerIdentityRefuted({}, answered by {}) — recordTransportHint(refuted)",
                          dialed,
                          claimant);
                swimHints.accept(new TransportObservation.IdentityRefuted(dialed, claimant));
            }
        };
    }

    /// A QUIC link to `nodeId` was (re)established: start a new ClusterSync missed-pong epoch, then
    /// send SWIM `PeerReachable`.
    private static void reportLinkEstablished(String callback,
                                              NodeId nodeId,
                                              Consumer<TransportObservation> swimHints,
                                              Consumer<NodeId> linkEstablished) {
        LOG.debug("QuicPeerState: {}({}) — link epoch + recordTransportHint(reachable)", callback, nodeId);
        linkEstablished.accept(nodeId);
        swimHints.accept(new TransportObservation.PeerReachable(nodeId));
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
    private static void attachQuicConnectivityReporter(ClusterNetwork network, ConnectivityWiring wiring) {
        if (! (network instanceof QuicClusterNetwork quicNetwork)) {
            return;
        }

        quicNetwork.setFollowerObservationWiring(wiring.reporter(), wiring.epoch());
    }

    /// Builds [ConnectivityWiring]. An inbound attach calls the reporter and the adapter synchronously on the
    /// QUIC event loop (`QuicClusterServer` Hello handler -> `QuicClusterNetwork.onPeerConnected` ->
    /// `processViewChange(ADD)` -> `reportPeerConnection`), and a channel-driven removal calls them on the loop
    /// too, so neither may take the `KVStore` monitor. `ClusterIncarnation.current` reads through the
    /// synchronized `getTyped`, which waits out a whole snapshot restore. The incarnation therefore comes from
    /// an [ObservedIncarnation] that the committed key's notifications keep current — every commit that
    /// changes it (genesis, restore, declare-genesis) and every replay after a snapshot install.
    ///
    /// #1529 (v1635 N3), known tear, metrics only: the transport hands over `term`/`counter` from the epoch
    /// it captured, while `incarnation` is read at report time. An observation straddling an incarnation
    /// change can therefore mix two epochs. Nothing fences on these observations (they feed connectivity
    /// telemetry), so it is recorded rather than fixed.
    static ConnectivityWiring connectivityWiring(PeerObservationBuffer buffer,
                                                 ObservedIncarnation incarnation,
                                                 Supplier<Epoch> epochSupplier,
                                                 Consumer<NodeId> onNttConnect,
                                                 Consumer<NodeId> onNttDisconnect) {
        PeerConnectivityReporter reporter = new PeerConnectivityReporter() {
            @Contract
            @Override
            public void onPeerDisconnected(NodeId peerId, long term, long counter, boolean deathPathInitiated) {
                var now = System.currentTimeMillis();

                buffer.pushConnectivity(new PeerConnectivityObservation(peerId,
                                                                        ConnectivityState.DISCONNECTED,
                                                                        incarnation.current(),
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
                                                                        incarnation.current(),
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

        return new ConnectivityWiring(reporter, epochAdapter);
    }

    /// #1050 — only the FAULTY edge reaches [`ClusterTopologyManager#onSwimFaulty`]; every other observation
    /// (including `DepartedObserved`, which SWIM emits as the second half of the same FAULTY-edge pair) is
    /// dropped here. The CTM acts on it only for a reap it has abandoned.
    @Contract
    static void routeSwimFaultyToCtm(SwimObservation observation, ClusterTopologyManager clusterTopologyManager) {
        if (observation instanceof SwimObservation.FaultyObserved faulty) {
            clusterTopologyManager.onSwimFaulty(faulty.peer());
        }
    }

    /// Feed a QUIC `ConnectionEstablished` to the membership FSM and the SWIM health detector. The transport
    /// attaches the Hello `NodeInfo` for a topology-unknown peer AND for a known peer that supplied labels, so
    /// the FSM descriptor upsert sees the role either way. The SWIM feed keeps its established source order:
    /// the topology's own `NodeInfo` first, the Hello's only when topology does not know the peer, the bare id
    /// when neither exists. Package-private with the sinks as parameters so the routing is testable.
    @Contract
    static void routeConnectionEstablished(NetworkServiceMessage.ConnectionEstablished connection,
                                           Function<NodeId, Option<NodeInfo>> topologyLookup,
                                           Consumer<NodeInfo> membershipDescriptor,
                                           Consumer<NodeInfo> swimConnectedInfo,
                                           Consumer<NodeId> swimConnectedId) {
        connection.nodeInfo().onPresent(membershipDescriptor);
        topologyLookup.apply(connection.nodeId())
                      .orElse(connection::nodeInfo)
                      .onPresent(swimConnectedInfo)
                      .onEmpty(() -> swimConnectedId.accept(connection.nodeId()));
    }

    /// Route a SWIM observation edge into the authoritative [`MembershipFsm`]. HEALTHY / SUSPECT /
    /// FAULTY / DEPARTED / UNKNOWN map to the matching `onSwim*` ingress; JoinAnnounced /
    /// MemberDiscovered upsert the member descriptor (address, role, source — the role a gossiped
    /// `MembershipUpdate` carries reaches the FSM here). The FSM
    /// drives the per-member lifecycle and, on the DEAD edge, hard-evicts from NTT — the membership-
    /// death authority. Leader-gating happens inside the FSM. Package-private so the node tests drive
    /// the real routing; `@Contract` because it is the sink of a `Consumer<SwimObservation>` listener.
    @Contract
    static void routeSwimEdgeToMembershipFsm(SwimObservation observation, MembershipFsm membershipFsm) {
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

    private static boolean isWorkerDescriptor(MemberDescriptor descriptor) {
        return "worker".equalsIgnoreCase(descriptor.role()) || "spot".equalsIgnoreCase(descriptor.role());
    }

    static Collection<NodeInfo> desiredDialTargets(MembershipFsm membershipFsm,
                                                   org.pragmatica.aether.node.health.WorkerEndpointDirectory endpoints,
                                                   Predicate<NodeId> connectionEligibility) {
        var members = membershipFsm.memberStates()
                                   .keySet()
                                   .stream()
                                   .filter(membershipFsm::isTrackedAndNotDead)
                                   .filter(connectionEligibility)
                                   .collect(Collectors.toSet());

        return Stream.concat(metadataDirectory(membershipFsm, members).stream(),
                             endpoints.desiredConnections().stream())
                     .collect(Collectors.toMap(NodeInfo::id,
                                               info -> info,
                                               (membership, _) -> membership))
                     .values();
    }

    private static void refreshHierarchyPeerPolicy(org.pragmatica.aether.node.health.HierarchyPeerPolicy policy,
                                                   Supplier<Set<NodeId>> routingCores,
                                                   org.pragmatica.aether.worker.health.CommunityMemberDirectory communities,
                                                   Function<String, Option<AetherValue.GovernorAnnouncementValue>> governors,
                                                   RabiaNode<KVCommand<AetherKey>> cluster,
                                                   MembershipFsm membership,
                                                   CoreSwimHealthDetector swim) {
        var cores = new HashSet<>(routingCores.get());

        if (!policy.worker()) {
            membership.memberStates()
                      .keySet()
                      .stream()
                      .filter(node -> isCoreMember(membership, node))
                      .forEach(cores::add);
        }

        var governorIds = communities.communities()
                                     .stream()
                                     .flatMap(community -> governors.apply(community)
                                                                    .filter(value -> !value.dissolved())
                                                                    .stream())
                                     .map(AetherValue.GovernorAnnouncementValue::governorId)
                                     .collect(Collectors.toSet());
        var ownCommunity = communities.assignment(cluster.self());
        var members = ownCommunity.map(community -> Set.copyOf(communities.members(community))).or(Set.of());
        var ownGovernor = ownCommunity.flatMap(governors::apply)
                                      .filter(value -> !value.dissolved())
                                      .map(AetherValue.GovernorAnnouncementValue::governorId);

        policy.refresh(cores,
                       governorIds,
                       members,
                       ownGovernor,
                       cluster.leaderManager().leader(),
                       Set.copyOf(membership.reachableMembers(List.copyOf(cores))));
        if (!policy.worker()) {
            swim.setMembershipEligibility(policy::shouldObserve);
            swim.observePeerDirectory(membership.desiredConnections()
                                                .stream()
                                                .filter(target -> policy.shouldObserve(target.id()))
                                                .map(target -> dialNodeInfo(target,
                                                                            membership.memberDescriptor(target.id())))
                                                .toList());
            // #1803: the scope above now covers the committed electorate; ask for the voters this node's SWIM
            // view still lacks (a replacement minted before the current leader existed was never told of it).
            swim.requestMembershipView(installedVoterIds(cluster));
        }
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

    record unused() implements MembershipAssembly {}
}
