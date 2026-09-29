// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalResolution;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1659 (v1670 addendum): the HOST re-authorizes a forwarded request against the route it will serve it by -- its own
/// longest local match. The ingress authorized against its own view, which during propagation can lack a narrower,
/// stricter child route the host already serves: the ingress sees only a PUBLIC `/api/` and forwards
/// `/api/admin/secret`, and the host serves it through its local `/api/admin/` (`role:admin`). The host must refuse.
/// "The ingress's view lacked the child" is modelled by the forward itself: whatever the ingress checked, this host
/// sees only the forwarded request and its own routes.
class AppHttpServerForwardReauthorizationTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("reauth-host").unwrap();
    private static final NodeId SENDER_NODE = NodeId.nodeId("reauth-ingress").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final String VALID_API_KEY = "reauth-test-key-24680";
    private static final int TEST_PORT = 18094;

    private RecordingClusterNetwork network;
    private CapturingSerializer serializer;
    private CountingRouter router;

    @BeforeEach
    void setUp() {
        network = new RecordingClusterNetwork();
        serializer = new CapturingSerializer();
        router = new CountingRouter();
    }

    private AppHttpServer hostServing(String prefix, SecurityPolicy policy, HttpRequestContext forwarded) {
        return hostServing(new StubRoutePublisher("GET", prefix, SELF_NODE, router, policy, policy), forwarded);
    }

    private AppHttpServer hostServing(StubRoutePublisher publisher, HttpRequestContext forwarded) {
        return AppHttpServer.appHttpServer(AppHttpConfig.appHttpConfig(TEST_PORT, Set.of(VALID_API_KEY)),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.some(publisher),
                                           Option.some(network),
                                           Option.some(serializer),
                                           Option.some(new StubDeserializer(forwarded)),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<org.pragmatica.aether.update.DeploymentManager>none());
    }

    @Test
    void forwardedRequest_hostServesAStricterChildRoute_refusesWith403_andNeverDispatches() {
        var host = hostServing("/api/admin/", SecurityPolicy.roleRequired("admin"), withApiKey("/api/admin/secret"));

        host.onHttpForwardRequest(forwardRequest("corr-child"));

        var relayed = relayedResponse();

        assertThat(relayed.statusCode()).as("the host's own route policy refuses: %s", body(relayed)).isEqualTo(403);
        assertThat(body(relayed)).contains("role 'admin' required");
        assertThat(router.handleCount()).as("a refused request must never reach the slice").isZero();
    }

    /// Also the HOST half of v1670's P5 (the rollout forward): the host refuses whatever its own route refuses, however
    /// the forward arose. The ingress half -- an ingress under a rollout forwarding a request it admitted under a
    /// PUBLIC local parent -- is not modelled here; no test combines the two halves.
    @Test
    void forwardedRequest_withoutACredential_isRefusedWith401_andTheChallengeHeader() {
        var host = hostServing("/api/admin/", SecurityPolicy.roleRequired("admin"), withoutCredential("/api/admin/secret"));

        host.onHttpForwardRequest(forwardRequest("corr-anon"));

        var relayed = relayedResponse();

        assertThat(relayed.statusCode()).isEqualTo(401);
        assertThat(relayed.headers()).containsEntry("WWW-Authenticate", "ApiKey realm=\"Aether\"");
        assertThat(router.handleCount()).isZero();
    }

    /// v1670 R2-N4: the route a forwarded request is re-authorized against and the router that serves it come from
    /// ONE resolution. The stub's separate `findLocalRoute` answers a stale PUBLIC parent -- what a second lookup could
    /// read after an undeploy between the two -- while its single `resolveLocal` names the admin child and its router.
    /// Re-authorizing from a second lookup would admit the request; the single resolution refuses it.
    @Test
    void forwardedRequest_isReauthorizedByTheSameResolutionThatNamesItsRouter() {
        var host = hostServing(new StubRoutePublisher("GET",
                                                      "/api/admin/",
                                                      SELF_NODE,
                                                      router,
                                                      SecurityPolicy.roleRequired("admin"),
                                                      SecurityPolicy.publicRoute()),
                               withoutCredential("/api/admin/secret"));

        host.onHttpForwardRequest(forwardRequest("corr-one-resolution"));

        assertThat(relayedResponse().statusCode()).isEqualTo(401);
        assertThat(router.handleCount()).isZero();
    }

    // ---- v1670 "cred path": the PRODUCTION node codec carries the credential, and the host validates it with the
    // same validator the ingress uses. Real keys with roles, so an admin key and a service key are told apart.
    private static final String ADMIN_KEY = "cred-path-admin-key-13579";
    private static final String SERVICE_KEY = "cred-path-service-key-24680";

    private AppHttpServer roleKeyedHost(org.pragmatica.serialization.Deserializer deserializer) {
        var keys = Map.of(ADMIN_KEY,
                          org.pragmatica.aether.config.ApiKeyEntry.apiKeyEntry("admin-caller", Set.of("admin")),
                          SERVICE_KEY,
                          org.pragmatica.aether.config.ApiKeyEntry.apiKeyEntry("service-caller", Set.of("service")));
        var config = AppHttpConfig.appHttpConfig(true,
                                                 TEST_PORT,
                                                 keys,
                                                 AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                                 org.pragmatica.aether.config.SecurityMode.API_KEY,
                                                 Option.empty(),
                                                 org.pragmatica.aether.config.HttpProtocol.H1)
                                  .unwrap();
        var adminRoute = SecurityPolicy.roleRequired("admin");

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.some(new StubRoutePublisher("GET", "/api/admin/", SELF_NODE, router, adminRoute, adminRoute)),
                                           Option.some(network),
                                           Option.some(serializer),
                                           Option.some(deserializer),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<org.pragmatica.aether.update.DeploymentManager>none());
    }

    private static org.pragmatica.serialization.SliceCodec productionCodec() {
        return org.pragmatica.aether.node.NodeCodecs.nodeCodecs(org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs());
    }

    private static HttpForwardRequest encodedForward(byte[] bytes) {
        return new HttpForwardRequest(SENDER_NODE,
                                      "corr-cred",
                                      "req-cred",
                                      bytes,
                                      org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline.APP,
                                      Deadline.NO_BUDGET);
    }

    private static HttpRequestContext keyed(String key) {
        return HttpRequestContext.httpRequestContext("/api/admin/secret", "GET", Map.of(), Map.of("X-API-Key", List.of(key)), "req-cred");
    }

    @Test
    void productionCodec_carriesTheApiKeyHeader() {
        var codec = productionCodec();
        HttpRequestContext decoded = codec.decode(codec.encode(keyed(ADMIN_KEY)));

        assertThat(decoded.headers()).containsEntry("X-API-Key", List.of(ADMIN_KEY));
    }

    /// An admin key, forwarded through the production codec to a ROLE:admin host: served, and -- v1670 R2-N6 -- the
    /// slice runs under the host's validated SecurityContext, as a local request does.
    @Test
    void adminKeyForward_throughTheProductionCodec_isServed_underTheCallersPrincipal() {
        var codec = productionCodec();

        roleKeyedHost(codec).onHttpForwardRequest(encodedForward(codec.encode(keyed(ADMIN_KEY))));

        var relayed = relayedResponse();

        assertThat(relayed.statusCode()).as("authorized admin forward: %s", body(relayed)).isEqualTo(200);
        assertThat(router.handleCount()).isEqualTo(1);
        assertThat(router.principalSeen()).as("the slice sees the forwarded caller's principal").isEqualTo("api-key:admin-caller");
    }

    @Test
    void serviceKeyForward_throughTheProductionCodec_isRefused403() {
        var codec = productionCodec();

        roleKeyedHost(codec).onHttpForwardRequest(encodedForward(codec.encode(keyed(SERVICE_KEY))));

        assertThat(relayedResponse().statusCode()).isEqualTo(403);
        assertThat(router.handleCount()).isZero();
    }

    /// v1670 R2-N5: under `security_mode = "none"` the validator permits every caller (with admin), so the host's own
    /// NONE-mode guard is the ONLY refusal of a forwarded request for an auth-requiring route.
    @Test
    void noneModeHost_refusesAForwardForAnAuthRequiringRoute_withoutCallingTheSlice() {
        var adminRoute = SecurityPolicy.roleRequired("admin");
        var host = AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(TEST_PORT),
                                               ForwardingTimeouts.forwardingTimeouts(),
                                               SELF_NODE,
                                               HttpRouteRegistry.httpRouteRegistry(),
                                               Option.some(new StubRoutePublisher("GET", "/api/admin/", SELF_NODE, router, adminRoute, adminRoute)),
                                               Option.some(network),
                                               Option.some(serializer),
                                               Option.some(new StubDeserializer(withoutCredential("/api/admin/secret"))),
                                               Option.none(),
                                               Option.none(),
                                               Option.none(),
                                               Option.none(),
                                               Option.<org.pragmatica.aether.update.DeploymentManager>none());

        host.onHttpForwardRequest(forwardRequest("corr-none-mode"));

        assertThat(relayedResponse().statusCode()).isEqualTo(401);
        assertThat(router.handleCount()).isZero();
    }

    /// CONTROL: a forwarded request the host's own policy admits is served exactly as before.
    @Test
    void forwardedRequest_admittedByTheHostsPolicy_isServed() {
        var host = hostServing("/api/", SecurityPolicy.unspecified(), withApiKey("/api/orders"));

        host.onHttpForwardRequest(forwardRequest("corr-ok"));

        var relayed = relayedResponse();

        assertThat(relayed.statusCode()).isEqualTo(200);
        assertThat(router.handleCount()).isEqualTo(1);
    }

    private HttpResponseData relayedResponse() {
        var response = (HttpForwardResponse) network.sentMessages().getFirst();

        assertThat(response.success()).as("a refusal travels as a relayed HTTP response, not a transport error").isTrue();

        return serializer.encoded()
                         .stream()
                         .filter(HttpResponseData.class::isInstance)
                         .map(HttpResponseData.class::cast)
                         .findFirst()
                         .orElseThrow();
    }

    private static String body(HttpResponseData data) {
        return new String(data.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static HttpForwardRequest forwardRequest(String correlationId) {
        return new HttpForwardRequest(SENDER_NODE,
                                      correlationId,
                                      "req-" + correlationId,
                                      new byte[] {1},
                                      org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline.APP,
                                      Deadline.NO_BUDGET);
    }

    private static HttpRequestContext withApiKey(String path) {
        return HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of("X-API-Key", List.of(VALID_API_KEY)), "req-fwd");
    }

    private static HttpRequestContext withoutCredential(String path) {
        return HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req-fwd");
    }

    private static final class CountingRouter implements SliceRouter {
        private final AtomicInteger handleCount = new AtomicInteger();

        int handleCount() {
            return handleCount.get();
        }

        private final java.util.concurrent.atomic.AtomicReference<String> principalSeen = new java.util.concurrent.atomic.AtomicReference<>("");

        String principalSeen() {
            return principalSeen.get();
        }

        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            handleCount.incrementAndGet();
            principalSeen.set(org.pragmatica.aether.http.handler.security.SecurityContextHolder.currentContext()
                                                                                              .map(context -> context.principal()
                                                                                                                     .value())
                                                                                              .or(""));

            return Promise.success(HttpResponseData.httpResponseData(200, "served"));
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

    /// Minimal publisher hosting one live local route, mirroring AppHttpServerLocalDispatchTest's stub.
    /// `security` is what the single [#resolveLocal] resolution answers; `separateLookupSecurity` is what the
    /// separate `findLocalRoute` answers -- equal unless a test models a stale second lookup (R2-N4).
    private record StubRoutePublisher(String httpMethod,
                                      String pathPrefix,
                                      NodeId nodeId,
                                      SliceRouter router,
                                      SecurityPolicy security,
                                      SecurityPolicy separateLookupSecurity)
        implements HttpRoutePublisher {
        @Override
        public Option<LocalResolution> resolveLocal(String method, String path) {
            return matches(method, path)
                   ? Option.some(new LocalResolution(new LocalRouteInfo(httpMethod,
                                                                        pathPrefix,
                                                                        TEST_ARTIFACT.asString(),
                                                                        "create",
                                                                        security),
                                                     Option.some(router)))
                   : Option.none();
        }

        private boolean matches(String method, String path) {
            return httpMethod.equalsIgnoreCase(method) && path.startsWith(pathPrefix);
        }

        @Override
        public Set<HttpNodeRouteKey> allLocalRoutes() {
            return Set.of(HttpNodeRouteKey.httpNodeRouteKey(httpMethod, pathPrefix, nodeId));
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
                   ? Option.some(new LocalRouteInfo(httpMethod,
                                                    pathPrefix,
                                                    TEST_ARTIFACT.asString(),
                                                    "create",
                                                    separateLookupSecurity))
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

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        private final List<ProtocolMessage> sentMessages = new ArrayList<>();

        synchronized List<ProtocolMessage> sentMessages() {
            return List.copyOf(sentMessages);
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override public synchronized <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sentMessages.add(message);
            return unit();
        }

        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return 1;}
        @Override public Set<NodeId> connectedPeers() {return Set.of(SENDER_NODE);}
        @Override public Option<Server> server() {return Option.none();}
    }

    /// Keeps what the host encoded, so a test reads the status the ingress would relay to the client.
    private static final class CapturingSerializer implements Serializer {
        private final List<Object> encoded = new ArrayList<>();

        synchronized List<Object> encoded() {
            return List.copyOf(encoded);
        }

        @Override public <T> void write(ByteBuf byteBuf, T object) {}

        @Override
        public synchronized <T> byte[] encode(T value) {
            encoded.add(value);
            return new byte[] {2};
        }
    }

    /// decode() returns the prepared request context regardless of the wire bytes — the codec is
    /// not what these tests pin.
    private static final class StubDeserializer implements Deserializer {
        private final HttpRequestContext context;

        StubDeserializer(HttpRequestContext context) {
            this.context = context;
        }

        @Override public <T> T read(ByteBuf byteBuf) {return null;}

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            return (T) context;
        }
    }
}
