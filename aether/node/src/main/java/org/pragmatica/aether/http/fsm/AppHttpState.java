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


public sealed interface AppHttpState extends FsmState<AppHttpState, ClusterFsmEvent> {
    AppHttpContext ctx();

    record Stopped(AppHttpContext ctx) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            if (event instanceof StartRequested) {
                tx.transitionTo(ctx.starting());

                return;
            }
            tx.ignore();
        }
    }

    record Starting(AppHttpContext ctx) implements AppHttpState {
        @Override
        @Contract
        public void handle(ClusterFsmEvent event, TransitionRequest<AppHttpState, ClusterFsmEvent> tx) {
            switch (event) {
                case H1Ready(HttpServer server) -> tx.transitionTo(new H1Only(ctx, server), ctx::republishStateAfterBind);
                case H3Ready(HttpServer server) -> tx.transitionTo(new H3Only(ctx, server), ctx::republishStateAfterBind);
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
                case StopRequested _, ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped(),
                                                                                    () -> ctx.stopServers(server, h3));
                default -> tx.ignore();
            }
        }
    }
}
