// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Theme C — split-brain protection.
///
/// On `QuorumDisappeared` the FSM transitions from `RouteReady` (or `CertRotating`) to
/// `Quiesced`, which causes `AppHttpContext.currentRoutes()` to return `RouteTable.empty()`.
/// Existing dispatch in the request handler then naturally returns 503/404 for application
/// traffic — preventing a minority partition from continuing to serve stale routes.
///
/// On `QuorumEstablished` from `Quiesced`, the FSM transitions back to `RouteReady` and
/// re-reads the authoritative route table via the wired route supplier.
class AppHttpStateQuorumDisappearedTest {

    private static FsmTestHarness<AppHttpState, ClusterFsmEvent> newHarness(Supplier<RouteTable> routeSupplier) {
        var ctxHolder = new AtomicReference<AppHttpContext>();
        return FsmTestHarness.<AppHttpState, ClusterFsmEvent>harness(
                "app-http-quorum-disappeared-test",
                fsm -> buildContextAndStopped(fsm, ctxHolder, routeSupplier));
    }

    private static AppHttpState buildContextAndStopped(Fsm<AppHttpState, ClusterFsmEvent> fsm,
                                                       AtomicReference<AppHttpContext> ctxHolder,
                                                       Supplier<RouteTable> routeSupplier) {
        var ctx = new AppHttpContext(fsm, routeSupplier, () -> false);
        ctxHolder.set(ctx);
        return ctx.stopped();
    }

    @Test
    void routeReady_onQuorumDisappeared_transitionsToQuiesced() {
        var routes = nonEmptyRouteTable();
        var harness = newHarness(() -> routes);
        var h1 = fakeHttpServer(8080);
        var h3 = fakeHttpServer(8443);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(routes));
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);

        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());

        assertThat(harness.state()).isInstanceOf(AppHttpState.Quiesced.class);
        var quiesced = (AppHttpState.Quiesced) harness.state();
        assertThat(quiesced.server().unwrap()).isSameAs(h1);
        assertThat(quiesced.h3().unwrap()).isSameAs(h3);
    }

    @Test
    void quiesced_currentRoutesIsEmpty() {
        var routes = nonEmptyRouteTable();
        var harness = newHarness(() -> routes);
        var ctx = harnessContext(harness);
        var h1 = fakeHttpServer(8080);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(routes));
        assertThat(ctx.currentRoutes().localRoutes()).isNotEmpty();

        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());

        // Split-brain protection: even though servers stay bound, the current route table
        // returned to the dispatch path is empty so application traffic gets 503/404.
        assertThat(ctx.currentRoutes().localRoutes()).isEmpty();
        assertThat(ctx.currentRoutes().remoteRoutes()).isEmpty();
        // Servers remain bound — boundPort is still observable from the Quiesced state.
        assertThat(ctx.boundPort().unwrap()).isEqualTo(8080);
    }

    @Test
    void quiesced_onQuorumEstablished_transitionsBackToRouteReady_withRouteRefetch() {
        var initialRoutes = nonEmptyRouteTable();
        var refetchedRoutes = freshRouteTable();
        var supplied = new AtomicReference<>(initialRoutes);
        var harness = newHarness(supplied::get);
        var h1 = fakeHttpServer(8080);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(initialRoutes));
        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Quiesced.class);

        // Authoritative table updates while quiesced (e.g., new routes published while in
        // minority partition). On QuorumEstablished the transition action re-reads from
        // the supplier and the resulting RouteReady should carry the *refetched* table.
        supplied.set(refetchedRoutes);

        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());

        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(h1);
        assertThat(routeReady.routes()).isSameAs(refetchedRoutes);
    }

    @Test
    void certRotating_onQuorumDisappeared_transitionsToQuiesced() {
        var routes = nonEmptyRouteTable();
        var harness = newHarness(() -> routes);
        var h1 = fakeHttpServer(7000);
        var h3 = fakeHttpServer(7443);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(routes));
        var bundle = CertificateBundle.certificateBundle(new byte[0],
                                                         new byte[0],
                                                         new byte[0],
                                                         Instant.now().plus(1, ChronoUnit.DAYS));
        harness.dispatch(new AppHttpEvents.CertRotationRequested(bundle));
        assertThat(harness.state()).isInstanceOf(AppHttpState.CertRotating.class);

        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());

        assertThat(harness.state()).isInstanceOf(AppHttpState.Quiesced.class);
        var quiesced = (AppHttpState.Quiesced) harness.state();
        assertThat(quiesced.server().unwrap()).isSameAs(h1);
        assertThat(quiesced.h3().unwrap()).isSameAs(h3);
    }

    @Test
    void quiesced_routeTablePublished_isIgnored() {
        var routes = nonEmptyRouteTable();
        var harness = newHarness(() -> routes);
        var h1 = fakeHttpServer(8080);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(routes));
        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        var quiescedBefore = harness.state();

        harness.dispatch(new AppHttpEvents.RouteTablePublished(freshRouteTable()));

        // Quiesced ignores RouteTablePublished — only QuorumEstablished re-activates routing.
        assertThat(harness.state()).isSameAs(quiescedBefore);
    }

    private static AppHttpContext harnessContext(FsmTestHarness<AppHttpState, ClusterFsmEvent> harness) {
        return harness.state().ctx();
    }

    private static RouteTable nonEmptyRouteTable() {
        var nodeId = NodeId.nodeId("split-brain-self").unwrap();
        var route = HttpNodeRouteKey.httpNodeRouteKey("GET", "/api/v1/", nodeId);
        return RouteTable.routeTable(Set.of(route), List.of());
    }

    private static RouteTable freshRouteTable() {
        var nodeId = NodeId.nodeId("split-brain-self").unwrap();
        var route = HttpNodeRouteKey.httpNodeRouteKey("GET", "/api/v2/", nodeId);
        return RouteTable.routeTable(Set.of(route), List.of());
    }

    private static HttpServer fakeHttpServer(int port) {
        return new HttpServer() {
            @Override public int port() {return port;}

            @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        };
    }
}
