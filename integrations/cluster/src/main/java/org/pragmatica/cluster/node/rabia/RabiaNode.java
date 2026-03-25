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
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeAdded;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManagementMessage.AddNode;
import org.pragmatica.consensus.topology.TopologyManagementMessage.RemoveNode;
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
import java.util.function.Consumer;
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

    /// Get the route entries for RabiaNode's internal components.
    /// These should be combined with other entries when building the final router.
    List<Entry<?>> routeEntries();

    /// Creates a RabiaNode without metrics collection.
    /// Uses local leader election (backward compatible).
    /// Auto-generates self-signed TLS for QUIC transport.
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                         ConsensusMetrics.noop(), false);
    }

    /// Creates a RabiaNode with metrics collection.
    /// Uses local leader election (backward compatible).
    /// Auto-generates self-signed TLS for QUIC transport.
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer, metrics, false);
    }

    /// Creates a RabiaNode with metrics collection and consensus-based leader election.
    ///
    /// When `useConsensusLeaderElection` is true:
    ///
    ///   - Leader proposals are submitted through consensus (KVStore with LeaderKey)
    ///   - LeaderChange notifications are sent asynchronously after commit
    ///   - All nodes agree on leader through consensus protocol
    ///
    ///
    /// When `useConsensusLeaderElection` is false (default):
    ///
    ///   - Leader is computed locally on view change
    ///   - LeaderChange notifications are sent immediately (synchronously)
    ///   - Backward compatible with existing behavior
    ///
    ///
    /// Auto-generates self-signed TLS for QUIC transport.
    ///
    /// @param config                     Node configuration
    /// @param delegateRouter             DelegateRouter for message routing (caller must wire after collecting all routes)
    /// @param stateMachine               State machine for consensus
    /// @param serializer                 Message serializer
    /// @param deserializer               Message deserializer
    /// @param metrics                    Consensus metrics collector
    /// @param useConsensusLeaderElection Whether to use consensus-based leader election
    /// @return Result containing RabiaNode instance, or failure if topology/TLS creation fails
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                         metrics, useConsensusLeaderElection, RabiaPersistence.inMemory());
    }

    /// Creates a RabiaNode with metrics, consensus-based leader election,
    /// and explicit persistence implementation.
    /// Auto-generates self-signed TLS for QUIC transport.
    ///
    /// @param config                     Node configuration
    /// @param delegateRouter             DelegateRouter for message routing
    /// @param stateMachine               State machine for consensus
    /// @param serializer                 Message serializer
    /// @param deserializer               Message deserializer
    /// @param metrics                    Consensus metrics collector
    /// @param useConsensusLeaderElection Whether to use consensus-based leader election
    /// @param persistence                Persistence implementation for consensus state
    /// @return Result containing RabiaNode instance, or failure if topology/TLS creation fails
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence) {
        return rabiaNode(config, delegateRouter, stateMachine, serializer, deserializer,
                         metrics, useConsensusLeaderElection, persistence, Option.empty());
    }

    /// Creates a RabiaNode with full QUIC transport configuration.
    /// Uses provided TLS config or auto-generates self-signed certs when absent.
    ///
    /// @param config                     Node configuration
    /// @param delegateRouter             DelegateRouter for message routing
    /// @param stateMachine               State machine for consensus
    /// @param serializer                 Message serializer
    /// @param deserializer               Message deserializer
    /// @param metrics                    Consensus metrics collector
    /// @param useConsensusLeaderElection Whether to use consensus-based leader election
    /// @param persistence                Persistence implementation for consensus state
    /// @param tlsConfig                  TLS configuration (empty for auto-generated self-signed)
    /// @return Result containing RabiaNode instance, or failure if topology/TLS creation fails
    static <C extends Command> Result<RabiaNode<C>> rabiaNode(NodeConfig config,
                                                              DelegateRouter delegateRouter,
                                                              StateMachine<C> stateMachine,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              ConsensusMetrics metrics,
                                                              boolean useConsensusLeaderElection,
                                                              RabiaPersistence<C> persistence,
                                                              Option<TlsConfig> tlsConfig) {
        return Result.all(
            TopologyObserver.topologyObserver(config.topology(), delegateRouter),
            QuicTlsProvider.serverContext(tlsConfig),
            QuicTlsProvider.clientContext(tlsConfig)
        ).map((topologyManager, serverSsl, clientSsl) -> assembleNode(config,
                                                                       delegateRouter,
                                                                       stateMachine,
                                                                       serializer,
                                                                       deserializer,
                                                                       metrics,
                                                                       topologyManager,
                                                                       serverSsl,
                                                                       clientSsl,
                                                                       useConsensusLeaderElection,
                                                                       persistence));
    }

    @SuppressWarnings("unchecked")
    private static <C extends Command> RabiaNode<C> assembleNode(NodeConfig config,
                                                                 DelegateRouter delegateRouter,
                                                                 StateMachine<C> stateMachine,
                                                                 Serializer serializer,
                                                                 Deserializer deserializer,
                                                                 ConsensusMetrics metrics,
                                                                 TopologyObserver topologyManager,
                                                                 QuicSslContext serverSsl,
                                                                 QuicSslContext clientSsl,
                                                                 boolean useConsensusLeaderElection,
                                                                 RabiaPersistence<C> persistence) {
        var network = new QuicClusterNetwork(topologyManager,
                                             serializer,
                                             deserializer,
                                             delegateRouter,
                                             serverSsl,
                                             clientSsl);
        var activationGated = config.activationGated();
        var consensus = new RabiaEngine<>(topologyManager, network, stateMachine, config.protocol(), metrics, activationGated, persistence);
        // Create leader manager - for consensus mode, we wire the proposal handler
        // Extract expected cluster members for deterministic leader selection
        var expectedCluster = config.topology()
                                    .coreNodes()
                                    .stream()
                                    .map(info -> info.id())
                                    .toList();
        LeaderManager leaderManager;
        if (useConsensusLeaderElection) {
            // Consensus-based leader election: submit proposals through consensus
            LeaderManager.LeaderProposalHandler proposalHandler =
            (candidate, viewSequence) -> submitLeaderProposal(consensus, candidate, DEFAULT_PROPOSAL_TIMEOUT);
            leaderManager = LeaderManager.leaderManager(config.topology()
                                                              .self(),
                                                        delegateRouter,
                                                        proposalHandler,
                                                        expectedCluster);
        } else {
            // Local election mode: backward compatible
            leaderManager = LeaderManager.leaderManager(config.topology()
                                                              .self(),
                                                        delegateRouter);
        }
        // Collect sealed hierarchy entries
        var topologyMgmtRoutes = SealedBuilder.from(TopologyManagementMessage.class)
                                              .route(route(AddNode.class, topologyManager::handleAddNodeMessage),
                                                     route(RemoveNode.class, topologyManager::handleRemoveNodeMessage),
                                                     route(SetClusterSize.class, topologyManager::handleSetClusterSize));
        var networkMsgRoutes = SealedBuilder.from(NetworkMessage.class)
                                            .route(route(DiscoverNodes.class, topologyManager::handleDiscoverNodes),
                                                   route(DiscoveredNodes.class, topologyManager::handleDiscoveredNodes),
                                                   route(Hello.class,
                                                         _ -> {}));
        var networkServiceRoutes = SealedBuilder.from(NetworkServiceMessage.class)
                                                .route(route(ConnectedNodesList.class, topologyManager::reconcile),
                                                       route(ConnectNode.class, network::connect),
                                                       route(DisconnectNode.class, network::disconnect),
                                                       route(ListConnectedNodes.class, network::listNodes),
                                                       route(ConnectionFailed.class,
                                                             topologyManager::handleConnectionFailed),
                                                       route(ConnectionEstablished.class,
                                                             topologyManager::handleConnectionEstablished),
                                                       route(Send.class, network::handleSend),
                                                       route(Broadcast.class, network::handleBroadcast));
        var topologyChangeRoutes = SealedBuilder.from(TopologyChangeNotification.class)
                                                .route(route(NodeAdded.class, leaderManager::nodeAdded),
                                                       route(NodeRemoved.class, leaderManager::nodeRemoved),
                                                       route(NodeDown.class, leaderManager::nodeDown));
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
        allEntries.add(topologyChangeRoutes);
        allEntries.add(syncRoutes);
        allEntries.add(asyncRoutes);
        // IMPORTANT: Order matters! Consensus must activate BEFORE LeaderManager emits LeaderChange.
        // LeaderChange handlers (e.g., ClusterDeploymentManager) may immediately call cluster.apply(),
        // which requires the consensus engine to be active.
        allEntries.add(route(QuorumStateNotification.class, consensus::quorumState));
        allEntries.add(route(QuorumStateNotification.class, leaderManager::watchQuorumState));
        // NOTE: Leader election commit handling (ValuePut<LeaderKey, LeaderValue> -> onLeaderCommitted)
        // is done by AetherNode.handleLeaderCommit(), not here. RabiaNode only provides the consensus
        // infrastructure; the application layer (AetherNode) wires the leader commit notifications.
        record rabiaNode<C extends Command>(NodeConfig config,
                                            StateMachine<C> stateMachine,
                                            ClusterNetwork network,
                                            TopologyManager topologyManager,
                                            RabiaEngine<C> consensus,
                                            LeaderManager leaderManager,
                                            List<Entry<?>> routeEntries) implements RabiaNode<C> {
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
                               List.copyOf(allEntries));
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

    /// Default timeout for leader proposals.
    TimeSpan DEFAULT_PROPOSAL_TIMEOUT = timeSpan(3).seconds();

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
