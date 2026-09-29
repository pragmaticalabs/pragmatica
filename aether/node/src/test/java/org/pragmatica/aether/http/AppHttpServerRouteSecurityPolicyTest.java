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
import org.pragmatica.aether.http.HttpRoutePublisher.LocalResolution;
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
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
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
/// A fourth test (`dispatch_doesNotAdoptRemotePolicy_whenLocalRouteMatchedButUndeclared`) pins the
/// #866 review F2 fix: a matched LOCAL route governs its own policy, so state (a) resolves to the
/// global policy even when a remote node advertises a broader, explicitly-public prefix.
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
    private static final NodeId REMOTE_NODE = NodeId.nodeId("remote-node-route-sec").unwrap();
    private static final NodeId OTHER_REMOTE_NODE = NodeId.nodeId("other-remote-node-route-sec").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final Artifact REMOTE_ARTIFACT = Artifact.artifact("com.example:parent:1.0.0").unwrap();
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
        startServer("/local/", routePolicy, HttpRouteRegistry.httpRouteRegistry());
    }

    private void startServer(String pathPrefix, SecurityPolicy routePolicy, HttpRouteRegistry registry) {
        startServer(pathPrefix, routePolicy, registry, SecurityOverrides.EMPTY);
    }

    private void startServer(String pathPrefix,
                             SecurityPolicy routePolicy,
                             HttpRouteRegistry registry,
                             SecurityOverrides committed) {
        startServer(StubRoutePublisher.hosting("GET", pathPrefix, routePolicy, committed), registry);
    }

    private void startServer(StubRoutePublisher publisher, HttpRouteRegistry registry) {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        var config = AppHttpConfig.appHttpConfig(PORT, Set.of(VALID_API_KEY));

        server = AppHttpServer.appHttpServer(config,
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

    @Test
    void dispatch_doesNotAdoptRemotePolicy_whenLocalRouteMatchedButUndeclared() throws Exception {
        // #866 review F2. Two slices with NESTED prefixes -- the examples/pricing-engine topology --
        // in the partially-migrated state the #763 remedy instructions produce: the PARENT slice has
        // been given an explicit `default = "public"`, the CHILD has not been migrated yet.
        //
        // The child's route is LOCAL and Unspecified. findRouteSecurityPolicy used to filter that
        // Unspecified away and then consult REMOTE routes, where the parent's broader
        // "/api/v1/pricing/" prefix still matches: computeRouteTable excludes a remote route only on
        // exact `method:pathPrefix` identity, and route matching is by PREFIX. Once #763 made
        // `Public` adoptable (isExplicitPolicy flipped from filtering Public to filtering
        // Unspecified), the parent's PUBLIC policy was adopted and the child served with no
        // credential. A local match now governs outright: Unspecified means "inherit the global
        // policy", never "ask a remote node".
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remotePublicRoute("GET", "/api/v1/pricing/"));

        // Instrument check: the remote parent route must actually be registered and must carry a
        // DIFFERENT identity from the local child route, or the fallthrough this test pins is not
        // reachable and a passing assertion would prove nothing.
        assertThat(registry.findRoute("GET", "/api/v1/pricing/").isPresent()).isTrue();
        assertThat(registry.allRoutes()).singleElement()
                                        .satisfies(route -> {
                                            assertThat(route.routeIdentity()).isEqualTo("GET:/api/v1/pricing/");
                                            assertThat(route.security()).isEqualTo("PUBLIC");
                                        });

        startServer("/api/v1/pricing/analytics/", SecurityPolicy.unspecified(), registry);

        var withoutKey = get("/api/v1/pricing/analytics/high-value");
        assertThat(withoutKey.statusCode()).isEqualTo(401);

        // The 401 is the policy gate, not a missing route: the same request WITH a credential is
        // served by the local analytics route.
        var withKey = getWithApiKey("/api/v1/pricing/analytics/high-value", VALID_API_KEY);
        assertThat(withKey.statusCode()).isEqualTo(200);
        assertThat(withKey.body()).contains("served-locally");
    }

    /// #1659: this node does NOT host `/echo/`; another node does, and its replicated entry is STALE (published
    /// before the override, UNSPECIFIED). This node's COMMITTED override locks `/echo/` to `role:admin`. The ingress
    /// must enforce it -- before #1659 it authorized against the stale entry, inherited the global API-key policy,
    /// accepted any valid key, and forwarded: fail open.
    @Test
    void remoteRoute_isRefused_byTheIngressCommittedOverride_whenThePeerEntryIsStale() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRoute("GET", "/echo/", "UNSPECIFIED"));

        assertThat(registry.allRoutes()).as("control: the remote route is registered with the stale policy")
                                        .singleElement()
                                        .satisfies(route -> assertThat(route.security()).isEqualTo("UNSPECIFIED"));

        startServer("/local/", SecurityPolicy.unspecified(), registry, adminOverrideOn("GET /echo/*"));

        var response = getWithApiKey("/echo/probe", VALID_API_KEY);

        assertThat(response.statusCode()).as("a valid key without the admin role: %s", response.body()).isEqualTo(403);
        assertThat(response.body()).contains("role 'admin' required");
    }

    /// #1659, no committed override at this ingress: the replicated policy applies -- the STRONGEST a serving node
    /// published. One peer still advertises `role:admin` (its relaxing republish has not landed), another already
    /// UNSPECIFIED: the route stays admin until every node has republished (fail closed).
    @Test
    void remoteRoute_withoutACommittedOverride_isAsStrictAsItsStrictestNode() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRoute("GET", "/echo/", "UNSPECIFIED"));
        registry.onNodeRoutesPut(remoteRouteFrom(OTHER_REMOTE_NODE, "GET", "/echo/", "ROLE:admin"));

        startServer("/local/", SecurityPolicy.unspecified(), registry);

        var response = getWithApiKey("/echo/probe", VALID_API_KEY);

        assertThat(response.statusCode()).as("strictest node governs: %s", response.body()).isEqualTo(403);
    }

    /// #1659 (v1670 P4): two REMOTE routes with nested prefixes, the outer PUBLIC and the inner `ROLE:admin`. The
    /// hosting node serves `/api/admin/secret` by its LONGEST prefix and does not re-authorize a forwarded request,
    /// so the ingress must judge it by the inner route too: 401 without a credential. Before, `findFirst` over the
    /// ascending registry picked the outer PUBLIC route and forwarded it -- the "HTTP forwarding not available" 503.
    @Test
    void remoteRoute_nestedPrefixes_theInnerRouteGovernsItsSubtree() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRouteOf(REMOTE_ARTIFACT, "/api/", "PUBLIC"));
        registry.onNodeRoutesPut(remoteRouteOf(TEST_ARTIFACT, "/api/admin/", "ROLE:admin"));

        assertThat(registry.allRoutes()).as("CONTROL: both nested remote routes are registered").hasSize(2);

        startServer("/local/", SecurityPolicy.unspecified(), registry);

        var response = get("/api/admin/secret");

        assertThat(response.statusCode()).as("the inner route's policy, not the outer PUBLIC one: %s", response.body())
                                         .isEqualTo(401);
    }

    /// #1659 (v1670 P4b): the inner route is UNDECLARED and this ingress's COMMITTED override locks it to
    /// `role:admin`; a broader PUBLIC remote route also matches. A valid key without the role gets 403.
    @Test
    void remoteRoute_nestedPrefixes_committedOverrideOnTheInnerRoute_isEnforced() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRouteOf(REMOTE_ARTIFACT, "/api/", "PUBLIC"));
        registry.onNodeRoutesPut(remoteRouteOf(TEST_ARTIFACT, "/api/admin/", "UNSPECIFIED"));

        startServer("/local/", SecurityPolicy.unspecified(), registry, adminOverrideOn("GET /api/admin/*"));

        var response = getWithApiKey("/api/admin/secret", VALID_API_KEY);

        assertThat(response.statusCode()).as("a valid key without the admin role: %s", response.body()).isEqualTo(403);
        assertThat(response.body()).contains("role 'admin' required");
    }

    /// CONTROL for the two above: with only the inner route registered it is enforced, so their outcome is decided
    /// by which of the nested routes governs, not by the inner route's policy failing on its own.
    @Test
    void remoteRoute_innerRouteAlone_isEnforced() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRouteOf(TEST_ARTIFACT, "/api/admin/", "ROLE:admin"));

        startServer("/local/", SecurityPolicy.unspecified(), registry);

        assertThat(get("/api/admin/secret").statusCode()).isEqualTo(401);
    }

    /// CONTROL, the other side: a request under the outer prefix only is still judged by the outer PUBLIC route and
    /// passes authorization (503: admitted, then no forwarder in this fixture) -- the inner route governs its own
    /// subtree and nothing more.
    @Test
    void remoteRoute_nestedPrefixes_theOuterRouteStillGovernsTheRestOfItsSubtree() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRouteOf(REMOTE_ARTIFACT, "/api/", "PUBLIC"));
        registry.onNodeRoutesPut(remoteRouteOf(TEST_ARTIFACT, "/api/admin/", "ROLE:admin"));

        startServer("/local/", SecurityPolicy.unspecified(), registry);

        var response = get("/api/public-thing");

        assertThat(response.statusCode()).as("admitted by the outer PUBLIC route: %s", response.body()).isEqualTo(503);
        assertThat(response.body()).contains("HTTP forwarding not available");
    }

    /// #1659 (v1670 audit b), through the ingress: the undeclared remote `/api/admin/` is locked to `role:admin` by a
    /// committed override, and a broader PUBLIC override `/api/*` is ALSO committed. A valid key without the role
    /// must get 403 whichever of the two is listed first -- first-listed-wins let the PUBLIC parent shadow the child.
    /// Under the DEFAULT `strengthen_only` policy: `public` is refused on an undeclared route, which then inherits
    /// the global API-key policy and admits any valid key -- the child's `role:admin` never applies.
    @Test
    void remoteRoute_overlappingOverrides_theMostSpecificGoverns_parentListedFirst() throws Exception {
        assertChildOverrideEnforced(List.of(PARENT_PUBLIC_OVERRIDE, CHILD_ADMIN_OVERRIDE));
    }

    @Test
    void remoteRoute_overlappingOverrides_theMostSpecificGoverns_childListedFirst() throws Exception {
        assertChildOverrideEnforced(List.of(CHILD_ADMIN_OVERRIDE, PARENT_PUBLIC_OVERRIDE));
    }

    private static final SecurityOverrides.Entry PARENT_PUBLIC_OVERRIDE = SecurityOverrides.Entry.entry("GET /api/*", "public");
    private static final SecurityOverrides.Entry CHILD_ADMIN_OVERRIDE = SecurityOverrides.Entry.entry("GET /api/admin/*", "role:admin");

    private void assertChildOverrideEnforced(List<SecurityOverrides.Entry> entries) throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRouteOf(TEST_ARTIFACT, "/api/admin/", "UNSPECIFIED"));

        startServer("/local/",
                    SecurityPolicy.unspecified(),
                    registry,
                    SecurityOverrides.securityOverrides(entries, SecurityOverridePolicy.STRENGTHEN_ONLY));

        var response = getWithApiKey("/api/admin/secret", VALID_API_KEY);

        assertThat(response.statusCode()).as("override order %s: %s", entries, response.body()).isEqualTo(403);
        assertThat(response.body()).contains("role 'admin' required");
    }

    /// v1670 P5 control: with NO rollout, an ingress hosting a PUBLIC `/api/` serves `/api/admin/secret` through its
    /// local `/api/` -- local-first dispatch, the protected remote child is never forwarded to. The rollout variant
    /// forwards it; that forward is refused by the host (`AppHttpServerForwardReauthorizationTest`, P5).
    @Test
    void p5Control_withoutARollout_theLocalParentServesAndNothingIsForwarded() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        registry.onNodeRoutesPut(remoteRouteOf(TEST_ARTIFACT, "/api/admin/", "ROLE:admin"));

        startServer("/api/", SecurityPolicy.publicRoute(), registry);

        var response = get("/api/admin/secret");

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        assertThat(response.body()).contains("served-locally");
    }

    /// #1678 (v1670 P6), at a NON-hosting ingress: one remote slice publishes two sibling routes under the base
    /// `/orders/` -- `GET /orders/{id}` PUBLIC and `GET /orders/{id}/admin` `role:admin`. Keyed by base alone, the
    /// sibling put LAST overwrote the other, so one declaration order authorized the admin sibling as PUBLIC. The
    /// ingress now picks the sibling by SHAPE, through the router's own rule: 401 without a credential, in both
    /// orders, while the public sibling stays admitted.
    @Test
    void remoteSiblingRoutes_theAdminSiblingIsEnforced_inEitherPublishOrder() throws Exception {
        for (var adminFirst : List.of(false, true)) {
            var registry = HttpRouteRegistry.httpRouteRegistry();
            registry.onNodeRoutesPut(siblingRoutes(adminFirst));

            startServer("/local/", SecurityPolicy.unspecified(), registry);

            assertThat(get("/orders/5/admin").statusCode()).as("admin sibling, admin first: %s", adminFirst).isEqualTo(401);
            var publicSibling = get("/orders/5");
            assertThat(publicSibling.statusCode()).as("public sibling admitted (503 = forwarded, no forwarder): %s",
                                                      publicSibling.body())
                                                  .isEqualTo(503);
            server.stop().await();
            server = null;
        }
    }

    /// #1678 (CodeRabbit C1): THIS node serves only the PUBLIC sibling `GET /orders/{id}`; the admin sibling
    /// `GET /orders/{id}/admin` lives only on another node. The request for the admin sibling must not be answered
    /// by the local public sibling (a 404 there); it resolves to the REMOTE admin sibling and is authorized by it --
    /// 401 without a credential, 403 for a key without the role -- while `/orders/5` is still served locally.
    @Test
    void siblingServedOnlyElsewhere_isAuthorizedByTheRemoteSibling_notAnsweredByTheLocalOne() throws Exception {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        var localPublic = RouteEntry.activeRoute("GET", "/orders/", "getOrder", "PUBLIC", "PUBLIC", 1, List.of());
        var remoteAdmin = RouteEntry.activeRoute("GET", "/orders/", "adminOrder", "ROLE:admin", "ROLE:admin", 2, List.of("admin"));

        registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(SELF_NODE, TEST_ARTIFACT),
                                                                    NodeRoutesValue.nodeRoutesValue(List.of(localPublic), Epoch.ZERO)),
                                                Option.none()));
        registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(REMOTE_NODE, REMOTE_ARTIFACT),
                                                                    NodeRoutesValue.nodeRoutesValue(List.of(remoteAdmin), Epoch.ZERO)),
                                                Option.none()));
        startServer(new StubRoutePublisher("GET",
                                           "/orders/",
                                           SecurityPolicy.publicRoute(),
                                           new StubSliceRouter(),
                                           SecurityOverrides.EMPTY,
                                           SecurityPolicy.publicRoute(),
                                           path -> !path.contains("/admin"),
                                           Option.some(Set.of(HttpRouteRegistry.RouteInfo.shapeKeyOf(1, List.of())))),
                    registry);

        assertThat(get("/orders/5/admin").statusCode()).as("no credential, remote admin sibling").isEqualTo(401);
        var withKey = getWithApiKey("/orders/5/admin", VALID_API_KEY);
        assertThat(withKey.statusCode()).as("a key without the admin role: %s", withKey.body()).isEqualTo(403);
        assertThat(withKey.body()).contains("role 'admin' required");
        var publicSibling = get("/orders/5");
        assertThat(publicSibling.statusCode()).as("CONTROL: the local public sibling is still served: %s", publicSibling.body())
                                              .isEqualTo(200);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> siblingRoutes(boolean adminFirst) {
        var publicOrder = RouteEntry.activeRoute("GET", "/orders/", "getOrder", "PUBLIC", "PUBLIC", 1, List.of());
        var adminOrder = RouteEntry.activeRoute("GET", "/orders/", "adminOrder", "ROLE:admin", "ROLE:admin", 2, List.of("admin"));
        var routes = adminFirst
                     ? List.of(adminOrder, publicOrder)
                     : List.of(publicOrder, adminOrder);
        var value = NodeRoutesValue.nodeRoutesValue(routes, Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(REMOTE_NODE, TEST_ARTIFACT), value), Option.none());
    }

    /// #1678 (v1670 R2-N4), at the ingress: a request's policy and its dispatch come from ONE local resolution. The
    /// stub's separate `findLocalRoute` answers a stale PUBLIC policy -- what a second lookup could read after an
    /// undeploy between the two -- while its single `resolveLocal` answers `role:admin`. Authorizing from a second
    /// lookup would serve the request without a credential; the single resolution refuses it.
    @Test
    void localRoute_isAuthorizedByTheSameResolutionThatDispatchesIt() throws Exception {
        startServer(new StubRoutePublisher("GET",
                                           "/local/",
                                           SecurityPolicy.roleRequired("admin"),
                                           new StubSliceRouter(),
                                           SecurityOverrides.EMPTY,
                                           SecurityPolicy.publicRoute(),
                                           _ -> true,
                                           Option.none()),
                    HttpRouteRegistry.httpRouteRegistry());

        assertThat(get("/local/thing").statusCode()).isEqualTo(401);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> remoteRouteOf(Artifact artifact, String prefix, String security) {
        var key = NodeRoutesKey.nodeRoutesKey(REMOTE_NODE, artifact);
        var route = RouteEntry.activeRoute("GET", prefix, "handle", security, security);
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static SecurityOverrides adminOverrideOn(String pattern) {
        return SecurityOverrides.securityOverrides(List.of(SecurityOverrides.Entry.entry(pattern, "role:admin")),
                                                   SecurityOverridePolicy.STRENGTHEN_ONLY);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> remoteRoute(String method, String prefix, String security) {
        return remoteRouteFrom(REMOTE_NODE, method, prefix, security);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> remoteRouteFrom(NodeId node,
                                                                          String method,
                                                                          String prefix,
                                                                          String security) {
        var key = NodeRoutesKey.nodeRoutesKey(node, REMOTE_ARTIFACT);
        var route = RouteEntry.activeRoute(method, prefix, "echo", security, "UNSPECIFIED");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> remotePublicRoute(String method, String prefix) {
        var key = NodeRoutesKey.nodeRoutesKey(REMOTE_NODE, REMOTE_ARTIFACT);
        var route = RouteEntry.activeRoute(method, prefix, "list", "PUBLIC");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
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
    /// `security` is what the single `resolveLocal` resolution answers; `separateLookupSecurity` is what the separate
    /// `findLocalRoute` answers -- equal unless a test models a stale second lookup (#1678, v1670 R2-N4).
    private record StubRoutePublisher(String httpMethod,
                                      String pathPrefix,
                                      SecurityPolicy security,
                                      SliceRouter router,
                                      SecurityOverrides committed,
                                      SecurityPolicy separateLookupSecurity,
                                      java.util.function.Predicate<String> servesPath,
                                      Option<Set<String>> shapeKeys)
        implements HttpRoutePublisher {
        static StubRoutePublisher hosting(String httpMethod,
                                          String pathPrefix,
                                          SecurityPolicy security,
                                          SecurityOverrides committed) {
            return new StubRoutePublisher(httpMethod,
                                          pathPrefix,
                                          security,
                                          new StubSliceRouter(),
                                          committed,
                                          security,
                                          _ -> true,
                                          Option.none());
        }

        @Override
        public Option<Set<String>> localShapeKeys(String method, String prefix) {
            return shapeKeys;
        }

        @Override
        public Option<LocalResolution> resolveLocal(String method, String path) {
            return findLocalRoute(method, path).map(route -> new LocalResolution(new LocalRouteInfo(route.httpMethod(),
                                                                                                    route.pathPrefix(),
                                                                                                    route.artifactCoord(),
                                                                                                    route.sliceMethod(),
                                                                                                    security),
                                                                                 Option.some(router)));
        }

        /// The real rule over this stub's committed overrides, as the production publisher answers.
        @Override
        public Option<SecurityPolicy> committedOverride(String method, String prefix, SecurityPolicy declared) {
            return SecurityOverrideApplier.overriddenPolicy(method, prefix, declared, committed);
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
            return matches(method, path) && servesPath.test(path)
                   ? Option.some(new LocalRouteInfo(httpMethod, pathPrefix, TEST_ARTIFACT.asString(), "create", separateLookupSecurity))
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
