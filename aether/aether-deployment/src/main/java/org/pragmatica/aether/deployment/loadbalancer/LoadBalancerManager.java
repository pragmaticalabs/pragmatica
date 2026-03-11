package org.pragmatica.aether.deployment.loadbalancer;

import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.environment.LoadBalancerState.loadBalancerState;
import static org.pragmatica.aether.environment.RouteChange.routeChange;

/// Manages external load balancer configuration in response to cluster state changes.
/// Only active on the leader node — follows the same Dormant/Active pattern as ClusterDeploymentManager.
@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks — void required by messaging framework
public interface LoadBalancerManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut);

    @MessageReceiver
    void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    /// Create a MapSubscription adapter for DHT events.
    default MapSubscription<HttpNodeRouteKey, HttpNodeRouteValue> asHttpRouteSubscription() {
        return new MapSubscription<>() {
            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onPut(HttpNodeRouteKey key, HttpNodeRouteValue value) {
                onRoutePut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onRemove(HttpNodeRouteKey key) {
                onRouteRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
            }
        };
    }

    sealed interface LoadBalancerManagerState {
        default void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut) {}

        default void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove) {}

        default void onTopologyChange(TopologyChangeNotification topologyChange) {}

        record Dormant() implements LoadBalancerManagerState {}

        record Active(LoadBalancerProvider provider,
                      TopologyManager topologyManager,
                      KVStore<AetherKey, AetherValue> kvStore,
                      int appHttpPort,
                      Set<String> trackedNodeIps,
                      Map<String, Set<NodeId>> routeNodes) implements LoadBalancerManagerState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override
            public void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var routeIdentity = routeIdentity(key);
                routeNodes.computeIfAbsent(routeIdentity,
                                           _ -> new HashSet<>())
                          .add(key.nodeId());
                handleRouteChange(key.httpMethod(), key.pathPrefix(), routeNodes.get(routeIdentity));
            }

            @Override
            public void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                var routeIdentity = routeIdentity(key);
                var nodes = routeNodes.get(routeIdentity);
                if (nodes == null) {
                    return;
                }
                nodes.remove(key.nodeId());
                if (nodes.isEmpty()) {
                    routeNodes.remove(routeIdentity);
                    handleRouteRemoval(key.httpMethod(), key.pathPrefix());
                } else {
                    handleRouteChange(key.httpMethod(), key.pathPrefix(), nodes);
                }
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                switch (topologyChange) {
                    case NodeRemoved(NodeId removedNode, _) -> handleNodeDeparture(removedNode);
                    case NodeDown(NodeId downNode, _) -> handleNodeDeparture(downNode);
                    default -> {}
                }
            }

            void reconcile() {
                var allNodeIps = new HashSet<String>();
                var routes = new ArrayList<RouteChange>();
                var aggregated = new HashMap<String, Set<NodeId>>();
                kvStore.forEach(HttpNodeRouteKey.class,
                                HttpNodeRouteValue.class,
                                (key, _) -> aggregated.computeIfAbsent(routeIdentity(key),
                                                                       _ -> new HashSet<>())
                                                      .add(key.nodeId()));
                routeNodes.clear();
                routeNodes.putAll(aggregated);
                aggregated.forEach((identity, nodeIds) -> collectRouteForReconciliation(identity,
                                                                                        nodeIds,
                                                                                        allNodeIps,
                                                                                        routes));
                replaceTrackedIps(allNodeIps);
                log.info("Reconciling load balancer: {} routes, {} node IPs", routes.size(), allNodeIps.size());
                loadBalancerState(allNodeIps, routes).onSuccess(state -> provider.reconcile(state)
                                                                                 .onFailure(cause -> log.error("Load balancer reconciliation failed: {}",
                                                                                                               cause.message())))
                                 .onFailure(cause -> log.error("Failed to build load balancer state: {}",
                                                               cause.message()));
            }

            private void replaceTrackedIps(Set<String> newIps) {
                trackedNodeIps.retainAll(newIps);
                trackedNodeIps.addAll(newIps);
            }

            private void collectRouteForReconciliation(String routeIdentity,
                                                       Set<NodeId> nodeIds,
                                                       Set<String> allNodeIps,
                                                       List<RouteChange> routes) {
                var parts = routeIdentity.split(":", 2);
                var nodeIps = resolveNodeIps(nodeIds);
                allNodeIps.addAll(nodeIps);
                if (!nodeIps.isEmpty()) {
                    routeChange(parts[0], parts[1], nodeIps).onSuccess(routes::add)
                               .onFailure(cause -> log.warn("Failed to create route change for {}: {}",
                                                            routeIdentity,
                                                            cause.message()));
                }
            }

            private void handleRouteRemoval(String httpMethod, String pathPrefix) {
                log.info("Route removed: {} {}", httpMethod, pathPrefix);
                routeChange(httpMethod,
                            pathPrefix,
                            Set.of()).onSuccess(change -> provider.onRouteChanged(change)
                                                                  .onFailure(cause -> log.error("Failed to remove load balancer route {} {}: {}",
                                                                                                httpMethod,
                                                                                                pathPrefix,
                                                                                                cause.message())))
                           .onFailure(cause -> log.error("Failed to create route change for removal of {} {}: {}",
                                                         httpMethod,
                                                         pathPrefix,
                                                         cause.message()));
            }

            private void handleRouteChange(String httpMethod, String pathPrefix, Set<NodeId> nodeIds) {
                var nodeIps = resolveNodeIps(nodeIds);
                trackedNodeIps.addAll(nodeIps);
                log.debug("Route changed: {} {} -> {} nodes",
                          httpMethod,
                          pathPrefix,
                          nodeIps.size());
                routeChange(httpMethod, pathPrefix, nodeIps).onSuccess(change -> provider.onRouteChanged(change)
                                                                                         .onFailure(cause -> log.error("Failed to update load balancer route {} {}: {}",
                                                                                                                       httpMethod,
                                                                                                                       pathPrefix,
                                                                                                                       cause.message())))
                           .onFailure(cause -> log.error("Failed to create route change for {} {}: {}",
                                                         httpMethod,
                                                         pathPrefix,
                                                         cause.message()));
            }

            private void handleNodeDeparture(NodeId departedNode) {
                topologyManager.get(departedNode)
                               .map(NodeInfo::address)
                               .map(addr -> addr.host())
                               .onPresent(this::removeNodeIp);
            }

            private void removeNodeIp(String ip) {
                if (trackedNodeIps.remove(ip)) {
                    log.info("Node departed, removing IP {} from load balancer", ip);
                    provider.onNodeRemoved(ip)
                            .onFailure(cause -> log.error("Failed to remove node {} from load balancer: {}",
                                                          ip,
                                                          cause.message()));
                }
            }

            private Set<String> resolveNodeIps(Set<NodeId> nodeIds) {
                return nodeIds.stream()
                              .flatMap(nodeId -> topologyManager.get(nodeId)
                                                                .map(NodeInfo::address)
                                                                .map(addr -> addr.host())
                                                                .stream())
                              .collect(Collectors.toSet());
            }

            private static String routeIdentity(HttpNodeRouteKey key) {
                return key.httpMethod() + ":" + key.pathPrefix();
            }
        }
    }

    static LoadBalancerManager loadBalancerManager(NodeId self,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   TopologyManager topologyManager,
                                                   LoadBalancerProvider provider,
                                                   int appHttpPort) {
        record loadBalancerManager(NodeId self,
                                   KVStore<AetherKey, AetherValue> kvStore,
                                   TopologyManager topologyManager,
                                   LoadBalancerProvider provider,
                                   int appHttpPort,
                                   AtomicReference<LoadBalancerManagerState> state) implements LoadBalancerManager {
            private static final Logger log = LoggerFactory.getLogger(loadBalancerManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Node {} became leader, activating load balancer manager", self);
                    var activeState = new LoadBalancerManagerState.Active(provider,
                                                                          topologyManager,
                                                                          kvStore,
                                                                          appHttpPort,
                                                                          ConcurrentHashMap.newKeySet(),
                                                                          new ConcurrentHashMap<>());
                    state.set(activeState);
                    activeState.reconcile();
                } else {
                    log.info("Node {} is not leader, deactivating load balancer manager", self);
                    state.set(new LoadBalancerManagerState.Dormant());
                }
            }

            @Override
            public void onRoutePut(ValuePut<HttpNodeRouteKey, HttpNodeRouteValue> valuePut) {
                state.get()
                     .onRoutePut(valuePut);
            }

            @Override
            public void onRouteRemove(ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue> valueRemove) {
                state.get()
                     .onRouteRemove(valueRemove);
            }

            @Override
            public void onTopologyChange(TopologyChangeNotification topologyChange) {
                state.get()
                     .onTopologyChange(topologyChange);
            }
        }
        return new loadBalancerManager(self,
                                       kvStore,
                                       topologyManager,
                                       provider,
                                       appHttpPort,
                                       new AtomicReference<>(new LoadBalancerManagerState.Dormant()));
    }
}
