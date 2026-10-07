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
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
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


/// #1983: the validated `SecurityContext` must be BOUND on the owner when a management request arrives forwarded, as it
/// is on the node that took the client call. The owner re-validates the forwarded headers, but the context it got back
/// was discarded and the route ran unbound, so every reader of the principal saw ANONYMOUS: an authenticated OPERATOR
/// Maven push that a non-owner forwarded to the DEPLOYMENT owner was refused 401 by `MavenProtocolRoutes.admitPush`
/// (security on, dev mode off). The response is read from the serializer, since the forwarded answer leaves the node as
/// encoded `HttpResponseData`.
class ManagementForwardedSecurityContextTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeId SENDER = NodeId.nodeId("node-sender").unwrap();
    private static final String PUSH_PATH = "/repository/org/example/art/1.0.0/art-1.0.0.jar";
    private static final long HEALTHY_BUDGET_MILLIS = 30_000;

    private final RecordingClusterNetwork network = new RecordingClusterNetwork();
    private final CapturingSerializer serializer = new CapturingSerializer();

    @Test
    void forwardedOperatorPush_isAdmitted_becauseTheValidatedPrincipalIsBoundOnTheOwner() {
        server(authenticated(AuthorizationRole.OPERATOR)).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).as("an authenticated OPERATOR push forwarded to the owner is admitted, not refused 401")
                                       .isEqualTo(201);
    }

    /// Control: the same forwarded push, with the in-route gate genuinely live, is refused for a caller below OPERATOR.
    /// The management gate (OPERATOR on `/repository` mutations) refuses it first, so the control proves the forwarded
    /// path enforces authorization, and that the 201 above is not simply "nothing is checked".
    @Test
    void forwardedPush_byACallerBelowOperator_isStillRefused() {
        server(authenticated(AuthorizationRole.VIEWER)).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isIn(401, 403);
    }

    private static SecurityContext authenticated(AuthorizationRole role) {
        return SecurityContext.securityContext("ops-alice", java.util.Set.of(), role).unwrap();
    }

    private static HttpForwardRequest forwardRequest() {
        return new HttpForwardRequest(SENDER, "corr-1983", "req-1983", new byte[] {1}, Pipeline.MANAGEMENT, HEALTHY_BUDGET_MILLIS);
    }

    private ManagementServerImpl server(SecurityContext validated) {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);
        var maven = mock(MavenProtocolHandler.class);
        var validator = mock(SecurityValidator.class);

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(SELF);
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.clusterTopologyManager()).thenReturn(Option.none());
        when(node.consumerGroupCoordinator()).thenReturn(ConsumerGroupCoordinator.noOp());
        when(node.consumerGroupRegistry()).thenReturn(ConsumerGroupRegistry.consumerGroupRegistry());
        when(node.streamNamespacesService()).thenReturn(mock(StreamNamespacesService.class));
        when(node.hasCompleteClusterView()).thenReturn(true);
        when(node.mavenProtocolHandler()).thenReturn(maven);
        when(maven.handlePut(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Promise.success(new MavenResponse(201, "text/plain", "created".getBytes())));
        when(validator.validate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.pragmatica.lang.Result.success(validated));

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
                                        validator,
                                        true,
                                        Map::of,
                                        Option.none(),
                                        Option.none(),
                                        HttpProtocol.H1,
                                        ForwardingTimeouts.forwardingTimeouts(),
                                        Option.some(network),
                                        Option.some(serializer),
                                        Option.some(new StubDeserializer(HttpRequestContext.httpRequestContext(PUSH_PATH,
                                                                                                                "PUT",
                                                                                                                Map.of(),
                                                                                                                Map.of(),
                                                                                                                new byte[] {1, 2, 3},
                                                                                                                "req-1983"))),
                                        _ -> {},
                                        Set::of);
    }

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}
        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}
        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {return unit();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public int connectedNodeCount() {return 1;}
        @Override public Set<NodeId> connectedPeers() {return Set.of(SENDER);}
        @Override public Option<Server> server() {return Option.none();}
    }

    /// Keeps the `HttpResponseData` the owner encodes for the sender: the status is what the test reads.
    private static final class CapturingSerializer implements Serializer {
        private volatile HttpResponseData captured;

        int status() {
            assertThat(captured).as("the owner answered the forwarded request").isNotNull();

            return captured.statusCode();
        }

        @Override public <T> void write(ByteBuf byteBuf, T object) {}

        @Override
        public <T> byte[] encode(T value) {
            if (value instanceof HttpResponseData data) {
                captured = data;
            }

            return new byte[] {2};
        }
    }

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
