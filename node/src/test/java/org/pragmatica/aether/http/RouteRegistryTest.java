package org.pragmatica.aether.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RouteValue;
import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.aether.slice.routing.BindingSource;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteRegistryTest {

    private RouteRegistry registry;
    private Artifact testArtifact;
    private TestClusterNode testCluster;
    private TestKVStore testKvStore;

    @BeforeEach
    void setUp() {
        testCluster = new TestClusterNode();
        testKvStore = new TestKVStore();
        registry = RouteRegistry.routeRegistry(testCluster, testKvStore);
        testArtifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    // === Registration Tests ===

    @Test
    void registry_starts_empty() {
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void onValuePut_adds_route_to_cache() {
        var routeKey = RouteKey.routeKey("GET", "/api/users/{id}");
        var routeValue = new RouteValue(
            testArtifact,
            "getUser",
            "GET",
            "/api/users/{id}",
            List.of(new Binding("id", new BindingSource.PathVar("id")))
        );

        var valuePut = new ValuePut<AetherKey, AetherValue>(
            new KVCommand.Put<>(routeKey, routeValue),
            Option.none()
        );

        registry.onValuePut(valuePut);

        assertThat(registry.allRoutes()).hasSize(1);
        assertThat(registry.allRoutes().getFirst().value()).isEqualTo(routeValue);
    }

    @Test
    void onValueRemove_removes_route_from_cache() {
        var routeKey = RouteKey.routeKey("GET", "/api/users");
        var routeValue = new RouteValue(
            testArtifact,
            "listUsers",
            "GET",
            "/api/users",
            List.of()
        );

        // Add route
        registry.onValuePut(new ValuePut<>(
            new KVCommand.Put<>(routeKey, routeValue),
            Option.none()
        ));

        assertThat(registry.allRoutes()).hasSize(1);

        // Remove route
        registry.onValueRemove(new ValueRemove<>(
            new KVCommand.Remove<>(routeKey),
            Option.some(routeValue)
        ));

        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void multiple_routes_can_be_registered() {
        registerRoute("GET", "/api/users", "listUsers");
        registerRoute("POST", "/api/users", "createUser");
        registerRoute("GET", "/api/orders", "listOrders");

        assertThat(registry.allRoutes()).hasSize(3);
    }

    // === Matching Tests ===

    @Test
    void match_returns_matching_route() {
        registerRoute("GET", "/api/users/{id}", "getUser",
            List.of(new Binding("id", new BindingSource.PathVar("id"))));

        var result = registry.match(HttpMethod.GET, "/api/users/123");

        assertThat(result.isPresent()).isTrue();
        result.onPresent(match -> {
            assertThat(match.pathVariables()).containsEntry("id", "123");
        });
    }

    @Test
    void match_returns_empty_for_no_match() {
        registerRoute("GET", "/api/users", "listUsers");

        var result = registry.match(HttpMethod.GET, "/nonexistent");

        assertThat(result.isPresent()).isFalse();
    }

    @Test
    void match_distinguishes_methods() {
        registerRoute("GET", "/api/users", "listUsers");
        registerRoute("POST", "/api/users", "createUser");

        var getResult = registry.match(HttpMethod.GET, "/api/users");
        var postResult = registry.match(HttpMethod.POST, "/api/users");

        assertThat(getResult.isPresent()).isTrue();
        assertThat(postResult.isPresent()).isTrue();

        getResult.onPresent(match ->
            assertThat(match.route().target().methodName()).isEqualTo("listUsers"));
        postResult.onPresent(match ->
            assertThat(match.route().target().methodName()).isEqualTo("createUser"));
    }

    @Test
    void match_extracts_path_variables() {
        registerRoute("GET", "/api/orders/{orderId}/items/{itemId}", "getOrderItem",
            List.of(
                new Binding("orderId", new BindingSource.PathVar("orderId")),
                new Binding("itemId", new BindingSource.PathVar("itemId"))
            ));

        var result = registry.match(HttpMethod.GET, "/api/orders/123/items/456");

        assertThat(result.isPresent()).isTrue();
        result.onPresent(match -> {
            assertThat(match.pathVariables()).containsEntry("orderId", "123");
            assertThat(match.pathVariables()).containsEntry("itemId", "456");
        });
    }

    // === Idempotent Registration Tests ===

    @Test
    void register_new_route_submits_to_consensus() {
        registry.register(
            testArtifact,
            "handleOrder",
            "POST",
            "/api/orders",
            List.of()
        ).await()
         .onFailureRun(Assertions::fail);

        assertThat(testCluster.appliedCommands).hasSize(1);
    }

    @Test
    void register_existing_route_with_same_target_succeeds_without_consensus() {
        // Add route via notification
        registerRoute("POST", "/api/orders", "handleOrder");

        int commandsBefore = testCluster.appliedCommands.size();

        // Try to register the same route
        registry.register(
            testArtifact,
            "handleOrder",
            "POST",
            "/api/orders",
            List.of()
        ).await()
         .onFailureRun(Assertions::fail);

        // No new consensus commands should have been issued
        assertThat(testCluster.appliedCommands).hasSize(commandsBefore);
    }

    @Test
    void register_conflicting_route_fails() {
        // Add route via notification
        registerRoute("POST", "/api/orders", "existingHandler");

        // Try to register with different handler
        registry.register(
            testArtifact,
            "differentHandler",
            "POST",
            "/api/orders",
            List.of()
        ).await()
         .onSuccessRun(Assertions::fail)
         .onFailure(cause -> {
             assertThat(cause).isInstanceOf(RouteRegistry.RouteRegistryError.RouteConflict.class);
             assertThat(cause.message()).contains("already registered");
         });
    }

    // === Non-Route Events Ignored ===

    @Test
    void ignores_non_route_key_put() {
        var endpointKey = new AetherKey.EndpointKey(
            testArtifact,
            org.pragmatica.aether.slice.MethodName.methodName("test").unwrap(),
            1
        );
        var endpointValue = new AetherValue.EndpointValue(
            org.pragmatica.consensus.NodeId.randomNodeId()
        );

        registry.onValuePut(new ValuePut<>(
            new KVCommand.Put<>(endpointKey, endpointValue),
            Option.none()
        ));

        assertThat(registry.allRoutes()).isEmpty();
    }

    // === Helper Methods ===

    private void registerRoute(String method, String pattern, String methodName) {
        registerRoute(method, pattern, methodName, List.of());
    }

    private void registerRoute(String method, String pattern, String methodName, List<Binding> bindings) {
        var routeKey = RouteKey.routeKey(method, pattern);
        var routeValue = new RouteValue(testArtifact, methodName, method, pattern, bindings);

        registry.onValuePut(new ValuePut<>(
            new KVCommand.Put<>(routeKey, routeValue),
            Option.none()
        ));
    }

    // === Test Doubles ===

    static class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        List<List<KVCommand<AetherKey>>> appliedCommands = new java.util.ArrayList<>();

        @Override
        public org.pragmatica.consensus.NodeId self() {
            return org.pragmatica.consensus.NodeId.randomNodeId();
        }

        @Override
        public Promise<org.pragmatica.lang.Unit> start() {
            return Promise.success(org.pragmatica.lang.Unit.unit());
        }

        @Override
        public Promise<org.pragmatica.lang.Unit> stop() {
            return Promise.success(org.pragmatica.lang.Unit.unit());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            appliedCommands.add(commands);
            return Promise.success((List<R>) commands.stream().map(_ -> Option.none()).toList());
        }
    }

    static class TestKVStore extends KVStore<AetherKey, AetherValue> {
        TestKVStore() {
            super(null, null, null);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.none();
        }
    }
}
