package org.pragmatica.aether.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Set;

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
        registerRoute("GET", "/users/", Set.of(NODE_1));
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
        registerRoute("GET", "/users/", Set.of(NODE_1));
        registry.findRoute("GET", "/users/123")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/users/");
                });
    }

    @Test
    void findRoute_returns_prefix_match_with_nested_path() {
        registerRoute("GET", "/api/v1/", Set.of(NODE_1, NODE_2));
        registry.findRoute("GET", "/api/v1/users/123/orders")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/api/v1/");
                });
    }

    @Test
    void findRoute_returns_most_specific_prefix() {
        registerRoute("GET", "/api/", Set.of(NODE_1));
        registerRoute("GET", "/api/v1/", Set.of(NODE_1, NODE_2));
        registerRoute("GET", "/api/v1/users/", Set.of(NODE_2));
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
        registerRoute("GET", "/users/", Set.of(NODE_1));
        assertThat(registry.findRoute("GET", "/orders/123").isEmpty()).isTrue();
    }

    @Test
    void findRoute_distinguishes_http_methods() {
        registerRoute("GET", "/users/", Set.of(NODE_1));
        registerRoute("POST", "/users/", Set.of(NODE_2));
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> assertThat(route.nodes()).containsExactly(NODE_1));
        registry.findRoute("POST", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> assertThat(route.nodes()).containsExactly(NODE_2));
    }

    @Test
    void findRoute_is_case_insensitive_for_method() {
        registerRoute("GET", "/users/", Set.of(NODE_1));
        assertThat(registry.findRoute("get", "/users/").isPresent()).isTrue();
        assertThat(registry.findRoute("Get", "/users/").isPresent()).isTrue();
    }

    @Test
    void findRoute_normalizes_input_path() {
        registerRoute("GET", "/users/", Set.of(NODE_1));
        // Without leading slash
        assertThat(registry.findRoute("GET", "users/123").isPresent()).isTrue();
        // Without trailing slash
        assertThat(registry.findRoute("GET", "/users").isPresent()).isTrue();
    }

    @Test
    void onRouteRemove_removes_route() {
        registerRoute("GET", "/users/", Set.of(NODE_1));
        assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
        unregisterRoute("GET", "/users/");
        assertThat(registry.findRoute("GET", "/users/").isEmpty()).isTrue();
    }

    @Test
    void allRoutes_returns_all_registered_routes() {
        registerRoute("GET", "/users/", Set.of(NODE_1));
        registerRoute("POST", "/orders/", Set.of(NODE_2));
        var allRoutes = registry.allRoutes();
        assertThat(allRoutes).hasSize(2);
    }

    @Test
    void allRoutes_returns_empty_when_no_routes() {
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void route_can_be_updated() {
        registerRoute("GET", "/users/", Set.of(NODE_1));
        registerRoute("GET", "/users/", Set.of(NODE_1, NODE_2));
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.nodes()).containsExactlyInAnyOrder(NODE_1, NODE_2);
                });
    }

    private void registerRoute(String method, String path, Set<NodeId> nodes) {
        var key = HttpRouteKey.httpRouteKey(method, path);
        var value = HttpRouteValue.httpRouteValue(nodes);
        var command = new KVCommand.Put<HttpRouteKey, HttpRouteValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        registry.onRoutePut(notification);
    }

    private void unregisterRoute(String method, String path) {
        var key = HttpRouteKey.httpRouteKey(method, path);
        var command = new KVCommand.Remove<HttpRouteKey>(key);
        var notification = new ValueRemove<HttpRouteKey, HttpRouteValue>(command, Option.none());
        registry.onRouteRemove(notification);
    }
}
