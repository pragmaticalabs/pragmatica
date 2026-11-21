// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.ManagementRouter.GrantedHandler;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.security.AdminGrant;
import org.pragmatica.aether.http.security.Authenticated;
import org.pragmatica.aether.http.security.TestAdminGrants;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;


/// The router hands a [GrantedHandler] route the request's [Authenticated] as an argument, and only that route: the
/// grant never travels any other way. A call that hides the type from the router (the 1-argument `handle`) refuses
/// instead of running ungranted, and the type stays package-private so only a route declared here can receive a grant.
class ManagementRouterGrantedHandlerTest {
    @Test
    void handle_grantedRoute_receivesTheAuthenticatedItWasGiven() {
        var probe = GrantedRouteProbe.answering();
        var router = ManagementRouter.managementRouter(probe);
        var given = new AtomicReference<Authenticated>();
        var writer = new StatusWriter();

        TestAdminGrants.withAdminGrant(grant -> handleGranted(router, writer, given, grant));

        assertThat(writer.status()).isEqualTo(HttpStatus.OK);
        assertThat(probe.received().toOptional()).as("the route received the very Authenticated the caller passed")
                                    .containsSame(given.get());
        assertThat(probe.liveAtCall()).as("its grant was live while the route ran").isTrue();
    }

    /// A request that matches a declared [ManagementRoute] is dispatched by its registered name, a different branch from the
    /// plain path lookup: it too hands a granted route the `Authenticated` the caller passed. Red if that branch passes an
    /// anonymous one.
    @Test
    void handle_matchedManagementRoute_grantedRouteReceivesTheAuthenticatedItWasGiven() {
        var received = new AtomicReference<Authenticated>();
        GrantedHandler<String> handler = (_, authenticated) -> {
            received.set(authenticated);
            return Promise.success("\"live\"");
        };
        var router = ManagementRouter.managementRouter(ManagementRoutes.<String>route(ManagementRoute.HEALTH_LIVE).toJson(handler));
        var given = Authenticated.authenticated(SecurityContext.securityContext("matched-caller", java.util.Set.of(), AuthorizationRole.ADMIN).unwrap());
        var writer = new StatusWriter();

        assertThat(router.handle(new PathRequest("/health/live"), writer, given)).isTrue();

        assertThat(writer.status()).isEqualTo(HttpStatus.OK);
        assertThat(received.get()).as("the matched-route branch passed the caller's Authenticated").isSameAs(given);
    }

    @Test
    void handle_withoutAnAuthenticated_passesNoGrant() {
        var probe = GrantedRouteProbe.answering();
        var writer = new StatusWriter();

        TestAdminGrants.withAdminGrant(_ -> assertThat(ManagementRouter.managementRouter(probe)
                                                                       .handle(new ProbeRequest(), writer)).isTrue());

        assertThat(probe.received().map(Authenticated::grant).toOptional()).as("the route ran, with no grant")
                                                              .contains(Option.none());
    }

    @Test
    void handle_oneArgumentCall_refusesInsteadOfRunningTheRoute() {
        var ran = new AtomicBoolean();
        GrantedHandler<String> granted = (_, _) -> markRan(ran);

        var outcome = granted.handle(mock(RequestContext.class)).await();

        assertThat(outcome.isFailure()).as("the plain call refuses").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isSameAs(GrantedHandler.UNGRANTED_CALL));
        assertThat(ran.get()).as("the granted body never ran").isFalse();
    }

    @Test
    void grantedHandler_isNotPublic() {
        assertThat(Modifier.isPublic(GrantedHandler.class.getModifiers())).as("only a route in this package can receive a grant")
                                                                          .isFalse();
    }

    private static void handleGranted(ManagementRouter router,
                                      StatusWriter writer,
                                      AtomicReference<Authenticated> given,
                                      AdminGrant grant) {
        given.set(Authenticated.authenticated(SecurityContext.securityContext(), Option.some(grant)));

        assertThat(router.handle(new ProbeRequest(), writer, given.get())).isTrue();
    }

    private static Promise<String> markRan(AtomicBoolean ran) {
        ran.set(true);

        return Promise.success("ran");
    }

    private record PathRequest(String path) implements HttpRequest {
        @Override public String requestId() {return "req-matched";}
        @Override public HttpMethod method() {return HttpMethod.GET;}
        @Override public Headers headers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    private record ProbeRequest() implements HttpRequest {
        @Override public String requestId() {return "req-granted-probe";}
        @Override public HttpMethod method() {return HttpMethod.GET;}
        @Override public String path() {return GrantedRouteProbe.PATH;}
        @Override public Headers headers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    private static final class StatusWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();

        HttpStatus status() {
            assertThat(status.get()).as("the route answered").isNotNull();

            return status.get();
        }

        @Override
        public void write(HttpStatus status, byte[] content, ContentType contentType) {
            this.status.set(status);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
