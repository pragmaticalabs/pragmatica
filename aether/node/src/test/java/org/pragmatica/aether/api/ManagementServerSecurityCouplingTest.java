// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.GrantedRouteProbe;
import org.pragmatica.aether.api.routes.ManagementRouter;
import org.pragmatica.aether.config.ApiKeyEntry;
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
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.AdminGrant;
import org.pragmatica.aether.http.security.Authenticated;
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
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final String ADMIN_KEY = "grant-admin-key";
    private static final Map<String, ApiKeyEntry> ADMIN_KEYS = Map.of(ADMIN_KEY,
                                                                      ApiKeyEntry.apiKeyEntry("grant-admin",
                                                                                              Set.of("service"),
                                                                                              "ADMIN"));

    private final RecordingClusterNetwork network = new RecordingClusterNetwork();
    private final CapturingSerializer serializer = new CapturingSerializer();
    private final MembershipFsm fsm = MembershipFsm.membershipFsm();

    @Test
    void missingCredentials_refusalNamesTheManagementPlane() {
        var validator = validatorReturning(SecurityError.MISSING_API_KEY.result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(401);
        assertThat(serializer.bodyText()).contains("Management API")
                                         .contains("authentication refused")
                                         .contains("X-API-Key header required")
                                         .doesNotContain("authorization_role");
    }

    /// #2926: an unknown key is unauthenticated -- 401, never 403 -- and says so with the RFC 6750 error code.
    @Test
    void invalidCredentials_refusedWith401_andTheInvalidTokenChallenge() {
        var validator = validatorReturning(SecurityError.INVALID_API_KEY.result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(401);
        assertThat(serializer.header("WWW-Authenticate")).isEqualTo("ApiKey realm=\"Aether\", error=\"invalid_token\"");
    }

    @Test
    void missingCredentials_refusedWith401_andAChallengeWithoutAnErrorCode() {
        var validator = validatorReturning(SecurityError.MISSING_API_KEY.result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.header("WWW-Authenticate")).isEqualTo("ApiKey realm=\"Aether\"");
    }

    @Test
    void accessDenied_refusedWith403_andNoChallenge() {
        var validator = validatorReturning(new SecurityError.AccessDenied("no").result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(403);
        assertThat(serializer.header("WWW-Authenticate")).isNull();
    }

    /// #2926: a failed key fetch is the server's fault -- 503 and no challenge, since no credential can fix it.
    @Test
    void keyFetchFailure_refusedWith503_andNoChallenge() {
        var validator = validatorReturning(new SecurityError.JwksFetchFailed("jwks down").result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(503);
        assertThat(serializer.header("WWW-Authenticate")).isNull();
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

    /// The management port serves no static assets any more (the dashboard was removed): `/`, `/index.html` and the
    /// old asset directories fall through to the same unmatched-path answer as any unknown route, `404` with the body
    /// `File not found: <path>` that operator runbooks quote. Mutation: answering 200, or a different body, turns
    /// every iteration red.
    @Test
    void unmatchedPath_isAnswered404FileNotFound_andFormerDashboardPathsServeNoAsset() {
        var server = server(validatorReturning(SecurityError.NO_VALIDATOR_CONFIGURED.result()), false);

        for (var path : List.of("/api/v1/no-such-route", "/", "/index.html", "/js/app.js", "/css/tokens.css", "/dashboard")) {
            var writer = new RecordingResponseWriter();

            server.handleRequest(new StubHttpRequest(path), writer);

            assertThat(writer.status()).as("status for %s", path).isEqualTo(404);
            assertThat(writer.bodyText()).as("body for %s", path).isEqualTo("File not found: " + path);
        }
    }

    /// The local secured path hands a granted route the very [Authenticated] the validator returned for an ADMIN key,
    /// and its grant is live while the route runs. Red if the binding wraps dispatch only (the grant is minted outside the
    /// scope, born dead) or if dispatch passes an ungranted `Authenticated`.
    @Test
    void grantedRoute_localPath_adminKey_receivesTheValidatedAuthenticatedWithALiveGrant() {
        var validator = RecordingValidator.recording(SecurityValidator.apiKeyValidator(ADMIN_KEYS));
        var probe = GrantedRouteProbe.answering();
        var writer = new RecordingResponseWriter();

        server(validator, true, probe).handleRequest(GrantRequest.adminGet(GrantedRouteProbe.PATH), writer);

        assertThat(writer.status()).isEqualTo(200);
        assertThat(validator.returned().flatMap(Authenticated::grant).toOptional()).as("the ADMIN key minted a grant").isPresent();
        assertThat(probe.received().toOptional()).as("the route received the Authenticated the server validated")
                                    .containsSame(validator.returned().unwrap());
        assertThat(probe.liveAtCall()).as("the grant was live while the route ran").isTrue();
        assertThat(validator.liveAtValidation()).as("the scope was open at the credential match").isTrue();
    }

    /// The forwarded secured path: the owner validates again inside the forwarded request's own scope and hands the
    /// granted route that `Authenticated`. Red if the forwarded dispatch drops it for an ungranted one.
    @Test
    void grantedRoute_forwardedPath_adminKey_receivesTheValidatedAuthenticatedWithALiveGrant() {
        var validator = RecordingValidator.recording(SecurityValidator.apiKeyValidator(ADMIN_KEYS));
        var probe = GrantedRouteProbe.answering();
        var forwarded = HttpRequestContext.httpRequestContext(GrantedRouteProbe.PATH,
                                                              "GET",
                                                              Map.of(),
                                                              Map.of("X-API-Key", List.of(ADMIN_KEY)),
                                                              new byte[0],
                                                              "req-grant-forwarded");

        server(validator, true, forwarded, probe::routerWith).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(200);
        assertThat(validator.returned().flatMap(Authenticated::grant).toOptional()).as("the ADMIN key minted a grant").isPresent();
        assertThat(probe.received().toOptional()).as("the route received the Authenticated the owner validated")
                                    .containsSame(validator.returned().unwrap());
        assertThat(probe.liveAtCall()).as("the grant was live while the route ran").isTrue();
    }

    /// `security_mode = "none"` validates nothing, so a granted route runs with no grant even when an ADMIN key is
    /// configured and sent. Red if the open path validates and passes the grant it minted.
    @Test
    void grantedRoute_securityDisabled_adminKeySent_receivesNoGrant() {
        var validator = RecordingValidator.recording(SecurityValidator.apiKeyValidator(ADMIN_KEYS));
        var probe = GrantedRouteProbe.answering();
        var writer = new RecordingResponseWriter();

        server(validator, false, probe).handleRequest(GrantRequest.adminGet(GrantedRouteProbe.PATH), writer);

        assertThat(writer.status()).isEqualTo(200);
        assertThat(validator.returned().toOptional()).as("the validator was never consulted").isEmpty();
        assertThat(probe.received().map(Authenticated::grant).toOptional()).as("the route ran, with no grant")
                                                              .contains(Option.none());
    }

    /// The grant dies when `handleRequest` returns: it was live in the route, and is dead to anyone still holding it.
    @Test
    void grant_isDeadOnceHandleRequestReturns() {
        var probe = GrantedRouteProbe.answering();
        var writer = new RecordingResponseWriter();

        server(SecurityValidator.apiKeyValidator(ADMIN_KEYS), true, probe).handleRequest(GrantRequest.adminGet(GrantedRouteProbe.PATH),
                                                                                         writer);

        assertThat(probe.liveAtCall()).as("control: live inside the route").isTrue();
        assertThat(probe.received().flatMap(Authenticated::grant).map(AdminGrant::live).toOptional()).as("dead after the return")
                                                                                         .contains(false);
    }

    /// The enforce-failure path: the validator mints an ADMIN grant, enforcement then refuses (the context it returns
    /// is VIEWER, on an OPERATOR route), and the grant is dead once the refusal returns.
    @Test
    void grant_isDeadOnceHandleRequestReturns_afterAnEnforceFailure() {
        var validator = RecordingValidator.downgradingToViewer(SecurityValidator.apiKeyValidator(ADMIN_KEYS));
        var writer = new RecordingResponseWriter();

        server(validator, true, GrantedRouteProbe.answering()).handleRequest(GrantRequest.admin(HttpMethod.PUT, PUSH_PATH),
                                                                             writer);

        assertThat(writer.status()).as("enforcement refused").isEqualTo(403);
        assertThat(validator.liveAtValidation()).as("control: live at the credential match").isTrue();
        assertThat(validator.returned().flatMap(Authenticated::grant).map(AdminGrant::live).toOptional()).as("dead after the refusal")
                                                                                            .contains(false);
    }

    /// The throwing-handler path: the route throws out of `handleRequest`, and the grant is dead after the throw.
    @Test
    void grant_isDeadOnceHandleRequestThrows() {
        var probe = GrantedRouteProbe.throwing();
        var server = server(SecurityValidator.apiKeyValidator(ADMIN_KEYS), true, probe);

        assertThatThrownBy(() -> server.handleRequest(GrantRequest.adminGet(GrantedRouteProbe.PATH),
                                                      new RecordingResponseWriter())).isInstanceOf(IllegalStateException.class);

        assertThat(probe.liveAtCall()).as("control: live inside the route").isTrue();
        assertThat(probe.received().flatMap(Authenticated::grant).map(AdminGrant::live).toOptional()).as("dead after the throw")
                                                                                         .contains(false);
    }

    /// A request carrying the test ADMIN key.
    private record GrantRequest(HttpMethod method, String path) implements HttpRequest {
        static GrantRequest adminGet(String path) {
            return admin(HttpMethod.GET, path);
        }

        static GrantRequest admin(HttpMethod method, String path) {
            return new GrantRequest(method, path);
        }

        @Override public String requestId() {return "req-grant-local";}
        @Override public Headers headers() {return Headers.headers(Map.of("X-API-Key", List.of(ADMIN_KEY)));}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    /// Delegates to a real validator and records what it returned and whether that grant was live at the match;
    /// the downgrading variant returns the same grant over a VIEWER context, so enforcement refuses after the mint.
    private static final class RecordingValidator implements SecurityValidator {
        private final SecurityValidator delegate;
        private final boolean downgrade;
        private final AtomicReference<Authenticated> returned = new AtomicReference<>();
        private final AtomicBoolean liveAtValidation = new AtomicBoolean();

        private RecordingValidator(SecurityValidator delegate, boolean downgrade) {
            this.delegate = delegate;
            this.downgrade = downgrade;
        }

        static RecordingValidator recording(SecurityValidator delegate) {
            return new RecordingValidator(delegate, false);
        }

        static RecordingValidator downgradingToViewer(SecurityValidator delegate) {
            return new RecordingValidator(delegate, true);
        }

        Option<Authenticated> returned() {
            return Option.option(returned.get());
        }

        boolean liveAtValidation() {
            return liveAtValidation.get();
        }

        @Override
        public Result<Authenticated> validate(HttpRequestContext request, SecurityPolicy policy) {
            return delegate.validate(request, policy).map(this::record);
        }

        private Authenticated record(Authenticated authenticated) {
            var outcome = downgrade
                          ? Authenticated.authenticated(viewer(), authenticated.grant())
                          : authenticated;

            liveAtValidation.set(outcome.grant().map(AdminGrant::live).or(false));
            returned.set(outcome);

            return outcome;
        }

        private static SecurityContext viewer() {
            return SecurityContext.securityContext("grant-viewer", Set.of(), AuthorizationRole.VIEWER).unwrap();
        }
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
        private volatile String body = "";

        String bodyText() {
            return body;
        }

        int status() {
            assertThat(status).as("the server answered").isNotNull();

            return status.code();
        }

        @Override public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status = status;
            this.body = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public ResponseWriter header(String name, String value) {return this;}
    }

    private static SecurityValidator validatorReturning(Result<SecurityContext> outcome) {
        var validator = mock(SecurityValidator.class);

        when(validator.validate(any(), any())).thenReturn(outcome.map(Authenticated::authenticated));
        when(validator.context(any(), any())).thenCallRealMethod();

        return validator;
    }

    private static HttpForwardRequest forwardRequest() {
        return new HttpForwardRequest(SENDER, "corr-962", "req-962", new byte[] {1}, Pipeline.MANAGEMENT, HEALTHY_BUDGET_MILLIS);
    }

    private ManagementServerImpl server(SecurityValidator validator, boolean securityEnabled) {
        return server(validator,
                      securityEnabled,
                      HttpRequestContext.httpRequestContext(PUSH_PATH, "PUT", Map.of(), Map.of(), new byte[] {1, 2, 3}, "req-962"),
                      ManagementRouter::managementRouter);
    }

    private ManagementServerImpl server(SecurityValidator validator, boolean securityEnabled, GrantedRouteProbe probe) {
        return server(validator,
                      securityEnabled,
                      HttpRequestContext.httpRequestContext(PUSH_PATH, "PUT", Map.of(), Map.of(), new byte[] {1, 2, 3}, "req-962"),
                      probe::routerWith);
    }

    private ManagementServerImpl server(SecurityValidator validator,
                                        boolean securityEnabled,
                                        HttpRequestContext forwarded,
                                        Fn1<ManagementRouter, RouteSource[]> routerAssembly) {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);
        var maven = mock(MavenProtocolHandler.class);
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
                                        NodeLifecycleRoutes.DrainPort.none(),
                                        NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> List.of(), OperatorWarningSink.logOnly()),
                                        routerAssembly);
    }

    private static final class RecordingClusterNetwork implements ClusterNetwork {
        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}
        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}
        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {return Promise.success(new WriteOutcome.Sent(nodeId));}

        @Override
        public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl) {
            return send(nodeId, message);
        }
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

        String header(String name) {
            assertThat(captured).as("the owner answered the forwarded request").isNotNull();

            return captured.headers().get(name);
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
