// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.GrantedBodyRoute;
import org.pragmatica.aether.api.routes.GrantedRouteProbe;
import org.pragmatica.aether.api.routes.ManagementRouter;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AppHttpConfig;
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
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;
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
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
/// #1121: `security_mode = "none"` is FULLY OPEN on the management plane, and every grant a route receives still comes
/// from `validate`. With security off the server calls the OFF validator ([SecurityValidator#managementValidator]) inside
/// the request's scope, as it does with security on: the route gets a live grant for the `security-off` principal, the
/// context stays empty, nothing is enforced and nothing is bound to the holder. A refusal from that validator fails
/// closed, locally and forwarded. The OFF arms below pin it; the ON arms beside them are the controls.
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

    /// OFF, forwarded: a route above VIEWER (the push is OPERATOR) is served to a caller with no credential. Red if the
    /// enforcement runs when security is off (the OFF caller's context is empty, and an empty context holds no role).
    @Test
    void securityDisabled_forwardedPush_isServedAboveViewerByTheOffValidator() {
        server(offValidator(), false).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).as("security_mode = none serves a non-public route to an anonymous caller")
                                       .isEqualTo(201);
    }

    @Test
    void accessDeniedFromTheValidator_refusalNamesThePlaneAndTheAuthorizationRoleAxis() {
        var validator = validatorReturning(new SecurityError.AccessDenied("key may not use this plane").result());

        server(validator, true).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(403);
        assertThat(serializer.bodyText()).contains("Management API").contains("authorization_role").contains("key may not use this plane");
    }

    /// OFF, local, a validator that refuses: the request fails closed through the one refusal path and the route never
    /// runs. Red if the failure still dispatches.
    @Test
    void securityDisabled_localPath_aRefusingValidator_failsClosedAndTheRouteNeverRuns() {
        var validator = validatorReturning(SecurityError.MISSING_API_KEY.result());
        var probe = GrantedRouteProbe.answering();
        var writer = new RecordingResponseWriter();

        server(validator, false, probe).handleRequest(GrantRequest.adminGet(GrantedRouteProbe.PATH), writer);

        verify(validator).validate(any(), any());
        assertThat(writer.status()).as("the sender gets the refusal").isEqualTo(401);
        assertThat(probe.received().toOptional()).as("the route never ran").isEmpty();
    }

    /// OFF, forwarded, a validator that refuses: the receiving node's refusal is relayed to the forwarding node as the
    /// forward's response. Red if the failure dispatches, or if the capture the refusal was written to is not relayed.
    @Test
    void securityDisabled_forwardedPath_aRefusingValidator_failsClosedAndIsRelayed() {
        var validator = validatorReturning(SecurityError.MISSING_API_KEY.result());
        var probe = GrantedRouteProbe.answering();
        var forwarded = HttpRequestContext.httpRequestContext(GrantedRouteProbe.PATH,
                                                              "GET",
                                                              Map.of(),
                                                              Map.of(),
                                                              new byte[0],
                                                              "req-off-refused");

        server(validator, false, forwarded, probe::routerWith).onHttpForwardRequest(forwardRequest());

        verify(validator).validate(any(), any());
        assertThat(serializer.status()).as("the forwarding node receives the refusal").isEqualTo(401);
        assertThat(probe.received().toOptional()).as("the route never ran").isEmpty();
    }

    /// Control for the OFF refusals above: the same local request with security ENABLED consults the validator and is
    /// refused, so the OFF cases are the gate and not a request that can never be refused.
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
        var server = server(offValidator(), false);

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

    /// OFF, local: the granted route receives the very [Authenticated] the OFF validator returned, with a live grant for the
    /// `security-off` principal, even though an ADMIN key is sent (no credential is matched). Red if the validation runs
    /// outside the request's scope (the grant is born dead) or if dispatch passes an ungranted [Authenticated].
    @Test
    void grantedRoute_securityDisabled_localPath_receivesALiveGrantFromTheOffValidator() {
        var validator = RecordingValidator.recording(offValidator());
        var probe = GrantedRouteProbe.answering();
        var writer = new RecordingResponseWriter();

        server(validator, false, probe).handleRequest(GrantRequest.adminGet(GrantedRouteProbe.PATH), writer);

        assertThat(writer.status()).isEqualTo(200);
        assertThat(probe.received().toOptional()).as("the route received the Authenticated the server validated")
                                                 .containsSame(validator.returned().unwrap());
        assertThat(probe.liveAtCall()).as("the grant was live while the route ran").isTrue();
        assertThat(validator.liveAtValidation()).as("the scope was open at the validation").isTrue();
        assertThat(grantedBy(probe.received().unwrap())).isEqualTo("service:security-off");
    }

    /// OFF, forwarded: the same, on the receiving node's own scope.
    @Test
    void grantedRoute_securityDisabled_forwardedPath_receivesALiveGrantFromTheOffValidator() {
        var validator = RecordingValidator.recording(offValidator());
        var probe = GrantedRouteProbe.answering();
        var forwarded = HttpRequestContext.httpRequestContext(GrantedRouteProbe.PATH,
                                                              "GET",
                                                              Map.of(),
                                                              Map.of(),
                                                              new byte[0],
                                                              "req-off-forwarded");

        server(validator, false, forwarded, probe::routerWith).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(200);
        assertThat(probe.received().toOptional()).as("the route received the Authenticated the owner validated")
                                                 .containsSame(validator.returned().unwrap());
        assertThat(probe.liveAtCall()).as("the grant was live while the route ran").isTrue();
        assertThat(grantedBy(probe.received().unwrap())).isEqualTo("service:security-off");
    }

    /// OFF, two sequential requests: each route runs under its own grant. The second grant is live in its route and is not
    /// the first, which is dead by then. Red if the grant is memoised across requests.
    @Test
    void securityDisabled_sequentialRequests_eachRouteHoldsItsOwnLiveGrant() {
        var grants = new ArrayList<AdminGrant>();
        var liveInRoute = new ArrayList<Boolean>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            grants.add(auth.grant().unwrap());
            liveInRoute.add(liveGrant(auth));
            return Promise.success("\"x\"");
        });
        var server = offServer(route);

        server.handleRequest(new KeyedRequest(PATH_A, Option.none()), new RecordingResponseWriter());
        server.handleRequest(new KeyedRequest(PATH_A, Option.none()), new RecordingResponseWriter());

        assertThat(liveInRoute).as("each grant was live in its own route").containsExactly(true, true);
        assertThat(grants.get(1)).as("the second grant is not the first").isNotSameAs(grants.get(0));
        assertThat(grants.get(0).live()).as("the first is dead once its request returned").isFalse();
        assertThat(grants.get(1).live()).as("and so is the second").isFalse();
    }

    /// OFF: the route reads an EMPTY context, and nothing is bound to the holder. Red if the OFF context (the ADMIN
    /// service principal) is handed to the route as the caller, or if the holder is bound as with security on.
    @Test
    void securityDisabled_routeSeesAnEmptyContext_andNothingIsBoundToTheHolder() {
        var context = new AtomicReference<SecurityContext>();
        var held = new AtomicReference<Option<SecurityContext>>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            context.set(auth.context());
            held.set(SecurityContextHolder.currentContext());
            return Promise.success("\"x\"");
        });

        offServer(route).handleRequest(new KeyedRequest(PATH_A, Option.none()), new RecordingResponseWriter());

        assertThat(context.get()).as("the Authenticated's context is the empty one").isEqualTo(SecurityContext.securityContext());
        assertThat(context.get().isAuthenticated()).isFalse();
        assertThat(held.get().isEmpty()).as("no context is bound to the holder").isTrue();
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

    private static final String KEY_A = "grant-admin-a";
    private static final String KEY_B = "grant-admin-b";
    private static final String KEY_VIEWER = "grant-viewer-key";
    private static final Map<String, ApiKeyEntry> ISOLATION_KEYS = Map.of(KEY_A,
                                                                          ApiKeyEntry.apiKeyEntry("admin-a", Set.of("service"), "ADMIN"),
                                                                          KEY_B,
                                                                          ApiKeyEntry.apiKeyEntry("admin-b", Set.of("service"), "ADMIN"),
                                                                          KEY_VIEWER,
                                                                          ApiKeyEntry.apiKeyEntry("viewer-c", Set.of("service"), "VIEWER"));
    private static final String PATH_A = "/api/v1/test/grant-a";
    private static final String PATH_B = "/api/v1/test/grant-b";

    /// Request B dispatched inside request A's route on the same thread: A's grant is dead inside B and B's is live; after B
    /// returns A's is live again and B's is dead. Red if a grant is live under any open scope instead of its own (the identity
    /// check dropped from [AdminGrant#live]).
    @Test
    void grants_nestedRequestOnOneThread_neverCross() {
        var serverRef = new AtomicReference<ManagementServerImpl>();
        var aAuth = new AtomicReference<Authenticated>();
        var bAuth = new AtomicReference<Authenticated>();
        var aLiveInB = new AtomicReference<Boolean>();
        var bLiveInB = new AtomicReference<Boolean>();
        var aLiveAfterB = new AtomicReference<Boolean>();
        var bLiveAfterB = new AtomicReference<Boolean>();
        var writerB = new RecordingResponseWriter();
        var routeB = GrantedBodyRoute.grantedBodyRoute(PATH_B, (_, auth) -> {
            bAuth.set(auth);
            aLiveInB.set(liveGrant(aAuth.get()));
            bLiveInB.set(liveGrant(auth));
            return Promise.success("\"b\"");
        });
        var routeA = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            aAuth.set(auth);
            serverRef.get().handleRequest(new KeyedRequest(PATH_B, Option.some(KEY_B)), writerB);
            aLiveAfterB.set(liveGrant(auth));
            bLiveAfterB.set(liveGrant(bAuth.get()));
            return Promise.success("\"a\"");
        });
        var writerA = new RecordingResponseWriter();

        serverRef.set(isolationServer(routeA, routeB));
        serverRef.get().handleRequest(new KeyedRequest(PATH_A, Option.some(KEY_A)), writerA);

        assertThat(writerA.status()).isEqualTo(200);
        assertThat(writerB.status()).isEqualTo(200);
        assertThat(aAuth.get().context().principal()).as("two requests, two principals").isNotEqualTo(bAuth.get().context().principal());
        assertThat(bLiveInB.get()).as("B's grant is live in B").isTrue();
        assertThat(aLiveInB.get()).as("A's grant is dead inside the nested B").isFalse();
        assertThat(aLiveAfterB.get()).as("A's grant is live again after B").isTrue();
        assertThat(bLiveAfterB.get()).as("B's grant is dead after B").isFalse();
    }

    /// Two requests on two threads with both scopes open at once (latch-driven): each thread sees its own grant live and the
    /// other's dead. Red under the same identity-check mutant as the nested test.
    @Test
    void grants_concurrentRequestsOnTwoThreads_neverCross() throws Exception {
        var aAuth = new AtomicReference<Authenticated>();
        var bAuth = new AtomicReference<Authenticated>();
        var published = new CountDownLatch(2);
        var checked = new CountDownLatch(2);
        var aSeesB = new AtomicReference<Boolean>();
        var bSeesA = new AtomicReference<Boolean>();
        var aOwn = new AtomicReference<Boolean>();
        var bOwn = new AtomicReference<Boolean>();
        var routeA = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            aAuth.set(auth);
            published.countDown();
            await(published);
            aSeesB.set(liveGrant(bAuth.get()));
            aOwn.set(liveGrant(auth));
            checked.countDown();
            await(checked);
            return Promise.success("\"a\"");
        });
        var routeB = GrantedBodyRoute.grantedBodyRoute(PATH_B, (_, auth) -> {
            bAuth.set(auth);
            published.countDown();
            await(published);
            bSeesA.set(liveGrant(aAuth.get()));
            bOwn.set(liveGrant(auth));
            checked.countDown();
            await(checked);
            return Promise.success("\"b\"");
        });
        var server = isolationServer(routeA, routeB);
        var threadA = new Thread(() -> server.handleRequest(new KeyedRequest(PATH_A, Option.some(KEY_A)), new RecordingResponseWriter()));
        var threadB = new Thread(() -> server.handleRequest(new KeyedRequest(PATH_B, Option.some(KEY_B)), new RecordingResponseWriter()));

        threadA.start();
        threadB.start();
        threadA.join(15_000);
        threadB.join(15_000);

        assertThat(aOwn.get()).as("A's grant is live on A's thread").isTrue();
        assertThat(bOwn.get()).as("B's grant is live on B's thread").isTrue();
        assertThat(aSeesB.get()).as("A's thread sees B's grant dead").isFalse();
        assertThat(bSeesA.get()).as("B's thread sees A's grant dead").isFalse();
    }

    /// Minting is role-gated and authentication-gated: an unauthenticated request answers 401 and the route never runs; a
    /// VIEWER key runs the route with no grant. Red if a grant is minted for any role.
    @Test
    void mint_unauthenticatedIsRefused_viewerRunsUngranted() {
        var received = new AtomicReference<Authenticated>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            received.set(auth);
            return Promise.success("\"x\"");
        });
        var server = isolationServer(route);
        var anonymous = new RecordingResponseWriter();
        var viewer = new RecordingResponseWriter();

        server.handleRequest(new KeyedRequest(PATH_A, Option.none()), anonymous);

        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(received.get()).as("the route never ran unauthenticated").isNull();

        server.handleRequest(new KeyedRequest(PATH_A, Option.some(KEY_VIEWER)), viewer);

        assertThat(viewer.status()).isEqualTo(200);
        assertThat(received.get().grant().isEmpty()).as("a VIEWER mints no grant").isTrue();
    }

    /// The forwarded path hands the route the forwarded request's own credential, validated again on this node: a VIEWER key
    /// gives the VIEWER principal and no grant; no key is refused before the route runs. Red if the forward dispatch drops the
    /// validated [Authenticated], or mints for any role.
    @Test
    void forwardedPath_viewerKey_routeSeesTheViewerPrincipalUngranted() {
        var received = new AtomicReference<Authenticated>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            received.set(auth);
            return Promise.success("\"x\"");
        });
        var forwarded = HttpRequestContext.httpRequestContext(PATH_A, "GET", Map.of(), Map.of("X-API-Key", List.of(KEY_VIEWER)), new byte[0], "req-fwd-viewer");

        forwardingServer(forwarded, route).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(200);
        assertThat(received.get().grant().isEmpty()).as("a forwarded VIEWER mints no grant").isTrue();
        assertThat(received.get().context().principal().toString()).as("the principal is the forwarded key's").contains("viewer-c");
    }

    @Test
    void forwardedPath_noKey_isRefusedBeforeTheRoute() {
        var received = new AtomicReference<Authenticated>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            received.set(auth);
            return Promise.success("\"x\"");
        });
        var forwarded = HttpRequestContext.httpRequestContext(PATH_A, "GET", Map.of(), Map.of(), new byte[0], "req-fwd-none");

        forwardingServer(forwarded, route).onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(401);
        assertThat(received.get()).as("the route never ran").isNull();
    }

    /// The local secured path binds the validated caller's context for the dispatch: a route reads the authenticated
    /// principal from [SecurityContextHolder], not the anonymous one (the forwarded path pins the same binding).
    @Test
    void localPath_securedDispatch_bindsTheValidatedContext() {
        var inRoute = new AtomicReference<SecurityContext>();
        var received = new AtomicReference<Authenticated>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            received.set(auth);
            inRoute.set(SecurityContextHolder.currentContext().or(SecurityContext.securityContext()));
            return Promise.success("\"x\"");
        });

        isolationServer(route).handleRequest(new KeyedRequest(PATH_A, Option.some(KEY_A)), new RecordingResponseWriter());

        assertThat(inRoute.get().isAuthenticated()).as("the bound context is the caller's, not anonymous").isTrue();
        assertThat(inRoute.get().principal()).isEqualTo(received.get().context().principal());
    }

    /// OFF on the forwarded path: a granted route gets a live grant, the empty context, and no holder binding, as locally.
    @Test
    void forwardedPath_securityDisabled_routeReceivesALiveGrantAndAnEmptyContext() {
        var received = new AtomicReference<Authenticated>();
        var live = new AtomicBoolean();
        var held = new AtomicReference<Option<SecurityContext>>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            received.set(auth);
            live.set(liveGrant(auth));
            held.set(SecurityContextHolder.currentContext());
            return Promise.success("\"x\"");
        });
        var forwarded = HttpRequestContext.httpRequestContext(PATH_A, "GET", Map.of(), Map.of(), new byte[0], "req-fwd-open");

        server(offValidator(), false, forwarded, sources -> GrantedBodyRoute.routerWith(sources, route))
                .onHttpForwardRequest(forwardRequest());

        assertThat(serializer.status()).isEqualTo(200);
        assertThat(received.get()).as("the route ran with an Authenticated").isNotNull();
        assertThat(live.get()).as("its grant was live in the route").isTrue();
        assertThat(received.get().context()).as("the context is empty").isEqualTo(SecurityContext.securityContext());
        assertThat(held.get().isEmpty()).as("no context is bound to the holder").isTrue();
    }


    /// ms18 (#3550): an unsupported forwarded method is refused with an error reply to the forwarding node, without
    /// throwing, before validation and dispatch.
    @Test
    void forwardedPath_unsupportedMethod_returnsBeforeValidationAndDispatch() {
        var validator = RecordingValidator.recording(SecurityValidator.apiKeyValidator(ISOLATION_KEYS));

        assertUnsupportedMethodRefused(validator, true, Map.of("X-API-Key", List.of(KEY_A)));
    }

    /// ms18 (#3550), security OFF: the guard sits above the mode branch, so the same refusal and the same reply apply.
    @Test
    void forwardedPath_unsupportedMethod_securityOff_returnsBeforeValidationAndDispatch() {
        assertUnsupportedMethodRefused(RecordingValidator.recording(offValidator()), false, Map.of());
    }

    private void assertUnsupportedMethodRefused(RecordingValidator validator, boolean securityEnabled, Map<String, List<String>> headers) {
        var received = new AtomicReference<Authenticated>();
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, auth) -> {
            received.set(auth);
            return Promise.success("\"x\"");
        });
        var forwarded = HttpRequestContext.httpRequestContext(PATH_A, "BOGUS", Map.of(), headers, new byte[0], "req-fwd-bogus");
        var node = server(validator, securityEnabled, forwarded, sources -> GrantedBodyRoute.routerWith(sources, route));

        assertThatCode(() -> node.onHttpForwardRequest(forwardRequest())).doesNotThrowAnyException();
        assertThat(network.sentTargets()).as("the single reply goes to the request's sender").containsExactly(SENDER);
        assertThat(network.sent()).as("exactly one reply, to the forwarding node").singleElement().isInstanceOfSatisfying(HttpForwardResponse.class, reply -> {
            assertThat(reply.success()).as("a refusal, not a success").isFalse();
            assertThat(reply.correlationId()).isEqualTo("corr-962");
            assertThat(new String(reply.payload(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("Unsupported HTTP method: BOGUS");
        });
        assertThat(validator.returned().toOptional()).as("the validator was never consulted").isEmpty();
        assertThat(received.get()).as("the route never ran").isNull();
    }

    /// The management access-log line carries the validated principal, not "anonymous".
    @Test
    void localPath_securedDispatch_accessLogCarriesTheValidatedPrincipal() {
        var route = GrantedBodyRoute.grantedBodyRoute(PATH_A, (_, _) -> Promise.success("\"x\""));

        try (var audit = LogCapture.capture("org.pragmatica.aether.audit", Level.ALL, false)) {
            isolationServer(route).handleRequest(new KeyedRequest(PATH_A, Option.some(KEY_A)), new RecordingResponseWriter());

            assertThat(audit.messages()).filteredOn(m -> m.contains("MGMT_ACCESS"))
                                        .singleElement()
                                        .asString()
                                        .contains("admin-a")
                                        .doesNotContain("anonymous");
        }
    }

    private static boolean liveGrant(Authenticated authenticated) {
        return authenticated.grant().map(AdminGrant::live).or(false);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private ManagementServerImpl isolationServer(RouteSource... extra) {
        return server(SecurityValidator.apiKeyValidator(ISOLATION_KEYS),
                      true,
                      HttpRequestContext.httpRequestContext(PUSH_PATH, "PUT", Map.of(), Map.of(), new byte[] {1, 2, 3}, "req-962"),
                      sources -> GrantedBodyRoute.routerWith(sources, extra));
    }

    private ManagementServerImpl forwardingServer(HttpRequestContext forwarded, RouteSource... extra) {
        return server(SecurityValidator.apiKeyValidator(ISOLATION_KEYS), true, forwarded, sources -> GrantedBodyRoute.routerWith(sources, extra));
    }

    /// A request carrying an optional `X-API-Key`.
    private record KeyedRequest(String path, Option<String> key) implements HttpRequest {
        @Override public String requestId() {return "req-keyed";}
        @Override public HttpMethod method() {return HttpMethod.GET;}
        @Override public Headers headers() {return Headers.headers(key.map(k -> Map.of("X-API-Key", List.of(k))).or(Map.of()));}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
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

    /// The validator the assembly builds for a `security_mode = "none"` management plane.
    private static SecurityValidator offValidator() {
        return SecurityValidator.managementValidator(AppHttpConfig.insecureAppHttpConfig(0),
                                                     () -> mock(KVStore.class))
                                .validator();
    }

    private ManagementServerImpl offServer(RouteSource... extra) {
        return server(offValidator(),
                      false,
                      HttpRequestContext.httpRequestContext(PUSH_PATH, "PUT", Map.of(), Map.of(), new byte[] {1, 2, 3}, "req-962"),
                      sources -> GrantedBodyRoute.routerWith(sources, extra));
    }

    private static String grantedBy(Authenticated authenticated) {
        return authenticated.grant().unwrap().grantedBy().value();
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
        private final List<ProtocolMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        private final List<NodeId> targets = new java.util.concurrent.CopyOnWriteArrayList<>();

        List<ProtocolMessage> sent() {return List.copyOf(sent);}

        List<NodeId> sentTargets() {return List.copyOf(targets);}

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return unit();}
        @Override public void connect(NetworkServiceMessage.ConnectNode connectNode) {}
        @Override public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}
        @Override public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(NetworkServiceMessage.Send send) {}
        @Override public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}
        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {
            sent.add(message);
            targets.add(nodeId);

            return Promise.success(new WriteOutcome.Sent(nodeId));
        }

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
