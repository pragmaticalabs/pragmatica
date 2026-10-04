// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.loadbalancer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.environment.LoadBalancerState.loadBalancerState;
import static org.pragmatica.aether.environment.RouteChange.routeChange;


@SuppressWarnings("JBCT-RET-01")
public interface LoadBalancerManager {
    Promise<Unit> activate();
    Promise<Unit> deactivate();
    boolean isActive();

    @MessageReceiver
    void onMembershipDecision(MembershipDecision decision);

    org.pragmatica.lang.Unit onNodeDeparture(NodeId node);

    @MessageReceiver
    void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown);

    @MessageReceiver
    void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);

    @MessageReceiver
    void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove);

    sealed interface LoadBalancerManagerState {
        default void onMembershipDecision(MembershipDecision decision) {}

        default org.pragmatica.lang.Unit onNodeDeparture(NodeId node) {
            return org.pragmatica.lang.Unit.unit();
        }

        default void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {}

        default void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {}

        default void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {}

        record Dormant() implements LoadBalancerManagerState {}

        record Active(LoadBalancerProvider provider,
                      TopologyManager topologyManager,
                      KVStore<AetherKey, AetherValue> kvStore,
                      int appHttpPort,
                      Set<String> trackedNodeIps,
                      Map<NodeId, String> nodeAddresses,
                      Map<NodeRoutesKey, Set<String>> contributions,
                      AtomicBoolean activated,
                      ConcurrentLinkedDeque<PendingRouteEvent> pendingEvents,
                      ReentrantLock activationLock) implements LoadBalancerManagerState {
            private static final Logger log = LoggerFactory.getLogger(Active.class);

            @Override
            public void onMembershipDecision(MembershipDecision decision) {
                switch (decision) {
                    case NodeRemoved(NodeId removedNode, _, _, _) -> onNodeDeparture(removedNode);
                    case NodeDecommissioned(NodeId decommissioned, _, _, _) -> onNodeDeparture(decommissioned);
                    default -> {}
                }
            }

            @Override
            public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
                onNodeDeparture(selfShutdown.nodeId());
            }

            @Override
            public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
                if (activated.get()) {
                    applyPut(valuePut);

                    return;
                }

                activationLock.lock();
                try {
                    if (activated.get()) {
                        applyPut(valuePut);
                    } else {
                        pendingEvents.add(new PendingRouteEvent.Put(valuePut));
                    }
                } finally {
                    activationLock.unlock();
                }
            }

            @Override
            public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
                if (activated.get()) {
                    applyRemove(valueRemove);

                    return;
                }

                activationLock.lock();
                try {
                    if (activated.get()) {
                        applyRemove(valueRemove);
                    } else {
                        pendingEvents.add(new PendingRouteEvent.Remove(valueRemove));
                    }
                } finally {
                    activationLock.unlock();
                }
            }

            /// #1314: a put REPLACES its key's contribution, so a route the new value omits is subtracted, and every
            /// route either value names is re-announced from the aggregate.
            private void applyPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
                var key = valuePut.cause().key();
                var routeIdentities = routeIdentitiesOf(valuePut.cause().value());
                var affected = new LinkedHashSet<>(routeIdentities);

                Option.option(contributions.put(key, routeIdentities)).onPresent(affected::addAll);
                affected.forEach(this::announceRoute);
            }

            /// #1314: only the removed key's contribution goes. The node keeps every route it still serves from
            /// another artifact.
            private void applyRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
                Option.option(contributions.remove(valueRemove.cause().key()))
                      .onPresent(removed -> removed.forEach(this::announceRoute));
            }

            private void announceRoute(String routeIdentity) {
                var nodes = nodesServing(routeIdentity);
                var parts = routeIdentity.split(":", 2);

                if (nodes.isEmpty()) {
                    handleRouteRemoval(parts[0], parts[1]);
                } else {
                    handleRouteChange(parts[0], parts[1], nodes);
                }
            }

            private Set<NodeId> nodesServing(String routeIdentity) {
                return contributions.entrySet()
                                    .stream()
                                    .filter(entry -> entry.getValue()
                                                          .contains(routeIdentity))
                                    .map(entry -> entry.getKey()
                                                       .nodeId())
                                    .collect(Collectors.toSet());
            }

            private Map<String, Set<NodeId>> routeNodes() {
                var aggregated = new HashMap<String, Set<NodeId>>();

                contributions.forEach((key, identities) -> identities.forEach(identity -> aggregated.computeIfAbsent(identity,
                                                                                                                      _ -> new HashSet<>())
                                                                                                    .add(key.nodeId())));

                return aggregated;
            }

            void reconcile() {
                activationLock.lock();
                try {
                    var snapshot = new HashMap<NodeRoutesKey, Set<String>>();

                    kvStore.forEach(NodeRoutesKey.class,
                                    NodeRoutesValue.class,
                                    (key, value) -> snapshot.put(key, routeIdentitiesOf(value)));
                    contributions.clear();
                    contributions.putAll(snapshot);
                    replayPendingEvents();
                    activated.set(true);
                    var allNodeIps = new HashSet<String>();
                    var routes = new ArrayList<RouteChange>();

                    routeNodes().forEach((identity, nodeIds) -> collectRouteForReconciliation(identity,
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
                } finally {
                    activationLock.unlock();
                }
            }

            private void replayPendingEvents() {
                PendingRouteEvent event;

                while ((event = pendingEvents.poll()) != null) {
                    dispatchPending(event);
                }
            }

            private void dispatchPending(PendingRouteEvent event) {
                switch (event) {
                    case PendingRouteEvent.Put put -> applyPut(put.put());
                    case PendingRouteEvent.Remove remove -> applyRemove(remove.remove());
                }
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

            public org.pragmatica.lang.Unit onNodeDeparture(NodeId departedNode) {
                contributions.keySet().removeIf(key -> key.nodeId()
                                                          .equals(departedNode));
                org.pragmatica.lang.Option.option(nodeAddresses.remove(departedNode))
                                          .orElse(() -> topologyManager.get(departedNode)
                                                                       .map(NodeInfo::address)
                                                                       .map(address -> address.host()))
                                          .onPresent(this::removeNodeIp);

                return org.pragmatica.lang.Unit.unit();
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
                                                                .onPresent(ip -> nodeAddresses.put(nodeId, ip))
                                                                .stream())
                              .collect(Collectors.toSet());
            }

            private static Set<String> routeIdentitiesOf(NodeRoutesValue value) {
                return value.routes()
                            .stream()
                            .filter(RouteEntry::isRoutable)
                            .map(route -> route.httpMethod() + ":" + route.pathPrefix())
                            .collect(Collectors.toUnmodifiableSet());
            }
        }

        sealed interface PendingRouteEvent {
            record Put(ValuePut<NodeRoutesKey, NodeRoutesValue> put) implements PendingRouteEvent {}

            record Remove(ValueRemove<NodeRoutesKey, NodeRoutesValue> remove) implements PendingRouteEvent {}
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
            public Promise<Unit> activate() {
                log.info("Node {} became leader, activating load balancer manager", self);
                var activeState = new LoadBalancerManagerState.Active(provider,
                                                                      topologyManager,
                                                                      kvStore,
                                                                      appHttpPort,
                                                                      ConcurrentHashMap.newKeySet(),
                                                                      new ConcurrentHashMap<>(),
                                                                      new ConcurrentHashMap<>(),
                                                                      new AtomicBoolean(false),
                                                                      new ConcurrentLinkedDeque<>(),
                                                                      new ReentrantLock());

                state.set(activeState);
                activeState.reconcile();

                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> deactivate() {
                log.info("Node {} is not leader, deactivating load balancer manager", self);
                state.set(new LoadBalancerManagerState.Dormant());

                return Promise.unitPromise();
            }

            @Override
            public boolean isActive() {
                return state.get() instanceof LoadBalancerManagerState.Active;
            }

            @Override
            public void onMembershipDecision(MembershipDecision decision) {
                state.get().onMembershipDecision(decision);
            }

            @Override
            public org.pragmatica.lang.Unit onNodeDeparture(NodeId node) {
                state.get().onNodeDeparture(node);

                return org.pragmatica.lang.Unit.unit();
            }

            @Override
            public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {
                state.get().onSelfShutdown(selfShutdown);
            }

            @Override
            public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
                state.get().onNodeRoutesPut(valuePut);
            }

            @Override
            public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
                state.get().onNodeRoutesRemove(valueRemove);
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
