// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.config.SecurityMode;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #888 — the FULL `security_mode` x effective-policy grid, through a live `AppHttpServer` with a
/// real `HttpClient`, one anonymous `GET` per cell.
///
/// Four server configurations (`none`, `api-key`, `jwt` WITHOUT `jwks_url`, `jwt` WITH it) times six
/// effective policies (`public`, `authenticated`, `role:admin`, `unspecified`, `api_key`,
/// `bearer_token`). The expected status per cell is written down as a literal table, not derived from
/// the code under test, so a change to any cell is a change to this file.
///
/// The #888 cells are `jwt`-without-config x every non-`public` policy. Before the fix that
/// configuration installed `permitAllValidator`, whose context carries `Role.ADMIN`, so all five
/// answered `200` to an anonymous caller — `role:admin` included. Reverting the production hunk in
/// `AppHttpServerAdapter#buildSecurityValidator` turns exactly those five cells red.
///
/// The `public` column doubles as the harness's positive control: every configuration must actually
/// SERVE a request, so a refused cell cannot be mistaken for a server that never came up.
///
/// JWKS fetching is lazy (`JwksKeyStore` fetches on first token lookup), so the jwt-with-config
/// column never touches the network: an anonymous request fails at `MISSING_BEARER_TOKEN`.
class AppHttpServerSecurityGridTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node-sec-grid").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:grid:1.0.0").unwrap();
    private static final String VALID_API_KEY = "sec-grid-test-key-13579";
    private static final String UNREACHABLE_JWKS = "http://127.0.0.1:1/.well-known/jwks.json";
    private static final int PORT = 19094;
    private static final String SERVED = "served-locally";

    private AppHttpServer server;
    private HttpClient httpClient;

    /// The server-side configuration axis. `JWT_WITHOUT_CONFIG` is `security_mode = "jwt"` with no
    /// `[app-http] jwks_url` — the #888 configuration.
    enum Setup {
        NONE,
        API_KEY,
        JWT_WITHOUT_CONFIG,
        JWT_WITH_CONFIG;

        AppHttpConfig config() {
            return switch (this) {
                case NONE -> appHttp(SecurityMode.NONE, Option.empty());
                case API_KEY -> AppHttpConfig.appHttpConfig(PORT, Set.of(VALID_API_KEY));
                case JWT_WITHOUT_CONFIG -> appHttp(SecurityMode.JWT, Option.empty());
                case JWT_WITH_CONFIG -> appHttp(SecurityMode.JWT, Option.some(JwtConfig.jwtConfig(UNREACHABLE_JWKS).unwrap()));
            };
        }

        private static AppHttpConfig appHttp(SecurityMode mode, Option<JwtConfig> jwtConfig) {
            return AppHttpConfig.appHttpConfig(true,
                                               PORT,
                                               Map.of(),
                                               AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                               mode,
                                               jwtConfig,
                                               HttpProtocol.H1)
                                .unwrap();
        }
    }

    /// The route-side policy axis: every value `RouteSecurityLevel` can declare plus the two only an
    /// operator override can produce.
    enum Policy {
        PUBLIC(SecurityPolicy.publicRoute()),
        AUTHENTICATED(SecurityPolicy.authenticated()),
        ROLE_ADMIN(SecurityPolicy.roleRequired("admin")),
        UNSPECIFIED(SecurityPolicy.unspecified()),
        API_KEY(SecurityPolicy.apiKeyRequired()),
        BEARER_TOKEN(SecurityPolicy.bearerTokenRequired());

        final SecurityPolicy policy;

        Policy(SecurityPolicy policy) {
            this.policy = policy;
        }
    }

    record Cell(Setup setup, Policy policy, int expectedStatus) {
        String name() {
            return setup + " x " + policy + " -> " + expectedStatus;
        }
    }

    /// The pinned grid, anonymous caller. Read: `none` refuses every auth-requiring policy before any
    /// validator runs (`NO_VALIDATOR_CONFIGURED`) and resolves `unspecified` to its global `public`
    /// policy; `api-key` and both `jwt` columns resolve `unspecified` to their credential type and
    /// refuse every non-`public` policy for want of a credential (or, for the mismatched credential
    /// type, with `UNENFORCEABLE_POLICY`). No configuration serves anything but `public` anonymously.
    static final List<Cell> GRID = List.of(new Cell(Setup.NONE, Policy.PUBLIC, 200),
                                           new Cell(Setup.NONE, Policy.AUTHENTICATED, 401),
                                           new Cell(Setup.NONE, Policy.ROLE_ADMIN, 401),
                                           new Cell(Setup.NONE, Policy.UNSPECIFIED, 200),
                                           new Cell(Setup.NONE, Policy.API_KEY, 401),
                                           new Cell(Setup.NONE, Policy.BEARER_TOKEN, 401),
                                           new Cell(Setup.API_KEY, Policy.PUBLIC, 200),
                                           new Cell(Setup.API_KEY, Policy.AUTHENTICATED, 401),
                                           new Cell(Setup.API_KEY, Policy.ROLE_ADMIN, 401),
                                           new Cell(Setup.API_KEY, Policy.UNSPECIFIED, 401),
                                           new Cell(Setup.API_KEY, Policy.API_KEY, 401),
                                           new Cell(Setup.API_KEY, Policy.BEARER_TOKEN, 401),
                                           new Cell(Setup.JWT_WITHOUT_CONFIG, Policy.PUBLIC, 200),
                                           new Cell(Setup.JWT_WITHOUT_CONFIG, Policy.AUTHENTICATED, 401),
                                           new Cell(Setup.JWT_WITHOUT_CONFIG, Policy.ROLE_ADMIN, 401),
                                           new Cell(Setup.JWT_WITHOUT_CONFIG, Policy.UNSPECIFIED, 401),
                                           new Cell(Setup.JWT_WITHOUT_CONFIG, Policy.API_KEY, 401),
                                           new Cell(Setup.JWT_WITHOUT_CONFIG, Policy.BEARER_TOKEN, 401),
                                           new Cell(Setup.JWT_WITH_CONFIG, Policy.PUBLIC, 200),
                                           new Cell(Setup.JWT_WITH_CONFIG, Policy.AUTHENTICATED, 401),
                                           new Cell(Setup.JWT_WITH_CONFIG, Policy.ROLE_ADMIN, 401),
                                           new Cell(Setup.JWT_WITH_CONFIG, Policy.UNSPECIFIED, 401),
                                           new Cell(Setup.JWT_WITH_CONFIG, Policy.API_KEY, 401),
                                           new Cell(Setup.JWT_WITH_CONFIG, Policy.BEARER_TOKEN, 401));

    @AfterEach
    void tearDown() {
        stopServer();
    }

    @TestFactory
    Stream<DynamicTest> anonymousRequest_perGridCell() {
        return GRID.stream()
                   .map(cell -> DynamicTest.dynamicTest(cell.name(), () -> assertCell(cell)));
    }

    @Test
    void grid_coversEveryConfigurationAndEveryPolicyExactlyOnce() {
        var expected = (long) Setup.values().length * Policy.values().length;
        var distinct = GRID.stream()
                           .map(cell -> cell.setup() + "/" + cell.policy())
                           .distinct()
                           .count();

        assertThat(GRID).hasSize((int) expected);
        assertThat(distinct).isEqualTo(expected);
    }

    /// #888 acceptance (3): boot with `security_mode = "jwt"` and no jwt config, declare `role:admin`,
    /// and an anonymous caller must be refused. Before the fix this answered `200`: the injected
    /// `permitAll` context holds `Role.ADMIN`, so `enforceRoleIfRequired` passed.
    @Test
    void jwtModeWithoutJwtConfig_refusesRoleAdminRouteToAnonymousCaller() throws Exception {
        startServer(Setup.JWT_WITHOUT_CONFIG, SecurityPolicy.roleRequired("admin"));

        var response = get("/local/thing", Option.none());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain(SERVED);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"Aether\"");
    }

    /// No credential can satisfy the no-config validator either: a bearer token presented to a node
    /// that has nothing to verify it against is refused, not waved through.
    @Test
    void jwtModeWithoutJwtConfig_refusesRoleAdminRoute_evenWithABearerTokenPresented() throws Exception {
        startServer(Setup.JWT_WITHOUT_CONFIG, SecurityPolicy.roleRequired("admin"));

        var response = get("/local/thing", Option.some("Bearer eyJhbGciOiJub25lIn0.e30."));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain(SERVED);
    }

    /// #888 acceptance (4): whatever a misconfigured `jwt` node does, it must not be MORE permissive
    /// than `security_mode = "none"`. Compared live, per policy, against the same anonymous request.
    @Test
    void jwtModeWithoutJwtConfig_isNeverMorePermissiveThanNone() throws Exception {
        for (var policy : Policy.values()) {
            var underNone = statusFor(Setup.NONE, policy);
            var underJwtWithoutConfig = statusFor(Setup.JWT_WITHOUT_CONFIG, policy);

            if (underNone != 200) {
                assertThat(underJwtWithoutConfig).as("policy %s: none refused with %d, jwt-without-config must not serve",
                                                     policy,
                                                     underNone)
                                                 .isNotEqualTo(200);
            }
        }
    }

    /// Each cell owns its server: `@AfterEach` runs once per `@TestFactory`, not once per dynamic
    /// test, so without the `finally` the first cell's server would stay bound on the port and answer
    /// every later cell — a NONE-mode server hosting a `public` route serves everything.
    private void assertCell(Cell cell) throws Exception {
        startServer(cell.setup(), cell.policy().policy);

        try {
            var response = get("/local/thing", Option.none());

            assertThat(response.statusCode()).as(cell.name()).isEqualTo(cell.expectedStatus());

            if (cell.expectedStatus() == 200) {
                assertThat(response.body()).as(cell.name()).contains(SERVED);
            } else {
                assertThat(response.body()).as(cell.name()).doesNotContain(SERVED);
            }
        } finally {
            stopServer();
        }
    }

    private int statusFor(Setup setup, Policy policy) throws Exception {
        startServer(setup, policy.policy);

        try {
            return get("/local/thing", Option.none()).statusCode();
        } finally {
            stopServer();
        }
    }

    private void startServer(Setup setup, SecurityPolicy routePolicy) {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        var publisher = StubRoutePublisher.hosting("GET", "/local/", routePolicy);

        server = AppHttpServer.appHttpServer(setup.config(),
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
        server.start().await().unwrap();
    }

    private void stopServer() {
        if (server != null) {
            server.stop().await();
            server = null;
        }
    }

    private HttpResponse<String> get(String path, Option<String> authorization) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + path)).GET();

        authorization.onPresent(value -> builder.header("Authorization", value));

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /// Minimal HttpRoutePublisher stub hosting exactly one local route carrying a caller-supplied
    /// SecurityPolicy — mirrors AppHttpServerRouteSecurityPolicyTest's StubRoutePublisher.
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
            return Promise.success(HttpResponseData.httpResponseData(200, "{\"result\":\"" + SERVED + "\"}"));
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
