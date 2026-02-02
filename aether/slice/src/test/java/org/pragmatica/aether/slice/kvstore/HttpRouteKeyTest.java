package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRouteKeyTest {
    @Test
    void httpRouteKey_from_string_succeeds() {
        HttpRouteKey.httpRouteKey("http-routes/GET:/users/")
                    .onSuccess(key -> {
                        assertThat(key.httpMethod()).isEqualTo("GET");
                        assertThat(key.pathPrefix()).isEqualTo("/users/");
                    })
                    .onFailureRun(Assertions::fail);
    }

    @Test
    void httpRouteKey_from_string_with_nested_path_succeeds() {
        HttpRouteKey.httpRouteKey("http-routes/POST:/api/v1/orders/")
                    .onSuccess(key -> {
                        assertThat(key.httpMethod()).isEqualTo("POST");
                        assertThat(key.pathPrefix()).isEqualTo("/api/v1/orders/");
                    })
                    .onFailureRun(Assertions::fail);
    }

    @Test
    void httpRouteKey_factory_normalizes_path() {
        var key = HttpRouteKey.httpRouteKey("GET", "users");
        assertThat(key.pathPrefix()).isEqualTo("/users/");
    }

    @Test
    void httpRouteKey_factory_normalizes_path_with_leading_slash() {
        var key = HttpRouteKey.httpRouteKey("POST", "/orders");
        assertThat(key.pathPrefix()).isEqualTo("/orders/");
    }

    @Test
    void httpRouteKey_factory_normalizes_path_with_trailing_slash() {
        var key = HttpRouteKey.httpRouteKey("PUT", "items/");
        assertThat(key.pathPrefix()).isEqualTo("/items/");
    }

    @Test
    void httpRouteKey_factory_preserves_already_normalized_path() {
        var key = HttpRouteKey.httpRouteKey("DELETE", "/products/");
        assertThat(key.pathPrefix()).isEqualTo("/products/");
    }

    @Test
    void httpRouteKey_factory_uppercases_method() {
        var key = HttpRouteKey.httpRouteKey("get", "/users/");
        assertThat(key.httpMethod()).isEqualTo("GET");
    }

    @Test
    void httpRouteKey_asString_produces_correct_format() {
        var key = HttpRouteKey.httpRouteKey("GET", "/users/");
        assertThat(key.asString()).isEqualTo("http-routes/GET:/users/");
    }

    @Test
    void httpRouteKey_roundtrip_consistency() {
        var originalKey = HttpRouteKey.httpRouteKey("POST", "/api/orders/");
        var keyString = originalKey.asString();
        HttpRouteKey.httpRouteKey(keyString)
                    .onSuccess(parsedKey -> assertThat(parsedKey).isEqualTo(originalKey))
                    .onFailureRun(Assertions::fail);
    }

    @Test
    void httpRouteKey_from_string_with_invalid_prefix_fails() {
        HttpRouteKey.httpRouteKey("invalid/GET:/users/")
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-routes key format"));
    }

    @Test
    void httpRouteKey_from_string_with_missing_colon_fails() {
        HttpRouteKey.httpRouteKey("http-routes/GET/users/")
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-routes key format"));
    }

    @Test
    void httpRouteKey_from_string_with_empty_method_fails() {
        HttpRouteKey.httpRouteKey("http-routes/:/users/")
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-routes key format"));
    }

    @Test
    void httpRouteKey_from_string_with_empty_path_fails() {
        HttpRouteKey.httpRouteKey("http-routes/GET:")
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> assertThat(cause.message()).contains("Invalid http-routes key format"));
    }

    @Test
    void httpRouteKey_matches_httpRoutePattern() {
        var key = HttpRouteKey.httpRouteKey("GET", "/users/");
        var pattern = new AetherKey.AetherKeyPattern.HttpRoutePattern();
        assertThat(key.matches(pattern)).isTrue();
    }

    @Test
    void httpRouteKey_does_not_match_other_patterns() {
        var key = HttpRouteKey.httpRouteKey("GET", "/users/");
        var endpointPattern = new AetherKey.AetherKeyPattern.EndpointPattern();
        assertThat(key.matches(endpointPattern)).isFalse();
    }

    @Test
    void httpRouteKey_equality_works() {
        var key1 = HttpRouteKey.httpRouteKey("GET", "/users/");
        var key2 = HttpRouteKey.httpRouteKey("GET", "/users/");
        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }

    @Test
    void httpRouteKey_inequality_with_different_method() {
        var key1 = HttpRouteKey.httpRouteKey("GET", "/users/");
        var key2 = HttpRouteKey.httpRouteKey("POST", "/users/");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void httpRouteKey_inequality_with_different_path() {
        var key1 = HttpRouteKey.httpRouteKey("GET", "/users/");
        var key2 = HttpRouteKey.httpRouteKey("GET", "/orders/");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void httpRouteKey_toString_returns_asString() {
        var key = HttpRouteKey.httpRouteKey("GET", "/users/");
        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
