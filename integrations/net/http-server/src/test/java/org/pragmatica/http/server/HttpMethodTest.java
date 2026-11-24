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

    /// #3679: the fold is ASCII only, so a letter that upper-cases into a method letter is not that method
    /// (`ſ` U+017F upper-cases to `S`, `ı` U+0131 to `I`).
    @Test
    void from_refuses_a_non_ascii_letter_that_case_folds_into_a_method() {
        assertTrue(HttpMethod.httpMethod("PO\u017fT").isFailure());
        assertTrue(HttpMethod.httpMethod("OPT\u0131ONS").isFailure());
    }

    /// #3679: one parser: the `Option` form accepts exactly what the `Result` form accepts.
    @Test
    void fromString_agrees_with_httpMethod() {
        for (var name : new String[]{"GET", "get", "PoSt", "PROPFIND", "PO\u017fT", "OPT\u0131ONS", ""}) {
            assertEquals(HttpMethod.httpMethod(name).isSuccess(), HttpMethod.fromString(name).isPresent(), name);
        }
    }
}
