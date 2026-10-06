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
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.security.SecurityValidator;
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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.ResponseWriter;
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


/// #1039 SEND side, WIRED: that `dispatchManagementRequest` still CONSULTS the owner decision.
///
/// [ManagementServerPartitionOwnerTest] pins the decision (`answersPartitionLocally`,
/// `partitionOwner`, the engine-key identity); nothing pinned that `dispatchManagementRequest`
/// calls `tryForwardToRouteOwner` at all. A decision that is correct and never called is precisely
/// the defect the receive side shipped with — `ManagementRouteError.OwnerForwardLoop` was
/// unreachable behind documentation promising it fired — so the same probe is owed on this side.
/// [ManagementForwardOwnerGuardWiringTest] is this test's mirror on the receive path.
///
/// The observable is the wire: a forwarded request leaves as an `HttpForwardRequest` addressed to
/// the resolved owner, so "did it forward?" is answered by the cluster network rather than by
/// inspecting a boolean.
///
/// The node fixture below duplicates that sibling's deliberately: the two need different stub sets
/// (this one additionally needs `httpRouteRegistry`, `taskGroupOwnerResolver`, `leader`, and a peer
/// set containing the owner), and each pins a different path. Sharing them would couple two probes
/// that must be able to fail independently.
class ManagementServerForwardDispatchTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeId OWNER = NodeId.nodeId("node-owner").unwrap();
    private static final String REPLICAS_PATH = "/api/v1/streams/myns/orders/1.0.0/replicas/7";

    private final RecordingClusterNetwork network = new RecordingClusterNetwork();
    private final RecordingResponseWriter writer = new RecordingResponseWriter();
    private final StreamPartitionManager partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Test
    void dispatchManagementRequest_forwardsToThePartitionOwner_whenThisNodeIsNotTheOwner() {
        dispatch(server(Option.some(OWNER)), REPLICAS_PATH);

        assertThat(network.targets())
                .as("a PartitionOwner-targeted route must leave this node for the resolved owner; "
                   + "answering locally is what produced servedByOwner=false on every port")
                .containsExactly(OWNER);
        assertThat(network.sentMessages().getFirst()).isInstanceOf(HttpForwardRequest.class);
        assertThat(writer.writes())
                .as("nothing may be written locally for a request that was forwarded")
                .isEmpty();
    }

    @Test
    void dispatchManagementRequest_doesNotForward_forAnUnmatchedPath() {
        // Guard on the over-forwarding direction, and the control for the test above: the same
        // fixture, the same resolvable owner, a path that is not owner-targeted — nothing goes out.
        dispatch(server(Option.some(OWNER)), "/api/v1/no/such/route");

        assertThat(network.targets())
                .as("only routes whose target is PartitionOwner are owner-forwarded from here")
                .isEmpty();
    }

    /// #1921 (e): a forwarded route is validated BEFORE it leaves this node. A partition that is not an integer, or an address that
    /// does not parse, used to resolve no owner and ride the forwarder to a 503 ("the cluster is unavailable") for what is the
    /// caller's own malformed request. Nothing may be sent and the answer is 400.
    @Test
    void dispatchManagementRequest_answers400AndSendsNothing_whenThePartitionIsNotAnInteger() {
        dispatch(server(Option.some(OWNER)), "/api/v1/streams/myns/orders/1.0.0/replicas/abc");

        assertThat(network.targets()).isEmpty();
        assertThat(writer.writes()).containsExactly(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dispatchManagementRequest_answers400AndSendsNothing_whenTheStreamAddressIsMalformed() {
        dispatch(server(Option.some(OWNER)), "/api/v1/streams/myns/orders/not-a-version/replicas/7");

        assertThat(network.targets()).isEmpty();
        assertThat(writer.writes()).containsExactly(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dispatchManagementRequest_answers400AndSendsNothing_whenTheNodeIdIsBlank() {
        dispatch(server(Option.some(OWNER)), "/api/v1/nodes/status/ ");

        assertThat(network.targets()).isEmpty();
        assertThat(writer.writes()).containsExactly(HttpStatus.BAD_REQUEST);
    }

    /// Control, and the boundary of (e): a WELL-FORMED node id that names no node this one is connected to is not a malformed
    /// request, and keeps the forwarder's 503. If this went 400 the validation would be refusing on reachability, not on form.
    @Test
    void dispatchManagementRequest_stillAnswers503_whenTheNodeIdIsWellFormedButNamesNoConnectedNode() {
        dispatch(server(Option.some(OWNER)), "/api/v1/nodes/status/node-nobody");

        assertThat(network.targets()).isEmpty();
        assertThat(writer.writes()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /// Control: a well-formed partition whose owner cannot be resolved keeps the forwarder's `PartitionOwnerUnresolved` 503 (#1039).
    @Test
    void dispatchManagementRequest_stillAnswers503_whenTheRequestIsWellFormedButTheOwnerIsUnresolvable() {
        dispatch(server(Option.none()), REPLICAS_PATH);

        assertThat(network.targets()).isEmpty();
        assertThat(writer.writes()).containsExactly(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void dispatch(ManagementServerImpl server, String path) {
        server.dispatchManagementRequest(new StubHttpRequest(path),
                                         InstrumentedResponseWriter.instrumentedResponseWriter(writer),
                                         "GET",
                                         System.nanoTime());
    }

    private ManagementServerImpl server(Option<NodeId> owner) {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(SELF);
        when(node.leader()).thenReturn(Option.none());
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.httpRouteRegistry()).thenReturn(HttpRouteRegistry.httpRouteRegistry());
        when(node.taskGroupOwnerResolver()).thenReturn(HttpForwarder.UNASSIGNED_RESOLVER);
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
                                        Option.some(new StubDeserializer()),
                                        _ -> {},
                                        Set::of);
    }

    private record StubHttpRequest(String path) implements HttpRequest {
        @Override public String requestId() {return "req-dispatch";}
        @Override public HttpMethod method() {return HttpMethod.GET;}
        @Override public Headers headers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final List<HttpStatus> writes = new ArrayList<>();

        synchronized List<HttpStatus> writes() {
            return List.copyOf(writes);
        }

        @Override public synchronized void write(HttpStatus status, byte[] body, ContentType contentType) {
            writes.add(status);
        }

        @Override public ResponseWriter header(String name, String value) {return this;}
    }

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        private final List<ProtocolMessage> sentMessages = new ArrayList<>();
        private final List<NodeId> targets = new ArrayList<>();

        synchronized List<ProtocolMessage> sentMessages() {
            return List.copyOf(sentMessages);
        }

        synchronized List<NodeId> targets() {
            return List.copyOf(targets);
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}

        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override public synchronized <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            targets.add(nodeId);
            sentMessages.add(message);

            return unit();
        }

        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return 2;}
        @Override public Set<NodeId> connectedPeers() {return Set.of(OWNER);}
        @Override public Option<Server> server() {return Option.none();}
    }

    private static final class StubSerializer implements Serializer {
        @Override public <T> void write(ByteBuf byteBuf, T object) {}
        @Override public <T> byte[] encode(T value) {return new byte[] {2};}
    }

    private static final class StubDeserializer implements Deserializer {
        @Override public <T> T read(ByteBuf byteBuf) {return null;}

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {return (T) HttpResponseData.httpResponseData(200, "{}");}
    }
}
