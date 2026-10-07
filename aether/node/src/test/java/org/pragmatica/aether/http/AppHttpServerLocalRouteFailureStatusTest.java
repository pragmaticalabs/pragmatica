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
import org.pragmatica.aether.node.entityforward.EntityForwardService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #1737: a local route whose router fails with a [Cause.Transient] cause (a router that does not convert its own
/// failures to a response) answers 503 over the real Netty path, not the 500 "Request processing failed" fallback.
/// A non-transient failure stays 500. The SliceRouter conversion is pinned by SliceRouterTransientStatusTest.
class AppHttpServerLocalRouteFailureStatusTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("failure-node").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final NodeId OWNER = NodeId.nodeId("dead-owner").unwrap();
    private static final int TEST_PORT = 18096;

    record Refused(String message) implements Cause.Transient {}

    private HttpRouteRegistry registry;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        registry = HttpRouteRegistry.httpRouteRegistry();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private int statusFor(Cause failure) throws Exception {
        return answerFor(failure).statusCode();
    }

    private HttpResponse<String> answerFor(Cause failure) throws Exception {
        var server = AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(TEST_PORT),
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 SELF_NODE,
                                                 registry,
                                                 Option.some(new StubRoutePublisher("GET", "/local/", SELF_NODE, new FailingRouter(failure))),
                                                 Option.none(), Option.none(), Option.none(), Option.none(),
                                                 Option.none(), Option.none(), Option.none(),
                                                 Option.<org.pragmatica.aether.update.DeploymentManager>none());

        server.start().await();
        try {
            return httpClient.send(java.net.http.HttpRequest.newBuilder()
                                                            .uri(URI.create("http://localhost:" + TEST_PORT + "/local/thing"))
                                                            .GET()
                                                            .build(),
                                   HttpResponse.BodyHandlers.ofString());
        } finally {
            server.stop().await();
        }
    }

    @Test
    void localRouteFailure_answers503_forTransientCause() throws Exception {
        assertThat(statusFor(new Refused("Stream partition s[0] is not yet promoted on this node"))).isEqualTo(503);
    }

    @Test
    void localRouteFailure_answers500_forNonTransientCause() throws Exception {
        assertThat(statusFor(Causes.cause("disk on fire"))).isEqualTo(500);
    }

    /// #1973: the REAL cause an entity forward produces when the transport refuses the send (the owner was just killed and
    /// this node still routes the key to it) answers 503 with the retry hint in the body, not the 500 "Request processing
    /// failed" of an untyped cause, and never a 200. The cause is produced by the real forward service, not hand-built.
    @Test
    void localRouteFailure_entityForwardSendRefused_answers503WithTheRetryHint() throws Exception {
        var answer = answerFor(entityForwardFailure(new WriteOutcome.NoPeerState(OWNER)));

        assertThat(answer.statusCode()).isEqualTo(503);
        assertThat(answer.body()).contains("refused at send").contains("safe to retry").doesNotContain("outcome unknown");
    }

    /// The sibling: a forward that was SENT and timed out has an unknown outcome. Owner ruling bcfb04232 answers every
    /// transient cause 503, timeouts included, so it is 503 too, but its body says the outcome is unknown and does NOT say
    /// "safe to retry": the two 503s are told apart by the body.
    @Test
    void localRouteFailure_entityForwardTimedOutAfterSend_answers503_withAnOutcomeUnknownBody() throws Exception {
        var service = EntityForwardService.entityForwardService(SELF_NODE,
                                                                (target, message) -> Promise.success(new WriteOutcome.Sent(target)),
                                                                TimeSpan.timeSpan(50).millis());
        var failure = service.forwardGet(OWNER, "orders", new byte[]{1}).await().fold(cause -> cause, _ -> Causes.cause("unexpected success"));
        var answer = answerFor(failure);

        assertThat(answer.statusCode()).isEqualTo(503);
        assertThat(answer.body()).contains("outcome unknown")
                                .contains("may have applied the command")
                                .contains("retry only an idempotent operation")
                                .doesNotContain("safe to retry");
    }

    private static Cause entityForwardFailure(WriteOutcome refusal) {
        var service = EntityForwardService.entityForwardService(SELF_NODE,
                                                                (target, message) -> Promise.success(refusal),
                                                                TimeSpan.timeSpan(60).seconds());

        return service.forwardGet(OWNER, "orders", new byte[]{1}).await().fold(cause -> cause, _ -> Causes.cause("unexpected success"));
    }

    private record FailingRouter(Cause failure) implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            return failure.promise();
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
