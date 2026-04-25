// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.statemachine.Fsm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
    private static final Logger log = LoggerFactory.getLogger(AppHttpContext.class);

    /// Default route-table supplier used when callers construct a context without wiring authority
    /// data. Returns an empty table; the FSM will still fire `RouteTablePublished` carrying the
    /// empty snapshot. Tests that construct contexts directly use this to avoid depending on the
    /// real registry.
    private static final Supplier<RouteTable> EMPTY_ROUTE_TABLE = RouteTable::empty;

    /// Default quorum supplier — reports "not established" so `publishQuorumStateIfEstablished`
    /// becomes a no-op. The adapter overrides this with the live observation.
    private static final BooleanSupplier QUORUM_NOT_ESTABLISHED = () -> false;

    private final Fsm<AppHttpState, ClusterFsmEvent> fsm;
    private final AppHttpState stopped;
    private final AppHttpState starting;
    private final Supplier<RouteTable> routeTableSupplier;
    private final BooleanSupplier quorumEstablishedSupplier;

    public AppHttpContext(Fsm<AppHttpState, ClusterFsmEvent> fsm) {
        this(fsm, EMPTY_ROUTE_TABLE, QUORUM_NOT_ESTABLISHED);
    }

    public AppHttpContext(Fsm<AppHttpState, ClusterFsmEvent> fsm,
                          Supplier<RouteTable> routeTableSupplier,
                          BooleanSupplier quorumEstablishedSupplier) {
        this.fsm = fsm;
        this.stopped = new AppHttpState.Stopped(this);
        this.starting = new AppHttpState.Starting(this);
        this.routeTableSupplier = routeTableSupplier;
        this.quorumEstablishedSupplier = quorumEstablishedSupplier;
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

    // --- Route / quorum republish helpers ---

    /// Recompute the authoritative [`RouteTable`] from the wired supplier and dispatch a fresh
    /// [`AppHttpEvents.RouteTablePublished`]. Replaces the adapter-side `publishRouteTable()` so
    /// the FSM owns both the state and the authoritative re-read.
    @Contract
    public void publishRouteTable() {
        var table = routeTableSupplier.get();
        log.debug("Router rebuilt: {} local routes, {} remote routes",
                  table.localRoutes().size(),
                  table.remoteRoutes().size());
        fsm.dispatch(new AppHttpEvents.RouteTablePublished(table));
    }

    /// Dispatch a fresh [`ClusterFsmEvent.QuorumEstablished`] iff the wired supplier reports that
    /// quorum is currently held. Used after bind completion to recover from the case where quorum
    /// was already established before the server reached a routable state — the original
    /// notification would otherwise have been ignored by [`AppHttpState.Starting`].
    @Contract
    public void publishQuorumStateIfEstablished() {
        if (quorumEstablishedSupplier.getAsBoolean()) {
            fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
        }
    }

    /// Re-read authoritative route + quorum state and dispatch the corresponding events. Invoked
    /// as the transition action when [`AppHttpState.Starting`] moves to `H1Only` / `H3Only`, so a
    /// node booting after route state and quorum settled still reaches `RouteReady`.
    ///
    /// Order matters: routes first (so the new state carries a populated table), quorum second
    /// (no-op in `RouteReady`, but kept for completeness when only `H1Only`/`H3Only` was reached
    /// without the route publish).
    @Contract
    public void republishStateAfterBind() {
        publishRouteTable();
        publishQuorumStateIfEstablished();
    }

    // --- Helpers ---

    /// Stop any optional H1/H3 [`HttpServer`]s without returning a promise — used as the transition
    /// action for `StopRequested`/`Shutdown`. Fire-and-forget because the FSM guarantees a single
    /// CAS winner invokes this, and the adapter's public `stop()` awaits completion separately
    /// via [`#stopServersAsync`].
    @Contract
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
    /// Returns a point-in-time snapshot: the FSM may transition concurrently and the returned
    /// value will not reflect subsequent changes.
    public RouteTable currentRoutes() {
        return switch (fsm.current()) {
            case AppHttpState.RouteReady(_, _, _, RouteTable routes) -> routes;
            case AppHttpState.CertRotating(_, _, _, _, RouteTable routes) -> routes;
            default -> RouteTable.empty();
        };
    }

    /// Primary bound port, preferring the HTTP/1.1 server, falling back to HTTP/3. Returns
    /// [`Option#none`] when no server is bound (Stopped / Starting). Point-in-time snapshot:
    /// subsequent FSM transitions can invalidate the observation.
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

    /// Returns a point-in-time snapshot of the H1/H3 [`HttpServer`] pair. The FSM may transition
    /// concurrently; callers must treat the returned value as immutable and not re-query for
    /// consistency within a single rotation/stop operation.
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
