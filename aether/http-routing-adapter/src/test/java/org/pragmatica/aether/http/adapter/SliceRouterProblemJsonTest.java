// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.ProblemDetailJson;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.SliceVersionRegistry.VersionInfo;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2791 part 1: every error a [SliceRouter] answers is `application/problem+json` whose body parses as a
/// [ProblemDetail] carrying the response status, the request path as `instance` and no cause text beyond
/// [HttpError#clientMessage]. The `type` member is part 2 and stays `about:blank`. A successful answer keeps the route's
/// own content type (the guard against a fix that is too broad).
class SliceRouterProblemJsonTest {
    private static final String SECRET = "disk-secret-do-not-leak";
    private static final String VERSION_HEADER = "API-Version";

    record Invalid(String message) implements Cause.Validation {}

    record Conflicting(String message) implements HttpStatusAware {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record Challenged(HttpStatus status, Cause origin) implements HttpError {
        @Override
        public Map<String, String> responseHeaders() {
            return Map.of("WWW-Authenticate", "ApiKey realm=\"Test\"");
        }

        @Override
        public String message() {
            return status.message();
        }
    }

    @Test
    void routeMiss_is404ProblemJson() {
        assertProblem(send(okRoute(), "/missing"), 404, "/missing");
    }

    @Test
    void unknownMethod_is405ProblemJson() {
        var response = send(okRoute(), "BREW", "/thing");

        assertProblem(response, 405, "/thing");
    }

    @Test
    void validationFailure_is422ProblemJson() {
        assertProblem(send(failing(new Invalid("name must not be blank")), "/thing"), 422, "/thing");
    }

    @Test
    void causeFailureWithoutHttpStatusAware_is500ProblemJson_withTodaysClientText() {
        var failure = Causes.cause(SECRET);
        var response = send(failing(failure), "/thing");

        assertProblem(response, 500, "/thing");
        assertThat(problem(response).detail().or("")).isEqualTo(HttpError.clientMessage(failure));
    }

    @Test
    void causeFailureWithHttpStatusAware_isItsDeclaredStatusProblemJson() {
        assertProblem(send(failing(new Conflicting("order already exists")), "/thing"), 409, "/thing");
    }

    @Test
    void unexpectedException_is500ProblemJson_withTodaysClientText() {
        var failure = Causes.fromThrowable(new IllegalStateException(SECRET));
        var response = send(failing(failure), "/thing");

        assertProblem(response, 500, "/thing");
        assertThat(problem(response).detail().or("")).isEqualTo(HttpError.clientMessage(failure));
    }

    @Test
    void refusalWithHeaders_isProblemJson_andKeepsItsChallengeHeader() {
        var response = send(failing(new Challenged(HttpStatus.UNAUTHORIZED, Causes.cause("no key"))), "/thing");

        assertProblem(response, 401, "/thing");
        assertThat(response.headers()).containsEntry("WWW-Authenticate", "ApiKey realm=\"Test\"");
    }

    @Test
    void emptyOption_is404ProblemJson() {
        var route = Route.<Option<String>> get("/thing").withoutParameters().to(_ -> Promise.success(Option.<String> none())).asJson();

        assertProblem(send(route, "/thing"), 404, "/thing");
    }

    /// A route that declares binary output but returns a value that cannot be rendered as bytes: the body cannot be
    /// produced, which is a server fault.
    @Test
    void serializationFailure_is500ProblemJson_withAConstantDetail() {
        var route = Route.<Integer> get("/thing").withoutParameters().to(_ -> Promise.success(7)).as(CommonContentType.APPLICATION_OCTET_STREAM);
        var response = send(route, "/thing");

        assertProblem(response, 500, "/thing");
        assertThat(problem(response).detail().or("")).isEqualTo("Serialization failed");
    }

    @Test
    void headerMode_missingVersionHeader_is400ProblemJson() {
        assertProblem(sendVersioned(true, Map.of()), 400, "/api/catalog/items");
    }

    @Test
    void headerMode_unknownVersion_is404ProblemJson() {
        assertProblem(sendVersioned(false, Map.of(VERSION_HEADER.toLowerCase(Locale.ROOT), List.of("9"))), 404, "/api/catalog/items");
    }

    @Test
    void success_keepsTheRoutesOwnContentType() {
        var response = send(okRoute(), "/thing");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(contentType(response)).startsWith("application/json");
    }

    private static void assertProblem(HttpResponseData response, int status, String path) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(contentType(response)).startsWith("application/problem+json");
        var problem = problem(response);

        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.type()).as("part 2 owns `type`; part 1 leaves it as it is").isEqualTo("about:blank");
        assertThat(problem.instance().or("")).isEqualTo(path);
    }

    private static ProblemDetail problem(HttpResponseData response) {
        return ProblemDetailJson.CODEC.decode(response.body()).unwrap();
    }

    private static String contentType(HttpResponseData response) {
        return response.headers().getOrDefault("Content-Type", "<none>");
    }

    private static Route<String> failing(Cause failure) {
        return Route.<String> get("/thing").withoutParameters().to(_ -> failure.<String> promise()).asJson();
    }

    private static Route<String> okRoute() {
        return Route.<String> get("/thing").withoutParameters().to(_ -> Promise.success("ok")).asJson();
    }

    private static HttpResponseData send(Route<?> route, String path) {
        return send(route, "GET", path);
    }

    private static HttpResponseData send(Route<?> route, String method, String path) {
        var router = SliceRouter.sliceRouter(route, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());

        return handle(router, method, path, Map.of());
    }

    private static HttpResponseData sendVersioned(boolean requireHeader, Map<String, List<String>> headers) {
        var registry = SliceVersionRegistry.sliceVersionRegistry("/api/catalog",
                                                                 requireHeader,
                                                                 Option.option(2),
                                                                 List.of(VersionInfo.versionInfo(1, false, Option.none()),
                                                                         VersionInfo.versionInfo(2, false, Option.none())));
        var routes = Stream.of(1, 2)
                           .<Route<?>> map(SliceRouterProblemJsonTest::versionedRoute)
                           .toList();
        var source = new RouteSource() {
            @Override
            public Stream<Route<?>> routes() {
                return routes.stream();
            }

            @Override
            public SliceVersionRegistry versionRegistry() {
                return registry;
            }
        };
        var router = SliceRouter.sliceRouter(source,
                                             ErrorMapper.defaultMapper(),
                                             JsonMapper.defaultJsonMapper(),
                                             RouteMountMode.headerMode(VERSION_HEADER));

        return handle(router, "GET", "/api/catalog/items", headers);
    }

    private static Route<String> versionedRoute(int version) {
        return Route.<String> get("/items")
                    .to(_ -> Promise.success("list-v" + version))
                    .versioned(version)
                    .as(CommonContentType.APPLICATION_JSON);
    }

    private static HttpResponseData handle(SliceRouter router, String method, String path, Map<String, List<String>> headers) {
        return router.handle(HttpRequestContext.httpRequestContext(path, method, Map.of(), headers, "req_test"))
                     .await(timeSpan(5).seconds())
                     .unwrap();
    }
}
