/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestTest {

    // Test implementation of HttpRequest interface
    record TestHttpRequest(
        String requestId,
        HttpMethod method,
        String path,
        Headers headers,
        QueryParams queryParams,
        byte[] body
    ) implements HttpRequest {}

    @Test
    void header_returns_value_case_insensitively() {
        var headers = Headers.fromSingleValueMap(Map.of("content-type", "application/json", "x-custom", "value"));
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/path", headers, QueryParams.empty(), new byte[0]);

        assertThat(ctx.headers().get("Content-Type").isPresent()).isTrue();
        ctx.headers().get("Content-Type").onPresent(v -> assertThat(v).isEqualTo("application/json"));

        assertThat(ctx.headers().get("X-Custom").isPresent()).isTrue();
        ctx.headers().get("X-Custom").onPresent(v -> assertThat(v).isEqualTo("value"));
    }

    @Test
    void header_returns_empty_for_missing_header() {
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/path", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.headers().get("X-Missing").isPresent()).isFalse();
    }

    @Test
    void queryParam_returns_values() {
        var params = QueryParams.queryParams(Map.of(
            "foo", List.of("bar"),
            "baz", List.of("qux")
        ));
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/path", Headers.empty(), params, new byte[0]);

        assertThat(ctx.queryParams().get("foo").isPresent()).isTrue();
        ctx.queryParams().get("foo").onPresent(v -> assertThat(v).isEqualTo("bar"));

        assertThat(ctx.queryParams().get("baz").isPresent()).isTrue();
        ctx.queryParams().get("baz").onPresent(v -> assertThat(v).isEqualTo("qux"));
    }

    @Test
    void queryParam_returns_empty_for_missing_param() {
        var params = QueryParams.queryParams(Map.of("foo", List.of("bar")));
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/path", Headers.empty(), params, new byte[0]);

        assertThat(ctx.queryParams().get("missing").isPresent()).isFalse();
    }

    @Test
    void queryParams_returns_all_params() {
        var params = QueryParams.queryParams(Map.of(
            "a", List.of("1"),
            "b", List.of("2"),
            "c", List.of("3")
        ));
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/path", Headers.empty(), params, new byte[0]);

        var map = ctx.queryParams().asMap();
        assertThat(map).containsEntry("a", List.of("1"));
        assertThat(map).containsEntry("b", List.of("2"));
        assertThat(map).containsEntry("c", List.of("3"));
    }

    @Test
    void queryParams_returns_empty_map_for_empty_query() {
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/path", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.queryParams().asMap()).isEmpty();
    }

    @Test
    void bodyAsString_returns_utf8_content() {
        var body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        var ctx = new TestHttpRequest("req_1", HttpMethod.POST, "/path", Headers.empty(), QueryParams.empty(), body);

        assertThat(ctx.bodyAsString()).isEqualTo("Hello, World!");
    }

    @Test
    void bodyAsString_returns_empty_for_empty_body() {
        var ctx = new TestHttpRequest("req_1", HttpMethod.POST, "/path", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.bodyAsString()).isEmpty();
    }

    @Test
    void hasBody_returns_true_when_body_present() {
        var body = "content".getBytes(StandardCharsets.UTF_8);
        var ctx = new TestHttpRequest("req_1", HttpMethod.POST, "/path", Headers.empty(), QueryParams.empty(), body);

        assertThat(ctx.hasBody()).isTrue();
    }

    @Test
    void hasBody_returns_false_when_body_empty() {
        var ctx = new TestHttpRequest("req_1", HttpMethod.POST, "/path", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.hasBody()).isFalse();
    }

    @Test
    void contentType_returns_content_type_header() {
        var headers = Headers.fromSingleValueMap(Map.of("content-type", "application/json"));
        var ctx = new TestHttpRequest("req_1", HttpMethod.POST, "/path", headers, QueryParams.empty(), new byte[0]);

        assertThat(ctx.headers().get("content-type").isPresent()).isTrue();
        ctx.headers().get("content-type").onPresent(v -> assertThat(v).isEqualTo("application/json"));
    }

    @Test
    void requestId_is_accessible() {
        var ctx = new TestHttpRequest("req_123", HttpMethod.GET, "/path", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.requestId()).isEqualTo("req_123");
    }

    @Test
    void method_is_accessible() {
        var ctx = new TestHttpRequest("req_1", HttpMethod.POST, "/path", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.method()).isEqualTo(HttpMethod.POST);
    }

    @Test
    void path_is_accessible() {
        var ctx = new TestHttpRequest("req_1", HttpMethod.GET, "/api/users", Headers.empty(), QueryParams.empty(), new byte[0]);

        assertThat(ctx.path()).isEqualTo("/api/users");
    }
}
