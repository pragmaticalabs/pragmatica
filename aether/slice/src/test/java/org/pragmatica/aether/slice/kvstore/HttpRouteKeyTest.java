package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRouteKeyTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();

    @Test
    void httpNodeRouteKey_from_string_succeeds() {
        HttpNodeRouteKey.httpNodeRouteKey("http-node-routes/GET:/users/:node-1")
                        .onSuccess(key -> {
                            assertThat(key.httpMethod()).isEqualTo("GET");
                            assertThat(key.pathPrefix()).isEqualTo("/users/");
                            assertThat(key.nodeId()).isEqualTo(NODE_1);
                        })
                        .onFailureRun(Assertions::fail);
    }

    @Test
    void httpNodeRouteKey_from_string_with_nested_path_succeeds() {
        HttpNodeRouteKey.httpNodeRouteKey("http-node-routes/POST:/api/v1/orders/:node-2")
                        .onSuccess(key -> {
                            assertThat(key.httpMethod()).isEqualTo("POST");
                            assertThat(key.pathPrefix()).isEqualTo("/api/v1/orders/");
                            assertThat(key.nodeId()).isEqualTo(NODE_2);
                        })
                        .onFailureRun(Assertions::fail);
    }

    @Test
    void httpNodeRouteKey_factory_normalizes_path() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "users", NODE_1);
        assertThat(key.pathPrefix()).isEqualTo("/users/");
    }

    @Test
    void httpNodeRouteKey_factory_normalizes_path_with_leading_slash() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("POST", "/orders", NODE_1);
        assertThat(key.pathPrefix()).isEqualTo("/orders/");
    }

    @Test
    void httpNodeRouteKey_factory_normalizes_path_with_trailing_slash() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("PUT", "items/", NODE_1);
        assertThat(key.pathPrefix()).isEqualTo("/items/");
    }

    @Test
    void httpNodeRouteKey_factory_preserves_already_normalized_path() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("DELETE", "/products/", NODE_1);
        assertThat(key.pathPrefix()).isEqualTo("/products/");
    }

    @Test
    void httpNodeRouteKey_factory_uppercases_method() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("get", "/users/", NODE_1);
        assertThat(key.httpMethod()).isEqualTo("GET");
    }

    @Test
    void httpNodeRouteKey_asString_produces_correct_format() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        assertThat(key.asString()).isEqualTo("http-node-routes/GET:/users/:node-1");
    }

    @Test
    void httpNodeRouteKey_roundtrip_consistency() {
        var originalKey = HttpNodeRouteKey.httpNodeRouteKey("POST", "/api/orders/", NODE_2);
        var keyString = originalKey.asString();
        HttpNodeRouteKey.httpNodeRouteKey(keyString)
                        .onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey))
                        .onFailureRun(Assertions::fail);
    }

    @Test
    void httpNodeRouteKey_from_string_with_invalid_prefix_fails() {
        HttpNodeRouteKey.httpNodeRouteKey("invalid/GET:/users/:node-1")
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-node-routes key format"));
    }

    @Test
    void httpNodeRouteKey_from_string_with_missing_colons_fails() {
        HttpNodeRouteKey.httpNodeRouteKey("http-node-routes/GET/users/node-1")
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-node-routes key format"));
    }

    @Test
    void httpNodeRouteKey_from_string_with_only_one_colon_fails() {
        HttpNodeRouteKey.httpNodeRouteKey("http-node-routes/GET:/users/")
                        .onSuccessRun(Assertions::fail)
                        .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-node-routes key format"));
    }

    @Test
    void httpNodeRouteKey_routeIdentity_returns_method_and_prefix() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        assertThat(key.routeIdentity()).isEqualTo("GET:/users/");
    }

    @Test
    void httpNodeRouteKey_equality_works() {
        var key1 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        var key2 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }

    @Test
    void httpNodeRouteKey_inequality_with_different_method() {
        var key1 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        var key2 = HttpNodeRouteKey.httpNodeRouteKey("POST", "/users/", NODE_1);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void httpNodeRouteKey_inequality_with_different_path() {
        var key1 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        var key2 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/orders/", NODE_1);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void httpNodeRouteKey_inequality_with_different_nodeId() {
        var key1 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        var key2 = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_2);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void httpNodeRouteKey_toString_returns_asString() {
        var key = HttpNodeRouteKey.httpNodeRouteKey("GET", "/users/", NODE_1);
        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
