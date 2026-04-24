// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.statemachine.Fsm;

import static org.pragmatica.lang.Unit.unit;

/// Shared context for the app HTTP server FSM. Holds:
/// - The bound [`Fsm`] reference (injected via constructor-driven initial-state factory).
/// - Per-FSM singletons for the data-free states (`stopped`, `starting`) so CAS compares against a
///   stable object per lifecycle cycle.
/// - Utility helpers that are shared across state records (e.g. stopping optional servers).
///
/// Callers MUST NOT invoke [`#dispatch`] from the initial-state factory — the FSM is transiently
/// uninitialized during factory execution.
public final class AppHttpContext {

    private final Fsm<AppHttpState, ClusterFsmEvent> fsm;
    private final AppHttpState stopped;
    private final AppHttpState starting;

    public AppHttpContext(Fsm<AppHttpState, ClusterFsmEvent> fsm) {
        this.fsm = fsm;
        this.stopped = new AppHttpState.Stopped(this);
        this.starting = new AppHttpState.Starting(this);
    }

    // --- FSM / state access ---

    public Fsm<AppHttpState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public AppHttpState stopped() {
        return stopped;
    }

    public AppHttpState starting() {
        return starting;
    }

    // --- Helpers ---

    /// Stop any optional H1/H3 [`HttpServer`]s without returning a promise — used as the transition
    /// action for `StopRequested`/`Shutdown`. Fire-and-forget because the FSM guarantees a single
    /// CAS winner invokes this, and the adapter's public `stop()` awaits completion separately
    /// via [`#stopServersAsync`].
    @SuppressWarnings("JBCT-RET-01")
    public void stopServers(Option<HttpServer> server, Option<HttpServer> h3) {
        server.onPresent(HttpServer::stop);
        h3.onPresent(HttpServer::stop);
    }

    /// Stop optional H1/H3 [`HttpServer`]s and return a [`Promise`] that completes when both are
    /// torn down. The adapter uses this from public `stop()` to provide back-pressure to callers.
    public Promise<Unit> stopServersAsync(Option<HttpServer> server, Option<HttpServer> h3) {
        var h1Stop = server.map(HttpServer::stop).or(Promise.success(unit()));
        var h3Stop = h3.map(HttpServer::stop).or(Promise.success(unit()));
        return h1Stop.flatMap(_ -> h3Stop);
    }

    /// Current route table carried by the FSM — `RouteReady.routes` / `CertRotating.routes`, or
    /// [`RouteTable#empty`] in any other state. Single source of truth for the request handler.
    public RouteTable currentRoutes() {
        return switch (fsm.current()) {
            case AppHttpState.RouteReady(_, _, _, RouteTable routes) -> routes;
            case AppHttpState.CertRotating(_, _, _, _, RouteTable routes) -> routes;
            default -> RouteTable.empty();
        };
    }

    /// Primary bound port, preferring the HTTP/1.1 server, falling back to HTTP/3. Returns
    /// [`Option#none`] when no server is bound (Stopped / Starting).
    public Option<Integer> boundPort() {
        return switch (fsm.current()) {
            case AppHttpState.H1Only(_, HttpServer server) -> Option.some(server.port());
            case AppHttpState.H3Only(_, HttpServer h3) -> Option.some(h3.port());
            case AppHttpState.Dual(_, HttpServer server, _) -> Option.some(server.port());
            case AppHttpState.RouteReady(_, Option<HttpServer> server, Option<HttpServer> h3, _) ->
                    server.map(HttpServer::port).orElse(() -> h3.map(HttpServer::port));
            case AppHttpState.CertRotating(_, Option<HttpServer> server, Option<HttpServer> h3, _, _) ->
                    server.map(HttpServer::port).orElse(() -> h3.map(HttpServer::port));
            case AppHttpState.Stopped _, AppHttpState.Starting _ -> Option.none();
        };
    }

    /// True iff the FSM is in a routed steady state (`RouteReady` or `CertRotating`).
    public boolean isRouteReady() {
        return fsm.current() instanceof AppHttpState.RouteReady
               || fsm.current() instanceof AppHttpState.CertRotating;
    }

    /// Snapshot of the current [`HttpServer`] pair — used by the rotate pipeline to stop the
    /// previous servers before binding the replacements.
    public record ServerPair(Option<HttpServer> server, Option<HttpServer> h3) {}

    public ServerPair currentServers() {
        return switch (fsm.current()) {
            case AppHttpState.H1Only(_, HttpServer server) -> new ServerPair(Option.some(server), Option.none());
            case AppHttpState.H3Only(_, HttpServer h3) -> new ServerPair(Option.none(), Option.some(h3));
            case AppHttpState.Dual(_, HttpServer server, HttpServer h3) ->
                    new ServerPair(Option.some(server), Option.some(h3));
            case AppHttpState.RouteReady(_, Option<HttpServer> server, Option<HttpServer> h3, _) ->
                    new ServerPair(server, h3);
            case AppHttpState.CertRotating(_, Option<HttpServer> server, Option<HttpServer> h3, _, _) ->
                    new ServerPair(server, h3);
            case AppHttpState.Stopped _, AppHttpState.Starting _ ->
                    new ServerPair(Option.none(), Option.none());
        };
    }
}
