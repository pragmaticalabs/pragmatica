package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternTest {

    @Test
    void compile_exactPath_matchesExactly() {
        var pattern = PathPattern.compile("GET:/api/orders");

        var match = pattern.match(HttpMethod.GET, "/api/orders");

        assertThat(match.isPresent()).isTrue();
        assertThat(match.unwrap()).isEmpty();
    }

    @Test
    void compile_exactPath_rejectsWrongPath() {
        var pattern = PathPattern.compile("GET:/api/orders");

        var match = pattern.match(HttpMethod.GET, "/api/users");

        assertThat(match.isPresent()).isFalse();
    }

    @Test
    void compile_exactPath_rejectsWrongMethod() {
        var pattern = PathPattern.compile("GET:/api/orders");

        var match = pattern.match(HttpMethod.POST, "/api/orders");

        assertThat(match.isPresent()).isFalse();
    }

    @Test
    void compile_pathWithVariable_extractsVariable() {
        var pattern = PathPattern.compile("GET:/api/orders/{orderId}");

        var match = pattern.match(HttpMethod.GET, "/api/orders/ORD-12345678");

        assertThat(match.isPresent()).isTrue();
        assertThat(match.unwrap()).containsEntry("orderId", "ORD-12345678");
    }

    @Test
    void compile_pathWithMultipleVariables_extractsAll() {
        var pattern = PathPattern.compile("GET:/api/customers/{customerId}/orders/{orderId}");

        var match = pattern.match(HttpMethod.GET, "/api/customers/CUST-001/orders/ORD-002");

        assertThat(match.isPresent()).isTrue();
        assertThat(match.unwrap())
            .containsEntry("customerId", "CUST-001")
            .containsEntry("orderId", "ORD-002");
    }

    @Test
    void compile_pathWithVariableInMiddle_matchesCorrectly() {
        var pattern = PathPattern.compile("GET:/api/users/{userId}/settings");

        var match = pattern.match(HttpMethod.GET, "/api/users/123/settings");

        assertThat(match.isPresent()).isTrue();
        assertThat(match.unwrap()).containsEntry("userId", "123");
    }

    @Test
    void compile_postMethod_matchesPost() {
        var pattern = PathPattern.compile("POST:/api/orders");

        var match = pattern.match(HttpMethod.POST, "/api/orders");

        assertThat(match.isPresent()).isTrue();
    }

    @Test
    void compile_deleteMethod_matchesDelete() {
        var pattern = PathPattern.compile("DELETE:/api/orders/{orderId}");

        var match = pattern.match(HttpMethod.DELETE, "/api/orders/123");

        assertThat(match.isPresent()).isTrue();
        assertThat(match.unwrap()).containsEntry("orderId", "123");
    }

    @Test
    void compile_trailingSlashMismatch_doesNotMatch() {
        var pattern = PathPattern.compile("GET:/api/orders");

        var match = pattern.match(HttpMethod.GET, "/api/orders/");

        assertThat(match.isPresent()).isFalse();
    }

    @Test
    void compile_partialPathMatch_doesNotMatch() {
        var pattern = PathPattern.compile("GET:/api/orders/{orderId}");

        var match = pattern.match(HttpMethod.GET, "/api/orders");

        assertThat(match.isPresent()).isFalse();
    }

    @Test
    void compile_longerPathThanPattern_doesNotMatch() {
        var pattern = PathPattern.compile("GET:/api/orders/{orderId}");

        var match = pattern.match(HttpMethod.GET, "/api/orders/123/items");

        assertThat(match.isPresent()).isFalse();
    }
}
