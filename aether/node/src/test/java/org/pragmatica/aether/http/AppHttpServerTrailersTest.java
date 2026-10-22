// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.node.ClusterTestPorts;
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
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2310: request trailers reach a slice on the node that received the request AND on the node a request is forwarded
/// to. The forward carries the request as an encoded [HttpRequestContext], so its `trailers` component is part of the
/// wire shape (see `wire-assignment-baseline.txt`).
///
/// The ingress test pins `toHttpRequestContext`, the one conversion both the local dispatch and the forward use, over
/// a real HTTP/1.1 chunked request. The forward test pins the other hop: the production node codec carries the
/// trailers, and the host dispatches the decoded request with them.
class AppHttpServerTrailersTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("trailers-host").unwrap();
    private static final NodeId SENDER_NODE = NodeId.nodeId("trailers-ingress").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    private RecordingClusterNetwork network;
    private CapturingSerializer serializer;
    private CapturingRouter router;

    @BeforeEach
    void setUp() {
        network = new RecordingClusterNetwork();
        serializer = new CapturingSerializer();
        router = new CapturingRouter();
    }

    @Test
    void chunkedRequest_withTrailers_reachesTheLocalSliceWithThem() throws IOException {
        var port = ClusterTestPorts.freeTcpAndUdpPort();
        var server = server(port, Option.none());

        server.start().await();
        try {
            var status = exchange(port,
                                  "POST /api/upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n"
                                  + "Connection: close\r\n\r\n5\r\nhello\r\n0\r\n"
                                  + "x-checksum: abc123\r\nauthorization: Bearer forged\r\n\r\n");

            assertThat(status).isEqualTo(200);
        } finally {
            server.stop().await();
        }

        var seen = router.seen();

        assertThat(seen.trailers()).containsEntry("x-checksum", List.of("abc123"));
        assertThat(seen.trailers()).as("a forbidden trailer field never reaches the slice")
                                   .doesNotContainKey("authorization");
        assertThat(seen.headers()).doesNotContainKey("x-checksum");
    }

    @Test
    void forwardedRequest_carriesItsTrailersThroughTheProductionCodec_toTheHostsSlice() {
        var codec = productionCodec();
        var forwarded = HttpRequestContext.httpRequestContext("/api/upload",
                                                              "POST",
                                                              Map.of(),
                                                              Map.of(),
                                                              "hello".getBytes(StandardCharsets.UTF_8),
                                                              Map.of("x-checksum", List.of("abc123")),
                                                              "req-fwd",
                                                              SecurityContext.securityContext());

        server(ClusterTestPorts.freeTcpAndUdpPort(), Option.<Deserializer>some(codec)).onHttpForwardRequest(encodedForward(codec.encode(forwarded)));

        assertThat(router.seen().trailers()).containsEntry("x-checksum", List.of("abc123"));
    }

    @Test
    void forwardedRequest_withoutTrailers_reachesTheHostsSliceWithNone() {
        var codec = productionCodec();
        var forwarded = HttpRequestContext.httpRequestContext("/api/upload",
                                                              "POST",
                                                              Map.of(),
                                                              Map.of("x-client", List.of("probe")),
                                                              "req-fwd");

        server(ClusterTestPorts.freeTcpAndUdpPort(), Option.<Deserializer>some(codec)).onHttpForwardRequest(encodedForward(codec.encode(forwarded)));

        assertThat(router.seen().trailers()).isEmpty();
    }

    private AppHttpServer server(int port, Option<Deserializer> deserializer) {
        var publisher = new StubRoutePublisher("POST", "/api/", SELF_NODE, router, SecurityPolicy.publicRoute());

        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(port),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.some(publisher),
                                           Option.some(network),
                                           Option.some(serializer),
                                           deserializer,
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<org.pragmatica.aether.update.DeploymentManager>none());
    }

    private static SliceCodec productionCodec() {
        return org.pragmatica.aether.node.NodeCodecs.nodeCodecs(org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs());
    }

    private static HttpForwardRequest encodedForward(byte[] bytes) {
        return new HttpForwardRequest(SENDER_NODE,
                                      "corr-trailers",
                                      "req-trailers",
                                      bytes,
                                      org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline.APP,
                                      Deadline.NO_BUDGET);
    }

    private static int exchange(int port, String raw) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

            return Integer.parseInt(response.substring(9, 12));
        }
    }

    /// Keeps the request the slice was handed.
    private static final class CapturingRouter implements SliceRouter {
        private final AtomicReference<HttpRequestContext> seen = new AtomicReference<>();

        HttpRequestContext seen() {
            assertThat(seen.get()).as("the request reached the slice").isNotNull();

            return seen.get();
        }

        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            seen.set(request);

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

    /// Minimal publisher hosting one live local route, mirroring AppHttpServerForwardReauthorizationTest's stub.
    private record StubRoutePublisher(String httpMethod,
                                      String pathPrefix,
                                      NodeId nodeId,
                                      SliceRouter router,
                                      SecurityPolicy security) implements HttpRoutePublisher {
        @Override
        public Option<LocalResolution> resolveLocal(String method, String path) {
            return matches(method, path)
                   ? Option.some(new LocalResolution(new LocalRouteInfo(httpMethod,
                                                                        pathPrefix,
                                                                        TEST_ARTIFACT.asString(),
                                                                        "upload",
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
                   ? Option.some(new LocalRouteInfo(httpMethod, pathPrefix, TEST_ARTIFACT.asString(), "upload", security))
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

    private static final class CapturingSerializer implements Serializer {
        @Override public <T> void write(ByteBuf byteBuf, T object) {}

        @Override
        public <T> byte[] encode(T value) {
            return new byte[] {2};
        }
    }
}
