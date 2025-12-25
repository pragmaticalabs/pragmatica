package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteKeyTest {

    @Test
    void routeKey_creates_from_method_and_pattern() {
        var key = AetherKey.RouteKey.routeKey("POST", "/api/orders");

        assertThat(key.method()).isEqualTo("POST");
        assertThat(key.pathHash()).isEqualTo("/api/orders".hashCode());
    }

    @Test
    void routeKey_normalizes_method_to_uppercase() {
        var key = AetherKey.RouteKey.routeKey("post", "/api/orders");

        assertThat(key.method()).isEqualTo("POST");
    }

    @Test
    void routeKey_asString_returns_formatted_key() {
        var key = AetherKey.RouteKey.routeKey("GET", "/api/users/{id}");

        assertThat(key.asString()).startsWith("routes/GET:");
        assertThat(key.asString()).contains(String.valueOf("/api/users/{id}".hashCode()));
    }

    @Test
    void routeKey_parsing_roundtrip() {
        var original = AetherKey.RouteKey.routeKey("DELETE", "/api/orders/{orderId}");
        var serialized = original.asString();

        AetherKey.RouteKey.routeKey(serialized)
            .onFailureRun(Assertions::fail)
            .onSuccess(parsed -> {
                assertThat(parsed.method()).isEqualTo(original.method());
                assertThat(parsed.pathHash()).isEqualTo(original.pathHash());
            });
    }

    @Test
    void routeKey_parsing_fails_for_invalid_prefix() {
        AetherKey.RouteKey.routeKey("invalid/key")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Invalid route key format"));
    }

    @Test
    void routeKey_parsing_fails_for_missing_colon() {
        AetherKey.RouteKey.routeKey("routes/GET123")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Invalid route key format"));
    }

    @Test
    void routeKey_parsing_fails_for_non_numeric_hash() {
        AetherKey.RouteKey.routeKey("routes/GET:abc")
            .onSuccessRun(Assertions::fail);
    }

    @Test
    void routeKey_matches_route_pattern() {
        var key = AetherKey.RouteKey.routeKey("POST", "/api/orders");
        var pattern = new AetherKey.AetherKeyPattern.RoutePattern();

        assertThat(key.matches(pattern)).isTrue();
    }

    @Test
    void routeKey_does_not_match_other_patterns() {
        var key = AetherKey.RouteKey.routeKey("POST", "/api/orders");
        var blueprintPattern = new AetherKey.AetherKeyPattern.BlueprintPattern();

        assertThat(key.matches(blueprintPattern)).isFalse();
    }

    @Test
    void same_method_different_path_produces_different_keys() {
        var key1 = AetherKey.RouteKey.routeKey("GET", "/api/orders");
        var key2 = AetherKey.RouteKey.routeKey("GET", "/api/users");

        assertThat(key1.pathHash()).isNotEqualTo(key2.pathHash());
    }

    @Test
    void same_path_different_method_produces_different_keys() {
        var key1 = AetherKey.RouteKey.routeKey("GET", "/api/orders");
        var key2 = AetherKey.RouteKey.routeKey("POST", "/api/orders");

        assertThat(key1.method()).isNotEqualTo(key2.method());
        assertThat(key1.asString()).isNotEqualTo(key2.asString());
    }
}
