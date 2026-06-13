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

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


public final class AppHttpContext {
    private static final Logger log = LoggerFactory.getLogger(AppHttpContext.class);
    public static final Supplier<RouteTable> EMPTY_ROUTE_TABLE_SENTINEL = RouteTable::empty;
    private static final Supplier<RouteTable> EMPTY_ROUTE_TABLE = EMPTY_ROUTE_TABLE_SENTINEL;
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

    public Fsm<AppHttpState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract
    public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public AppHttpState stopped() {
        return stopped;
    }

    public AppHttpState starting() {
        return starting;
    }

    public Supplier<RouteTable> routeTableSupplier() {
        return routeTableSupplier;
    }

    @Contract
    public void publishRouteTable() {
        var table = routeTableSupplier.get();

        log.debug("Router rebuilt: {} local routes, {} remote routes",
                  table.localRoutes().size(),
                  table.remoteRoutes().size());
        fsm.dispatch(new AppHttpEvents.RouteTablePublished(table));
    }

    @Contract
    public void publishQuorumStateIfEstablished() {
        if (quorumEstablishedSupplier.getAsBoolean()) {
            fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
        }
    }

    @Contract
    public void republishStateAfterBind() {
        publishRouteTable();
        publishQuorumStateIfEstablished();
    }

    @Contract
    public void stopServers(Option<HttpServer> server, Option<HttpServer> h3) {
        server.onPresent(HttpServer::stop);
        h3.onPresent(HttpServer::stop);
    }

    public Promise<Unit> stopServersAsync(Option<HttpServer> server, Option<HttpServer> h3) {
        var h1Stop = server.map(HttpServer::stop).or(Promise.success(unit()));
        var h3Stop = h3.map(HttpServer::stop).or(Promise.success(unit()));

        return h1Stop.flatMap(_ -> h3Stop);
    }

    public RouteTable currentRoutes() {
        return switch (fsm.current()) {
            case AppHttpState.RouteReady(_, _, _, RouteTable routes) -> routes;
            case AppHttpState.CertRotating(_, _, _, _, RouteTable routes) -> routes;
            case AppHttpState.Quiesced _ -> RouteTable.empty();
            default -> RouteTable.empty();
        };
    }

    public Option<Integer> boundPort() {
        return switch (fsm.current()) {
            case AppHttpState.H1Only(_, HttpServer server) -> Option.some(server.port());
            case AppHttpState.H3Only(_, HttpServer h3) -> Option.some(h3.port());
            case AppHttpState.Dual(_, HttpServer server, _) -> Option.some(server.port());
            case AppHttpState.RouteReady(_, Option<HttpServer> server, Option<HttpServer> h3, _) -> server.map(HttpServer::port).orElse(() -> h3.map(HttpServer::port));
            case AppHttpState.CertRotating(_, Option<HttpServer> server, Option<HttpServer> h3, _, _) -> server.map(HttpServer::port).orElse(() -> h3.map(HttpServer::port));
            case AppHttpState.Quiesced(_, Option<HttpServer> server, Option<HttpServer> h3) -> server.map(HttpServer::port).orElse(() -> h3.map(HttpServer::port));
            case AppHttpState.Stopped _, AppHttpState.Starting _ -> Option.none();
        };
    }

    public boolean isRouteReady() {
        return fsm.current() instanceof AppHttpState.RouteReady || fsm.current() instanceof AppHttpState.CertRotating;
    }

    public record ServerPair(Option<HttpServer> server, Option<HttpServer> h3) {}

    public ServerPair currentServers() {
        return switch (fsm.current()) {
            case AppHttpState.H1Only(_, HttpServer server) -> new ServerPair(Option.some(server), Option.none());
            case AppHttpState.H3Only(_, HttpServer h3) -> new ServerPair(Option.none(), Option.some(h3));
            case AppHttpState.Dual(_, HttpServer server, HttpServer h3) -> new ServerPair(Option.some(server),
                                                                                          Option.some(h3));
            case AppHttpState.RouteReady(_, Option<HttpServer> server, Option<HttpServer> h3, _) -> new ServerPair(server,
                                                                                                                   h3);
            case AppHttpState.CertRotating(_, Option<HttpServer> server, Option<HttpServer> h3, _, _) -> new ServerPair(server,
                                                                                                                        h3);
            case AppHttpState.Quiesced(_, Option<HttpServer> server, Option<HttpServer> h3) -> new ServerPair(server, h3);
            case AppHttpState.Stopped _, AppHttpState.Starting _ -> new ServerPair(Option.none(), Option.none());
        };
    }
}
