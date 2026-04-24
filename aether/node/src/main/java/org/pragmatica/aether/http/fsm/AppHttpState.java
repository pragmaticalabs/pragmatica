// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.aether.http.fsm.AppHttpEvents.CertRotationApplied;
import org.pragmatica.aether.http.fsm.AppHttpEvents.CertRotationRequested;
import org.pragmatica.aether.http.fsm.AppHttpEvents.H1Ready;
import org.pragmatica.aether.http.fsm.AppHttpEvents.H3Ready;
import org.pragmatica.aether.http.fsm.AppHttpEvents.RouteTablePublished;
import org.pragmatica.aether.http.fsm.AppHttpEvents.StartRequested;
import org.pragmatica.aether.http.fsm.AppHttpEvents.StopRequested;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

/// Sealed state hierarchy for the app HTTP server FSM. Flattens every dimension previously tracked
/// via separate atomics (`serverRef`, `h3ServerRef`, `routeTableRef`, `routeSyncReceived`) into a
/// single authoritative state.
///
/// - [`Stopped`] / [`Starting`] — per-context singletons (data-free, stable identity for CAS).
/// - [`H1Only`] / [`H3Only`] / [`Dual`] — fresh records, carry the bound [`HttpServer`](s) before
///   routes are published.
/// - [`RouteReady`] — fresh record, carries the optional server refs and the current
///   [`RouteTable`]. Every route-table update is a fresh `RouteReady → RouteReady` swap.
/// - [`CertRotating`] — fresh record, carries the *previous* server refs plus the new bundle and
///   the preserved route table so the adapter can orchestrate stop-old / start-new IO.
///
/// Events ignored in a state fall through to `tx.ignore()` — no silent early-returns. Shared
/// cluster-lifecycle events ([`ClusterFsmEvent.QuorumEstablished`], [`ClusterFsmEvent.Shutdown`])
/// and domain events ([`AppHttpEvents`]) are handled uniformly.
public sealed interface AppHttpState extends FsmState<AppHttpState, ClusterFsmEvent> {

    AppHttpContext ctx();

    // --- State records ---

    /// Idle lifecycle state. Only `StartRequested` advances the FSM; everything else is ignored.
    record Stopped(AppHttpContext ctx) implements AppHttpState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            if (event instanceof StartRequested) {
                tx.transitionTo(ctx.starting());
                return;
            }
            tx.ignore();
        }
    }

    /// Awaiting the first bound [`HttpServer`]. `H1Ready` moves to `H1Only`; `H3Ready` moves to
    /// `H3Only`. A `StopRequested` / `Shutdown` resets to `Stopped`.
    ///
    /// RouteTablePublished arriving while bind is in flight is intentionally ignored — routes
    /// arrive via the KV subscription path, which may fire before `H1Ready` completes; dropping
    /// them here is safe because the adapter rebuilds and re-dispatches on every KV callback (and
    /// on `QuorumEstablished` once the server is bound and routes are observable).
    record Starting(AppHttpContext ctx) implements AppHttpState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H1Ready(HttpServer server) -> tx.transitionTo(new H1Only(ctx, server));
                case H3Ready(HttpServer server) -> tx.transitionTo(new H3Only(ctx, server));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    /// HTTP/1.1 bound; HTTP/3 not (yet) expected. `H3Ready` promotes to `Dual`; a route publication
    /// promotes to `RouteReady` carrying this server. `Shutdown` stops everything.
    record H1Only(AppHttpContext ctx, HttpServer server) implements AppHttpState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H3Ready(HttpServer h3) -> tx.transitionTo(new Dual(ctx, server, h3));
                case RouteTablePublished(RouteTable routes) ->
                        tx.transitionTo(new RouteReady(ctx, Option.some(server), Option.none(), routes));
                case ClusterFsmEvent.QuorumEstablished _ ->
                        tx.transitionTo(new RouteReady(ctx, Option.some(server), Option.none(), RouteTable.empty()));
                case StopRequested _, ClusterFsmEvent.Shutdown _ ->
                        tx.transitionTo(ctx.stopped(), () -> ctx.stopServers(Option.some(server), Option.none()));
                default -> tx.ignore();
            }
        }
    }

    /// HTTP/3 bound; HTTP/1.1 not (yet) expected. Symmetric counterpart of [`H1Only`].
    record H3Only(AppHttpContext ctx, HttpServer h3) implements AppHttpState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H1Ready(HttpServer server) -> tx.transitionTo(new Dual(ctx, server, h3));
                case RouteTablePublished(RouteTable routes) ->
                        tx.transitionTo(new RouteReady(ctx, Option.none(), Option.some(h3), routes));
                case ClusterFsmEvent.QuorumEstablished _ ->
                        tx.transitionTo(new RouteReady(ctx, Option.none(), Option.some(h3), RouteTable.empty()));
                case StopRequested _, ClusterFsmEvent.Shutdown _ ->
                        tx.transitionTo(ctx.stopped(), () -> ctx.stopServers(Option.none(), Option.some(h3)));
                default -> tx.ignore();
            }
        }
    }

    /// Both protocols bound; no routes published yet.
    record Dual(AppHttpContext ctx, HttpServer server, HttpServer h3) implements AppHttpState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case RouteTablePublished(RouteTable routes) ->
                        tx.transitionTo(new RouteReady(ctx, Option.some(server), Option.some(h3), routes));
                case ClusterFsmEvent.QuorumEstablished _ ->
                        tx.transitionTo(new RouteReady(ctx, Option.some(server), Option.some(h3), RouteTable.empty()));
                case StopRequested _, ClusterFsmEvent.Shutdown _ ->
                        tx.transitionTo(ctx.stopped(),
                                        () -> ctx.stopServers(Option.some(server), Option.some(h3)));
                default -> tx.ignore();
            }
        }
    }

    /// Terminal steady state: servers bound (any combination of H1/H3), routes published. Every
    /// subsequent [`RouteTablePublished`] is a fresh `RouteReady → RouteReady` swap that replaces
    /// the carried table. [`CertRotationRequested`] transitions to [`CertRotating`] carrying the
    /// current server refs as "previous" so the adapter can stop them.
    record RouteReady(AppHttpContext ctx,
                      Option<HttpServer> server,
                      Option<HttpServer> h3,
                      RouteTable routes) implements AppHttpState {

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case RouteTablePublished(RouteTable updated) ->
                        tx.transitionTo(new RouteReady(ctx, server, h3, updated));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.ignore();
                case CertRotationRequested(var bundle) ->
                        tx.transitionTo(new CertRotating(ctx, server, h3, bundle, routes));
                case StopRequested _, ClusterFsmEvent.Shutdown _ ->
                        tx.transitionTo(ctx.stopped(), () -> ctx.stopServers(server, h3));
                default -> tx.ignore();
            }
        }
    }

    /// Transient state while the TLS cert is being rotated. Carries the previous server refs so
    /// the adapter can tear them down as part of the rotate pipeline. [`CertRotationApplied`]
    /// returns to [`RouteReady`] with the new refs and the preserved [`RouteTable`].
    record CertRotating(AppHttpContext ctx,
                        Option<HttpServer> prevServer,
                        Option<HttpServer> prevH3,
                        org.pragmatica.net.tcp.security.CertificateBundle newBundle,
                        RouteTable routes) implements AppHttpState {

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case CertRotationApplied(var newServer, var newH3, var updated) ->
                        tx.transitionTo(new RouteReady(ctx, newServer, newH3, updated));
                case RouteTablePublished(RouteTable updated) ->
                        tx.transitionTo(new CertRotating(ctx, prevServer, prevH3, newBundle, updated));
                case StopRequested _, ClusterFsmEvent.Shutdown _ ->
                        tx.transitionTo(ctx.stopped(), () -> ctx.stopServers(prevServer, prevH3));
                default -> tx.ignore();
            }
        }
    }
}
