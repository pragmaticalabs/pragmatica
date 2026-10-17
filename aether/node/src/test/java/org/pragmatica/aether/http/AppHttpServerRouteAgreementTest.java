// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #884, the dispatch half: the slice whose policy ADMITS a request must be the slice that SERVES
/// it. `findRouteSecurityPolicy` resolves the policy through
/// `HttpRoutePublisher.findLocalRoute`; `dispatchToRoute` used to run its OWN first-match scan
/// over `allLocalRoutes()` and could land on a different route entirely.
///
/// **Why the stub's route set is ORDERED, and what that costs.** Production's `allLocalRoutes()`
/// returns `Set.copyOf(...)`, an `ImmutableCollections.SetN` whose probe sequence is derived from
/// a `SALT` seeded once per JVM -- so the old first-match scan picked the parent on some node
/// starts and the child on others, and a test inside one JVM cannot vary that. The stub below
/// therefore pins ONE of the orders the real set takes: parent first, the order under which
/// first-match loses. That makes this a deterministic tripwire for a defect whose production form
/// is a coin flip, and it is the honest limit of the instrument -- it demonstrates the
/// disagreement, it does not sample its frequency.
class AppHttpServerRouteAgreementTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("agreement-node").unwrap();
    private static final Artifact OUTER = Artifact.artifact("com.example:agree-outer:1.0.0").unwrap();
    private static final Artifact INNER = Artifact.artifact("com.example:agree-inner:1.0.0").unwrap();
    private static final String METHOD = "GET";
    private static final String OUTER_PREFIX = "/api/agree/";
    private static final String INNER_PREFIX = "/api/agree/inner/";
    private static final int TEST_PORT = 18099;

    private HttpRouteRegistry registry;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();
    }

    /// The request the ticket describes: a path under the INNER prefix. The policy half resolves
    /// it to the inner slice, so the inner slice must answer it. Before the fix, dispatch scanned
    /// the local route set and took the first match -- the OUTER route -- and the parent slice
    /// answered a request the child's policy had admitted.
    @Test
    void aPathUnderTheNestedPrefix_isServedByTheSliceWhosePolicyAdmittedIt() throws Exception {
        var server = serverWith(nestedPairPublisher());

        server.start()
              .await();
        try {
            var response = get("/api/agree/inner/report");

            assertThat(response.statusCode()).as("the inner route is public, so the request is admitted")
                                             .isEqualTo(200);
            assertThat(response.body()).as("the slice that ANSWERED must be the one whose policy was consulted")
                                       .contains("served-by-inner")
                                       .doesNotContain("served-by-outer");
        } finally {
            server.stop()
                  .await();
        }
    }

    /// Positive control for the test above: the two routes really do carry different policies, and
    /// the difference really is reachable. A path under the OUTER prefix only resolves to the
    /// outer route, whose `api_key` policy cannot be satisfied under `SecurityMode.NONE`, so it is
    /// refused. Without this, "admitted under the inner route's policy" would be a claim about a
    /// distinction nothing in the fixture exercises.
    @Test
    void aPathOutsideTheNestedPrefix_isGovernedByTheOuterRoutesStricterPolicy() throws Exception {
        var server = serverWith(nestedPairPublisher());

        server.start()
              .await();
        try {
            var response = get("/api/agree/items/42");

            assertThat(response.statusCode()).as("the outer route requires an api key; no validator is configured")
                                             .isEqualTo(401);
            assertThat(response.body()).doesNotContain("served-by-inner")
                                       .doesNotContain("served-by-outer");
        } finally {
            server.stop()
                  .await();
        }
    }

    /// The forwarded-request path resolves its local router through the same rule
    /// (`findLocalRouterForPath`), so it is pinned here through the publisher rather than over the
    /// wire: the router for the inner path must be the inner slice's.
    @Test
    void theLocalRouterForANestedPath_belongsToTheInnerSlice() {
        var publisher = nestedPairPublisher();
        var picked = publisher.findLocalRoute(METHOD, "/api/agree/inner/report");

        assertThat(picked.isPresent()).isTrue();
        assertThat(picked.unwrap()
                         .artifactCoord()).isEqualTo(INNER.asString());
        assertThat(publisher.findLocalRouter(METHOD,
                                             picked.unwrap()
                                                   .pathPrefix())
                            .isPresent()).isTrue();
    }

    private AppHttpServer serverWith(HttpRoutePublisher publisher) {
        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(TEST_PORT),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           registry,
                                           Option.some(publisher),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<org.pragmatica.aether.update.DeploymentManager>none());
    }

    private static HttpRoutePublisher nestedPairPublisher() {
        return new NestedPairPublisher();
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + TEST_PORT + path))
                                 .GET()
                                 .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /// Two locally-hosted slices with NESTED prefixes and DIFFERENT policies -- the configuration
    /// #884 names. `findLocalRoute` applies the longest-prefix rule; `allLocalRoutes` returns the
    /// parent first, one of the orders the production `Set.copyOf` takes.
    private record Route(String prefix, Artifact artifact, SecurityPolicy security, SliceRouter router) {}

    private static final class NestedPairPublisher implements HttpRoutePublisher {
        private final List<Route> routes = List.of(new Route(OUTER_PREFIX,
                                                             OUTER,
                                                             SecurityPolicy.apiKeyRequired(),
                                                             new BodySliceRouter("served-by-outer")),
                                                   new Route(INNER_PREFIX,
                                                             INNER,
                                                             SecurityPolicy.publicRoute(),
                                                             new BodySliceRouter("served-by-inner")));

        @Override
        public Set<HttpNodeRouteKey> allLocalRoutes() {
            var ordered = new LinkedHashSet<HttpNodeRouteKey>();

            routes.forEach(route -> ordered.add(HttpNodeRouteKey.httpNodeRouteKey(METHOD, route.prefix(), SELF_NODE)));

            return ordered;
        }

        @Override
        public Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path) {
            return longestMatch(httpMethod, path).map(route -> new LocalRouteInfo(METHOD,
                                                                                  route.prefix(),
                                                                                  route.artifact()
                                                                                       .asString(),
                                                                                  "handle",
                                                                                  route.security()));
        }

        @Override
        public Option<SliceRouter> findLocalRouter(String httpMethod, String pathPrefix) {
            return Option.from(routes.stream()
                                     .filter(route -> METHOD.equalsIgnoreCase(httpMethod) && route.prefix()
                                                                                                  .equals(pathPrefix))
                                     .findFirst())
                         .map(Route::router);
        }

        private Option<Route> longestMatch(String httpMethod, String path) {
            var normalized = path.endsWith("/")
                             ? path
                             : path + "/";

            return Option.from(routes.stream()
                                     .filter(route -> METHOD.equalsIgnoreCase(httpMethod) && normalized.startsWith(route.prefix()))
                                     .max(java.util.Comparator.comparingInt(route -> route.prefix()
                                                                                          .length())));
        }

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
            return Promise.success(unit());
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
        public Option<HttpRequestHandler> getHandler(Artifact artifact) {
            return Option.none();
        }

        @Override
        public Option<SliceRouter> getSliceRouter(Artifact artifact) {
            return Option.from(routes.stream()
                                     .filter(route -> route.artifact()
                                                           .equals(artifact))
                                     .findFirst())
                         .map(Route::router);
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
        public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
            return unit();
        }

        @Override
        public Map<Artifact, SliceVersionRegistry> versionRegistries() {
            return Map.of();
        }
    }

    /// A router that answers 200 with a fixed body naming the slice it belongs to.
    private record BodySliceRouter(String body) implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            return Promise.success(HttpResponseData.httpResponseData(200, "{\"result\":\"" + body + "\"}"));
        }

        @Override
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return this;
        }

        @Override
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return this;
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return SliceVersionRegistry.UNVERSIONED;
        }
    }
}
