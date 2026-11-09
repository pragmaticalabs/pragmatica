// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.successstatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2805 (#2155): the success status and `Location` a route declares in `routes.toml`, run end to end through the
/// router the processor generated for a real slice. Owner ruling 2026-11-08: `status` + `location`; a `Unit` result
/// answers 204, or the declared status; a null success is a JBCT violation answered 500 with a WARN; an empty `Option`
/// answers 404.
class GeneratedSuccessStatusRouteTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final String ARTICLE = "{\"slug\":\"hello world\",\"title\":\"Hi\"}";

    private SliceRouter router;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        router = new SuccessStatusSliceRoutes().create(SuccessStatusSlice.successStatusSlice(), JsonMapper.valueObjectJsonMapper());
        capture = LogCapture.capture(SliceRouter.class, Level.WARN, true);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    @Test
    void declared201_answersCreatedWithBody_andLocationFromTheResultField() {
        var response = send("POST", "/api/blog/articles", ARTICLE);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response)).isEqualTo("{\"slug\":\"hello world\",\"title\":\"Hi\"}");
        assertThat(response.headers()).containsEntry("Location", "/api/blog/articles/hello%20world");
    }

    @Test
    void declared201_withoutLocation_answersCreatedWithNoLocationHeader() {
        var response = send("POST", "/api/blog/registrations", ARTICLE);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response)).isEqualTo("{\"slug\":\"hello world\",\"title\":\"Hi\"}");
        assertThat(response.headers()).doesNotContainKey("Location");
    }

    @Test
    void locationField_ofAValueObject_rendersThroughItsValueMapping() {
        var response = send("POST", "/api/blog/drafts", "{\"slug\":\"d-1\",\"title\":\"Draft\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers()).containsEntry("Location", "/api/blog/drafts/d-1/view");
    }

    @Test
    void declared202_onUnit_answersAcceptedWithNoBody() {
        var response = send("POST", "/api/blog/jobs", ARTICLE);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
    }

    /// Owner ruling 2026-11-08: the Location is built from fields, each value percent-encoded as one path segment
    /// while the template's literal parts stay untouched.
    @Test
    void locationField_withSlashAndSpace_isPercentEncodedAsOneSegment() {
        var response = send("POST", "/api/blog/articles", "{\"slug\":\"a/b c\",\"title\":\"Hi\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers()).containsEntry("Location", "/api/blog/articles/a%2Fb%20c");
    }

    /// Owner ruling 2026-11-08: a declared 204 on Unit never carries a body.
    @Test
    void declared204_onUnit_answersNoContentWithNoBody() {
        var response = send("POST", "/api/blog/purge/known", "");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void unit_withNoDeclaration_answers204WithNoBody() {
        var response = send("DELETE", "/api/blog/articles/known", "");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void nullSuccess_answers500_andWarnsNamingTheRoute() {
        var response = send("GET", "/api/blog/broken", "");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(String.join("\n", capture.messages())).contains("null success value")
                                                         .contains("'broken'");
    }

    @Test
    void emptyOption_answers404WithProblemBody() {
        var response = send("GET", "/api/blog/articles/unknown", "");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response)).contains("\"status\":404");
    }

    @Test
    void presentOption_answers200WithTheValue() {
        var response = send("GET", "/api/blog/articles/known", "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("{\"slug\":\"known\",\"title\":\"Known\"}");
    }

    @Test
    void locationWithoutStatus_answersTheDefault200_withTheLocation() {
        var response = send("POST", "/api/blog/located", ARTICLE);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Location", "/api/blog/articles/hello%20world");
    }

    @Test
    void locationOnAnOptionResult_readsThePresentValue_andAnEmptyOneStillAnswers404() {
        var present = send("GET", "/api/blog/lookup/known", "");

        assertThat(present.statusCode()).isEqualTo(200);
        assertThat(present.headers()).containsEntry("Location", "/api/blog/articles/known");
        assertThat(send("GET", "/api/blog/lookup/unknown", "").statusCode()).isEqualTo(404);
    }

    /// #2965: a route whose method takes an injected [org.pragmatica.aether.http.handler.RequestHeaders] is wrapped in
    /// `Route.withRequestContext`; the wrapper kept the status 200 and no Location instead of the declared 201 + Location.
    @Test
    void declared201_withInjectedRequestHeaders_answersCreatedWithLocation() {
        var response = send("POST", "/api/blog/with-headers", ARTICLE);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers()).containsEntry("Location", "/api/blog/articles/hello%20world");
    }

    /// #2965: the same for an injected [org.pragmatica.aether.http.handler.security.SecurityContext].
    @Test
    void declared201_withInjectedSecurityContext_answersCreatedWithLocation() {
        var response = send("POST", "/api/blog/with-context", ARTICLE);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers()).containsEntry("Location", "/api/blog/articles/hello%20world");
    }

    /// A route that declares neither key generates the source it always did: only the nine declaring routes carry
    /// `.withSuccess(...)`.
    @Test
    void undeclaredRoutes_generateNoSuccessDeclaration() throws IOException {
        var generated = Files.readString(generatedRoutes());

        assertThat(generated.split("\\.withSuccess\\(", -1)).hasSize(10);
        assertThat(generated).contains(".withSuccess(org.pragmatica.http.routing.SuccessSpec.successSpec(202))");
    }

    private static Path generatedRoutes() {
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", "successstatus", "SuccessStatusSliceRoutes.java");
        var moduleDir = Paths.get(System.getProperty("user.dir"));

        return Files.exists(moduleDir.resolve(relative))
               ? moduleDir.resolve(relative)
               : moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    private HttpResponseData send(String method, String path, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(path,
                                                                   method,
                                                                   Map.of(),
                                                                   Map.of(),
                                                                   body.getBytes(StandardCharsets.UTF_8),
                                                                   "req_status"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()), response -> response);
    }

    private static String body(HttpResponseData response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
