// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.headersslice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2879, run against the GENERATED router: a slice method that takes `RequestHeaders` receives the headers of the
/// request that reached its route, for every route shape, case-insensitively and with every value of a repeated name.
/// The generated source is also pinned: the headers are taken from the request context passed into the route handler,
/// never from a ScopedValue.
class GeneratedRequestHeadersRouteTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final Map<String, List<String>> PROBE = Map.of("X-Probe", List.of("v1", "v2"));

    private static String generated;

    private SliceRouter router;

    @BeforeAll
    static void readGeneratedSource() throws Exception {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "headersslice", "HeadersSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @BeforeEach
    void setUp() {
        router = new HeadersSliceRoutes().create(HeadersSlice.headersSlice(), JsonMapper.defaultJsonMapper());
    }

    @Nested
    class EveryRouteShape {
        @Test
        void noParams_receivesTheRequestHeaders() {
            assertServed(send("GET", "/api/headers/none", Map.of(), PROBE, new byte[0]), "none");
        }

        @Test
        void path_receivesTheRequestHeaders_besideThePathValue() {
            assertServed(send("GET", "/api/headers/path/abc", Map.of(), PROBE, new byte[0]), "path=abc");
        }

        @Test
        void query_receivesTheRequestHeaders_besideTheQueryValue() {
            assertServed(send("GET", "/api/headers/query", Map.of("name", List.of("zed")), PROBE, new byte[0]), "name=zed");
        }

        @Test
        void body_receivesTheRequestHeaders_besideTheBody() {
            assertServed(send("POST", "/api/headers/body", Map.of(), PROBE, json("{\"text\":\"hello\"}")), "text=hello");
        }

        @Test
        void mergedPathAndBody_receivesTheRequestHeaders_besideBothValues() {
            assertServed(send("PUT", "/api/headers/items/7", Map.of(), PROBE, json("{\"id\":\"ignored\",\"text\":\"hello\"}")),
                         "path=7,text=hello");
        }

        @Test
        void mergedQueryAndBody_receivesTheRequestHeaders_besideBothValues() {
            assertServed(send("POST", "/api/headers/tagged", Map.of("tag", List.of("t1")), PROBE, json("{\"text\":\"hello\"}")),
                         "tag=t1,text=hello");
        }

        @Test
        void pathAndQuery_receivesTheRequestHeaders_besideBothValues() {
            assertServed(send("GET", "/api/headers/pq/9", Map.of("tag", List.of("t2")), PROBE, new byte[0]), "path=9,tag=t2");
        }

        @Test
        void mergedPathQueryAndBody_receivesTheRequestHeaders_besideAllValues() {
            assertServed(send("PUT", "/api/headers/pqb/5", Map.of("tag", List.of("t3")), PROBE, json("{\"id\":\"ignored\",\"text\":\"hello\"}")),
                         "path=5,tag=t3,text=hello");
        }

        @Test
        void multipart_receivesTheRequestHeaders_besideTheParsedForm() {
            var boundary = "XX";
            var body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nv\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
            var headers = Map.of("X-Probe", List.of("v1", "v2"),
                                 "Content-Type", List.of("multipart/form-data; boundary=" + boundary));

            assertServed(send("POST", "/api/headers/upload", Map.of(), headers, body), "fields=1");
        }

        @Test
        void headersDeclaredAfterTheRequestRecord_areStillInjected() {
            assertServed(send("POST", "/api/headers/ordered", Map.of(), PROBE, json("{\"text\":\"late\"}")), "text=late");
        }

        @Test
        void besideAPrincipal_bothAreInjected() {
            assertServed(send("POST", "/api/headers/secured", Map.of(), PROBE, json("{\"text\":\"s\"}")),
                         "principal=anonymous,text=s");
        }

        @Test
        void besideAPrincipalAndNoRequestRecord_bothAreInjected() {
            assertServed(send("GET", "/api/headers/whoami", Map.of(), PROBE, new byte[0]), "principal=anonymous");
        }
    }

    @Nested
    class HeaderSemantics {
        @Test
        void names_areCaseInsensitive() {
            var response = send("GET", "/api/headers/none", Map.of(), Map.of("X-PROBE", List.of("loud")), new byte[0]);

            assertThat(body(response)).contains("\"first\":\"loud\"");
        }

        @Test
        void absentHeader_isNone_andAllIsEmpty() {
            var response = send("GET", "/api/headers/none", Map.of(), Map.of("Accept", List.of("*/*")), new byte[0]);

            assertThat(body(response)).contains("\"first\":\"none\"").contains("\"all\":[]");
        }

        @Test
        void concurrentRequests_eachSeeOnlyTheirOwnHeaders() {
            var futures = IntStream.range(0, 64)
                                   .mapToObj(i -> CompletableFuture.supplyAsync(() -> body(send("GET",
                                                                                                "/api/headers/path/p" + i,
                                                                                                Map.of(),
                                                                                                Map.of("x-probe", List.of("h" + i)),
                                                                                                new byte[0]))))
                                   .toList();

            IntStream.range(0, 64)
                     .forEach(i -> assertThat(futures.get(i).join()).contains("\"first\":\"h" + i + "\"")
                                                                   .contains("path=p" + i));
        }
    }

    @Nested
    class GeneratedSource {
        @Test
        void headerRoutes_arePassedTheRequestContextDirectly() {
            assertThat(generated).contains("Route.withRequestContext(Route.<org.pragmatica.http.routing.Handler<com.example.headersslice.Echo>>get(\"/api/headers/none\")")
                                 .contains("org.pragmatica.lang.Promise.success(__request -> delegate.none(org.pragmatica.aether.http.handler.RequestHeaders.requestHeaders(__request.headers())))");
        }

        @Test
        void headers_areNeverReadFromAmbientState() {
            assertThat(generated).doesNotContain("ScopedValue").doesNotContain("SecurityContextHolder.scopedValue");
            assertThat(generated).doesNotContain("RequestHeadersHolder");
        }

        @Test
        void everyHeaderRoute_isWrapped_withMatchingParentheses() {
            var opens = generated.split("Route\\.withRequestContext\\(", -1).length - 1;
            var closes = generated.split("\\.asJson\\(\\)\\)", -1).length - 1;

            assertThat(opens).as("one wrapper per method of the slice").isEqualTo(12);
            assertThat(closes).as("each wrapper closes right after its output call").isEqualTo(12);
        }
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertServedBody(HttpResponseData response, String detail) {
        assertThat(response.statusCode()).as(body(response)).isEqualTo(200);
        assertThat(body(response)).contains("\"first\":\"v1\"")
                                  .contains("\"all\":[\"v1\",\"v2\"]")
                                  .contains("\"detail\":\"" + detail + "\"");
    }

    private static void assertServed(HttpResponseData response, String detail) {
        assertServedBody(response, detail);
    }

    private static String body(HttpResponseData response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private HttpResponseData send(String method,
                                  String path,
                                  Map<String, List<String>> query,
                                  Map<String, List<String>> headers,
                                  byte[] body) {
        return router.handle(HttpRequestContext.httpRequestContext(path, method, query, headers, body, "req_headers"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()), response -> response);
    }
}
