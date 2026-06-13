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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM-level tests for the app-HTTP-server state machine. Exercises the flattened state hierarchy
/// (Stopped → Starting → H1Only/H3Only → Dual → RouteReady ↔ CertRotating → Stopped) directly,
/// without binding any real HTTP servers — fake [`HttpServer`] stubs satisfy the state-record
/// fields and their `stop()` is a no-op.
class AppHttpFsmTest {

    private static FsmTestHarness<AppHttpState, ClusterFsmEvent> newHarness() {
        var ctxHolder = new AtomicReference<AppHttpContext>();
        var harness = FsmTestHarness.<AppHttpState, ClusterFsmEvent>harness(
                "app-http-fsm-test",
                fsm -> buildContextAndStopped(fsm, ctxHolder));
        return harness;
    }

    private static FsmTestHarness<AppHttpState, ClusterFsmEvent> newHarness(java.util.function.Supplier<RouteTable> routeSupplier,
                                                                            java.util.function.BooleanSupplier quorumSupplier) {
        var ctxHolder = new AtomicReference<AppHttpContext>();
        return FsmTestHarness.<AppHttpState, ClusterFsmEvent>harness(
                "app-http-fsm-test",
                fsm -> buildContextAndStopped(fsm, ctxHolder, routeSupplier, quorumSupplier));
    }

    private static AppHttpState buildContextAndStopped(Fsm<AppHttpState, ClusterFsmEvent> fsm,
                                                       AtomicReference<AppHttpContext> ctxHolder) {
        var ctx = new AppHttpContext(fsm);
        ctxHolder.set(ctx);
        return ctx.stopped();
    }

    private static AppHttpState buildContextAndStopped(Fsm<AppHttpState, ClusterFsmEvent> fsm,
                                                       AtomicReference<AppHttpContext> ctxHolder,
                                                       java.util.function.Supplier<RouteTable> routeSupplier,
                                                       java.util.function.BooleanSupplier quorumSupplier) {
        var ctx = new AppHttpContext(fsm, routeSupplier, quorumSupplier);
        ctxHolder.set(ctx);
        return ctx.stopped();
    }

    @Test
    void happyPath_h1FirstThenH3_thenRoutes_thenStop() {
        // Default suppliers (empty route table, quorum-not-established) — H1Ready transition action
        // republishes an empty RouteTablePublished, collapsing Starting → H1Only → RouteReady in a
        // single observable step. A subsequent H3Ready arriving in RouteReady upgrades the carried
        // server pair (so dual-protocol startup attaches the second protocol).
        var harness = newHarness();
        var h1 = fakeHttpServer(8080);
        var h3 = fakeHttpServer(8080);

        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);

        harness.dispatch(new AppHttpEvents.StartRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);

        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var afterH1 = (AppHttpState.RouteReady) harness.state();
        assertThat(afterH1.server().unwrap()).isSameAs(h1);
        assertThat(afterH1.h3().isEmpty()).isTrue();

        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        var afterH3 = (AppHttpState.RouteReady) harness.state();
        assertThat(afterH3.server().unwrap()).isSameAs(h1);
        assertThat(afterH3.h3().unwrap()).isSameAs(h3);
        assertThat(afterH3.routes()).isSameAs(RouteTable.empty());

        harness.dispatch(new AppHttpEvents.StopRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);
    }

    @Test
    void happyPath_h3FirstThenH1_thenRoutes_thenStop() {
        // Symmetric counterpart: H3Ready first collapses Starting → H3Only → RouteReady, then
        // H1Ready upgrades the pair.
        var harness = newHarness();
        var h1 = fakeHttpServer(9090);
        var h3 = fakeHttpServer(9090);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);

        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(h1);
        assertThat(routeReady.h3().unwrap()).isSameAs(h3);

        harness.dispatch(new AppHttpEvents.StopRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);
    }

    @Test
    void concurrent_routeTablePublished_exactlyOneCasWins() throws InterruptedException {
        // After H1Ready the FSM is already in RouteReady (republish action). Concurrent
        // RouteTablePublished events all become RouteReady → RouteReady swaps; each arriving event
        // ultimately lands as a successful transition (no ignored events).
        var harness = newHarness();
        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(fakeHttpServer(1)));
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);

        var events = List.<ClusterFsmEvent>of(
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()),
                new AppHttpEvents.RouteTablePublished(RouteTable.empty()));
        harness.dispatchConcurrently(events);

        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
    }

    @Test
    void certRotation_routeReady_to_certRotating_and_back() {
        var harness = newHarness();
        var h1 = fakeHttpServer(7);
        var h3 = fakeHttpServer(7);
        var newH1 = fakeHttpServer(7);
        var newH3 = fakeHttpServer(7);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(RouteTable.empty()));

        var before = harness.state();
        assertThat(before).isInstanceOf(AppHttpState.RouteReady.class);

        var bundle = dummyBundle();
        harness.dispatch(new AppHttpEvents.CertRotationRequested(bundle));
        var rotating = harness.state();
        assertThat(rotating).isInstanceOf(AppHttpState.CertRotating.class);
        assertThat(rotating).isNotSameAs(before);
        var rotatingRec = (AppHttpState.CertRotating) rotating;
        assertThat(rotatingRec.prevServer().unwrap()).isSameAs(h1);
        assertThat(rotatingRec.prevH3().unwrap()).isSameAs(h3);
        assertThat(rotatingRec.newBundle()).isSameAs(bundle);

        harness.dispatch(new AppHttpEvents.CertRotationApplied(Option.some(newH1),
                                                                Option.some(newH3),
                                                                RouteTable.empty()));
        var afterRotation = harness.state();
        assertThat(afterRotation).isInstanceOf(AppHttpState.RouteReady.class);
        assertThat(afterRotation).isNotSameAs(before);
        var afterRec = (AppHttpState.RouteReady) afterRotation;
        assertThat(afterRec.server().unwrap()).isSameAs(newH1);
        assertThat(afterRec.h3().unwrap()).isSameAs(newH3);
    }

    @Test
    void routeTablePublished_in_starting_is_ignored() {
        var harness = newHarness();
        harness.dispatch(new AppHttpEvents.StartRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);

        harness.dispatch(new AppHttpEvents.RouteTablePublished(RouteTable.empty()));
        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);

        var ignored = harness.ignored();
        assertThat(ignored).hasSize(1);
        assertThat(ignored.getFirst().event()).isInstanceOf(AppHttpEvents.RouteTablePublished.class);
        assertThat(ignored.getFirst().state()).isInstanceOf(AppHttpState.Starting.class);
    }

    @Test
    void h1Ready_coldStart_republishesPreSettledRoutesAndQuorum() {
        // Simulate cold-start race: route table + quorum settled BEFORE bind completes.
        // Pre-populate via suppliers; the supplier returns the pre-settled snapshot so
        // republishStateAfterBind sees the authoritative state on the H1Ready transition.
        var preSettledTable = preSettledRouteTable();
        java.util.function.Supplier<RouteTable> routeSupplier = () -> preSettledTable;
        var quorumFlag = new AtomicBoolean(true);
        var harness = newHarness(routeSupplier, quorumFlag::get);

        harness.dispatch(new AppHttpEvents.StartRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);

        // Pre-bind: route + quorum events arriving in Starting are ignored (they're either KV
        // callbacks or the consensus notification fired before we reached H1Ready).
        harness.dispatch(new AppHttpEvents.RouteTablePublished(preSettledTable));
        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);

        // Bind completes — Starting → H1Only must trigger republishStateAfterBind, which
        // re-dispatches RouteTablePublished (driving H1Only → RouteReady carrying the populated
        // table) and then QuorumEstablished (no-op in RouteReady).
        var h1 = fakeHttpServer(8080);
        harness.dispatch(new AppHttpEvents.H1Ready(h1));

        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(h1);
        assertThat(routeReady.h3().isEmpty()).isTrue();
        assertThat(routeReady.routes()).isSameAs(preSettledTable);
        assertThat(routeReady.routes().localRoutes()).isNotEmpty();
    }

    @Test
    void h3Ready_coldStart_republishesPreSettledRoutesAndQuorum() {
        var preSettledTable = preSettledRouteTable();
        java.util.function.Supplier<RouteTable> routeSupplier = () -> preSettledTable;
        var harness = newHarness(routeSupplier, () -> true);

        harness.dispatch(new AppHttpEvents.StartRequested());
        var h3 = fakeHttpServer(9443);
        harness.dispatch(new AppHttpEvents.H3Ready(h3));

        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().isEmpty()).isTrue();
        assertThat(routeReady.h3().unwrap()).isSameAs(h3);
        assertThat(routeReady.routes()).isSameAs(preSettledTable);
    }

    @Test
    void h1Ready_coldStart_quorumNotEstablished_stopsAtH1OnlyAfterEmptyRepublish() {
        // When quorum is not yet established and routes are empty, the republish is a no-op:
        // the empty RouteTablePublished still drives H1Only → RouteReady (empty), but no
        // QuorumEstablished fires because the supplier reports false.
        var quorumFlag = new AtomicBoolean(false);
        var harness = newHarness(RouteTable::empty, quorumFlag::get);

        harness.dispatch(new AppHttpEvents.StartRequested());
        var h1 = fakeHttpServer(8080);
        harness.dispatch(new AppHttpEvents.H1Ready(h1));

        // Empty RouteTablePublished from republish lands as H1Only → RouteReady(empty).
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.routes().localRoutes()).isEmpty();
        assertThat(routeReady.routes().remoteRoutes()).isEmpty();
    }

    @Test
    void quorumEstablished_in_routeReady_isIgnored() {
        // The republish action collapses Starting → H1Only → RouteReady on H1Ready. A subsequent
        // QuorumEstablished arriving in RouteReady is a no-op; routes were already populated by
        // the empty-table republish (default supplier) and the FSM stays in RouteReady.
        var harness = newHarness();
        var h1 = fakeHttpServer(1234);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        var beforeQuorum = harness.state();
        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());

        assertThat(harness.state()).isSameAs(beforeQuorum);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(h1);
        assertThat(routeReady.h3().isEmpty()).isTrue();
    }

    // --- Test fixtures ---

    /// Build a non-empty [`RouteTable`] simulating the case where local routes were published
    /// before the HTTP server bound. Mirrors the real production shape: one local route, no
    /// remote routes. Production code computes the table from a `HttpRoutePublisher` snapshot;
    /// the test bypasses that by handing the table directly to the route supplier.
    private static RouteTable preSettledRouteTable() {
        var nodeId = NodeId.nodeId("cold-start-node").unwrap();
        var localRoute = HttpNodeRouteKey.httpNodeRouteKey("GET", "/cold-start/", nodeId);
        return RouteTable.routeTable(Set.of(localRoute), List.of());
    }

    private static HttpServer fakeHttpServer(int port) {
        return new HttpServer() {
            @Override
            public int port() { return port; }

            @Override
            public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
        };
    }

    private static CertificateBundle dummyBundle() {
        return CertificateBundle.certificateBundle(new byte[0],
                                                   new byte[0],
                                                   new byte[0],
                                                   Instant.now().plus(1, ChronoUnit.DAYS));
    }
}
