// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #887 at the OUTERMOST OBSERVABLE — a real HTTP request, against a real [`AppHttpServer`] in the
/// shipped `security_mode = "api-key"`, resolved by the real
/// [`org.pragmatica.aether.http.HttpRoutePublisher`] implementation.
///
/// The escalation this pins, stated as the attacker sees it: a route that declared no `[security]`
/// stance is locked down by the operator's blueprint to `role:admin`. Pre-fix, the node HOSTING
/// that route resolved its policy from the raw pre-override routes, found `Unspecified`, fell back
/// to the global `ApiKeyRequired`, and never reached the role check — so a caller holding ANY valid
/// API key, with no admin role whatsoever, was served. Post-fix the same request is refused 403.
///
/// ## Why this test is not its own fixture
///
/// The publisher under test is the REAL `HttpRoutePublisherImpl`, populated through the REAL
/// `publishRoutes(...)` path via the ServiceLoader-discovered [`OverrideEnforcementRouteFactory`],
/// and given its override through the REAL `updateSecurityOverrides(...)`. The policy the server
/// enforces is therefore computed by production code end to end; nothing here reconstructs the
/// override rule or hands the server a hand-written policy.
///
/// [`RouterOnlyStub`] substitutes exactly one thing — `findLocalRouter`/`getSliceRouter`, the
/// dispatch plumbing — because the `publishRoutes(3-arg)` handler path populates `handlers` and not
/// `sliceRouters`, so without it an authorized request would 404 instead of 200 and the two
/// outcomes under test would be indistinguishable. That substitution is downstream of, and
/// independent of, every security decision asserted below: it cannot manufacture a 403, and reverting
/// the production hunk turns these 403s back into 200s with it still in place.
class AppHttpServerOverrideEnforcementTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node-override-enf").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact(OverrideEnforcementRouteFactory.ARTIFACT_COORD).unwrap();
    private static final int PORT = 19117;

    /// Two keys, identical except for their roles. The escalation is that the FIRST one works.
    private static final String SERVICE_KEY = "override-enf-service-key-11223";
    private static final String ADMIN_KEY = "override-enf-admin-key-44556";
    private static final String REQUEST_PATH = "/undeclared/thing";

    private AppHttpServer server;
    private HttpClient httpClient;
    private HttpRoutePublisher realPublisher;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        realPublisher = HttpRoutePublisher.httpRoutePublisher(SELF_NODE, new AcceptingCluster());
        realPublisher.publishRoutes(ARTIFACT, getClass().getClassLoader(), stubInvokerFacade())
                     .await(timeSpan(30).seconds())
                     .onFailure(cause -> Assertions.fail("route publication must succeed: " + cause.message()));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().await();
        }
    }

    private void startServer() {
        var config = AppHttpConfig.appHttpConfig(true,
                                                 PORT,
                                                 apiKeys(),
                                                 AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                                 SecurityMode.API_KEY,
                                                 Option.empty(),
                                                 HttpProtocol.H1)
                                  .unwrap();

        server = AppHttpServer.appHttpServer(config,
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             SELF_NODE,
                                             HttpRouteRegistry.httpRouteRegistry(),
                                             Option.some(new RouterOnlyStub(realPublisher, new StubSliceRouter())),
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

    private static Map<String, ApiKeyEntry> apiKeys() {
        var keys = new HashMap<String, ApiKeyEntry>();

        keys.put(SERVICE_KEY, ApiKeyEntry.apiKeyEntry("service-caller", Set.of("service")));
        keys.put(ADMIN_KEY, ApiKeyEntry.apiKeyEntry("admin-caller", Set.of("admin")));

        return Map.copyOf(keys);
    }

    private void lockRouteDownToAdmin() {
        var entry = SecurityOverrides.Entry.entry(OverrideEnforcementRouteFactory.METHOD
                                                  + " "
                                                  + OverrideEnforcementRouteFactory.PATH_PREFIX,
                                                  "role:admin");

        realPublisher.updateSecurityOverrides(SecurityOverrides.securityOverrides(List.of(entry),
                                                                                   SecurityOverridePolicy.STRENGTHEN_ONLY));
    }

    @Nested
    class InstrumentCheck {
        /// Every assertion below is about a policy decision on a route that must (a) exist locally
        /// and (b) be UNDECLARED before the override. If the ServiceLoader picked up a different
        /// provider, or the route prefix stopped matching, the escalation path is unreachable and a
        /// 403 would prove nothing. Pin both facts before testing the behaviour.
        @Test
        void findLocalRoute_matchesUndeclaredRoute_beforeAnyOverride() {
            var localRoute = realPublisher.findLocalRoute(OverrideEnforcementRouteFactory.METHOD, REQUEST_PATH);

            assertThat(localRoute.isPresent())
                    .as("the factory's route must be published and match, or this suite is vacuous")
                    .isTrue();
            assertThat(localRoute.map(HttpRoutePublisher.LocalRouteInfo::security).or(SecurityPolicy.publicRoute()))
                    .as("#887's scenario requires an UNDECLARED route; a declared one takes a different branch")
                    .isEqualTo(SecurityPolicy.unspecified());
        }

        /// The 403s below must be the ROLE gate, not an unauthenticated request or a broken route.
        /// With no override in force, an undeclared route inherits the global API_KEY policy: the
        /// service key is served, and no credential is refused.
        @Test
        void request_isServed_whenNoOverrideInForceAndKeyValid() throws Exception {
            startServer();

            assertThat(get(REQUEST_PATH).statusCode())
                    .as("no credential must be refused under api-key mode")
                    .isEqualTo(401);

            var response = getWithApiKey(REQUEST_PATH, SERVICE_KEY);

            assertThat(response.statusCode())
                    .as("without an override an undeclared route inherits API_KEY, which this key satisfies")
                    .isEqualTo(200);
            assertThat(response.body()).contains("served-locally");
        }
    }

    @Nested
    class OverrideEnforcedOnHostingNode {
        /// THE #887 PRIVILEGE ESCALATION, at the outermost observable.
        ///
        /// The route is hosted by THIS node — the case the pre-fix code could not reach, and the
        /// reason the defect survived: existing coverage exercised the remote path, where the
        /// override arrives via the KV entry and was enforced correctly.
        ///
        /// Pre-fix this request is answered 200 with the slice's body. Post-fix, 403.
        @Test
        void request_isRefused_whenOverrideRequiresRoleTheKeyLacks() throws Exception {
            lockRouteDownToAdmin();
            startServer();

            var response = getWithApiKey(REQUEST_PATH, SERVICE_KEY);

            assertThat(response.statusCode())
                    .as("a valid API key without the admin role must NOT reach an admin-locked route "
                        + "on the node hosting it")
                    .isEqualTo(403);
            assertThat(response.body())
                    .as("the refusal must be the role gate, and must not leak the slice's response")
                    .doesNotContain("served-locally");
        }

        /// Positive control for the test above: the 403 is the role check doing its job, not a route
        /// that stopped working. The same request with a key carrying `admin` is served.
        ///
        /// This is what makes the 403 falsifiable. Without it, a 403 caused by a broken route, a
        /// mis-typed path, or a publisher that returned nothing would read identically to the fix
        /// working.
        @Test
        void request_isServed_whenOverrideRequiresRoleTheKeyHolds() throws Exception {
            lockRouteDownToAdmin();
            startServer();

            var response = getWithApiKey(REQUEST_PATH, ADMIN_KEY);

            assertThat(response.statusCode())
                    .as("the admin-roled key must still be served — the override restricts, it does not break")
                    .isEqualTo(200);
            assertThat(response.body()).contains("served-locally");
        }

        /// #887 acceptance 3 at the observable: an override applied AFTER the server is already
        /// serving takes effect on the next request, with no redeployment and no republication of
        /// the slice. The same key, same path, is served and then refused.
        @Test
        void request_isRefused_whenOverrideAppliedWhileServerIsServing() throws Exception {
            startServer();

            assertThat(getWithApiKey(REQUEST_PATH, SERVICE_KEY).statusCode())
                    .as("before the override the key is sufficient")
                    .isEqualTo(200);

            lockRouteDownToAdmin();

            assertThat(getWithApiKey(REQUEST_PATH, SERVICE_KEY).statusCode())
                    .as("a runtime override must govern the very next request on the hosting node")
                    .isEqualTo(403);
        }
    }

    @Nested
    class OverrideArrivesOnlyViaReplicatedBlueprint {
        /// #887 E2 — THE CONDITION the fix has to meet, at the outermost observable.
        ///
        /// This models a node that did **NOT** serve `POST /api/v1/blueprints`. Its publisher is
        /// never handed the override directly — `updateSecurityOverrides` is not called by this test
        /// — and learns it only by deriving from the replicated blueprint, which every node holds.
        ///
        /// Before E2 that node held `SecurityOverrides.EMPTY` and served this request, because the
        /// override was installed in-process on the one node that answered the management call. A
        /// test that only ever exercises the request-serving node cannot tell the fix from the bug.
        @Test
        void request_isRefused_whenOverrideLearnedFromReplicatedBlueprintAlone() throws Exception {
            var synchronizer = synchronizerOver(replicatedStore(lockdownOverrides()));

            synchronizer.resync();
            startServer();

            assertThat(getWithApiKey(REQUEST_PATH, SERVICE_KEY).statusCode())
                    .as("a node that never served the blueprint request must still enforce the override")
                    .isEqualTo(403);
        }

        /// #887 E3 — the survival claim, observed rather than reasoned about.
        ///
        /// `activeOverrides` is in-memory, so a node restart or a DEPLOYMENT task-group migration used
        /// to bring the node up enforcing nothing. Here the node observes NO blueprint put at all —
        /// only the `ACTIVE` edge, which is exactly what a restart looks like: state restored, never
        /// seen arriving. Verifying what a condition MEANS is not verifying it still HOLDS.
        @Test
        void request_isRefused_whenOverrideRederivedOnActiveEdgeWithNoPutObserved() throws Exception {
            var synchronizer = synchronizerOver(replicatedStore(lockdownOverrides()));

            synchronizer.onQuorumStateChange(ClusterStateNotification.active());
            startServer();

            assertThat(getWithApiKey(REQUEST_PATH, SERVICE_KEY).statusCode())
                    .as("a restarted node must re-derive overrides from restored state, not come up empty")
                    .isEqualTo(403);
        }

        /// Control. Same wiring, blueprint carrying no override: the request is served. Without this,
        /// a 403 caused by the synchronizer breaking the route would read as the fix working.
        @Test
        void request_isServed_whenReplicatedBlueprintCarriesNoOverride() throws Exception {
            var synchronizer = synchronizerOver(replicatedStore(SecurityOverrides.EMPTY));

            synchronizer.resync();
            startServer();

            var response = getWithApiKey(REQUEST_PATH, SERVICE_KEY);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("served-locally");
        }
    }

    private SecurityOverrideSynchronizer synchronizerOver(KVStore<AetherKey, AetherValue> store) {
        return SecurityOverrideSynchronizer.securityOverrideSynchronizer(store, () -> Option.some(realPublisher));
    }

    private static SecurityOverrides lockdownOverrides() {
        var entry = SecurityOverrides.Entry.entry(OverrideEnforcementRouteFactory.METHOD
                                                  + " "
                                                  + OverrideEnforcementRouteFactory.PATH_PREFIX,
                                                  "role:admin");

        return SecurityOverrides.securityOverrides(List.of(entry), SecurityOverridePolicy.STRENGTHEN_ONLY);
    }

    /// A KV store holding the blueprint exactly as consensus replicates it to every node.
    private static KVStore<AetherKey, AetherValue> replicatedStore(SecurityOverrides overrides) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var id = BlueprintId.blueprintId(Artifact.artifact("com.example:test-blueprint:1.0.0").unwrap());
        var blueprint = ExpandedBlueprint.expandedBlueprint(id, List.of(), Option.none(), overrides);
        var command = new KVCommand.Put<AetherKey, AetherValue>(AppBlueprintKey.appBlueprintKey(id),
                                                                 AppBlueprintValue.appBlueprintValue(blueprint, false));

        store.process(store.createBatch(List.of(command)));

        return store;
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
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

    private static SliceInvokerFacade stubInvokerFacade() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                  String methodName,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
                return Causes.cause("stub invoker facade").result();
            }
        };
    }

    /// Delegates EVERY security-relevant call to the real publisher and substitutes only the
    /// dispatch-side router lookup. See the class javadoc for why that substitution cannot
    /// manufacture the outcomes asserted here.
    private record RouterOnlyStub(HttpRoutePublisher delegate, SliceRouter router) implements HttpRoutePublisher {
        @Override
        public Option<SliceRouter> findLocalRouter(String httpMethod, String pathPrefix) {
            return Option.some(router);
        }

        @Override
        public Option<SliceRouter> getSliceRouter(Artifact artifact) {
            return Option.some(router);
        }

        @Override
        public Option<LocalRouteInfo> findLocalRoute(String httpMethod, String path) {
            return delegate.findLocalRoute(httpMethod, path);
        }

        @Override
        public Set<HttpNodeRouteKey> allLocalRoutes() {
            return delegate.allLocalRoutes();
        }

        @Override
        public Unit updateSecurityOverrides(SecurityOverrides overrides) {
            return delegate.updateSecurityOverrides(overrides);
        }

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact, ClassLoader classLoader, SliceInvokerFacade invokerFacade) {
            return delegate.publishRoutes(artifact, classLoader, invokerFacade);
        }

        @Override
        public Promise<Unit> publishRoutes(Artifact artifact,
                                           ClassLoader classLoader,
                                           Object sliceInstance,
                                           SliceInvokerFacade invokerFacade) {
            return delegate.publishRoutes(artifact, classLoader, sliceInstance, invokerFacade);
        }

        @Override
        public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {
            return delegate.hasRoutes(classLoader, sliceInstance);
        }

        @Override
        public Promise<Unit> unpublishRoutes(Artifact artifact) {
            return delegate.unpublishRoutes(artifact);
        }

        @Override
        public Option<HttpRequestHandler> getHandler(Artifact artifact) {
            return delegate.getHandler(artifact);
        }

        @Override
        public Unit setVersioningMetricsSink(VersioningMetricsSink sink) {
            return delegate.setVersioningMetricsSink(sink);
        }

        @Override
        public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
            return delegate.setObservabilityCellRegistrar(registrar);
        }

        @Override
        public Map<Artifact, SliceVersionRegistry> versionRegistries() {
            return delegate.versionRegistries();
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

    /// `ClusterNode` double that accepts every route-publication apply. Route publication is not
    /// what this suite is about; it only has to succeed so `publishedRoutes` is populated.
    private static final class AcceptingCluster implements ClusterNode<KVCommand<AetherKey>> {
        @Override
        public NodeId self() {
            return SELF_NODE;
        }

        @Override
        public TopologyManager topologyManager() {
            return Assertions.fail("topologyManager() is not exercised by route publication");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
