package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Topology observer for cluster networks. Tracks connections, health states,
/// SWIM events, and reconnection. This is the read-only topology tracking component;
/// cluster size management is handled by ClusterTopologyManager in the deployment layer.
public interface TopologyObserver extends TopologyManager {
    /// Errors that can occur during topology observer creation.
    sealed interface TopologyError extends Cause {
        record SelfNodeNotInCoreNodes(NodeId self) implements TopologyError {
            @Override
            public String message() {
                return "Self node " + self + " must be in coreNodes";
            }
        }
    }

    @MessageReceiver
    void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList);

    @MessageReceiver
    void handleAddNodeMessage(TopologyManagementMessage.AddNode message);

    @MessageReceiver
    void handleRemoveNodeMessage(TopologyManagementMessage.RemoveNode removeNode);

    @MessageReceiver
    void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes);

    @MessageReceiver
    void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes);

    @MessageReceiver
    void handleConnectionFailed(NetworkServiceMessage.ConnectionFailed connectionFailed);

    @MessageReceiver
    void handleConnectionEstablished(NetworkServiceMessage.ConnectionEstablished connectionEstablished);

    @MessageReceiver
    void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message);

    static Result<TopologyObserver> topologyObserver(TopologyConfig config, MessageRouter router) {
        return topologyObserver(config, router, TimeSource.system());
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource) {
        // Validate that self node is in coreNodes - required for self() to work
        var selfInCoreNodes = config.coreNodes()
                                    .stream()
                                    .anyMatch(info -> info.id()
                                                          .equals(config.self()));
        if (!selfInCoreNodes) {
            return new TopologyError.SelfNodeNotInCoreNodes(config.self()).result();
        }
        record Manager(Map<NodeId, NodeState> nodeStatesById,
                       Map<NodeAddress, NodeId> nodeIdsByAddress,
                       MessageRouter router,
                       TopologyConfig config,
                       TimeSource timeSource,
                       AtomicBoolean active,
                       AtomicInteger effectiveClusterSize,
                       java.util.Set<NodeId> readyNodes) implements TopologyObserver {
            private static final Logger log = LoggerFactory.getLogger(TopologyObserver.class);

            Manager(Map<NodeId, NodeState> nodeStatesById,
                    Map<NodeAddress, NodeId> nodeIdsByAddress,
                    MessageRouter router,
                    TopologyConfig config,
                    TimeSource timeSource,
                    AtomicBoolean active,
                    AtomicInteger effectiveClusterSize,
                    java.util.Set<NodeId> readyNodes) {
                this.config = config;
                this.router = router;
                this.nodeStatesById = nodeStatesById;
                this.nodeIdsByAddress = nodeIdsByAddress;
                this.timeSource = timeSource;
                this.active = active;
                this.effectiveClusterSize = effectiveClusterSize;
                this.readyNodes = readyNodes;
                this.effectiveClusterSize.set(config.clusterSize());
                config().coreNodes()
                      .forEach(this::addNode);
                // Self node validation is done in the factory method before construction
                log.trace("Topology observer {} initialized with {} nodes, cluster size {}",
                          config.self(),
                          config.coreNodes(),
                          config.clusterSize());
                SharedScheduler.scheduleAtFixedRate(this::initReconcile, config.reconciliationInterval());
            }

            private Instant now() {
                return Instant.ofEpochSecond(0, timeSource.nanoTime());
            }

            private void initReconcile() {
                if (active.get()) {
                    router().route(new NetworkServiceMessage.ListConnectedNodes());
                } else if (nodeStatesById().isEmpty()) {
                    config().coreNodes()
                          .forEach(this::addNode);
                }
            }

            @Override
            public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {
                var snapshot = new HashSet<>(nodeStatesById.keySet());
                connectedNodesList.connected()
                                  .forEach(snapshot::remove);
                snapshot.forEach(this::requestConnectionIfEligible);
            }

            private void requestConnectionIfEligible(NodeId id) {
                Option.option(nodeStatesById.get(id))
                      .filter(state -> state.canAttemptConnection(now()))
                      .onPresent(_ -> requestConnection(id));
            }

            @Override
            public void handleAddNodeMessage(TopologyManagementMessage.AddNode message) {
                addNode(message.nodeInfo());
            }

            @Override
            public void handleRemoveNodeMessage(TopologyManagementMessage.RemoveNode removeNode) {
                removeNode(removeNode.nodeId());
            }

            @Override
            public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {
                var nodeInfos = nodeStatesById.values()
                                              .stream()
                                              .map(NodeState::info)
                                              .toList();
                router().route(new NetworkServiceMessage.Send(discoverNodes.self(),
                                                              new NetworkMessage.DiscoveredNodes(discoverNodes.self(),
                                                                                                 nodeInfos)));
            }

            @Override
            public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {
                discoveredNodes.nodes()
                               .forEach(this::addNode);
            }

            @Override
            public void handleConnectionFailed(NetworkServiceMessage.ConnectionFailed connectionFailed) {
                var nodeId = connectionFailed.nodeId();
                nodeStatesById.computeIfPresent(nodeId, (_, state) ->
                    processConnectionFailure(state, connectionFailed.cause()));
            }

            private NodeState processConnectionFailure(NodeState state, Cause cause) {
                var nodeId = state.info()
                                  .id();
                var newAttempts = state.failedAttempts() + 1;
                var backoff = config.backoff();
                var now = now();
                var delay = backoff.backoffStrategy()
                                   .nextTimeout(newAttempts);
                var nextAttempt = now.plusNanos(delay.nanos());
                log.debug("Node {} connection failed (attempt {}), next attempt after {}: {}",
                          nodeId,
                          newAttempts,
                          delay,
                          cause.message());
                return NodeState.suspected(state.info(), newAttempts, now, nextAttempt);
            }

            @Override
            public void handleConnectionEstablished(NetworkServiceMessage.ConnectionEstablished connectionEstablished) {
                var nodeId = connectionEstablished.nodeId();
                nodeStatesById.computeIfPresent(nodeId, (_, state) ->
                    processConnectionEstablished(state));
            }

            private NodeState processConnectionEstablished(NodeState state) {
                if (state.health() == NodeHealth.SUSPECTED) {
                    log.debug("Node {} recovered from suspected state", state.info().id());
                }
                return NodeState.healthy(state.info(), now());
            }

            private void addNode(NodeInfo nodeInfo) {
                var now = now();
                var initialState = NodeState.healthy(nodeInfo, now);
                // To avoid reliance on the networking layer behavior, adding is done
                // atomically and the command to establish the connection is sent only once.
                Option.option(nodeStatesById().putIfAbsent(nodeInfo.id(),
                                                           initialState))
                      .onEmpty(() -> {
                                   nodeIdsByAddress().putIfAbsent(nodeInfo.address(),
                                                                  nodeInfo.id());
                                   // Only request connection if topology observer is active (router is ready)
                if (active().get()) {
                                       requestConnection(nodeInfo.id());
                                   }
                               });
            }

            private void requestConnection(NodeId id) {
                router().route(new NetworkServiceMessage.ConnectNode(id));
            }

            private void removeNode(NodeId nodeId) {
                // Never remove self node - would cause NPE in self() method
                if (nodeId.equals(config.self())) {
                    log.warn("Ignoring removal of self node {}", nodeId);
                    return;
                }
                // Remove from ready set — node is no longer operational
                readyNodes.remove(nodeId);
                // To avoid reliance on the networking layer behavior, removing is done
                // atomically and command to drop the connection is sent only once.
                Option.option(nodeStatesById().remove(nodeId))
                      .onPresent(state -> {
                                     nodeIdsByAddress.remove(state.info()
                                                                  .address());
                                     router().route(new NetworkServiceMessage.DisconnectNode(nodeId));
                                 });
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.option(nodeStatesById.get(id))
                             .map(NodeState::info);
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.option(nodeStatesById.get(id));
            }

            @Override
            public List<NodeId> topology() {
                return nodeStatesById.keySet()
                                     .stream()
                                     .sorted()
                                     .toList();
            }

            @Override
            public int clusterSize() {
                return effectiveClusterSize.get();
            }

            @Override
            public void markReady(NodeId nodeId) {
                readyNodes.add(nodeId);
                log.debug("Node {} marked ready (ON_DUTY). Ready nodes: {}", nodeId, readyNodes.size());
            }

            @Override
            public void markReady(NodeId nodeId, NodeAddress address) {
                readyNodes.add(nodeId);
                // If this node is not in our topology, it was dynamically provisioned
                // by another leader. Add it and request connection.
                if (nodeStatesById.get(nodeId) == null && !nodeId.equals(config.self())) {
                    var nodeInfo = NodeInfo.nodeInfo(nodeId, address);
                    addNode(nodeInfo);
                    log.info("Node {} added to topology via ON_DUTY notification at {}", nodeId, address.asString());
                }
                log.debug("Node {} marked ready (ON_DUTY) with address. Ready nodes: {}", nodeId, readyNodes.size());
            }

            @Override
            public void markDeparted(NodeId nodeId) {
                readyNodes.remove(nodeId);
                log.debug("Node {} marked departed. Ready nodes: {}", nodeId, readyNodes.size());
            }

            @Override
            public int readyNodeCount() {
                // ON_DUTY from consensus is authoritative — no cross-reference against local topology.
                // Dynamically provisioned nodes may be ON_DUTY before appearing in local nodeStatesById.
                return (int) readyNodes.stream()
                                       .filter(id -> !isPassive(id))
                                       .count();
            }

            private int activeTopologySize() {
                return (int) nodeStatesById.values()
                                          .stream()
                                          .filter(state -> state.health() == NodeHealth.HEALTHY)
                                          .count();
            }

            @Override
            public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {
                int newSize = message.clusterSize();
                int currentSize = effectiveClusterSize.get();
                if (newSize < 3) {
                    log.warn("Rejecting cluster size change to {}: minimum is 3 for Byzantine fault tolerance", newSize);
                    return;
                }
                if (newSize > currentSize) {
                    int newQuorum = newSize / 2 + 1;
                    int activeNodes = activeTopologySize();
                    if (activeNodes < newQuorum) {
                        log.warn("Rejecting cluster size increase from {} to {}: only {} active nodes, need {} for new quorum",
                                 currentSize,
                                 newSize,
                                 activeNodes,
                                 newQuorum);
                        return;
                    }
                }
                int oldQuorum = currentSize / 2 + 1;
                int newQuorum = newSize / 2 + 1;
                int activeNodes = activeTopologySize();
                boolean wasBelow = activeNodes < oldQuorum;
                boolean nowAbove = activeNodes >= newQuorum;
                effectiveClusterSize.set(newSize);
                log.info("Cluster size changed from {} to {} (quorum: {} -> {})",
                         currentSize,
                         newSize,
                         oldQuorum,
                         newQuorum);
                if (wasBelow && nowAbove) {
                    log.info("Quorum re-established after cluster size change");
                    router.route(QuorumStateNotification.established());
                }
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return (socketAddress instanceof InetSocketAddress inet)
                       ? NodeAddress.nodeAddress(inet)
                                    .option()
                                    .flatMap(addr -> Option.option(nodeIdsByAddress.get(addr)))
                       : Option.empty();
            }

            @Override
            public Promise<Unit> start() {
                if (active().compareAndSet(false, true)) {
                    log.trace("Starting topology observer at {}", config.self());
                    initReconcile();
                }
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> stop() {
                active().set(false);
                return Promise.success(Unit.unit());
            }

            @Override
            public NodeInfo self() {
                // Self node is guaranteed to be in topology after constructor completes
                // (added via config.coreNodes().forEach(this::addNode))
                return nodeStatesById().get(config.self())
                                     .info();
            }

            @Override
            public TimeSpan pingInterval() {
                return config().pingInterval();
            }

            @Override
            public TimeSpan helloTimeout() {
                return config().helloTimeout();
            }

            @Override
            public Option<TlsConfig> tls() {
                return config().tls();
            }
        }
        return Result.success(new Manager(new ConcurrentHashMap<>(),
                                          new ConcurrentHashMap<>(),
                                          router,
                                          config,
                                          timeSource,
                                          new AtomicBoolean(false),
                                          new AtomicInteger(config.clusterSize()),
                                          ConcurrentHashMap.newKeySet()));
    }
}
