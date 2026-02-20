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
@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks — void required by messaging framework
public interface LoadBalancerManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onRoutePut(ValuePut<HttpRouteKey, HttpRouteValue> valuePut);

    @MessageReceiver
    void onRouteRemove(ValueRemove<HttpRouteKey, HttpRouteValue> valueRemove);

    @MessageReceiver
    void onTopologyChange(TopologyChangeNotification topologyChange);

    sealed interface LoadBalancerManagerState {
        default void onRoutePut(ValuePut<HttpRouteKey, HttpRouteValue> valuePut) {}

        default void onRouteRemove(ValueRemove<HttpRouteKey, HttpRouteValue> valueRemove) {}

        default void onTopologyChange(TopologyChangeNotification topologyChange) {}

        record Dormant() implements LoadBalancerManagerState {}

        record Active(LoadBalancerProvider provider,
                      TopologyManager topologyManager,
                      KVStore<AetherKey, AetherValue> kvStore,
                      int appHttpPort,
                      Set<String> trackedNodeIps) implements LoadBalancerManagerState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override
            public void onRoutePut(ValuePut<HttpRouteKey, HttpRouteValue> valuePut) {
                handleRouteChange(valuePut.cause()
                                          .key(),
                                  valuePut.cause()
                                          .value());
            }

            @Override
            public void onRouteRemove(ValueRemove<HttpRouteKey, HttpRouteValue> valueRemove) {
                handleRouteRemoval(valueRemove.cause()
                                              .key());
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

            private void collectRouteForReconciliation(HttpRouteKey routeKey,
                                                       HttpRouteValue routeValue,
                                                       Set<String> allNodeIps,
                                                       List<RouteChange> routes) {
                var nodeIps = resolveNodeIps(routeValue.nodes());
                allNodeIps.addAll(nodeIps);
                if (!nodeIps.isEmpty()) {
                    routeChange(routeKey.httpMethod(),
                                routeKey.pathPrefix(),
                                nodeIps).onSuccess(routes::add)
                               .onFailure(cause -> log.warn("Failed to create route change for {} {}: {}",
                                                            routeKey.httpMethod(),
                                                            routeKey.pathPrefix(),
                                                            cause.message()));
                }
            }

            private void handleRouteRemoval(HttpRouteKey routeKey) {
                log.info("Route removed: {} {}", routeKey.httpMethod(), routeKey.pathPrefix());
                routeChange(routeKey.httpMethod(),
                            routeKey.pathPrefix(),
                            Set.of()).onSuccess(change -> provider.onRouteChanged(change)
                                                                  .onFailure(cause -> log.error("Failed to remove load balancer route {} {}: {}",
                                                                                                routeKey.httpMethod(),
                                                                                                routeKey.pathPrefix(),
                                                                                                cause.message())))
                           .onFailure(cause -> log.error("Failed to create route change for removal of {} {}: {}",
                                                         routeKey.httpMethod(),
                                                         routeKey.pathPrefix(),
                                                         cause.message()));
            }

            private void handleRouteChange(HttpRouteKey routeKey, HttpRouteValue routeValue) {
                var nodeIps = resolveNodeIps(routeValue.nodes());
                trackedNodeIps.addAll(nodeIps);
                log.debug("Route changed: {} {} -> {} nodes",
                          routeKey.httpMethod(),
                          routeKey.pathPrefix(),
                          nodeIps.size());
                routeChange(routeKey.httpMethod(),
                            routeKey.pathPrefix(),
                            nodeIps).onSuccess(change -> provider.onRouteChanged(change)
                                                                 .onFailure(cause -> log.error("Failed to update load balancer route {} {}: {}",
                                                                                               routeKey.httpMethod(),
                                                                                               routeKey.pathPrefix(),
                                                                                               cause.message())))
                           .onFailure(cause -> log.error("Failed to create route change for {} {}: {}",
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
            public void onRoutePut(ValuePut<HttpRouteKey, HttpRouteValue> valuePut) {
                state.get()
                     .onRoutePut(valuePut);
            }

            @Override
            public void onRouteRemove(ValueRemove<HttpRouteKey, HttpRouteValue> valueRemove) {
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
