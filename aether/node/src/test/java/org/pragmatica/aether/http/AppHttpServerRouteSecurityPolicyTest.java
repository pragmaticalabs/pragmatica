// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #763 — request-time resolution of a route's declared security level, through the real server.
///
/// Three states, three tests: (a) a route that never declared a `[security]` stance
/// (`SecurityPolicy.unspecified()`) must INHERIT whatever the server's global policy demands;
/// (b) a route explicitly declared `public` must BYPASS the global policy regardless of server
/// mode; (c) a route explicitly declared `authenticated` is unaffected by the fix — it required a
/// credential before and still does. All three run against a live `AppHttpServer` in API_KEY mode
/// with a real `HttpClient`, mirroring [AppHttpServerLocalDispatchTest]'s local-route harness.
///
/// State (a) is pinned by TWO independent hunks living on either side of the compile-time/run-time
/// boundary: this test pins `AppHttpServerAdapter#isExplicitPolicy` (revert it and an `Unspecified`
/// route policy is treated as "explicit", so it stops falling back to the global policy — the
/// unauthenticated request that should 401 instead dispatches). The companion hunk,
/// `RouteConfigLoader#DEFAULT_SECURITY` (an absent `[security]` section must parse to `UNSPECIFIED`,
/// not `PUBLIC`), is compile-time codegen input that never reaches this runtime harness — it is
/// pinned separately by
/// `RouteConfigLoaderTest.MissingSecuritySection#load_succeeds_withUnspecifiedDefault_whenSecuritySectionMissing`.
class AppHttpServerRouteSecurityPolicyTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node-route-sec").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final String VALID_API_KEY = "route-sec-test-key-98765";
    private static final int PORT = 19093;

    private AppHttpServer server;
    private HttpClient httpClient;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().await();
        }
    }

    private void startServerWithRoutePolicy(SecurityPolicy routePolicy) {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        var config = AppHttpConfig.appHttpConfig(PORT, Set.of(VALID_API_KEY));
        var publisher = StubRoutePublisher.hosting("GET", "/local/", routePolicy);

        server = AppHttpServer.appHttpServer(config,
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             SELF_NODE,
                                             HttpRouteRegistry.httpRouteRegistry(),
                                             Option.some(publisher),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none());
        server.start().await();
    }

    @Test
    void dispatch_inheritsGlobalPolicy_whenRouteSecurityUnspecified() throws Exception {
        // #763 (a): no [security] section => SecurityPolicy.unspecified() at the route => the
        // global API_KEY policy applies => an unauthenticated request must 401, not serve.
        startServerWithRoutePolicy(SecurityPolicy.unspecified());

        var response = get("/local/thing");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void dispatch_bypassesGlobalPolicy_whenRouteDeclaredPublic() throws Exception {
        // #763 (b): an explicit `default = "public"` (or per-route "public") => bypasses the
        // global API_KEY policy for exactly this route => an unauthenticated request is served.
        startServerWithRoutePolicy(SecurityPolicy.publicRoute());

        var response = get("/local/thing");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("served-locally");
    }

    @Test
    void dispatch_staysAuthenticated_whenRouteExplicitlyAuthenticated() throws Exception {
        // #763 (c): an explicitly authenticated route is unchanged by the fix — still requires a
        // credential regardless of global mode, and still serves once one is presented.
        startServerWithRoutePolicy(SecurityPolicy.authenticated());

        var withoutKey = get("/local/thing");
        assertThat(withoutKey.statusCode()).isEqualTo(401);

        var withKey = getWithApiKey("/local/thing", VALID_API_KEY);
        assertThat(withKey.statusCode()).isEqualTo(200);
        assertThat(withKey.body()).contains("served-locally");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + PORT + path))
                                 .GET()
                                 .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithApiKey(String path, String apiKey) throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + PORT + path))
                                 .header("X-API-Key", apiKey)
                                 .GET()
                                 .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /// Minimal HttpRoutePublisher stub hosting exactly one local route carrying a caller-supplied
    /// SecurityPolicy — mirrors AppHttpServerLocalDispatchTest's StubRoutePublisher, parameterized
    /// by policy instead of hard-coding SecurityPolicy.publicRoute().
    private record StubRoutePublisher(String httpMethod, String pathPrefix, SecurityPolicy security, SliceRouter router)
        implements HttpRoutePublisher {
        static StubRoutePublisher hosting(String httpMethod, String pathPrefix, SecurityPolicy security) {
            return new StubRoutePublisher(httpMethod, pathPrefix, security, new StubSliceRouter());
        }

        private boolean matches(String method, String path) {
            return httpMethod.equalsIgnoreCase(method) && path.startsWith(pathPrefix);
        }

        @Override
        public Set<HttpNodeRouteKey> allLocalRoutes() {
            return Set.of(HttpNodeRouteKey.httpNodeRouteKey(httpMethod, pathPrefix, SELF_NODE));
        }

        @Override
        public Option<SliceRouter> findLocalRouter(String method, String prefix) {
            return matches(method, prefix)
                   ? Option.some(router)
                   : Option.none();
        }

        @Override
        public Option<LocalRouteInfo> findLocalRoute(String method, String path) {
            return matches(method, path)
                   ? Option.some(new LocalRouteInfo(httpMethod, pathPrefix, TEST_ARTIFACT.asString(), "create", security))
                   : Option.none();
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

    /// Minimal SliceRouter stub returning a fixed 200; unversioned registry, identity observability.
    private static final class StubSliceRouter implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            return Promise.success(HttpResponseData.httpResponseData(200, "{\"result\":\"served-locally\"}"));
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return SliceVersionRegistry.UNVERSIONED;
        }

        @Override
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return this;
        }

        @Override
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return this;
        }
    }
}
