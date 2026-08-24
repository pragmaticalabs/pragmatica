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

/// Stage 2 of deadline propagation, receiver side: a forwarded request carries the sender's
/// remaining budget on the wire, and a receiver whose sender is already gone REFUSES without
/// dispatching — 02w measured the alternative, where every abandoned forward hop left a receiver
/// computing an answer nobody collects, and those zombie dispatches fed the retry storm.
class AppHttpServerForwardBudgetTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("recv-node").unwrap();
    private static final NodeId SENDER_NODE = NodeId.nodeId("send-node").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final int TEST_PORT = 18093;

    private RecordingClusterNetwork network;
    private CountingRouter router;
    private AppHttpServer server;

    @BeforeEach
    void setUp() {
        network = new RecordingClusterNetwork();
        router = new CountingRouter();
        server = AppHttpServer.appHttpServer(AppHttpConfig.appHttpConfig(TEST_PORT),
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             SELF_NODE,
                                             HttpRouteRegistry.httpRouteRegistry(),
                                             Option.some(new StubRoutePublisher("GET", "/local/", SELF_NODE, router)),
                                             Option.some(network),
                                             Option.some(new StubSerializer()),
                                             Option.some(new StubDeserializer(context("/local/thing"))),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.<org.pragmatica.aether.update.DeploymentManager>none());
    }

    @Test
    void onHttpForwardRequest_expiredWireBudget_refusesWithoutDispatch() {
        server.onHttpForwardRequest(forwardRequest("corr-expired", 10));

        var response = (HttpForwardResponse) network.sentMessages().getFirst();

        assertThat(response.success()).isFalse();
        assertThat(new String(response.payload())).contains("budget exhausted");
        assertThat(router.handleCount()).as("the router must never run for an abandoned request").isZero();
    }

    @Test
    void onHttpForwardRequest_noBudgetSentinel_dispatchesAsBefore() {
        server.onHttpForwardRequest(forwardRequest("corr-legacy", Deadline.NO_BUDGET));

        var response = (HttpForwardResponse) network.sentMessages().getFirst();

        assertThat(response.success()).isTrue();
        assertThat(router.handleCount()).isEqualTo(1);
    }

    @Test
    void onHttpForwardRequest_healthyWireBudget_dispatchesUnderTheReboundDeadline() {
        server.onHttpForwardRequest(forwardRequest("corr-budgeted", 5_000));

        var response = (HttpForwardResponse) network.sentMessages().getFirst();

        assertThat(response.success()).isTrue();
        assertThat(router.handleCount()).isEqualTo(1);
        assertThat(router.sawBoundedDeadline())
            .as("the wire budget must be re-minted locally so downstream layers consume it")
            .isTrue();
    }

    private static HttpForwardRequest forwardRequest(String correlationId, long remainingMillis) {
        return new HttpForwardRequest(SENDER_NODE,
                                      correlationId,
                                      "req-" + correlationId,
                                      new byte[] {1},
                                      org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline.APP,
                                      remainingMillis);
    }

    private static HttpRequestContext context(String path) {
        return HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req-fwd");
    }

    private static final class CountingRouter implements SliceRouter {
        private final AtomicInteger handleCount = new AtomicInteger();
        private final AtomicReference<Boolean> boundedAtHandle = new AtomicReference<>(false);

        int handleCount() {
            return handleCount.get();
        }

        boolean sawBoundedDeadline() {
            return boundedAtHandle.get();
        }

        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            handleCount.incrementAndGet();
            boundedAtHandle.set(Deadline.current().isBounded());

            return Promise.success(HttpResponseData.httpResponseData(200, "{\"result\":\"served\"}"));
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

    private static final class StubSerializer implements Serializer {
        @Override public <T> void write(ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[] {2};}
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
