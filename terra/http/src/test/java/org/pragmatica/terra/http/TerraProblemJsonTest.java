// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.ProblemDetailJson;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.TerraContext;
import org.pragmatica.terra.TerraFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

/// #2791 part 1, Terra side: every error answer of a Terra route is `application/problem+json` whose body parses as a
/// [ProblemDetail] carrying the response status and the request path as `instance`, over the real Netty transport. The
/// rows are the router's (route miss, validation, Cause failure with and without [HttpStatusAware], unexpected
/// exception, auth refusal) and Terra's own (a dispatch that fails outside the router, an admission refusal while
/// draining). The `type` member is part 2 and stays `about:blank`.
class TerraProblemJsonTest {
    private static final String SECRET = "disk-secret-do-not-leak";
    private static final Cause UNEXPECTED = Causes.fromThrowable(new IllegalStateException(SECRET));
    private static final Map<String, ApiKeyEntry> KEYS = Map.of("reader-secret", ApiKeyEntry.apiKeyEntry("reader", Set.of("viewer"), "VIEWER"));

    record Sample() {}

    record Invalid(String message) implements Cause.Validation {}

    record Conflicting(String message) implements HttpStatusAware {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record Factory<T>(Class<T> sliceType, T value) implements TerraFactory<T> {
        public String artifact() { return "test:" + sliceType.getSimpleName() + ":1"; }
        public List<Class<?>> dependencies() { return List.of(); }
        public Promise<T> create(TerraContext context) { return Promise.success(value); }
    }

    /// The real router for the routes, optionally behind a router that throws for `/boom` before any route runs.
    record Routes<T>(Class<T> sliceType, List<Route<?>> routes) implements SliceRouterFactory<T> {
        public int routeSecurityContract() { return 1; }
        public SliceRouter create(T slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(T slice, JsonMapper mapper) { return create(slice, mapper, RouteMountMode.pathMode()); }
        public SliceRouter create(T slice, JsonMapper mapper, RouteMountMode mode) {
            var source = new RouteSource() {
                public Stream<Route<?>> routes() { return routes.stream(); }
                public SliceVersionRegistry versionRegistry() { return SliceVersionRegistry.UNVERSIONED; }
            };
            return new Throwing(SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), mapper, mode));
        }
    }

    record Throwing(SliceRouter delegate) implements SliceRouter {
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            if (request.path().equals("/boom")) {
                throw new IllegalStateException(SECRET);
            }
            return delegate.handle(request);
        }
        public SliceVersionRegistry versionRegistry() { return delegate.versionRegistry(); }
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return new Throwing(delegate.withObservability(sliceName, sink));
        }
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return new Throwing(delegate.withInvocationCells(decorator));
        }
    }

    private static Route<String> failing(String path, Cause failure) {
        return Route.<String>get(path).withoutParameters().to(_ -> failure.<String>promise())
                    .withSecurity(SecurityPolicy.publicRoute()).asJson();
    }

    private static List<Route<?>> routes() {
        return List.of(
            failing("/invalid", new Invalid("name must not be blank")),
            failing("/plain", Causes.cause(SECRET)),
            failing("/conflict", new Conflicting("order already exists")),
            failing("/unexpected", UNEXPECTED),
            Route.<String>get("/boom").withoutParameters().to(_ -> Promise.success("never")).withSecurity(SecurityPolicy.publicRoute()).asJson(),
            Route.<String>get("/private").withoutParameters().to(_ -> Promise.success("ok")).withSecurity(SecurityPolicy.unspecified()).asJson());
    }

    @Test void routeMiss_is404ProblemJson() throws Exception {
        assertProblem(get("/missing"), 404, "/missing");
    }

    @Test void validationFailure_is422ProblemJson() throws Exception {
        assertProblem(get("/invalid"), 422, "/invalid");
    }

    @Test void causeFailureWithoutHttpStatusAware_is500ProblemJson_withTodaysClientText() throws Exception {
        var response = get("/plain");

        assertProblem(response, 500, "/plain");
        assertThat(problem(response).detail().or("")).isEqualTo(HttpError.clientMessage(Causes.cause(SECRET)));
    }

    @Test void causeFailureWithHttpStatusAware_isItsDeclaredStatusProblemJson() throws Exception {
        assertProblem(get("/conflict"), 409, "/conflict");
    }

    @Test void unexpectedException_is500ProblemJson_withTodaysClientText() throws Exception {
        var response = get("/unexpected");

        assertProblem(response, 500, "/unexpected");
        assertThat(problem(response).detail().or("")).isEqualTo(HttpError.clientMessage(UNEXPECTED));
    }

    @Test void authRefusal_is401ProblemJson_andKeepsTheChallenge() throws Exception {
        var response = get("/private");

        assertProblem(response, 401, "/private");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
    }

    /// Terra's own row: the dispatch fails outside the router (here the router throws), so nothing above it would answer.
    @Test void dispatchFailureOutsideTheRouter_is500ProblemJson_withAConstantDetail() throws Exception {
        var response = get("/boom");

        assertProblem(response, 500, "/boom");
        assertThat(problem(response).detail().or("")).isEqualTo("Internal server error");
        assertThat(response.body()).doesNotContain(SECRET);
    }

    /// Terra's own row: a request that arrives while the host drains is refused before routing.
    @Test void admissionRefusalWhileDraining_is503ProblemJson() throws Exception {
        var result = Promise.<String>promise();
        var entered = new CountDownLatch(1);
        var slow = Route.<String>get("/slow").withoutParameters().to(_ -> { entered.countDown(); return result; })
                        .withSecurity(SecurityPolicy.publicRoute()).asText();
        var host = start(List.of(slow));
        try (var client = HttpClient.newHttpClient()) {
            var inFlight = client.sendAsync(request(host, "/slow"), HttpResponse.BodyHandlers.ofString());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closed = host.close();

            assertProblem(send(client, host, "/slow"), 503, "/slow");
            result.succeed("completed");
            assertThat(inFlight.get(10, TimeUnit.SECONDS).body()).isEqualTo("completed");
            assertThat(closed.await(timeSpan(10).seconds()).isSuccess()).isTrue();
        } finally { result.succeed("cleanup"); close(host); }
    }

    /// Tripwire (enabled): an over-limit body is answered by the listener's request gate BEFORE routing (in the
    /// http-server module, #3712 territory), with `{"error":...}` as `application/json`. When the gate answers
    /// problem+json, flip to the disabled assertion beside it.
    @Test void bodyTooLarge_isAnsweredByTheGate_asJsonErrorEnvelopeToday() throws Exception {
        var response = oversizedBody();

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("<none>")).startsWith("application/json");
        assertThat(response.body()).startsWith("{\"error\":");
    }

    @Disabled("#2791 part 1 leaves the gate: enable with the http-server change that makes RequestGate answer problem+json")
    @Test void bodyTooLarge_isProblemJson() throws Exception {
        assertProblem(oversizedBody(), 413, "/plain");
    }

    private static HttpResponse<String> oversizedBody() throws Exception {
        var host = start(routes(), HttpServerConfig.httpServerConfig("terra-problem", 0).withMaxContentLength(64));
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + "/plain")).timeout(Duration.ofSeconds(5))
                                     .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4096))).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } finally { close(host); }
    }

    private static void assertProblem(HttpResponse<String> response, int status, String path) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("<none>")).startsWith("application/problem+json");
        var problem = problem(response);

        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.type()).as("part 2 owns `type`; part 1 leaves it as it is").isEqualTo("about:blank");
        assertThat(problem.instance().or("")).isEqualTo(path);
    }

    private static ProblemDetail problem(HttpResponse<String> response) {
        return ProblemDetailJson.CODEC.decode(response.body().getBytes(StandardCharsets.UTF_8)).unwrap();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        var host = start(routes());
        try (var client = HttpClient.newHttpClient()) {
            return send(client, host, path);
        } finally { close(host); }
    }

    private static HttpResponse<String> send(HttpClient client, TerraHttpServer host, String path) throws Exception {
        return client.send(request(host, path), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(TerraHttpServer host, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path)).timeout(Duration.ofSeconds(5)).build();
    }

    private static TerraHttpServer start(List<Route<?>> routes) {
        return start(routes, HttpServerConfig.httpServerConfig("terra-problem", 0));
    }

    private static TerraHttpServer start(List<Route<?>> routes, HttpServerConfig transport) {
        var config = TerraHttpConfig.terraHttpConfig(transport, RouteMountMode.pathMode(), SecurityPolicy.apiKeyRequired()).unwrap();

        return TerraHttpServer.start(application(), config, HttpAuthenticator.apiKeyValidator(KEYS),
            List.of(new Routes<>(Sample.class, routes)), JsonMapper.defaultJsonMapper())
            .await(timeSpan(10).seconds()).unwrap();
    }

    private static TerraApplication application() {
        var factory = new Factory<>(Sample.class, new Sample());
        var provider = new ResourceProviderFacade() {
            public <R> Promise<R> provide(Class<R> type, String section) { return cause("unexpected resource").promise(); }
            public <R> Promise<R> provide(Class<R> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
        };
        return TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory),
            _ -> ConfigurationProvider.builder().build(), provider).await(timeSpan(5).seconds()).unwrap();
    }

    private static void close(TerraHttpServer host) {
        assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
    }
}
