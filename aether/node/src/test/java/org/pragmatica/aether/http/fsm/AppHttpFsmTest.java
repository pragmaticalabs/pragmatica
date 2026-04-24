// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.RouteTable;
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

    private static AppHttpState buildContextAndStopped(Fsm<AppHttpState, ClusterFsmEvent> fsm,
                                                       AtomicReference<AppHttpContext> ctxHolder) {
        var ctx = new AppHttpContext(fsm);
        ctxHolder.set(ctx);
        return ctx.stopped();
    }

    @Test
    void happyPath_h1FirstThenH3_thenRoutes_thenStop() {
        var harness = newHarness();
        var h1 = fakeHttpServer(8080);
        var h3 = fakeHttpServer(8080);

        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);

        harness.dispatch(new AppHttpEvents.StartRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);

        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        assertThat(harness.state()).isInstanceOf(AppHttpState.H1Only.class);

        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        assertThat(harness.state()).isInstanceOf(AppHttpState.Dual.class);

        var table = RouteTable.empty();
        harness.dispatch(new AppHttpEvents.RouteTablePublished(table));
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(h1);
        assertThat(routeReady.h3().unwrap()).isSameAs(h3);
        assertThat(routeReady.routes()).isSameAs(table);

        harness.dispatch(new AppHttpEvents.StopRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);
    }

    @Test
    void happyPath_h3FirstThenH1_thenRoutes_thenStop() {
        var harness = newHarness();
        var h1 = fakeHttpServer(9090);
        var h3 = fakeHttpServer(9090);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H3Ready(h3));
        assertThat(harness.state()).isInstanceOf(AppHttpState.H3Only.class);

        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        assertThat(harness.state()).isInstanceOf(AppHttpState.Dual.class);

        harness.dispatch(new AppHttpEvents.RouteTablePublished(RouteTable.empty()));
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);

        harness.dispatch(new AppHttpEvents.StopRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);
    }

    @Test
    void concurrent_routeTablePublished_exactlyOneCasWins() throws InterruptedException {
        var harness = newHarness();
        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(fakeHttpServer(1)));
        harness.dispatch(new AppHttpEvents.H3Ready(fakeHttpServer(1)));
        assertThat(harness.state()).isInstanceOf(AppHttpState.Dual.class);

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
        // Exactly one CAS wins the Dual → RouteReady transition. Losers forward to the new
        // RouteReady state (each arriving event is a valid RouteReady → RouteReady swap), so every
        // event ultimately lands as a successful transition — no ignored events from this path.
        var dualToRouteReady = harness.transitions().stream()
                                      .filter(t -> t.from() instanceof AppHttpState.Dual
                                                   && t.to() instanceof AppHttpState.RouteReady)
                                      .count();
        assertThat(dualToRouteReady).isEqualTo(1);
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
    void quorumEstablished_in_h1Only_transitions_to_routeReady_with_emptyTable() {
        var harness = newHarness();
        var h1 = fakeHttpServer(1234);

        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(h1));
        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());

        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.routes().localRoutes()).isEmpty();
        assertThat(routeReady.routes().remoteRoutes()).isEmpty();
        assertThat(routeReady.server().unwrap()).isSameAs(h1);
        assertThat(routeReady.h3().isEmpty()).isTrue();
    }

    // --- Test fixtures ---

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
