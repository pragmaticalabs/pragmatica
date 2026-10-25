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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Lifecycle of the app HTTP listeners. Every state handles every event: an event is either acted on, or
/// ignored for a reason that holds for that state. The reasons, per state (`AppHttpStateTotalityTest` pins
/// the whole state x event matrix and fails when a state or an event is added without a row):
///
/// - Everywhere: `LeaderChange`, `NodeAdded` and `NodeGone` are ignored. They say nothing about the listeners,
///   and the app HTTP adapter dispatches only its own events plus `QuorumEstablished`/`QuorumDisappeared`.
/// - `CertRotationApplied` carries listeners the rotation has already bound (it stopped the previous pair
///   first), so no state may drop it: that would leave the new pair bound and unreferenced (#2418).
///   `Stopped` and `Starting` close it, `H1Only`/`H3Only`/`Dual` adopt it, `RouteReady` and `Quiesced`
///   replace their pair with it, `CertRotating` completes the rotation.
/// - `H1Ready`/`H3Ready` reaching a state that already holds (or is rotating) that listener close the surplus one;
///   `RouteReady` and `Quiesced` take it as the replacement for the listener of that protocol.
/// - `Stopped`: `StopRequested`/`Shutdown` (already stopped), `RouteTablePublished`, `CertRotationRequested`,
///   `QuorumEstablished`, `QuorumDisappeared` (no listener to route, rotate or quiesce; a later start
///   re-reads route table and quorum after its bind).
/// - `Starting`: `StartRequested` (already starting), `RouteTablePublished`, `CertRotationRequested`,
///   `QuorumEstablished`, `QuorumDisappeared` (nothing is bound yet; `republishStateAfterBind` re-reads route
///   table and quorum from the authoritative suppliers once a listener is).
/// - `H1Only`, `H3Only`, `Dual`: `StartRequested` (already started); `CertRotationRequested` (no route table to
///   carry through a rotation, the previous pair stays until `CertRotationApplied` adopts the new one);
///   `QuorumDisappeared` (no routes are served before the first route table, which is what `Quiesced` means).
/// - `RouteReady`: `StartRequested` (already running), `QuorumEstablished` (already established).
/// - `CertRotating`: `StartRequested`, `CertRotationRequested` (a rotation is already in flight; overlapping
///   rotations are not serialized here), `QuorumEstablished` (the rotation completes first, and the route
///   table is carried by this state).
/// - `Quiesced`: `StartRequested`, `CertRotationRequested` (no routes to carry; the previous pair stays until
///   `CertRotationApplied` replaces it), `RouteTablePublished` and `QuorumDisappeared` (already quiesced).
public sealed interface AppHttpState extends FsmState<AppHttpState, ClusterFsmEvent> {
    Logger LOG = LoggerFactory.getLogger(AppHttpState.class);
    AppHttpContext ctx();

    record Stopped(AppHttpContext ctx) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case StartRequested _ -> tx.transitionTo(ctx.starting());
                case H1Ready(HttpServer server) -> tx.handle(() -> stopOrphanedServer(ctx,
                                                                                      Option.some(server),
                                                                                      Option.none()));
                case H3Ready(HttpServer h3) -> tx.handle(() -> stopOrphanedServer(ctx, Option.none(), Option.some(h3)));
                case CertRotationApplied(var newServer, var newH3, _) -> tx.handle(() -> stopOrphanedServer(ctx,
                                                                                                           newServer,
                                                                                                           newH3));
                default -> tx.ignore();
            }
        }
    }

    /// An H1Ready/H3Ready reaching Stopped means stop() won the race against an in-flight bind: the
    /// listener exists and is bound, but no state owns it, so nothing will ever close it and its port
    /// stays held for the life of the process (#1456). Close it here — the same rule
    /// `SwimHealthState.Stopped` applies to an orphaned SWIM protocol.
    ///
    /// The alternative — making stop() wait for start() to finish — is ruled out by #1308: a start
    /// can stay pending indefinitely when quorum cannot form, which is exactly what the abort path
    /// exists to escape. Closing from the losing publisher's side needs no wait.
    private static void stopOrphanedServer(AppHttpContext ctx, Option<HttpServer> server, Option<HttpServer> h3) {
        LOG.info("App HTTP listener bound after stop() had already run — closing the orphan (#1456)");
        ctx.stopServers(server, h3);
    }

    /// A listener reported to a state that already holds, or is about to hold, its own. The reporter
    /// bound a port that nothing in the FSM references, so the only way to release it is to close it
    /// here (#2418) — the same reasoning as [#stopOrphanedServer], for a state that is still running.
    private static void stopSurplusServer(AppHttpContext ctx, Option<HttpServer> server, Option<HttpServer> h3) {
        LOG.warn("App HTTP listener reported to a state that does not take it — closing the surplus listener (#2418)");
        ctx.stopServers(server, h3);
    }

    /// The state that owns the listeners a certificate rotation bound while the FSM was before its
    /// route table: the rotation already stopped the previous pair, so the new pair replaces it.
    private static AppHttpState adopted(AppHttpContext ctx, Option<HttpServer> server, Option<HttpServer> h3) {
        return server.<AppHttpState>fold(() -> h3.<AppHttpState>fold(ctx::starting, bound -> new H3Only(ctx, bound)),
                                         bound -> h3.<AppHttpState>fold(() -> new H1Only(ctx, bound),
                                                                        second -> new Dual(ctx, bound, second)));
    }

    record Starting(AppHttpContext ctx) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H1Ready(HttpServer server) -> tx.transitionTo(new H1Only(ctx, server), ctx::republishStateAfterBind);
                case H3Ready(HttpServer server) -> tx.transitionTo(new H3Only(ctx, server), ctx::republishStateAfterBind);
                case CertRotationApplied(var newServer, var newH3, _) -> tx.handle(() -> stopSurplusServer(ctx,
                                                                                                          newServer,
                                                                                                          newH3));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    record H1Only(AppHttpContext ctx, HttpServer server) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H3Ready(HttpServer h3) -> tx.transitionTo(new Dual(ctx, server, h3));
                case H1Ready(HttpServer extra) -> tx.handle(() -> stopSurplusServer(ctx, Option.some(extra), Option.none()));
                case CertRotationApplied(var newServer, var newH3, _) -> tx.transitionTo(adopted(ctx, newServer, newH3),
                                                                                         ctx::republishStateAfterBind);
                case RouteTablePublished(RouteTable routes) -> tx.transitionTo(new RouteReady(ctx,
                                                                                              Option.some(server),
                                                                                              Option.none(),
                                                                                              routes));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(new RouteReady(ctx,
                                                                                           Option.some(server),
                                                                                           Option.none(),
                                                                                           RouteTable.empty()));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(Option.some(server),
                                                                                                          Option.none()));
                default -> tx.ignore();
            }
        }
    }

    record H3Only(AppHttpContext ctx, HttpServer h3) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H1Ready(HttpServer server) -> tx.transitionTo(new Dual(ctx, server, h3));
                case H3Ready(HttpServer extra) -> tx.handle(() -> stopSurplusServer(ctx, Option.none(), Option.some(extra)));
                case CertRotationApplied(var newServer, var newH3, _) -> tx.transitionTo(adopted(ctx, newServer, newH3),
                                                                                         ctx::republishStateAfterBind);
                case RouteTablePublished(RouteTable routes) -> tx.transitionTo(new RouteReady(ctx,
                                                                                              Option.none(),
                                                                                              Option.some(h3),
                                                                                              routes));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(new RouteReady(ctx,
                                                                                           Option.none(),
                                                                                           Option.some(h3),
                                                                                           RouteTable.empty()));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(Option.none(),
                                                                                                          Option.some(h3)));
                default -> tx.ignore();
            }
        }
    }

    record Dual(AppHttpContext ctx, HttpServer server, HttpServer h3) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H1Ready(HttpServer extra) -> tx.handle(() -> stopSurplusServer(ctx, Option.some(extra), Option.none()));
                case H3Ready(HttpServer extra) -> tx.handle(() -> stopSurplusServer(ctx, Option.none(), Option.some(extra)));
                case CertRotationApplied(var newServer, var newH3, _) -> tx.transitionTo(adopted(ctx, newServer, newH3),
                                                                                         ctx::republishStateAfterBind);
                case RouteTablePublished(RouteTable routes) -> tx.transitionTo(new RouteReady(ctx,
                                                                                              Option.some(server),
                                                                                              Option.some(h3),
                                                                                              routes));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(new RouteReady(ctx,
                                                                                           Option.some(server),
                                                                                           Option.some(h3),
                                                                                           RouteTable.empty()));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(Option.some(server),
                                                                                                          Option.some(h3)));
                default -> tx.ignore();
            }
        }
    }

    record RouteReady(AppHttpContext ctx, Option<HttpServer> server, Option<HttpServer> h3, RouteTable routes) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case RouteTablePublished(RouteTable updated) -> tx.transitionTo(new RouteReady(ctx, server, h3, updated));
                case H1Ready(HttpServer newServer) -> tx.transitionTo(new RouteReady(ctx,
                                                                                     Option.some(newServer),
                                                                                     h3,
                                                                                     routes));
                case H3Ready(HttpServer newH3) -> tx.transitionTo(new RouteReady(ctx, server, Option.some(newH3), routes));
                case CertRotationApplied(var newServer, var newH3, _) -> tx.transitionTo(new RouteReady(ctx,
                                                                                                         newServer,
                                                                                                         newH3,
                                                                                                         routes));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.ignore();
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(new Quiesced(ctx, server, h3));
                case CertRotationRequested(var bundle) -> tx.transitionTo(new CertRotating(ctx,
                                                                                           server,
                                                                                           h3,
                                                                                           bundle,
                                                                                           routes));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(server, h3));
                default -> tx.ignore();
            }
        }
    }

    record CertRotating(AppHttpContext ctx,
                        Option<HttpServer> prevServer,
                        Option<HttpServer> prevH3,
                        org.pragmatica.net.tcp.security.CertificateBundle newBundle,
                        RouteTable routes) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case CertRotationApplied(var newServer, var newH3, var updated) -> tx.transitionTo(new RouteReady(ctx,
                                                                                                                  newServer,
                                                                                                                  newH3,
                                                                                                                  updated));
                case RouteTablePublished(RouteTable updated) -> tx.transitionTo(new CertRotating(ctx,
                                                                                                 prevServer,
                                                                                                 prevH3,
                                                                                                 newBundle,
                                                                                                 updated));
                case H1Ready(HttpServer extra) -> tx.handle(() -> stopSurplusServer(ctx, Option.some(extra), Option.none()));
                case H3Ready(HttpServer extra) -> tx.handle(() -> stopSurplusServer(ctx, Option.none(), Option.some(extra)));
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(new Quiesced(ctx, prevServer, prevH3));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(prevServer,
                                                                                                          prevH3));
                default -> tx.ignore();
            }
        }
    }

    record Quiesced(AppHttpContext ctx, Option<HttpServer> server, Option<HttpServer> h3) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(new RouteReady(ctx,
                                                                                           server,
                                                                                           h3,
                                                                                           RouteTable.empty()),
                                                                            ctx::publishRouteTable);
                case RouteTablePublished _, ClusterFsmEvent.QuorumDisappeared _ -> tx.ignore();
                case H1Ready(HttpServer newServer) -> tx.transitionTo(new Quiesced(ctx, Option.some(newServer), h3));
                case H3Ready(HttpServer newH3) -> tx.transitionTo(new Quiesced(ctx, server, Option.some(newH3)));
                case CertRotationApplied(var newServer, var newH3, _) -> tx.transitionTo(new Quiesced(ctx, newServer, newH3));
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(server, h3));
                default -> tx.ignore();
            }
        }
    }
}
