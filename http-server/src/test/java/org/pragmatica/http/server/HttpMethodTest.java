package org.pragmatica.http.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMethodTest {

    @Test
    void from_parses_uppercase_method() {
        assertThat(HttpMethod.from("GET")).isEqualTo(HttpMethod.GET);
        assertThat(HttpMethod.from("POST")).isEqualTo(HttpMethod.POST);
        assertThat(HttpMethod.from("PUT")).isEqualTo(HttpMethod.PUT);
        assertThat(HttpMethod.from("DELETE")).isEqualTo(HttpMethod.DELETE);
    }

    @Test
    void from_parses_lowercase_method() {
        assertThat(HttpMethod.from("get")).isEqualTo(HttpMethod.GET);
        assertThat(HttpMethod.from("post")).isEqualTo(HttpMethod.POST);
    }

    @Test
    void from_parses_mixed_case_method() {
        assertThat(HttpMethod.from("Get")).isEqualTo(HttpMethod.GET);
        assertThat(HttpMethod.from("PoSt")).isEqualTo(HttpMethod.POST);
    }

    @Test
    void from_throws_for_unknown_method() {
        assertThatThrownBy(() -> HttpMethod.from("INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown HTTP method");
    }

    @Test
    void all_methods_are_parseable() {
        for (HttpMethod method : HttpMethod.values()) {
            assertThat(HttpMethod.from(method.name())).isEqualTo(method);
        }
    }
}
