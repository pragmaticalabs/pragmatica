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
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
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
        kvStore = new KVStore<>(router, null, null);
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
        void dormantState_onTopologyChange_doesNothing() {
            manager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node1, List.of()));

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
            kvStore.process(new KVCommand.Put<>(routeKey, routeValue));

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
            manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(node2), false));

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
            manager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node1, List.of(node2)));

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

    // === Helpers ===

    private void activateAsLeader() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.some(selfNode), true));
    }

    private void fireNodeRoutesPut(String method, String path, NodeId nodeId) {
        var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
        var route = RouteEntry.activeRoute(method, path, "create");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route));
        var command = new KVCommand.Put<>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onNodeRoutesPut(notification);
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
