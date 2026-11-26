// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.ProblemDetailJson;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2791 part 1, Aether side: every error response on a slice route is `application/problem+json` with a body that parses
/// as a [ProblemDetail] carrying the response status. Each case runs over the real Netty path with a REAL [SliceRouter]
/// behind the local route, so a router row (route miss, validation, Cause failure, unexpected exception) and an
/// AppHttpServer row (auth refusal) are asserted on the wire, status + content type + parsed body. The `type` member is
/// part 2 and stays `about:blank`.
class AppHttpServerProblemJsonTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("problem-node").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final String SECRET = "disk-secret-do-not-leak";

    record Invalid(String message) implements Cause.Validation {}

    record Conflicting(String message) implements HttpStatusAware {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    private static Route<String> failing(Cause failure) {
        return Route.<String> get("/local/thing")
                    .withoutParameters()
                    .to(_ -> failure.<String> promise())
                    .asJson();
    }

    private static Route<String> okRoute() {
        return Route.<String> get("/local/thing")
                    .withoutParameters()
                    .to(_ -> Promise.success("ok"))
                    .asJson();
    }

    private record Answer(HttpResponse<String> http) {
        String contentType() {
            return http.headers().firstValue("Content-Type").orElse("<none>");
        }

        ProblemDetail problem() {
            return ProblemDetailJson.CODEC.decode(http.body().getBytes(StandardCharsets.UTF_8)).unwrap();
        }
    }

    private static void assertProblem(Answer answer, int status, String path) {
        assertThat(answer.http().statusCode()).isEqualTo(status);
        assertThat(answer.contentType()).startsWith("application/problem+json");
        var problem = answer.problem();

        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.type()).as("part 2 owns `type`; part 1 leaves it as it is").isEqualTo("about:blank");
        assertThat(problem.instance().or("")).isEqualTo(path);
    }

    @Test
    void routeMiss_inTheSliceRouter_isProblemJson() throws Exception {
        assertProblem(answer(okRoute(), "/local/missing"), 404, "/local/missing");
    }

    @Test
    void validationFailure_is422ProblemJson() throws Exception {
        assertProblem(answer(failing(new Invalid("name must not be blank")), "/local/thing"), 422, "/local/thing");
    }

    @Test
    void causeFailureWithoutHttpStatusAware_is500ProblemJson_withTodaysClientText() throws Exception {
        var failure = Causes.cause(SECRET);
        var answer = answer(failing(failure), "/local/thing");

        assertProblem(answer, 500, "/local/thing");
        assertThat(answer.problem().detail().or("")).as("no text beyond HttpError.clientMessage").isEqualTo(HttpError.clientMessage(failure));
    }

    @Test
    void causeFailureWithHttpStatusAware_isItsDeclaredStatusProblemJson() throws Exception {
        assertProblem(answer(failing(new Conflicting("order already exists")), "/local/thing"), 409, "/local/thing");
    }

    @Test
    void unexpectedException_is500ProblemJson_withTodaysClientText() throws Exception {
        var failure = Causes.fromThrowable(new IllegalStateException(SECRET));
        var answer = answer(failing(failure), "/local/thing");

        assertProblem(answer, 500, "/local/thing");
        assertThat(answer.problem().detail().or("")).as("no text beyond HttpError.clientMessage").isEqualTo(HttpError.clientMessage(failure));
    }

    /// The AppHttpServer row: a refusal before any slice runs. Already `application/problem+json` at the base; kept as the
    /// guard that the encoder change did not move it.
    @Test
    void authRefusal_is401ProblemJson() throws Exception {
        var server = server(AppHttpConfig.appHttpConfig(0, Set.of("some-valid-key")), okRoute(), SecurityPolicy.apiKeyRequired());

        try {
            assertProblem(new Answer(send(server, "/local/thing", "GET", "")), 401, "/local/thing");
        } finally {
            server.stop().await();
        }
    }

    /// Tripwire (enabled): an over-limit body is answered by the listener's request gate BEFORE routing
    /// (RequestGate#onRefused, in the http-server module #3712 is editing), with `{"error":...}` as `application/json`.
    /// When the gate answers problem+json, flip to the disabled assertion beside it.
    @Test
    void bodyTooLarge_isAnsweredByTheGate_asJsonErrorEnvelopeToday() throws Exception {
        var answer = oversizedBody();

        assertThat(answer.http().statusCode()).isEqualTo(413);
        assertThat(answer.contentType()).startsWith("application/json");
        assertThat(answer.http().body()).startsWith("{\"error\":");
    }

    @Disabled("#2791 part 1 leaves the gate: enable with the http-server change that makes RequestGate answer problem+json")
    @Test
    void bodyTooLarge_isProblemJson() throws Exception {
        assertProblem(oversizedBody(), 413, "/local/thing");
    }

    private Answer oversizedBody() throws Exception {
        var config = AppHttpConfig.appHttpConfig(true, 0, Map.of(), 64, SecurityMode.NONE, Option.none(), HttpProtocol.H1)
                                  .unwrap();
        var server = server(config, okRoute());

        try {
            return new Answer(send(server, "/local/thing", "POST", "x".repeat(4096)));
        } finally {
            server.stop().await();
        }
    }

    private Answer answer(Route<?> route, String path) throws Exception {
        var server = server(AppHttpConfig.insecureAppHttpConfig(0), route);

        try {
            return new Answer(send(server, path, "GET", ""));
        } finally {
            server.stop().await();
        }
    }

    private static HttpResponse<String> send(AppHttpServer server, String path, String method, String body) throws Exception {
        var port = TlsProbe.requireBoundPort(server.boundPort(), "app listener");
        var request = java.net.http.HttpRequest.newBuilder()
                                               .uri(URI.create("http://localhost:" + port + path))
                                               .timeout(Duration.ofSeconds(5))
                                               .method(method, body.isEmpty() ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                                               .build();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static AppHttpServer server(AppHttpConfig config, Route<?> route) {
        return server(config, route, SecurityPolicy.publicRoute());
    }

    private static AppHttpServer server(AppHttpConfig config, Route<?> route, SecurityPolicy policy) {
        var router = SliceRouter.sliceRouter(RouteSource.routeSource(route), ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());
        var server = AppHttpServer.appHttpServer(config,
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 SELF_NODE,
                                                 HttpRouteRegistry.httpRouteRegistry(),
                                                 Option.some(new StubRoutePublisher(router, policy)),
                                                 Option.none(), Option.none(), Option.none(), Option.none(),
                                                 Option.none(), Option.none(), Option.none(),
                                                 Option.<org.pragmatica.aether.update.DeploymentManager>none());

        TlsProbe.requireStarted(server.start().await(), "app listener");

        return server;
    }

    /// One live local route under `/local/`, the same shape as AppHttpServerLocalRouteFailureStatusTest.
    private record StubRoutePublisher(SliceRouter router, SecurityPolicy policy) implements HttpRoutePublisher {
        private boolean matches(String path) {
            return path.startsWith("/local/");
        }

        @Override
        public Set<HttpNodeRouteKey> allLocalRoutes() {
            return Set.of(HttpNodeRouteKey.httpNodeRouteKey("GET", "/local/", SELF_NODE),
                          HttpNodeRouteKey.httpNodeRouteKey("POST", "/local/", SELF_NODE));
        }

        @Override
        public Option<SliceRouter> findLocalRouter(String method, String prefix) {
            return matches(prefix)
                   ? Option.some(router)
                   : Option.none();
        }

        @Override
        public Option<LocalRouteInfo> findLocalRoute(String method, String path) {
            return matches(path)
                   ? Option.some(new LocalRouteInfo(method,
                                                    "/local/",
                                                    TEST_ARTIFACT.asString(),
                                                    "create",
                                                    policy))
                   : Option.none();
        }

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact,
                                           ClassLoader classLoader,
                                           Object sliceInstance,
                                           SliceInvokerFacade invokerFacade) {
            return Promise.success(unit());
        }

        @Override
        public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {
            return true;
        }

        @Override
        public Promise<Unit> unpublishRoutes(Artifact artifact) {
            return Promise.success(unit());
        }

        @Override
        public Option<SliceRouter> getSliceRouter(Artifact artifact) {
            return Option.some(router);
        }

        @Override
        public Unit updateSecurityOverrides(SecurityOverrides overrides) {
            return unit();
        }

        @Override
        public Unit setVersioningMetricsSink(VersioningMetricsSink sink) {
            return unit();
        }

        @Override
        public Map<Artifact, SliceVersionRegistry> versionRegistries() {
            return Map.of();
        }

        @Override
        public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
            return unit();
        }
    }
}
