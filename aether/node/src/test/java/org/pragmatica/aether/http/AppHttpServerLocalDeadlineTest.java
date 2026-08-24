// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.BeforeEach;
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
import org.pragmatica.lang.utils.Deadline;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// THE pin 02w run5 was missing: a real HTTP request through the real Netty server and the LOCAL
/// dispatch path must reach the route handler with the ambient request budget BOUND. The forwarded
/// path's re-mint is pinned by AppHttpServerForwardBudgetTest; the local mint at
/// `dispatchAuthenticated` had no pin, and run5 measured every entity forward waiting its full
/// 30s constant — the signature of `Deadline.current()` reading unbounded where the budget should
/// have been. This test walks the exact production path (Netty event loop → security validate →
/// mint → dispatchToRoute → router.handle) and fails if any link drops the binding.
class AppHttpServerLocalDeadlineTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("deadline-node").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final int TEST_PORT = 18095;

    private HttpRouteRegistry registry;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void localDispatch_overRealHttp_routeHandlerObservesBoundedDeadline() throws Exception {
        var observed = new AtomicReference<Deadline>();
        var router = new DeadlineObservingRouter(observed);
        var server = AppHttpServer.appHttpServer(AppHttpConfig.appHttpConfig(TEST_PORT),
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 SELF_NODE,
                                                 registry,
                                                 Option.some(new StubRoutePublisher("GET", "/local/", SELF_NODE, router)),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.<org.pragmatica.aether.update.DeploymentManager>none());

        server.start().await();
        try {
            var response = httpClient.send(java.net.http.HttpRequest.newBuilder()
                                                                    .uri(URI.create("http://localhost:" + TEST_PORT + "/local/thing"))
                                                                    .GET()
                                                                    .build(),
                                           HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(observed.get()).as("the route handler must run — otherwise this pins nothing").isNotNull();
            assertThat(observed.get().isBounded())
                .as("the request budget minted at dispatch must still be bound at the route handler")
                .isTrue();
            assertThat(observed.get().remaining().millis())
                .as("and it must be THIS request's ~10s budget, not some stale binding")
                .isGreaterThan(1_000)
                .isLessThanOrEqualTo(10_000);
        } finally {
            server.stop().await();
        }
    }

    private record DeadlineObservingRouter(AtomicReference<Deadline> observed) implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            observed.set(Deadline.current());

            return Promise.success(HttpResponseData.httpResponseData(200, "{\"result\":\"observed\"}"));
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

    /// Same stub shape as AppHttpServerLocalDispatchTest — one live local route.
    private record StubRoutePublisher(String httpMethod, String pathPrefix, NodeId nodeId, SliceRouter router)
        implements HttpRoutePublisher {
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

        @Override
        public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {
            return unit();
        }
    }
}
