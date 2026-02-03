package org.pragmatica.http.server;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.*;

class HttpMethodTest {

    @Test
    void from_parses_uppercase_method() {
        assertEquals(HttpMethod.GET, HttpMethod.httpMethod("GET").fold(_ -> null, v -> v));
        assertEquals(HttpMethod.POST, HttpMethod.httpMethod("POST").fold(_ -> null, v -> v));
        assertEquals(HttpMethod.PUT, HttpMethod.httpMethod("PUT").fold(_ -> null, v -> v));
        assertEquals(HttpMethod.DELETE, HttpMethod.httpMethod("DELETE").fold(_ -> null, v -> v));
    }

    @Test
    void from_parses_lowercase_method() {
        assertEquals(HttpMethod.GET, HttpMethod.httpMethod("get").fold(_ -> null, v -> v));
        assertEquals(HttpMethod.POST, HttpMethod.httpMethod("post").fold(_ -> null, v -> v));
    }

    @Test
    void from_parses_mixed_case_method() {
        assertEquals(HttpMethod.GET, HttpMethod.httpMethod("Get").fold(_ -> null, v -> v));
        assertEquals(HttpMethod.POST, HttpMethod.httpMethod("PoSt").fold(_ -> null, v -> v));
    }

    @Test
    void from_returns_error_for_unknown_method() {
        var result = HttpMethod.httpMethod("INVALID");

        assertTrue(result.isFailure());
        result.onFailure(cause -> {
            assertInstanceOf(HttpMethod.UnknownMethod.class, cause);
            assertTrue(cause.message().contains("Unknown HTTP method"));
        });
    }

    @Test
    void all_methods_are_parseable() {
        for (HttpMethod method : HttpMethod.values()) {
            assertEquals(method, HttpMethod.httpMethod(method.name()).fold(_ -> null, v -> v));
        }
    }
}
