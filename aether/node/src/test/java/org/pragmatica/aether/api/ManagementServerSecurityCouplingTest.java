// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
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
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;
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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.Unit.unit;


/// #962 / #1121: what the management plane tells a caller it refused, and what it does when security is off.
///
/// #962: the management plane authorizes on the `authorization_role` axis (ADMIN > OPERATOR > VIEWER, read from the
/// key's `authorization_role`), while app-HTTP route policies check the key's `roles` set. A 401/403 here used to read
/// "Access denied: role VIEWER cannot access OPERATOR endpoint" and name neither the plane nor the axis, so an operator
/// who had just enabled app-HTTP api-key mode diagnosed the wrong component. The refusal text must name both.
///
/// #1121: `security_mode = "none"` is FULLY OPEN on the management plane. `securityEnabled=false` never consults the
/// validator, so nothing refuses a non-public route; the earlier comment and changelog claim of a refusal was false.
/// The last test pins the documented behaviour, so a future change to it is a decision and not an accident. It is green
/// on today's code by design: it is a pin, not a reproduction.
class ManagementServerSecurityCouplingTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeId SENDER = NodeId.nodeId("node-sender").unwrap();
    private static final String PUSH_PATH = "/repository/org/example/art/1.0.0/art-1.0.0.jar";
    private static final long HEALTHY_BUDGET_MILLIS = 30_000;

    private final RecordingClusterNetwork network = new RecordingClusterNetwork();
    private final CapturingSerializer serializer = new CapturingSerializer();
    private final MembershipFsm fsm = MembershipFsm.membershipFsm();

    @Test
    void missingCredentials_refusalNamesTheManagementPlane() {
        var validator = validatorReturning(SecurityError.MISSING_API_KEY.result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(401);
        assertThat(serializer.bodyText()).contains("Management API").contains("X-API-Key header required");
    }

    @Test
    void insufficientRole_refusalNamesThePlaneAndTheAuthorizationRoleAxis() {
        var validator = validatorReturning(Result.success(SecurityContext.securityContext("ops-alice",
                                                                                          Set.of(),
                                                                                          AuthorizationRole.VIEWER)
                                                                         .unwrap()));

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(403);
        assertThat(serializer.bodyText()).contains("Management API")
                                         .contains("authorization_role")
                                         .contains("VIEWER")
                                         .contains("OPERATOR");
    }

    @Test
    void securityDisabled_anonymousPush_isServedWithoutConsultingTheValidator() {
        var validator = validatorReturning(SecurityError.NO_VALIDATOR_CONFIGURED.result());

        server(validator, false).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).as("security_mode = none serves a non-public route to an anonymous caller")
                                       .isEqualTo(201);
        verify(validator, never()).validate(any(), any());
    }

    @Test
    void accessDeniedFromTheValidator_refusalNamesThePlaneAndTheAuthorizationRoleAxis() {
        var validator = validatorReturning(new SecurityError.AccessDenied("key may not use this plane").result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(403);
        assertThat(serializer.bodyText()).contains("Management API").contains("authorization_role").contains("key may not use this plane");
    }

    @Test
    void securityDisabled_localPath_servesAnonymousRequestWithoutConsultingTheValidator() {
        var validator = validatorReturning(SecurityError.NO_VALIDATOR_CONFIGURED.result());
        var writer = new RecordingResponseWriter();

        server(validator, false).handleRequest(new StubHttpRequest("/api/v1/no-such-route"), writer);

        verify(validator, never()).validate(any(), any());
        assertThat(writer.status()).as("dispatch was reached, not refused").isNotIn(401, 403);
    }

    /// Control for the pin above: the same local request with security ENABLED does consult the validator and is
    /// refused, so the disabled case passing is the gate and not a request that can never be refused.
    @Test
    void securityEnabled_localPath_consultsTheValidatorAndRefuses() {
        var validator = validatorReturning(SecurityError.MISSING_API_KEY.result());
        var writer = new RecordingResponseWriter();

        server(validator, true).handleRequest(new StubHttpRequest("/api/v1/no-such-route"), writer);

        verify(validator).validate(any(), any());
        assertThat(writer.status()).isEqualTo(401);
    }

    private record StubHttpRequest(String path) implements HttpRequest {
        @Override public String requestId() {return "req-962-local";}
        @Override public HttpMethod method() {return HttpMethod.GET;}
        @Override public Headers headers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private volatile HttpStatus status;

        int status() {
            assertThat(status).as("the server answered").isNotNull();

            return status.code();
        }

        @Override public void write(HttpStatus status, byte[] body, ContentType contentType) {this.status = status;}

        @Override public ResponseWriter header(String name, String value) {return this;}
    }

    private static SecurityValidator validatorReturning(Result<SecurityContext> outcome) {
        var validator = mock(SecurityValidator.class);

        when(validator.validate(any(), any())).thenReturn(outcome);

        return validator;
    }

    private static HttpForwardRequest forwardRequest() {
        return new HttpForwardRequest(SENDER, "corr-962", "req-962", new byte[] {1}, Pipeline.MANAGEMENT, HEALTHY_BUDGET_MILLIS);
    }

    private ManagementServerImpl server(SecurityValidator validator, boolean securityEnabled) {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);
        var maven = mock(MavenProtocolHandler.class);
        var forwarded = HttpRequestContext.httpRequestContext(PUSH_PATH, "PUT", Map.of(), Map.of(), new byte[] {1, 2, 3}, "req-962");

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(SELF);
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.clusterTopologyManager()).thenReturn(Option.none());
        when(node.consumerGroupCoordinator()).thenReturn(ConsumerGroupCoordinator.noOp());
        when(node.consumerGroupRegistry()).thenReturn(ConsumerGroupRegistry.consumerGroupRegistry());
        when(node.streamNamespacesService()).thenReturn(mock(StreamNamespacesService.class));
        when(node.hasCompleteClusterView()).thenReturn(true);
        when(node.membershipFsm()).thenReturn(fsm);
        when(node.coreNodeIds()).thenReturn(Set.of(SELF));
        var kv = mock(org.pragmatica.cluster.state.kvstore.KVStore.class);

        when(kv.get(any())).thenReturn(Option.none());
        when(node.kvStore()).thenReturn(kv);
        when(node.mavenProtocolHandler()).thenReturn(maven);
        when(maven.handlePut(anyString(), any()))
                .thenReturn(Promise.success(new MavenResponse(201, "text/plain", "created".getBytes())));

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
                                        securityEnabled,
                                        Map::of,
                                        Option.none(),
                                        Option.none(),
                                        HttpProtocol.H1,
                                        ForwardingTimeouts.forwardingTimeouts(),
                                        Option.some(network),
                                        Option.some(serializer),
                                        Option.some(new StubDeserializer(forwarded)),
                                        _ -> {},
                                        Set::of,
                                        NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> List.of(), OperatorWarningSink.logOnly()));
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

    /// Keeps the `HttpResponseData` the owner encodes for the sender: status and problem body are what the tests read.
    private static final class CapturingSerializer implements Serializer {
        private volatile HttpResponseData captured;

        int status() {
            assertThat(captured).as("the owner answered the forwarded request").isNotNull();

            return captured.statusCode();
        }

        String bodyText() {
            assertThat(captured).as("the owner answered the forwarded request").isNotNull();

            return new String(captured.body(), java.nio.charset.StandardCharsets.UTF_8);
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
