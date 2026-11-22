// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.api.routes.ManagementRouter.GrantedHandler;
import org.pragmatica.aether.http.security.AdminGrant;
import org.pragmatica.aether.http.security.Authenticated;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// A `GET` management route whose handler is a [GrantedHandler], for tests outside this package (the type is
/// package-private, so only code here can implement it). It records the [Authenticated] the router handed it and
/// whether that grant was live while the handler ran; the throwing variant then throws, as a broken handler does.
public final class GrantedRouteProbe implements RouteSource {
    public static final String PATH = "/api/v1/test/granted-probe";

    private final boolean throwing;
    private final AtomicReference<Authenticated> received = new AtomicReference<>();
    private final AtomicBoolean liveAtCall = new AtomicBoolean();

    private GrantedRouteProbe(boolean throwing) {
        this.throwing = throwing;
    }

    public static GrantedRouteProbe answering() {
        return new GrantedRouteProbe(false);
    }

    public static GrantedRouteProbe throwing() {
        return new GrantedRouteProbe(true);
    }

    /// The router a management server assembles from its own `sources`, with this probe's route added.
    public ManagementRouter routerWith(RouteSource[] sources) {
        return ManagementRouter.managementRouter(Stream.concat(Stream.of(sources), Stream.of(this))
                                                       .toArray(RouteSource[]::new));
    }

    @Override
    public Stream<Route<?>> routes() {
        GrantedHandler<String> handler = this::handle;

        return Stream.of(Route.<String>get(PATH).toJson(handler));
    }

    /// The [Authenticated] the router handed the route, none if the route never ran.
    public Option<Authenticated> received() {
        return Option.option(received.get());
    }

    /// True when the route ran with a grant that was live at that moment.
    public boolean liveAtCall() {
        return liveAtCall.get();
    }

    private Promise<String> handle(RequestContext request, Authenticated authenticated) {
        received.set(authenticated);
        liveAtCall.set(authenticated.grant()
                                    .map(AdminGrant::live)
                                    .or(false));

        if (throwing) {
            throw new IllegalStateException("The granted probe route throws");
        }

        return Promise.success("\"granted\"");
    }
}
