package org.pragmatica.aether.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRouteRegistryTest {
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
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.httpMethod()).isEqualTo("GET");
                    assertThat(route.pathPrefix()).isEqualTo("/users/");
                    assertThat(route.artifact()).isEqualTo("org.example:user-service:1.0.0");
                    assertThat(route.sliceMethod()).isEqualTo("getUsers");
                });
    }

    @Test
    void findRoute_returns_prefix_match() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        registry.findRoute("GET", "/users/123")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/users/");
                });
    }

    @Test
    void findRoute_returns_prefix_match_with_nested_path() {
        registerRoute("GET", "/api/v1/", "org.example:api-service:1.0.0", "handleApi");
        registry.findRoute("GET", "/api/v1/users/123/orders")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/api/v1/");
                });
    }

    @Test
    void findRoute_returns_most_specific_prefix() {
        registerRoute("GET", "/api/", "org.example:api-service:1.0.0", "handleApi");
        registerRoute("GET", "/api/v1/", "org.example:api-v1-service:1.0.0", "handleApiV1");
        registerRoute("GET", "/api/v1/users/", "org.example:user-service:1.0.0", "handleUsers");
        // Most specific match should win
        registry.findRoute("GET", "/api/v1/users/123")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.pathPrefix()).isEqualTo("/api/v1/users/");
                    assertThat(route.artifact()).isEqualTo("org.example:user-service:1.0.0");
                });
    }

    @Test
    void findRoute_returns_empty_when_no_prefix_match() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        assertThat(registry.findRoute("GET", "/orders/123").isEmpty()).isTrue();
    }

    @Test
    void findRoute_distinguishes_http_methods() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        registerRoute("POST", "/users/", "org.example:user-service:1.0.0", "createUser");
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> assertThat(route.sliceMethod()).isEqualTo("getUsers"));
        registry.findRoute("POST", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> assertThat(route.sliceMethod()).isEqualTo("createUser"));
    }

    @Test
    void findRoute_is_case_insensitive_for_method() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        assertThat(registry.findRoute("get", "/users/").isPresent()).isTrue();
        assertThat(registry.findRoute("Get", "/users/").isPresent()).isTrue();
    }

    @Test
    void findRoute_normalizes_input_path() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        // Without leading slash
        assertThat(registry.findRoute("GET", "users/123").isPresent()).isTrue();
        // Without trailing slash
        assertThat(registry.findRoute("GET", "/users").isPresent()).isTrue();
    }

    @Test
    void onValueRemove_removes_route() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
        unregisterRoute("GET", "/users/");
        assertThat(registry.findRoute("GET", "/users/").isEmpty()).isTrue();
    }

    @Test
    void allRoutes_returns_all_registered_routes() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        registerRoute("POST", "/orders/", "org.example:order-service:1.0.0", "createOrder");
        var allRoutes = registry.allRoutes();
        assertThat(allRoutes).hasSize(2);
    }

    @Test
    void allRoutes_returns_empty_when_no_routes() {
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void route_can_be_updated() {
        registerRoute("GET", "/users/", "org.example:user-service:1.0.0", "getUsers");
        registerRoute("GET", "/users/", "org.example:user-service:2.0.0", "getUsersV2");
        registry.findRoute("GET", "/users/")
                .onEmpty(() -> { throw new AssertionError("Expected route to be found"); })
                .onPresent(route -> {
                    assertThat(route.artifact()).isEqualTo("org.example:user-service:2.0.0");
                    assertThat(route.sliceMethod()).isEqualTo("getUsersV2");
                });
    }

    private void registerRoute(String method, String path, String artifact, String sliceMethod) {
        var key = HttpRouteKey.httpRouteKey(method, path);
        var value = HttpRouteValue.httpRouteValue(artifact, sliceMethod);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        registry.onValuePut(notification);
    }

    private void unregisterRoute(String method, String path) {
        var key = HttpRouteKey.httpRouteKey(method, path);
        var command = new KVCommand.Remove<AetherKey>(key);
        var notification = new ValueRemove<AetherKey, AetherValue>(command, Option.none());
        registry.onValueRemove(notification);
    }
}
