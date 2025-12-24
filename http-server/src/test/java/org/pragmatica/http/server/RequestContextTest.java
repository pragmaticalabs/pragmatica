package org.pragmatica.http.server;

import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextTest {

    @Test
    void header_returns_value_case_insensitively() {
        var headers = Map.of("content-type", "application/json", "x-custom", "value");
        var ctx = new RequestContext(HttpMethod.GET, "/path", null, headers, null);

        assertThat(ctx.header("Content-Type").isPresent()).isTrue();
        ctx.header("Content-Type").onPresent(v -> assertThat(v).isEqualTo("application/json"));

        assertThat(ctx.header("X-Custom").isPresent()).isTrue();
        ctx.header("X-Custom").onPresent(v -> assertThat(v).isEqualTo("value"));
    }

    @Test
    void header_returns_empty_for_missing_header() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", null, Map.of(), null);

        assertThat(ctx.header("X-Missing").isPresent()).isFalse();
    }

    @Test
    void queryParam_parses_query_string() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", "foo=bar&baz=qux", Map.of(), null);

        assertThat(ctx.queryParam("foo").isPresent()).isTrue();
        ctx.queryParam("foo").onPresent(v -> assertThat(v).isEqualTo("bar"));

        assertThat(ctx.queryParam("baz").isPresent()).isTrue();
        ctx.queryParam("baz").onPresent(v -> assertThat(v).isEqualTo("qux"));
    }

    @Test
    void queryParam_handles_url_encoded_values() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", "name=John%20Doe&email=test%40example.com", Map.of(), null);

        ctx.queryParam("name").onPresent(v -> assertThat(v).isEqualTo("John Doe"));
        ctx.queryParam("email").onPresent(v -> assertThat(v).isEqualTo("test@example.com"));
    }

    @Test
    void queryParam_returns_empty_for_null_query() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", null, Map.of(), null);

        assertThat(ctx.queryParam("any").isPresent()).isFalse();
    }

    @Test
    void queryParam_returns_empty_for_missing_param() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", "foo=bar", Map.of(), null);

        assertThat(ctx.queryParam("missing").isPresent()).isFalse();
    }

    @Test
    void queryParams_returns_all_params() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", "a=1&b=2&c=3", Map.of(), null);

        var params = ctx.queryParams();
        assertThat(params).containsEntry("a", "1");
        assertThat(params).containsEntry("b", "2");
        assertThat(params).containsEntry("c", "3");
    }

    @Test
    void queryParams_returns_empty_map_for_null_query() {
        var ctx = new RequestContext(HttpMethod.GET, "/path", null, Map.of(), null);

        assertThat(ctx.queryParams()).isEmpty();
    }

    @Test
    void bodyAsString_returns_utf8_content() {
        var body = Unpooled.copiedBuffer("Hello, World!", CharsetUtil.UTF_8);
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, Map.of(), body);

        assertThat(ctx.bodyAsString()).isEqualTo("Hello, World!");
    }

    @Test
    void bodyAsString_returns_empty_for_null_body() {
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, Map.of(), null);

        assertThat(ctx.bodyAsString()).isEmpty();
    }

    @Test
    void hasBody_returns_true_when_body_present() {
        var body = Unpooled.copiedBuffer("content", CharsetUtil.UTF_8);
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, Map.of(), body);

        assertThat(ctx.hasBody()).isTrue();
    }

    @Test
    void hasBody_returns_false_when_body_null() {
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, Map.of(), null);

        assertThat(ctx.hasBody()).isFalse();
    }

    @Test
    void hasBody_returns_false_when_body_empty() {
        var body = Unpooled.EMPTY_BUFFER;
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, Map.of(), body);

        assertThat(ctx.hasBody()).isFalse();
    }

    @Test
    void contentType_returns_content_type_header() {
        var headers = Map.of("content-type", "application/json");
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, headers, null);

        assertThat(ctx.contentType().isPresent()).isTrue();
        ctx.contentType().onPresent(v -> assertThat(v).isEqualTo("application/json"));
    }

    @Test
    void isJson_returns_true_for_json_content_type() {
        var headers = Map.of("content-type", "application/json");
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, headers, null);

        assertThat(ctx.isJson()).isTrue();
    }

    @Test
    void isJson_returns_true_for_json_with_charset() {
        var headers = Map.of("content-type", "application/json; charset=utf-8");
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, headers, null);

        assertThat(ctx.isJson()).isTrue();
    }

    @Test
    void isJson_returns_false_for_non_json_content() {
        var headers = Map.of("content-type", "text/plain");
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, headers, null);

        assertThat(ctx.isJson()).isFalse();
    }

    @Test
    void isJson_returns_false_when_no_content_type() {
        var ctx = new RequestContext(HttpMethod.POST, "/path", null, Map.of(), null);

        assertThat(ctx.isJson()).isFalse();
    }
}
