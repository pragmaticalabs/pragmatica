// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.Unit.unit;


/// #1039 receive-side owner guard, WIRED: what a real `ManagementServerImpl` does with an
/// `HttpForwardRequest` for a `PartitionOwner` route.
///
/// [ForwardedOwnerGuardTest] pins the decision; this pins that the receive path CONSULTS it. The
/// distinction is the whole finding — before this change `ManagementRouteError.OwnerForwardLoop` was
/// unreachable code behind docs promising it fired, and a decision test alone would have stayed green
/// with the call site deleted (measured: 1368 aether/node tests, 0 failures, with the call site
/// removed).
///
/// The node is mocked only as far as the constructor and this one path reach. That is deliberate: the
/// alternative — no wired test at all — is what let the previous guard ship unreachable.
class ManagementForwardOwnerGuardWiringTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeId SENDER = NodeId.nodeId("node-sender").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("node-other").unwrap();
    private static final String REPLICAS_PATH = "/api/v1/streams/myns/orders/1.0.0/replicas/7";
    private static final long HEALTHY_BUDGET_MILLIS = 30_000;

    private final RecordingClusterNetwork network = new RecordingClusterNetwork();
    private final StreamPartitionManager partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Test
    void onHttpForwardRequest_refusesWithOwnerForwardLoop_whenTheReceiverIsNotTheResolvedOwner() {
        server(REPLICAS_PATH, Option.some(OTHER)).onHttpForwardRequest(forwardRequest());

        var response = soleResponse();

        assertThat(response.success())
                .as("a receiver that disagrees about the owner must refuse, not answer 200 with servedByOwner=false")
                .isFalse();
        assertThat(new String(response.payload()))
                .contains("Owner-forward loop on STREAM_REPLICAS")
                .contains(SENDER.id());
    }

    @Test
    void onHttpForwardRequest_refusesWithPartitionOwnerUnresolved_whenNoOwnerResolves() {
        server(REPLICAS_PATH, Option.none()).onHttpForwardRequest(forwardRequest());

        var response = soleResponse();

        assertThat(response.success()).isFalse();
        assertThat(new String(response.payload())).contains("No partition owner resolvable for STREAM_REPLICAS");
    }

    @Test
    void onHttpForwardRequest_dispatchesAsBefore_forARouteThatIsNotPartitionOwned() {
        // Regression guard on a SHARED receive path. An unmatched path is the cheapest probe that the
        // guard let dispatch proceed: the 404 is produced BELOW it, by the router fallthrough.
        server("/api/v1/no/such/route", Option.some(OTHER)).onHttpForwardRequest(forwardRequest());

        var response = soleResponse();

        assertThat(response.success())
                .as("only PartitionOwner routes are judged here; everything else dispatches untouched")
                .isTrue();
    }

    @Test
    void onHttpForwardRequest_dispatchesLocally_whenThisNodeIsTheResolvedOwner() {
        // The owner must still answer. What this pins is the negative: no refusal is emitted for the
        // node the sender correctly chose, so the guard cannot turn every forward into a 503.
        server(REPLICAS_PATH, Option.some(SELF)).onHttpForwardRequest(forwardRequest());

        assertThat(new String(soleResponse().payload()))
                .as("the resolved owner is not a loop and not unresolvable")
                .doesNotContain("Owner-forward loop")
                .doesNotContain("No partition owner resolvable");
    }

    private HttpForwardResponse soleResponse() {
        assertThat(network.sentMessages()).hasSize(1);

        return (HttpForwardResponse) network.sentMessages().getFirst();
    }

    private static HttpForwardRequest forwardRequest() {
        return new HttpForwardRequest(SENDER,
                                      "corr-owner-guard",
                                      "req-owner-guard",
                                      new byte[] {1},
                                      Pipeline.MANAGEMENT,
                                      HEALTHY_BUDGET_MILLIS);
    }

    private ManagementServerImpl server(String path, Option<NodeId> owner) {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(SELF);
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.clusterTopologyManager()).thenReturn(Option.none());
        when(node.consumerGroupCoordinator()).thenReturn(ConsumerGroupCoordinator.noOp());
        when(node.consumerGroupRegistry()).thenReturn(ConsumerGroupRegistry.consumerGroupRegistry());
        when(node.streamNamespacesService()).thenReturn(mock(StreamNamespacesService.class));
        when(node.streamReadRouter()).thenReturn(StreamReadRouter.streamReadRouter(partitionManager,
                                                                                   Option.none(),
                                                                                   Option.none(),
                                                                                   SELF,
                                                                                   (_, _) -> owner,
                                                                                   StreamReadForwardMetrics.NOOP));

        return new ManagementServerImpl(0,
                                        () -> node,
                                        mock(EntityCheckpointDriver.class),
                                        mock(AlertManager.class),
                                        mock(ObservabilityConfigRegistry.class),
                                        mock(InvocationTraceStore.class),
                                        mock(LogLevelRegistry.class),
                                        Option.none(),
                                        mock(ScheduledTaskRegistry.class),
                                        mock(ScheduledTaskManager.class),
                                        mock(SliceInvoker.class),
                                        mock(ScheduledTaskStateRegistry.class),
                                        Option.none(),
                                        mock(SecurityValidator.class),
                                        false,
                                        Map::of,
                                        Option.none(),
                                        Option.none(),
                                        HttpProtocol.H1,
                                        ForwardingTimeouts.forwardingTimeouts(),
                                        Option.some(network),
                                        Option.some(new StubSerializer()),
                                        Option.some(new StubDeserializer(HttpRequestContext.httpRequestContext(path,
                                                                                                                "GET",
                                                                                                                Map.of(),
                                                                                                                Map.of(),
                                                                                                                "req-owner-guard"))),
                                        _ -> {},
                                        Set::of);
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
        @Override public Set<NodeId> connectedPeers() {return Set.of(SENDER);}
        @Override public Option<Server> server() {return Option.none();}
    }

    private static final class StubSerializer implements Serializer {
        @Override public <T> void write(ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[] {2};}
    }

    /// decode() returns the prepared request context regardless of the wire bytes — the codec is not
    /// what this test pins.
    private static final class StubDeserializer implements Deserializer {
        private final HttpRequestContext context;

        StubDeserializer(HttpRequestContext context) {
            this.context = context;
        }

        @Override public <T> T read(ByteBuf byteBuf) {return null;}

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {return (T) context;}
    }
}
