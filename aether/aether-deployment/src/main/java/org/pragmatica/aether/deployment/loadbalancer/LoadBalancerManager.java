package org.pragmatica.aether.deployment.loadbalancer;

import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown;
import org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
public interface LoadBalancerManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    sealed interface LoadBalancerManagerState {
        default void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {}

        default void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {}

        default void onTopologyChange(TopologyChangeNotification topologyChange) {}

        record Dormant() implements LoadBalancerManagerState {}

        record Active(LoadBalancerProvider provider,
                      TopologyManager topologyManager,
                      KVStore<AetherKey, AetherValue> kvStore,
                      int appHttpPort,
                      Set<String> trackedNodeIps) implements LoadBalancerManagerState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                if (key instanceof HttpRouteKey routeKey && value instanceof HttpRouteValue routeValue) {
                    handleRouteChange(routeKey, routeValue);
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
                kvStore.forEach(HttpRouteKey.class,
                                HttpRouteValue.class,
                                (key, value) -> collectRouteForReconciliation(key, value, allNodeIps, routes));
                trackedNodeIps.clear();
                trackedNodeIps.addAll(allNodeIps);
                var state = loadBalancerState(allNodeIps, routes);
                log.info("Reconciling load balancer: {} routes, {} node IPs", routes.size(), allNodeIps.size());
                provider.reconcile(state)
                        .onFailure(cause -> log.error("Load balancer reconciliation failed: {}",
                                                      cause.message()));
            }

            private void collectRouteForReconciliation(HttpRouteKey routeKey,
                                                       HttpRouteValue routeValue,
                                                       Set<String> allNodeIps,
                                                       List<RouteChange> routes) {
                var nodeIps = resolveNodeIps(routeValue.nodes());
                allNodeIps.addAll(nodeIps);
                if (!nodeIps.isEmpty()) {
                    routes.add(routeChange(routeKey.httpMethod(), routeKey.pathPrefix(), nodeIps));
                }
            }

            private void handleRouteChange(HttpRouteKey routeKey, HttpRouteValue routeValue) {
                var nodeIps = resolveNodeIps(routeValue.nodes());
                trackedNodeIps.addAll(nodeIps);
                var change = routeChange(routeKey.httpMethod(), routeKey.pathPrefix(), nodeIps);
                log.debug("Route changed: {} {} -> {} nodes",
                          routeKey.httpMethod(),
                          routeKey.pathPrefix(),
                          nodeIps.size());
                provider.onRouteChanged(change)
                        .onFailure(cause -> log.error("Failed to update load balancer route {} {}: {}",
                                                      routeKey.httpMethod(),
                                                      routeKey.pathPrefix(),
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
                                                                          ConcurrentHashMap.newKeySet());
                    state.set(activeState);
                    activeState.reconcile();
                } else {
                    log.info("Node {} is not leader, deactivating load balancer manager", self);
                    state.set(new LoadBalancerManagerState.Dormant());
                }
            }

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                state.get()
                     .onValuePut(valuePut);
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                state.get()
                     .onValueRemove(valueRemove);
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
