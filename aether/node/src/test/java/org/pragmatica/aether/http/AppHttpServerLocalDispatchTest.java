// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
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

/// Regression coverage for the local-dispatch fast path: a request matching a locally-hosted
/// ACTIVE slice must serve locally even while the route-table snapshot is empty and
/// `isRouteReady()` is false (the generation-churn window observed under load).
class AppHttpServerLocalDispatchTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("test-node").unwrap();
    private static final NodeId REMOTE_NODE = NodeId.nodeId("remote-node").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final int TEST_PORT = 18091;

    private HttpRouteRegistry registry;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private AppHttpServer serverWithPublisher(Option<HttpRoutePublisher> publisher) {
        return AppHttpServer.appHttpServer(AppHttpConfig.appHttpConfig(TEST_PORT),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           registry,
                                           publisher,
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<org.pragmatica.aether.update.DeploymentManager>none());
    }

    @Test
    void dispatchToRoute_servesLocally_whenRouteNotReadyButSliceActive() throws Exception {
        var publisher = StubRoutePublisher.hosting("GET", "/local/", SELF_NODE);
        var server = serverWithPublisher(Option.some(publisher));

        server.start().await();
        try {
            // Reproduce the generation-churn window: routing quiesced => snapshot empty and
            // isRouteReady()=false, while the live publisher still hosts the ACTIVE slice.
            quiesceRouting(server);
            assertThat(server.isRouteReady()).isFalse();

            var response = get("/local/thing");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("served-locally");
        } finally {
            server.stop().await();
        }
    }

    @Test
    void dispatchToRoute_returns503_whenNoLocalInstanceAndRouteNotReady() throws Exception {
        // Publisher present but hosts a DIFFERENT route — no local instance for the requested path,
        // so the request must consult the route-ready barrier and 503.
        var publisher = StubRoutePublisher.hosting("GET", "/other/", SELF_NODE);
        var server = serverWithPublisher(Option.some(publisher));

        server.start().await();
        try {
            quiesceRouting(server);
            assertThat(server.isRouteReady()).isFalse();

            var response = get("/missing/thing");

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        } finally {
            server.stop().await();
        }
    }

    @Test
    void dispatchToRoute_forwardsRemote_whenRouteReadyAndRemoteRouteExists() throws Exception {
        // No local instance + a remote route in the registry. With no forwarder configured the
        // remote dispatch path is exercised (503 from "HTTP forwarding not available"), proving the
        // request reached remote dispatch rather than the barrier.
        registerNodeRoute("GET", "/remote/", REMOTE_NODE);
        var publisher = StubRoutePublisher.hosting("GET", "/local/", SELF_NODE);
        var server = serverWithPublisher(Option.some(publisher));

        server.start().await();
        try {
            var response = get("/remote/thing");

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("HTTP forwarding not available");
        } finally {
            server.stop().await();
        }
    }

    private static void quiesceRouting(AppHttpServer server) {
        server.onQuorumStateChange(ClusterStateNotification.passive());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + TEST_PORT + path))
                                 .GET()
                                 .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void registerNodeRoute(String method, String path, NodeId nodeId) {
        var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
        var route = RouteEntry.activeRoute(method, path, "create");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route));
        var command = new KVCommand.Put<>(key, value);

        registry.onNodeRoutesPut(new ValuePut<>(command, Option.none()));
    }

    /// Minimal HttpRoutePublisher stub: hosts exactly one live local route with a SliceRouter that
    /// returns a fixed 200. Mirrors the live ConcurrentHashMap-backed publisher's local source of
    /// truth without touching the cluster.
    private record StubRoutePublisher(String httpMethod, String pathPrefix, NodeId nodeId, SliceRouter router)
        implements HttpRoutePublisher {
        static StubRoutePublisher hosting(String httpMethod, String pathPrefix, NodeId nodeId) {
            return new StubRoutePublisher(httpMethod, pathPrefix, nodeId, new StubSliceRouter());
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
                                                    SecurityPolicy.publicRoute()))
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
    }
}
