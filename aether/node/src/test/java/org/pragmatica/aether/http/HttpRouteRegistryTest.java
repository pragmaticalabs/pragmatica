package org.pragmatica.aether.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRouteRegistryTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();

    private HttpRouteRegistry registry;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
    }

    @Test
    void findRoute_returns_empty_when_no_routes() {
        assertThat(registry.findRoute("GET", "/users/123").isEmpty()).isTrue();
    }

    @Test
    void findRoute_returns_exact_match() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.httpMethod()).isEqualTo("GET");
                    assertThat(route.pathPrefix()).isEqualTo("/users/");
                    assertThat(route.nodes()).containsExactly(NODE_1);
                });
    }

    @Test
    void findRoute_returns_prefix_match() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registry.findRoute("GET", "/users/123")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/users/");
                });
    }

    @Test
    void findRoute_returns_prefix_match_with_nested_path() {
        registerNodeRoute("GET", "/api/v1/", NODE_1);
        registerNodeRoute("GET", "/api/v1/", NODE_2);
        registry.findRoute("GET", "/api/v1/users/123/orders")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/api/v1/");
                });
    }

    @Test
    void findRoute_returns_most_specific_prefix() {
        registerNodeRoute("GET", "/api/", NODE_1);
        registerNodeRoute("GET", "/api/v1/", NODE_1);
        registerNodeRoute("GET", "/api/v1/", NODE_2);
        registerNodeRoute("GET", "/api/v1/users/", NODE_2);
        // Most specific match should win
        registry.findRoute("GET", "/api/v1/users/123")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/api/v1/users/");
                    assertThat(route.nodes()).containsExactly(NODE_2);
                });
    }

    @Test
    void findRoute_returns_empty_when_no_prefix_match() {
        registerNodeRoute("GET", "/users/", NODE_1);
        assertThat(registry.findRoute("GET", "/orders/123").isEmpty()).isTrue();
    }

    @Test
    void findRoute_distinguishes_http_methods() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registerNodeRoute("POST", "/users/", NODE_2);
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> assertThat(route.nodes()).containsExactly(NODE_1));
        registry.findRoute("POST", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> assertThat(route.nodes()).containsExactly(NODE_2));
    }

    @Test
    void findRoute_is_case_insensitive_for_method() {
        registerNodeRoute("GET", "/users/", NODE_1);
        assertThat(registry.findRoute("get", "/users/").isPresent()).isTrue();
        assertThat(registry.findRoute("Get", "/users/").isPresent()).isTrue();
    }

    @Test
    void findRoute_normalizes_input_path() {
        registerNodeRoute("GET", "/users/", NODE_1);
        // Without leading slash
        assertThat(registry.findRoute("GET", "users/123").isPresent()).isTrue();
        // Without trailing slash
        assertThat(registry.findRoute("GET", "/users").isPresent()).isTrue();
    }

    @Test
    void onRouteRemove_removes_node_from_route() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registerNodeRoute("GET", "/users/", NODE_2);
        assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
        // Remove NODE_1
        unregisterNodeRoute("GET", "/users/", NODE_1);
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to still exist"); })
                .onPresent(route -> assertThat(route.nodes()).containsExactly(NODE_2));
    }

    @Test
    void onRouteRemove_removes_route_when_last_node() {
        registerNodeRoute("GET", "/users/", NODE_1);
        assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
        unregisterNodeRoute("GET", "/users/", NODE_1);
        assertThat(registry.findRoute("GET", "/users/").isEmpty()).isTrue();
    }

    @Test
    void allRoutes_returns_all_registered_routes() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registerNodeRoute("POST", "/orders/", NODE_2);
        var allRoutes = registry.allRoutes();
        assertThat(allRoutes).hasSize(2);
    }

    @Test
    void allRoutes_returns_empty_when_no_routes() {
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void route_aggregates_multiple_nodes() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registerNodeRoute("GET", "/users/", NODE_2);
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.nodes()).containsExactlyInAnyOrder(NODE_1, NODE_2);
                });
    }

    @Test
    void evictNode_removes_node_from_all_routes() {
        registerNodeRoute("GET", "/users/", NODE_1);
        registerNodeRoute("GET", "/users/", NODE_2);
        registerNodeRoute("POST", "/orders/", NODE_1);
        registry.evictNode(NODE_1);
        registry.findRoute("GET", "/users/")
                .onPresent(route -> assertThat(route.nodes()).containsExactly(NODE_2));
        assertThat(registry.findRoute("POST", "/orders/").isEmpty()).isTrue();
    }

    private void registerNodeRoute(String method, String path, NodeId nodeId) {
        var key = HttpNodeRouteKey.httpNodeRouteKey(method, path, nodeId);
        var value = HttpNodeRouteValue.httpNodeRouteValue("test:artifact:1.0", "create");
        var command = new KVCommand.Put<HttpNodeRouteKey, HttpNodeRouteValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        registry.onRoutePut(notification);
    }

    private void unregisterNodeRoute(String method, String path, NodeId nodeId) {
        var key = HttpNodeRouteKey.httpNodeRouteKey(method, path, nodeId);
        var command = new KVCommand.Remove<HttpNodeRouteKey>(key);
        var notification = new ValueRemove<HttpNodeRouteKey, HttpNodeRouteValue>(command, Option.none());
        registry.onRouteRemove(notification);
    }
}
