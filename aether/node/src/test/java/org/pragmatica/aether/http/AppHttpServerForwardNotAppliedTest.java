// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.forward.ForwardError;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardNotApplied;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.NotAppliedReason;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2244 F1, sender side: a target's not-applied answer, delivered to the app server, reaches its forwarder and ends
/// the attempt as provably not applied. A POST refused that way answers "nothing was applied; safe to retry". If the
/// app server dropped the answer, the attempt would wait out its hop timeout and the POST would answer "may have been
/// applied" instead.
class AppHttpServerForwardNotAppliedTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("forwarding-node").unwrap();
    private static final NodeId REMOTE_NODE = NodeId.nodeId("refusing-node").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    private int port;
    private HttpClient httpClient;
    private RefusingNetwork network;
    private AppHttpServer server;

    @BeforeEach
    void setUp() {
        port = freeTcpPort();
        var registry = HttpRouteRegistry.httpRouteRegistry();

        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        network = new RefusingNetwork();
        registerNodeRoute(registry, "POST", "/remote/", REMOTE_NODE);
        server = AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(port),
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             SELF_NODE,
                                             registry,
                                             Option.some(new HostsNothingPublisher()),
                                             Option.some(network),
                                             Option.some(new StubSerializer()),
                                             Option.some(new StubDeserializer()),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.<org.pragmatica.aether.update.DeploymentManager>none());
        network.respondTo(server);
        server.start().await();
        server.onQuorumStateChange(ClusterStateNotification.active());
    }

    @AfterEach
    void tearDown() {
        server.stop().await();
    }

    @Test
    void postRefusedNotAppliedByItsTarget_answersNothingWasApplied() throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/remote/thing"))
                                 .timeout(Duration.ofSeconds(30))
                                 .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                 .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(network.forwards.get()).as("control: the POST was forwarded").isPositive();
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains(ForwardError.NOT_SENT_TEXT)
                                   .doesNotContain(ForwardError.OUTCOME_UNKNOWN_TEXT);
    }

    private static void registerNodeRoute(HttpRouteRegistry registry, String method, String path, NodeId nodeId) {
        var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
        var value = NodeRoutesValue.nodeRoutesValue(List.of(RouteEntry.activeRoute(method, path, "create")));

        registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// The target refuses every forward before dispatch, as a node with no local route for it would.
    private static final class RefusingNetwork implements ClusterNetwork {
        private final AtomicReference<AppHttpServer> responder = new AtomicReference<>();
        private final AtomicInteger forwards = new AtomicInteger();

        void respondTo(AppHttpServer server) {
            responder.set(server);
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof HttpForwardRequest request) {
                forwards.incrementAndGet();
                responder.get()
                         .onHttpForwardNotApplied(new HttpForwardNotApplied(REMOTE_NODE,
                                                                            request.correlationId(),
                                                                            request.requestId(),
                                                                            NotAppliedReason.ROUTE_NOT_FOUND,
                                                                            request.pipeline()));
            }

            return unit();
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return 1;}
        @Override public Set<NodeId> connectedPeers() {return Set.of(REMOTE_NODE);}
        @Override public Option<Server> server() {return Option.none();}
    }

    private static final class StubSerializer implements Serializer {
        @Override public <T> void write(ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[] {2};}
    }

    private static final class StubDeserializer implements Deserializer {
        @Override public <T> T read(ByteBuf byteBuf) {return null;}
    }

    /// Hosts no route locally: every request for a remote route takes the forward branch.
    private static final class HostsNothingPublisher implements HttpRoutePublisher {
        @Override public Set<HttpNodeRouteKey> allLocalRoutes() {return Set.of();}
        @Override public Option<SliceRouter> findLocalRouter(String method, String prefix) {return Option.none();}
        @Override public Option<LocalRouteInfo> findLocalRoute(String method, String path) {return Option.none();}

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

        @Override public boolean hasRoutes(ClassLoader classLoader, Object sliceInstance) {return false;}
        @Override public Promise<Unit> unpublishRoutes(Artifact artifact) {return Promise.success(unit());}
        @Override public Option<HttpRequestHandler> getHandler(Artifact artifact) {return Option.none();}
        @Override public Option<SliceRouter> getSliceRouter(Artifact artifact) {return Option.none();}
        @Override public Unit updateSecurityOverrides(SecurityOverrides overrides) {return unit();}
        @Override public Unit setVersioningMetricsSink(VersioningMetricsSink sink) {return unit();}
        @Override public Map<Artifact, SliceVersionRegistry> versionRegistries() {return Map.of();}
        @Override public Unit setObservabilityCellRegistrar(ObservabilityCellRegistrar registrar) {return unit();}
    }
}
