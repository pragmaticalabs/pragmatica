package org.pragmatica.cluster.node.rabia;

import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkMessage.DiscoverNodes;
import org.pragmatica.consensus.net.NetworkMessage.DiscoveredNodes;
import org.pragmatica.consensus.net.NetworkMessage.Hello;
import org.pragmatica.consensus.net.NetworkMessage.KeepAlive;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncRequest;
import org.pragmatica.consensus.net.NetworkMessage.KVSyncResponse;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectedNodesList;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectionEstablished;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectionFailed;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.consensus.rabia.ConsensusMetrics;
import org.pragmatica.consensus.rabia.RabiaEngine;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManagementMessage.SetClusterSize;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Tuple.Tuple2;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.messaging.MessageRouter.Entry.SealedBuilder;
import org.pragmatica.messaging.MessageRouter.ImmutableRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.netty.handler.codec.quic.QuicSslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.messaging.MessageRouter.Entry.route;

public interface RabiaNode<C extends Command> extends ClusterNode<C> {
    Logger log = LoggerFactory.getLogger(RabiaNode.class);

    ClusterNetwork network();

    LeaderManager leaderManager();

    /// Check if the consensus engine is active and ready for commands.
    boolean isActive();

    /// Authorize a gated consensus engine to start participating.
    /// Only meaningful for non-seed nodes with activation gating enabled.
    void authorizeActivation();

    /// Authorize a gated consensus engine to enter observer mode.
    /// The node will receive and apply committed Decisions but won't propose or vote.
    void authorizeObservation();

    /// Check if the consensus engine is in observer mode.
    boolean isObserving();

    /// Wave-1 §6.4 detect-only boot future-history listener pass-through
    /// (cluster-topology-overhaul spec): installs the listener on the wrapped
    /// [RabiaEngine] (`RabiaEngine#onBootFutureHistory`), invoked with
    /// `(persistedPhaseValue, clusterReportedPhaseValue)` when the node's persisted
    /// Rabia phase exceeds what the joined cluster reports at first sync restore.
    /// Diagnostic-only; default no-op for implementations without a Rabia engine.
    default void onBootFutureHistory(BiConsumer<Long, Long> listener) {}

    /// Post-restore listener pass-through: installs the listener on the wrapped [RabiaEngine]
    /// (`RabiaEngine#onStateRestored`), invoked after every successful sync restore once the
    /// restored state has been applied and is queryable. Late-joiner seam for state derived
    /// from KV notifications (e.g. the cluster gossip-key rotation): a node that syncs after
    /// the originating `ValuePut` must re-read it from the restored store. Restore can run
    /// more than once per process, so listeners must be idempotent. Default no-op for
    /// implementations without a Rabia engine.
    default void onStateRestored(Runnable listener) {}

    /// Get the route entries for RabiaNode's internal components.
    /// These should be combined with other entries when building the final router.
    List<Entry<?>> routeEntries();

    /// Phase 2 PR-B (cluster-convergence-reconciler) — leader-side `SyncHoldRegistry`
    /// tracking peers currently consuming a KV-sync snapshot. Phase 4's `LifecycleReconciler`
    /// consults this via `Supplier<Set<NodeId>>` (`registry::activeHolds`) to skip
    /// force-decommission for busy syncing nodes. Default returns an empty registry for
    /// legacy nodes constructed without convergence-reconciler wiring.
    default SyncHoldRegistry syncHoldRegistry() {
        return SyncHoldRegistry.syncHoldRegistry();
    }

    /// Creates a RabiaNode with full QUIC transport configuration.
    /// Requires explicit TLS config — QUIC has no plaintext mode.
    ///
    /// @param config                     Node configuration
    /// @param delegateRouter             DelegateRouter for message routing
    /// @param stateMachine               State machine for consensus
    /// @param serializer                 Message serializer
    /// @param deserializer               Message deserializer
    /// @param metrics                    Consensus metrics collector
    /// @param useConsensusLeaderElection Whether to use consensus-based leader election
    /// @param persistence                Persistence implementation for consensus state
    /// @param tlsConfig                  TLS configuration for QUIC cluster transport (required — no plaintext mode)
    /// @return Result containing RabiaNode instance, or failure if topology/TLS creation fails
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence,
                                                              TlsConfig tlsConfig) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                          metrics, useConsensusLeaderElection, persistence, tlsConfig,
                          () -> 0L, TopologyObserver.NEVER_DECOMMISSIONED);
    }

    /// Overload that accepts a `rabiaTermSupplier` plumbed into the [`LeaderManager`] so
    /// [`LeaderManager#currentLeaderEpoch`] returns the live cluster-side term. Legacy
    /// callers without a KV-backed lifecycle predicate use this overload.
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence,
                                                              TlsConfig tlsConfig,
                                                              Supplier<Long> rabiaTermSupplier) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                          metrics, useConsensusLeaderElection, persistence, tlsConfig,
                          rabiaTermSupplier, TopologyObserver.NEVER_DECOMMISSIONED);
    }

    /// Production overload: accepts an `isDecommissioned` predicate that consults the
    /// local KV-Store for `NodeLifecycleValue` atoms in DECOMMISSIONED state. The
    /// predicate is forwarded to [`TopologyObserver`] as the cross-restart persisted-tombstone
    /// hook: it covers nodes the cluster has durably retired across a process restart, which
    /// the in-memory membership state cannot (Wave 7 retired the observer's inert in-memory
    /// tombstone set; same-session resurrect protection lives in the aether FSM incarnation fence).
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence,
                                                              TlsConfig tlsConfig,
                                                              Supplier<Long> rabiaTermSupplier,
                                                              Predicate<NodeId> isDecommissioned) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                          metrics, useConsensusLeaderElection, persistence, tlsConfig,
                          rabiaTermSupplier, isDecommissioned, Option::none,
                          GenerationSnapshotSource.noop(), TopologyObserver.ZERO_HLC_SUPPLIER);
    }

    /// Full-arity overload accepting a `currentLeaderFromKvSupplier` — pull-side complement
    /// to the push-based `KVStoreNotification.ValuePut<LeaderKey>` listener. Production wiring
    /// (Aether's `AetherNode`) plumbs `() -> kvStore.get(LeaderKey.INSTANCE).map(LeaderValue::leader)`
    /// so the leader-election FSM can adopt the cluster-committed leader on entry to
    /// `Electing` / `ReElecting`, even when the rejoining node missed the notification path.
    ///
    /// Also accepts a `snapshotSource` and `hlcSupplier` that are forwarded to
    /// [`TopologyObserver`] so `MembershipDecision` emissions carry real HLC timestamps
    /// and are backed by the KV-Store generation snapshot rather than the legacy defaults.
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence,
                                                              TlsConfig tlsConfig,
                                                              Supplier<Long> rabiaTermSupplier,
                                                              Predicate<NodeId> isDecommissioned,
                                                              Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                                              GenerationSnapshotSource snapshotSource,
                                                              Supplier<HlcTimestamp> hlcSupplier) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                          metrics, useConsensusLeaderElection, persistence, tlsConfig,
                          rabiaTermSupplier, isDecommissioned, currentLeaderFromKvSupplier,
                          snapshotSource, hlcSupplier,
                          SyncHoldRegistry.syncHoldRegistry(),
                          SyncHoldConfig.defaults(),
                          () -> {});
    }

    /// Phase 2 PR-B overload accepting:
    /// - `syncHoldRegistry` — leader-side tracker of peers currently consuming a KV-sync
    ///   snapshot (Phase 4 reconciler consults `registry.activeHolds()`).
    /// - `syncHoldConfig` — configures the hold-duration formula `clamp(bytes/bps, min, max)`.
    /// - `onSyncResponseReceived` — invoked on the JOINING node when a `KVSyncResponse` lands;
    ///   typically wired to `() -> readinessTracker.markReady(self)`. Default in the legacy
    ///   overload is a no-op so embeddings that pre-date the convergence-reconciler keep working.
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence,
                                                              TlsConfig tlsConfig,
                                                              Supplier<Long> rabiaTermSupplier,
                                                              Predicate<NodeId> isDecommissioned,
                                                              Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                                              GenerationSnapshotSource snapshotSource,
                                                              Supplier<HlcTimestamp> hlcSupplier,
                                                              SyncHoldRegistry syncHoldRegistry,
                                                              SyncHoldConfig syncHoldConfig,
                                                              Runnable onSyncResponseReceived) {
        // Dedicated single-subscriber channel carrying the quorum established/lost
        // ClusterStateNotification edge from TopologyObserver to RabiaEngine ONLY. Its delegate
        // is wired AFTER `consensus` exists (in assembleNode) — TopologyObserver is built here,
        // before `consensus`, so it must receive a DelegateRouter whose delegate is replaced once
        // the single-route table { ClusterStateNotification -> consensus::clusterState } is known.
        var quorumPresenceRouter = DelegateRouter.delegate();
        return Result.all(
            TopologyObserver.topologyObserver(config.topology(), delegateRouter, snapshotSource, isDecommissioned, hlcSupplier, quorumPresenceRouter),
            QuicTlsProvider.serverContext(tlsConfig),
            QuicTlsProvider.clientContext(tlsConfig)
        ).map((topologyManager, serverSsl, clientSsl) -> assembleNode(config,
                                                                       delegateRouter,
                                                                       quorumPresenceRouter,
                                                                       stateMachine,
                                                                       serializer,
                                                                       deserializer,
                                                                       metrics,
                                                                       topologyManager,
                                                                       serverSsl,
                                                                       clientSsl,
                                                                       useConsensusLeaderElection,
                                                                       persistence,
                                                                       rabiaTermSupplier,
                                                                       currentLeaderFromKvSupplier,
                                                                       syncHoldRegistry,
                                                                       syncHoldConfig,
                                                                       onSyncResponseReceived));
    }

    @SuppressWarnings("unchecked")
    private static <C extends Command> RabiaNode<C> assembleNode(NodeConfig config,
                                                                 DelegateRouter delegateRouter,
                                                                 DelegateRouter quorumPresenceRouter,
                                                                 StateMachine<C> stateMachine,
                                                                 Serializer serializer,
                                                                 Deserializer deserializer,
                                                                 ConsensusMetrics metrics,
                                                                 TopologyObserver topologyManager,
                                                                 QuicSslContext serverSsl,
                                                                 QuicSslContext clientSsl,
                                                                 boolean useConsensusLeaderElection,
                                                                 RabiaPersistence<C> persistence,
                                                                 Supplier<Long> rabiaTermSupplier,
                                                                 Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                                                                 SyncHoldRegistry syncHoldRegistry,
                                                                 SyncHoldConfig syncHoldConfig,
                                                                 Runnable onSyncResponseReceived) {
        var network = new QuicClusterNetwork(topologyManager,
                                             serializer,
                                             deserializer,
                                             delegateRouter,
                                             serverSsl,
                                             clientSsl,
                                             config.clusterFormation());
        var activationGated = config.activationGated();
        var consensusBridge = org.pragmatica.consensus.rabia.ConsensusBridge.consensusBridge(delegateRouter);
        var consensus = new RabiaEngine<>(topologyManager,
                                          network,
                                          stateMachine,
                                          config.protocol(),
                                          metrics,
                                          activationGated,
                                          persistence,
                                          RabiaEngine.DEFAULT_PHASE_STALL_CHECK,
                                          consensusBridge);
        // Wire the dedicated quorum-presence channel: TopologyObserver delivers the quorum
        // established/lost ClusterStateNotification edge to RabiaEngine ONLY, via this private
        // single-subscriber router (NOT the shared bus). Built here because `consensus` only
        // now exists. A one-entry table cannot have a duplicate-route conflict, but the Result
        // is handled (not swallowed) per the codebase idiom — a validation failure is logged.
        buildAndWireRouter(quorumPresenceRouter,
                           List.of(route(ClusterStateNotification.class, consensus::clusterState)))
            .onFailure(cause -> log.error("Failed to wire quorum-presence channel: {}", cause));
        // Create leader manager - for consensus mode, we wire the proposal handler
        // Extract expected cluster members for deterministic leader selection
        var expectedCluster = config.topology()
                                    .coreNodes()
                                    .stream()
                                    .map(info -> info.id())
                                    .toList();
        LeaderManager leaderManager;
        if (useConsensusLeaderElection) {
            // Consensus-based leader election: submit proposals through consensus.
            // `consensus::isActive` is the push-side SSOT for "consensus engine is ready" — the
            // FSM consults it on entry to `QuorumWaiting` to decide whether to immediately
            // advance to electing.
            // `currentLeaderFromKvSupplier` is the pull-side SSOT for "cluster has elected a
            // leader" — the FSM consults it on entry to `Electing` / `ReElecting` (and on each
            // election tick) to short-circuit to `Led(leader)` whenever the local KV-Store
            // already records a committed leader. Closes the gap where the
            // `KVStoreNotification.ValuePut<LeaderKey>` push notification didn't fire (e.g.
            // snapshot-restored state didn't include LeaderKey, or listener wired late).
            LeaderManager.LeaderProposalHandler proposalHandler =
            (candidate, viewSequence) -> submitLeaderProposal(consensus, candidate, DEFAULT_PROPOSAL_TIMEOUT);
            leaderManager = LeaderManager.leaderManager(config.topology()
                                                              .self(),
                                                        delegateRouter,
                                                        proposalHandler,
                                                        expectedCluster,
                                                        rabiaTermSupplier,
                                                        consensus::isActive,
                                                        currentLeaderFromKvSupplier);
        } else {
            // Local election mode: backward compatible
            leaderManager = LeaderManager.leaderManager(config.topology()
                                                              .self(),
                                                        delegateRouter);
        }
        // Collect sealed hierarchy entries
        var topologyMgmtRoutes = SealedBuilder.from(TopologyManagementMessage.class)
                                              .route(route(SetClusterSize.class, topologyManager::handleSetClusterSize));
        var networkMsgRoutes = SealedBuilder.from(NetworkMessage.class)
                                            .route(route(DiscoverNodes.class, topologyManager::handleDiscoverNodes),
                                                   route(DiscoveredNodes.class, topologyManager::handleDiscoveredNodes),
                                                   route(Hello.class, _ -> {}),
                                                   // Transport-internal liveness beacon (Wave 5): swallowed by the
                                                   // QuicClusterNetwork inbound funnel BEFORE routing (it only refreshes
                                                   // the per-peer receipt clock). This route exists solely to satisfy
                                                   // sealed-hierarchy startup completeness — it must never receive traffic.
                                                   route(KeepAlive.class, _ -> {}),
                                                   route(KVSyncRequest.class,
                                                         request -> handleKVSyncRequest(stateMachine, delegateRouter, config, request,
                                                                                         syncHoldRegistry, syncHoldConfig)),
                                                   route(KVSyncResponse.class,
                                                         _ -> onSyncResponseReceived.run()));
        var networkServiceRoutes = SealedBuilder.from(NetworkServiceMessage.class)
                                                .route(route(ConnectedNodesList.class, topologyManager::reconcile),
                                                       route(ConnectNode.class, network::connect),
                                                       route(DisconnectNode.class, network::disconnect),
                                                       route(ListConnectedNodes.class, network::listNodes),
                                                       // R5: transport never mutates topology projection. ConnectionFailed/Established
                                                       // are forwarded to SWIM via QuicClusterNetwork's peer-state listener
                                                       // (AetherNode.attachQuicPeerStateListener -> swimDetector.recordTransportHint).
                                                       // The router routes are retained only to satisfy sealed-hierarchy coverage.
                                                       route(ConnectionFailed.class, _ -> {}),
                                                       route(ConnectionEstablished.class, _ -> {}),
                                                       route(Send.class, network::handleSend),
                                                       route(Broadcast.class, network::handleBroadcast));
        var transportObservationRoutes = SealedBuilder.from(TransportObservation.class)
                                                .route(route(PeerJoined.class, leaderManager::peerJoined),
                                                       route(PeerDisconnected.class, leaderManager::peerDisconnected),
                                                       route(PeerObservedFaulty.class, leaderManager::peerObservedFaulty),
                                                       route(PeerReconnected.class, leaderManager::peerReconnected),
                                                       route(SelfShutdown.class, leaderManager::selfShutdown));
        var syncRoutes = SealedBuilder.from(Synchronous.class)
                                      .route(route(Propose.class, consensus::processPropose),
                                             route(VoteRound1.class, consensus::processVoteRound1),
                                             route(VoteRound2.class, consensus::processVoteRound2),
                                             route(Decision.class, consensus::processDecision),
                                             route(SyncResponse.class,
                                                   (SyncResponse r) -> consensus.processSyncResponse(r)));
        var asyncRoutes = SealedBuilder.from(Asynchronous.class)
                                       .route(route(SyncRequest.class, consensus::handleSyncRequest),
                                              route(NewBatch.class,
                                                    (NewBatch b) -> consensus.handleNewBatch(b)));
        // Collect all entries
        var allEntries = new ArrayList<Entry<?>>();
        allEntries.add(topologyMgmtRoutes);
        allEntries.add(networkMsgRoutes);
        allEntries.add(networkServiceRoutes);
        allEntries.add(transportObservationRoutes);
        allEntries.add(syncRoutes);
        allEntries.add(asyncRoutes);
        // Quorum-signal flow is now strictly unidirectional:
        //   TopologyObserver --(private quorumPresenceRouter)--> RabiaEngine
        //   RabiaEngine --> ConsensusBridge --(shared bus ClusterStateNotification)--> consumers.
        // RabiaEngine is NO LONGER on the shared-bus ClusterStateNotification route — it receives
        // its established/lost activation trigger via the private quorumPresenceRouter wired above.
        // LeaderManager stays on the shared bus and receives ConsensusBridge's ACTIVE there. The
        // old "consensus must activate before LeaderManager emits LeaderChange" ordering invariant
        // is now structurally guaranteed by that chain (TopologyObserver -> Rabia -> ConsensusBridge
        // -> LeaderManager), not by route-registration order.
        allEntries.add(route(ClusterStateNotification.class, leaderManager::watchClusterState));
        // NOTE: Leader election commit handling (ValuePut<LeaderKey, LeaderValue> -> onLeaderCommitted)
        // is done by AetherNode.handleLeaderCommit(), not here. RabiaNode only provides the consensus
        // infrastructure; the application layer (AetherNode) wires the leader commit notifications.
        record rabiaNode<C extends Command>(NodeConfig config,
                                            StateMachine<C> stateMachine,
                                            ClusterNetwork network,
                                            TopologyManager topologyManager,
                                            RabiaEngine<C> consensus,
                                            LeaderManager leaderManager,
                                            List<Entry<?>> routeEntries,
                                            SyncHoldRegistry syncHoldRegistry) implements RabiaNode<C> {
            @Override
            public NodeId self() {
                return config().topology()
                             .self();
            }

            @Override
            public Promise<Unit> start() {
                return network().start()
                              .onSuccessRunAsync(topologyManager()::start)
                              .flatMap(consensus()::start);
            }

            @Override
            public Promise<Unit> stop() {
                leaderManager().stop();
                return consensus().stop()
                                .onResultRun(topologyManager()::stop)
                                .flatMap(network()::stop);
            }

            @Override
            public boolean isActive() {
                return consensus().isActive();
            }

            @Override
            public void authorizeActivation() {
                consensus().authorizeActivation();
            }

            @Override
            public void authorizeObservation() {
                consensus().authorizeObservation();
            }

            @Override
            public boolean isObserving() {
                return consensus().isObserving();
            }

            @Override
            public void onBootFutureHistory(BiConsumer<Long, Long> listener) {
                consensus().onBootFutureHistory(listener);
            }

            @Override
            public void onStateRestored(Runnable listener) {
                consensus().onStateRestored(listener);
            }

            @Override
            public <R> Promise<List<R>> apply(List<C> commands) {
                return consensus().apply(commands);
            }
        }
        return new rabiaNode<>(config,
                               stateMachine,
                               network,
                               topologyManager,
                               consensus,
                               leaderManager,
                               List.copyOf(allEntries),
                               syncHoldRegistry);
    }

    /// Builds an ImmutableRouter from route entries and wires it to a DelegateRouter.
    /// Validates all sealed hierarchies and merges entries into a single routing table.
    ///
    /// @param delegateRouter Router to wire
    /// @param entries        All route entries to include
    /// @return Result containing the ImmutableRouter, or failure if validation fails
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Result<MessageRouter> buildAndWireRouter(DelegateRouter delegateRouter, List<Entry<?>> entries) {
        // Validate all sealed hierarchies
        Set<Class<?>> validationErrors = new HashSet<>();
        for (var entry : entries) {
            validationErrors.addAll(entry.validate());
        }
        if (!validationErrors.isEmpty()) {
            var missing = validationErrors.stream()
                                          .map(Class::getSimpleName)
                                          .collect(Collectors.joining(", "));
            return new MessageRouter.InvalidMessageRouterConfiguration("Missing routes: " + missing).result();
        }
        // Collect all entries into routing table
        Map<Class, List<Consumer>> routingTable = new HashMap<>();
        for (var entry : entries) {
            entry.entries()
                 .forEach(tuple -> addToTable(routingTable, tuple));
        }
        // Create ImmutableRouter
        record immutableRouter(Map<Class, List<Consumer>> routingTable) implements ImmutableRouter {
            @Override
            public void route(Message message) {
                var handlers = routingTable.get(message.getClass());
                if (handlers != null) {
                    handlers.forEach(h -> h.accept(message));
                }
            }
        }
        var router = new immutableRouter(Map.copyOf(routingTable));
        delegateRouter.replaceDelegate(router);
        return Result.success(router);
    }

    @SuppressWarnings({"rawtypes"})
    private static void addToTable(Map<Class, List<Consumer>> table, Tuple2 tuple) {
        table.computeIfAbsent((Class) tuple.first(),
                              _ -> new ArrayList<>())
             .add((Consumer) tuple.last());
    }

    /// Leader-side KV-sync request handler. On arrival, computes a deadline-based hold via
    /// `SyncHoldConfig.holdMs(snapshotBytes)` and registers `request.sender()` in
    /// `syncHoldRegistry`. The Phase 4 reconciler (`activeHolds()` consumer) treats the peer
    /// as "actively syncing — do not force-decommission" while the deadline has not elapsed.
    ///
    /// Spec §5.4: the hold is cleared by either (a) deadline expiry (handled by
    /// `SyncHoldRegistry.activeHolds()` self-pruning) or (b) explicit `readyCandidate` arrival
    /// (wired in Phase 4 via `SyncHoldRegistry.clear`). The `makeSnapshot()` onFailure path
    /// also clears the hold so a failed snapshot doesn't leave a stale entry.
    private static <C extends Command> void handleKVSyncRequest(StateMachine<C> stateMachine,
                                                                      DelegateRouter delegateRouter,
                                                                      NodeConfig config,
                                                                      KVSyncRequest request,
                                                                      SyncHoldRegistry syncHoldRegistry,
                                                                      SyncHoldConfig syncHoldConfig) {
        // Register hold BEFORE makeSnapshot — on large states snapshot construction itself can
        // take seconds, and the reconciler should treat the peer as protected throughout.
        // Initial deadline uses syncHoldConfig.minHold(); refined once we know snapshot size.
        var preliminaryDeadline = System.currentTimeMillis() + syncHoldConfig.minHold().millis();
        syncHoldRegistry.register(request.sender(), preliminaryDeadline);
        stateMachine.makeSnapshot()
                    .onSuccess(snapshot -> dispatchSnapshot(delegateRouter, config, request, snapshot,
                                                            syncHoldRegistry, syncHoldConfig))
                    .onFailure(cause -> clearHoldOnFailure(request, syncHoldRegistry, cause));
    }

    private static void dispatchSnapshot(DelegateRouter delegateRouter,
                                          NodeConfig config,
                                          KVSyncRequest request,
                                          byte[] snapshot,
                                          SyncHoldRegistry syncHoldRegistry,
                                          SyncHoldConfig syncHoldConfig) {
        // Refine hold deadline now that we know snapshot bytes. The peer needs at least
        // bytes/expectedSyncBps milliseconds (clamped) to ingest — extend the hold to cover.
        var refinedDeadline = System.currentTimeMillis() + syncHoldConfig.holdMs(snapshot.length);
        syncHoldRegistry.register(request.sender(), refinedDeadline);
        delegateRouter.route(new Send(request.sender(),
                                      new KVSyncResponse(config.topology().self(), snapshot)));
    }

    private static void clearHoldOnFailure(KVSyncRequest request,
                                            SyncHoldRegistry syncHoldRegistry,
                                            org.pragmatica.lang.Cause cause) {
        syncHoldRegistry.clear(request.sender());
        log.error("Failed to create KV snapshot: {}", cause);
    }

    /// Default timeout for leader proposals. Must outlast QUIC reconcile (5s) so a
    /// proposal mid-reconnect doesn't time out before the link is rebuilt — otherwise
    /// election cascades retry-loops under chaos.
    TimeSpan DEFAULT_PROPOSAL_TIMEOUT = timeSpan(8).seconds();

    @SuppressWarnings("unchecked")
    private static <C extends Command> Promise<Unit> submitLeaderProposal(RabiaEngine<C> consensus,
                                                                          NodeId candidate,
                                                                          TimeSpan proposalTimeout) {
        log.info("Submitting leader proposal: candidate={}", candidate);
        var command = new KVCommand.Put<>(LeaderKey.INSTANCE, LeaderValue.leaderValue(candidate));
        // Timeout for leader proposals - if consensus doesn't complete in this time, fail and retry.
        // This handles the case where other nodes are still syncing and ignoring proposals.
        return consensus.apply(List.of((C) command))
                        .timeout(proposalTimeout)
                        .mapToUnit();
    }
}
