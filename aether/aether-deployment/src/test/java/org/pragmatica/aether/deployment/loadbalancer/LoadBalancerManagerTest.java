// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.loadbalancer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalancerManagerTest {

    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    private NodeId selfNode;
    private NodeId node1;
    private NodeId node2;
    private RecordingLoadBalancerProvider provider;
    private RecordingTopologyManager topologyManager;
    private KVStore<AetherKey, AetherValue> kvStore;
    private LoadBalancerManager manager;

    @BeforeEach
    void setUp() {
        selfNode = NodeId.randomNodeId();
        node1 = NodeId.randomNodeId();
        node2 = NodeId.randomNodeId();
        provider = new RecordingLoadBalancerProvider();
        topologyManager = new RecordingTopologyManager();
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
        manager = LoadBalancerManager.loadBalancerManager(selfNode, kvStore, topologyManager, provider, 8080);
    }

    @Nested
    class DormantState {
        @Test
        void dormantState_onNodeRoutesPut_doesNothing() {
            fireNodeRoutesPut("GET", "/api/test", node1);

            assertThat(provider.routeChanges).isEmpty();
            assertThat(provider.reconcileCalls).isEmpty();
        }

        @Test
        void dormantState_onNodeRoutesRemove_doesNothing() {
            fireNodeRoutesRemove(node1);

            assertThat(provider.routeChanges).isEmpty();
        }

        @Test
        void dormantState_onMembershipDecision_doesNothing() {
            manager.onMembershipDecision(MembershipDecision.nodeRemoved(node1, List.of()));

            assertThat(provider.nodeRemovals).isEmpty();
        }
    }

    @Nested
    class LeaderActivation {
        @Test
        void leaderChange_becomingLeader_activatesAndReconciles() {
            // Pre-populate KVStore with a compound route entry
            var routeKey = NodeRoutesKey.nodeRoutesKey(node1, TEST_ARTIFACT);
            var routeValue = NodeRoutesValue.nodeRoutesValue(List.of(
                RouteEntry.activeRoute("GET", "/api/users/", "list")));
            kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(routeKey, routeValue))));

            // Register node1 in topology so IP resolution works
            topologyManager.register(node1, "10.0.0.1", 8080);

            // Become leader
            activateAsLeader();

            assertThat(provider.reconcileCalls).hasSize(1);
            var state = provider.reconcileCalls.getFirst();
            assertThat(state.activeNodeIps()).contains("10.0.0.1");
            assertThat(state.routes()).hasSize(1);
        }

        @Test
        void leaderChange_losingLeadership_deactivates() {
            topologyManager.register(node1, "10.0.0.1", 8080);
            activateAsLeader();
            provider.clear();

            // Lose leadership
            manager.deactivate().await();

            // Subsequent events should be no-ops
            fireNodeRoutesPut("GET", "/api/test", node1);

            assertThat(provider.routeChanges).isEmpty();
        }
    }

    @Nested
    class ActiveState {
        @BeforeEach
        void activateManager() {
            topologyManager.register(node1, "10.0.0.1", 8080);
            topologyManager.register(node2, "10.0.0.2", 8080);
            activateAsLeader();
            provider.clear();
        }

        @Test
        void activeState_onNodeRoutesPut_callsProviderRouteChanged() {
            fireNodeRoutesPut("POST", "/api/orders/", node1);
            fireNodeRoutesPut("POST", "/api/orders/", node2);

            // Should have 2 notifications (one per put)
            assertThat(provider.routeChanges).hasSize(2);
            var lastChange = provider.routeChanges.getLast();
            assertThat(lastChange.httpMethod()).isEqualTo("POST");
            assertThat(lastChange.pathPrefix()).isEqualTo("/api/orders/");
            assertThat(lastChange.nodeIps()).containsExactlyInAnyOrder("10.0.0.1", "10.0.0.2");
        }

        @Test
        void activeState_onNodeRemoved_callsProviderNodeRemoved() {
            // First, trigger a route put so that node1's IP is tracked
            fireNodeRoutesPut("GET", "/api/test/", node1);
            provider.clear();

            // Remove node1
            manager.onMembershipDecision(MembershipDecision.nodeRemoved(node1, List.of(node2)));

            assertThat(provider.nodeRemovals).containsExactly("10.0.0.1");
        }

        @Test
        void workerDeparture_afterTopologyPruning_removesPreviouslyTrackedAddress() {
            fireNodeRoutesPut("GET", "/api/test/", node1);
            provider.clear();
            topologyManager.nodes.remove(node1);
            manager.onNodeDeparture(node1);
            assertThat(provider.nodeRemovals).containsExactly("10.0.0.1");
        }

        @Test
        void activeState_onNodeRoutesRemove_callsProviderWithUpdatedNodeSet() {
            // Add routes for two nodes
            fireNodeRoutesPut("DELETE", "/api/items/", node1);
            fireNodeRoutesPut("DELETE", "/api/items/", node2);
            provider.clear();

            // Remove node1's routes
            fireNodeRoutesRemove(node1);

            // Should still have node2
            assertThat(provider.routeChanges).hasSize(1);
            var change = provider.routeChanges.getFirst();
            assertThat(change.httpMethod()).isEqualTo("DELETE");
            assertThat(change.pathPrefix()).isEqualTo("/api/items/");
            assertThat(change.nodeIps()).containsExactly("10.0.0.2");
        }

        @Test
        void activeState_onNodeRoutesRemove_lastNode_callsProviderWithEmptyNodeIps() {
            fireNodeRoutesPut("GET", "/api/gone/", node1);
            provider.clear();

            fireNodeRoutesRemove(node1);

            assertThat(provider.routeChanges).hasSize(1);
            var change = provider.routeChanges.getFirst();
            assertThat(change.httpMethod()).isEqualTo("GET");
            assertThat(change.pathPrefix()).isEqualTo("/api/gone/");
            assertThat(change.nodeIps()).isEmpty();
        }
    }

    /// #1314: a `NodeRoutesKey` names one (node, artifact) contribution. Its removal takes away that contribution
    /// only, and its put REPLACES it. Before, removal dropped the node from every route it served, whatever the
    /// artifact, and a put only ever added.
    @Nested
    class ContributionScope {
        private static final Artifact ORDERS = Artifact.artifact("com.example:orders:1.0.0").unwrap();
        private static final Artifact USERS = Artifact.artifact("com.example:users:1.0.0").unwrap();
        private static final Artifact PROBE = Artifact.artifact("com.example:probe:1.0.0").unwrap();

        @BeforeEach
        void activateManager() {
            topologyManager.register(node1, "10.0.0.1", 8080);
            topologyManager.register(node2, "10.0.0.2", 8080);
            activateAsLeader();
            provider.clear();
        }

        @Test
        void onNodeRoutesRemove_oneArtifactOfTwoOnANode_theOtherArtifactKeepsTheNode() {
            manager.onNodeRoutesPut(put(node1, ORDERS, route("GET", "/orders/")));
            manager.onNodeRoutesPut(put(node1, USERS, route("GET", "/users/")));
            provider.clear();

            manager.onNodeRoutesRemove(remove(node1, ORDERS));

            assertThat(provider.routeChanges)
                    .as("only the removed artifact's route is announced")
                    .extracting(RouteChange::pathPrefix)
                    .containsExactly("/orders/");
            assertThat(provider.routeChanges.getFirst().nodeIps()).isEmpty();
            assertThat(reconciledNodeIps("GET", "/users/")).containsExactly("10.0.0.1");
        }

        @Test
        void onNodeRoutesPut_replacementValue_subtractsOmittedRoutes_andAddsNewOnes() {
            manager.onNodeRoutesPut(put(node1, ORDERS, route("GET", "/orders/"), route("POST", "/orders/")));
            provider.clear();

            manager.onNodeRoutesPut(put(node1, ORDERS, route("GET", "/orders/"), route("GET", "/invoices/")));

            assertThat(lastChange("POST", "/orders/").nodeIps())
                    .as("a route the replacement omits must be withdrawn")
                    .isEmpty();
            assertThat(lastChange("GET", "/invoices/").nodeIps()).containsExactly("10.0.0.1");
            assertThat(reconciledNodeIps("POST", "/orders/")).isEmpty();
            assertThat(reconciledNodeIps("GET", "/orders/")).containsExactly("10.0.0.1");
        }

        @Test
        void sharedRouteNodePair_staysUntilTheLastContributionIsRemoved() {
            manager.onNodeRoutesPut(put(node1, ORDERS, route("GET", "/shared/")));
            manager.onNodeRoutesPut(put(node1, USERS, route("GET", "/shared/")));
            manager.onNodeRoutesPut(put(node2, USERS, route("GET", "/shared/")));
            provider.clear();

            manager.onNodeRoutesRemove(remove(node1, ORDERS));
            assertThat(lastChange("GET", "/shared/").nodeIps()).containsExactlyInAnyOrder("10.0.0.1", "10.0.0.2");

            manager.onNodeRoutesRemove(remove(node1, USERS));
            assertThat(lastChange("GET", "/shared/").nodeIps()).containsExactly("10.0.0.2");
        }

        @Test
        void repeatedPutsAndRemoves_areIdempotent() {
            var orders = put(node1, ORDERS, route("GET", "/orders/"));
            var users = put(node1, USERS, route("GET", "/users/"));

            manager.onNodeRoutesPut(orders);
            manager.onNodeRoutesPut(orders);
            manager.onNodeRoutesPut(users);
            manager.onNodeRoutesPut(users);
            manager.onNodeRoutesRemove(remove(node1, USERS));
            provider.clear();
            manager.onNodeRoutesRemove(remove(node1, USERS));

            assertThat(provider.routeChanges).as("a repeated remove changes nothing").isEmpty();
            assertThat(reconciledNodeIps("GET", "/orders/")).containsExactly("10.0.0.1");
            assertThat(reconciledNodeIps("GET", "/users/")).isEmpty();
        }

        /// The nodes the manager currently holds for a route, read through its public surface: a probe node
        /// contributes the route under its own key, the announced change lists every serving node, and the probe's
        /// contribution is withdrawn again.
        private Set<String> reconciledNodeIps(String method, String path) {
            var probeNode = NodeId.randomNodeId();

            topologyManager.register(probeNode, "10.0.0.99", 8080);
            var before = provider.routeChanges.size();

            manager.onNodeRoutesPut(put(probeNode, PROBE, route(method, path)));
            var served = new java.util.HashSet<>(provider.routeChanges.get(provider.routeChanges.size() - 1).nodeIps());

            manager.onNodeRoutesRemove(remove(probeNode, PROBE));
            provider.routeChanges.subList(before, provider.routeChanges.size()).clear();
            served.remove("10.0.0.99");

            return served;
        }

        private RouteChange lastChange(String method, String path) {
            return provider.routeChanges.stream()
                                        .filter(change -> change.httpMethod().equals(method) && change.pathPrefix().equals(path))
                                        .reduce((first, second) -> second)
                                        .orElseThrow(() -> new AssertionError("no route change for " + method + " " + path));
        }
    }

    /// Theme F drain-and-subscribe race fix — the receiver path must buffer events
    /// fired during `reconcile()`'s `forEach` drain and replay them exactly once.
    @Nested
    class DrainAndSubscribeRace {
        @Test
        void midDrainPut_isAppliedExactlyOnceAfterReconcile() {
            topologyManager.register(node1, "10.0.0.1", 8080);
            topologyManager.register(node2, "10.0.0.2", 8080);

            // Pre-seed kvStore with node1's route. node2's route will be injected
            // mid-drain via a stub kvStore wrapper.
            var routeKey1 = NodeRoutesKey.nodeRoutesKey(node1, TEST_ARTIFACT);
            var routeValue1 = NodeRoutesValue.nodeRoutesValue(List.of(
                RouteEntry.activeRoute("GET", "/api/v1/", "list")));
            kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(routeKey1, routeValue1))));

            var midDrainKvStore = new MidDrainKvStore(kvStore);
            var midDrainManager = LoadBalancerManager.loadBalancerManager(selfNode,
                                                                           midDrainKvStore,
                                                                           topologyManager,
                                                                           provider,
                                                                           8080);
            midDrainKvStore.armMidDrainEvent(midDrainManager,
                                              putEvent(node2, "GET", "/api/v1/"));
            midDrainManager.activate().await();

            // The mid-drain event must be visible in the final state. node1 came from
            // forEach, node2 came from the buffered receiver event.
            assertThat(provider.reconcileCalls).hasSize(1);
            var finalState = provider.reconcileCalls.getFirst();
            assertThat(finalState.activeNodeIps())
                .containsExactlyInAnyOrder("10.0.0.1", "10.0.0.2");

            // The buffered event was applied during replay, not lost.
            assertThat(midDrainKvStore.midDrainFired).isTrue();
        }

        private ValuePut<NodeRoutesKey, NodeRoutesValue> putEvent(NodeId nodeId,
                                                                  String method,
                                                                  String path) {
            var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
            var route = RouteEntry.activeRoute(method, path, "list");
            var value = NodeRoutesValue.nodeRoutesValue(List.of(route));
            return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
        }
    }

    /// KVStore wrapper that fires a receiver event during `forEach`, simulating a
    /// notification arriving mid-snapshot-drain.
    private static final class MidDrainKvStore extends KVStore<AetherKey, AetherValue> {
        private final KVStore<AetherKey, AetherValue> delegate;
        private LoadBalancerManager manager;
        private ValuePut<NodeRoutesKey, NodeRoutesValue> midDrainEvent;
        boolean midDrainFired;

        MidDrainKvStore(KVStore<AetherKey, AetherValue> delegate) {
            super(null, null, null);
            this.delegate = delegate;
        }

        void armMidDrainEvent(LoadBalancerManager manager,
                              ValuePut<NodeRoutesKey, NodeRoutesValue> event) {
            this.manager = manager;
            this.midDrainEvent = event;
        }

        @Override
        public <KK, VV> void forEach(Class<KK> keyClass,
                                      Class<VV> valueClass,
                                      java.util.function.BiConsumer<KK, VV> consumer) {
            delegate.forEach(keyClass, valueClass, consumer);
            if (manager != null && midDrainEvent != null && !midDrainFired) {
                midDrainFired = true;
                manager.onNodeRoutesPut(midDrainEvent);
            }
        }
    }

    // === Helpers ===

    /// No-op serializer: these tests apply commands to the KV store directly (not via the
    /// consensus dedup path), so the content-based batch id is irrelevant — an empty encoding
    /// is sufficient to satisfy `StateMachine.createBatch`.
    private static org.pragmatica.serialization.Serializer noopSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    /// #1314 on the restart / leader-change path (v1882): contributions REBUILT from KV by reconcile, then one
    /// artifact's key is removed. The node must keep the route it still serves through the other artifact.
    @Test
    void reconcileFromKv_thenRemoveOneArtifact_keepsTheOtherArtifactsRoute() {
        var orders = org.pragmatica.aether.artifact.Artifact.artifact("com.example:orders:1.0.0").unwrap();
        var users = org.pragmatica.aether.artifact.Artifact.artifact("com.example:users:1.0.0").unwrap();
        kvStore.process(kvStore.createBatch(List.of(
            new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node1, orders),
                                NodeRoutesValue.nodeRoutesValue(List.of(RouteEntry.activeRoute("GET", "/shared/", "m"),
                                                                        RouteEntry.activeRoute("GET", "/orders/", "m")))),
            new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node1, users),
                                NodeRoutesValue.nodeRoutesValue(List.of(RouteEntry.activeRoute("GET", "/shared/", "m")))))));
        topologyManager.register(node1, "10.0.0.1", 8080);
        activateAsLeader();
        assertThat(provider.reconcileCalls).as("arming: activation reconciled from KV").hasSize(1);
        provider.clear();

        manager.onNodeRoutesRemove(remove(node1, orders));

        var shared = provider.routeChanges.stream().filter(c -> c.pathPrefix().equals("/shared/")).toList();
        var ordersChanges = provider.routeChanges.stream().filter(c -> c.pathPrefix().equals("/orders/")).toList();
        assertThat(ordersChanges).as("arming: the removed artifact's own route is announced").isNotEmpty();
        assertThat(ordersChanges.getLast().nodeIps()).as("/orders/ is withdrawn").isEmpty();
        assertThat(shared.stream().allMatch(c -> c.nodeIps().contains("10.0.0.1")))
            .as("/shared/ is never announced without node1, which still serves it through USERS: " + shared).isTrue();
    }

    private void activateAsLeader() {
        manager.activate().await();
    }

    private void fireNodeRoutesPut(String method, String path, NodeId nodeId) {
        var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
        var route = RouteEntry.activeRoute(method, path, "create");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route));
        var command = new KVCommand.Put<>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onNodeRoutesPut(notification);
    }

    private static RouteEntry route(String method, String path) {
        return RouteEntry.activeRoute(method, path, "handle");
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, Artifact artifact, RouteEntry... routes) {
        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, artifact),
                                                  NodeRoutesValue.nodeRoutesValue(List.of(routes))),
                              Option.none());
    }

    private static ValueRemove<NodeRoutesKey, NodeRoutesValue> remove(NodeId node, Artifact artifact) {
        return new ValueRemove<>(new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(node, artifact)), Option.none());
    }

    private void fireNodeRoutesRemove(NodeId nodeId) {
        var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
        var command = new KVCommand.Remove<NodeRoutesKey>(key);
        var notification = new ValueRemove<NodeRoutesKey, NodeRoutesValue>(command, Option.none());
        manager.onNodeRoutesRemove(notification);
    }

    // === Recording Stubs ===

    static class RecordingLoadBalancerProvider implements LoadBalancerProvider {
        final List<RouteChange> routeChanges = new ArrayList<>();
        final List<String> nodeRemovals = new ArrayList<>();
        final List<LoadBalancerState> reconcileCalls = new ArrayList<>();

        @Override
        public Promise<Unit> onRouteChanged(RouteChange routeChange) {
            routeChanges.add(routeChange);
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> onNodeRemoved(String nodeIp) {
            nodeRemovals.add(nodeIp);
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> reconcile(LoadBalancerState state) {
            reconcileCalls.add(state);
            return Promise.unitPromise();
        }

        void clear() {
            routeChanges.clear();
            nodeRemovals.clear();
            reconcileCalls.clear();
        }
    }

    static class RecordingTopologyManager implements TopologyManager {
        private final Map<NodeId, NodeInfo> nodes = new ConcurrentHashMap<>();

        void register(NodeId nodeId, String host, int port) {
            var address = NodeAddress.nodeAddress(host, port).unwrap();
            nodes.put(nodeId, NodeInfo.nodeInfo(nodeId, address));
        }

        @Override
        public NodeInfo self() {
            return nodes.values().iterator().next();
        }

        @Override
        public Option<NodeInfo> get(NodeId id) {
            return Option.option(nodes.get(id));
        }

        @Override
        public int clusterSize() {
            return nodes.size();
        }

        @Override
        public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
            return Option.none();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public TimeSpan pingInterval() {
            return TimeSpan.timeSpan(1000).millis();
        }

        @Override
        public TimeSpan helloTimeout() {
            return TimeSpan.timeSpan(5000).millis();
        }

        @Override
        public Option<TlsConfig> tls() {
            return Option.empty();
        }

        @Override
        public Option<org.pragmatica.consensus.topology.NodeState> getState(NodeId id) {
            return Option.none();
        }

        @Override
        public List<NodeId> topology() {
            return List.copyOf(nodes.keySet());
        }
    }
}
