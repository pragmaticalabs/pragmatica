package org.pragmatica.aether.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathPatternTest {

    @Test
    void compile_exactPath_matchesExactly() {
        PathPattern.compile("GET:/api/orders")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/orders");
                       assertThat(match.isPresent()).isTrue();
                       assertThat(match.unwrap()).isEmpty();
                   });
    }

    @Test
    void compile_exactPath_rejectsWrongPath() {
        PathPattern.compile("GET:/api/orders")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/users");
                       assertThat(match.isPresent()).isFalse();
                   });
    }

    @Test
    void compile_exactPath_rejectsWrongMethod() {
        PathPattern.compile("GET:/api/orders")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.POST, "/api/orders");
                       assertThat(match.isPresent()).isFalse();
                   });
    }

    @Test
    void compile_pathWithVariable_extractsVariable() {
        PathPattern.compile("GET:/api/orders/{orderId}")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/orders/ORD-12345678");
                       assertThat(match.isPresent()).isTrue();
                       assertThat(match.unwrap()).containsEntry("orderId", "ORD-12345678");
                   });
    }

    @Test
    void compile_pathWithMultipleVariables_extractsAll() {
        PathPattern.compile("GET:/api/customers/{customerId}/orders/{orderId}")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/customers/CUST-001/orders/ORD-002");
                       assertThat(match.isPresent()).isTrue();
                       assertThat(match.unwrap())
                           .containsEntry("customerId", "CUST-001")
                           .containsEntry("orderId", "ORD-002");
                   });
    }

    @Test
    void compile_pathWithVariableInMiddle_matchesCorrectly() {
        PathPattern.compile("GET:/api/users/{userId}/settings")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/users/123/settings");
                       assertThat(match.isPresent()).isTrue();
                       assertThat(match.unwrap()).containsEntry("userId", "123");
                   });
    }

    @Test
    void compile_postMethod_matchesPost() {
        PathPattern.compile("POST:/api/orders")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.POST, "/api/orders");
                       assertThat(match.isPresent()).isTrue();
                   });
    }

    @Test
    void compile_deleteMethod_matchesDelete() {
        PathPattern.compile("DELETE:/api/orders/{orderId}")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.DELETE, "/api/orders/123");
                       assertThat(match.isPresent()).isTrue();
                       assertThat(match.unwrap()).containsEntry("orderId", "123");
                   });
    }

    @Test
    void compile_trailingSlashMismatch_doesNotMatch() {
        PathPattern.compile("GET:/api/orders")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/orders/");
                       assertThat(match.isPresent()).isFalse();
                   });
    }

    @Test
    void compile_partialPathMatch_doesNotMatch() {
        PathPattern.compile("GET:/api/orders/{orderId}")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/orders");
                       assertThat(match.isPresent()).isFalse();
                   });
    }

    @Test
    void compile_longerPathThanPattern_doesNotMatch() {
        PathPattern.compile("GET:/api/orders/{orderId}")
                   .onFailureRun(Assertions::fail)
                   .onSuccess(pattern -> {
                       var match = pattern.match(HttpMethod.GET, "/api/orders/123/items");
                       assertThat(match.isPresent()).isFalse();
                   });
    }

    @Test
    void compile_missingColon_fails() {
        PathPattern.compile("GET/api/orders")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("must include method"));
    }

    @Test
    void compile_unknownMethod_fails() {
        PathPattern.compile("UNKNOWN:/api/orders")
                   .onSuccessRun(Assertions::fail)
                   .onFailure(cause -> assertThat(cause.message()).contains("Unknown HTTP method"));
    }
}
