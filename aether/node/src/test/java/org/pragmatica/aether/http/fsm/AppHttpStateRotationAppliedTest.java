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
import org.pragmatica.statemachine.FsmTestHarness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// #2418 — a certificate rotation stops the previous listeners and binds new ones, then reports them with
/// `CertRotationApplied`. Whatever state the FSM is in when the report arrives (the rotation can be overtaken by a
/// quorum drop, a quorum return or a stop), the new listeners must end up owned by the FSM, so that a later stop closes
/// them, or be closed on the spot when no state can own them.
class AppHttpStateRotationAppliedTest {

    private static final class FakeServer implements HttpServer {
        private final int port;
        private final AtomicBoolean stopped = new AtomicBoolean();

        FakeServer(int port) {
            this.port = port;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public Promise<Unit> stop() {
            stopped.set(true);
            return Promise.success(Unit.unit());
        }

        boolean isStopped() {
            return stopped.get();
        }
    }

    private static FsmTestHarness<AppHttpState, ClusterFsmEvent> newHarness() {
        return FsmTestHarness.<AppHttpState, ClusterFsmEvent>harness("app-http-rotation-applied-test",
                                                                      fsm -> new AppHttpContext(fsm).stopped());
    }

    /// Stopped -> RouteReady with the given pair, then a rotation request. The adapter has stopped the old pair by the
    /// time it reports, which the `stop()` calls model.
    private static void runningThenRotationRequested(FsmTestHarness<AppHttpState, ClusterFsmEvent> harness,
                                                     FakeServer oldH1,
                                                     FakeServer oldH3) {
        harness.dispatch(new AppHttpEvents.StartRequested());
        harness.dispatch(new AppHttpEvents.H1Ready(oldH1));
        harness.dispatch(new AppHttpEvents.H3Ready(oldH3));
        harness.dispatch(new AppHttpEvents.RouteTablePublished(RouteTable.empty()));
        harness.dispatch(new AppHttpEvents.CertRotationRequested(dummyBundle()));
        oldH1.stop();
        oldH3.stop();
    }

    private static AppHttpEvents.CertRotationApplied applied(FakeServer newH1, FakeServer newH3) {
        return new AppHttpEvents.CertRotationApplied(Option.some(newH1), Option.some(newH3), RouteTable.empty());
    }

    @Test
    void quiesced_onCertRotationApplied_adoptsTheNewPair_andStopClosesIt() {
        var harness = newHarness();
        var oldH1 = new FakeServer(1);
        var oldH3 = new FakeServer(2);
        var newH1 = new FakeServer(3);
        var newH3 = new FakeServer(4);

        runningThenRotationRequested(harness, oldH1, oldH3);
        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Quiesced.class);

        harness.dispatch(applied(newH1, newH3));

        var quiesced = (AppHttpState.Quiesced) harness.state();
        assertThat(quiesced.server().unwrap()).isSameAs(newH1);
        assertThat(quiesced.h3().unwrap()).isSameAs(newH3);

        harness.dispatch(new AppHttpEvents.StopRequested());

        assertThat(newH1.isStopped()).as("new H1 listener (bound by the rotation) closed by stop()").isTrue();
        assertThat(newH3.isStopped()).as("new H3 listener (bound by the rotation) closed by stop()").isTrue();
    }

    @Test
    void certRotating_onCertRotationApplied_withoutQuorumDrop_stopClosesTheNewPair() {
        var harness = newHarness();
        var newH1 = new FakeServer(3);
        var newH3 = new FakeServer(4);

        runningThenRotationRequested(harness, new FakeServer(1), new FakeServer(2));
        harness.dispatch(applied(newH1, newH3));
        harness.dispatch(new AppHttpEvents.StopRequested());

        assertThat(newH1.isStopped()).as("control: new H1 closed").isTrue();
        assertThat(newH3.isStopped()).as("control: new H3 closed").isTrue();
    }

    @Test
    void routeReady_onCertRotationApplied_afterQuorumReturned_replacesThePair_andStopClosesIt() {
        var harness = newHarness();
        var newH1 = new FakeServer(3);
        var newH3 = new FakeServer(4);

        runningThenRotationRequested(harness, new FakeServer(1), new FakeServer(2));
        harness.dispatch(new ClusterFsmEvent.QuorumDisappeared());
        harness.dispatch(new ClusterFsmEvent.QuorumEstablished());
        assertThat(harness.state()).isInstanceOf(AppHttpState.RouteReady.class);
        // The report carries the table captured BEFORE the rotation (empty here); the state's own, fresher table wins.
        var current = nonEmptyRouteTable();

        harness.dispatch(new AppHttpEvents.RouteTablePublished(current));
        harness.dispatch(applied(newH1, newH3));

        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(newH1);
        assertThat(routeReady.h3().unwrap()).isSameAs(newH3);
        assertThat(routeReady.routes()).isSameAs(current);

        harness.dispatch(new AppHttpEvents.StopRequested());

        assertThat(newH1.isStopped()).isTrue();
        assertThat(newH3.isStopped()).isTrue();
    }

    @Test
    void stopped_onCertRotationApplied_afterStopDuringRotation_closesTheNewPairOnTheSpot() {
        var harness = newHarness();
        var newH1 = new FakeServer(3);
        var newH3 = new FakeServer(4);

        runningThenRotationRequested(harness, new FakeServer(1), new FakeServer(2));
        harness.dispatch(new AppHttpEvents.StopRequested());
        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);
        assertThat(newH1.isStopped()).isFalse();

        harness.dispatch(applied(newH1, newH3));

        assertThat(harness.state()).isInstanceOf(AppHttpState.Stopped.class);
        assertThat(newH1.isStopped()).as("orphaned new H1 listener closed").isTrue();
        assertThat(newH3.isStopped()).as("orphaned new H3 listener closed").isTrue();
        assertThat(harness.handled()).hasSize(1);
    }

    /// A rotation report that reaches a state before its route table (the previous pair already stopped by the adapter)
    /// is adopted into the pair shape it reports; the republish after the adoption then lands it in `RouteReady`.
    private static FsmTestHarness<AppHttpState, ClusterFsmEvent> harnessIn(Function<AppHttpContext, AppHttpState> state) {
        return FsmTestHarness.<AppHttpState, ClusterFsmEvent>harness("app-http-rotation-adopt-test",
                                                                      fsm -> state.apply(new AppHttpContext(fsm)));
    }

    @Test
    void h1Only_onCertRotationApplied_withOnlyH1_adoptsTheH1() {
        var newH1 = new FakeServer(3);
        var harness = harnessIn(ctx -> new AppHttpState.H1Only(ctx, new FakeServer(1)));

        harness.dispatch(new AppHttpEvents.CertRotationApplied(Option.some(newH1), Option.none(), RouteTable.empty()));

        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(newH1);
        assertThat(routeReady.h3().isEmpty()).isTrue();
    }

    @Test
    void h1Only_onCertRotationApplied_withOnlyH3_adoptsTheH3() {
        var newH3 = new FakeServer(4);
        var harness = harnessIn(ctx -> new AppHttpState.H1Only(ctx, new FakeServer(1)));

        harness.dispatch(new AppHttpEvents.CertRotationApplied(Option.none(), Option.some(newH3), RouteTable.empty()));

        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().isEmpty()).isTrue();
        assertThat(routeReady.h3().unwrap()).isSameAs(newH3);
    }

    @Test
    void dual_onCertRotationApplied_withBoth_adoptsBoth() {
        var newH1 = new FakeServer(3);
        var newH3 = new FakeServer(4);
        var harness = harnessIn(ctx -> new AppHttpState.Dual(ctx, new FakeServer(1), new FakeServer(2)));

        harness.dispatch(applied(newH1, newH3));

        var routeReady = (AppHttpState.RouteReady) harness.state();
        assertThat(routeReady.server().unwrap()).isSameAs(newH1);
        assertThat(routeReady.h3().unwrap()).isSameAs(newH3);
    }

    @Test
    void dual_onCertRotationApplied_withNothingBound_fallsBackToStarting() {
        var harness = harnessIn(ctx -> new AppHttpState.Dual(ctx, new FakeServer(1), new FakeServer(2)));

        harness.dispatch(new AppHttpEvents.CertRotationApplied(Option.none(), Option.none(), RouteTable.empty()));

        assertThat(harness.state()).isInstanceOf(AppHttpState.Starting.class);
    }

    private static RouteTable nonEmptyRouteTable() {
        var route = HttpNodeRouteKey.httpNodeRouteKey("GET", "/rotation/", NodeId.nodeId("rotation-node").unwrap());

        return RouteTable.routeTable(Set.of(route), List.of());
    }

    private static CertificateBundle dummyBundle() {
        return CertificateBundle.certificateBundle(new byte[0],
                                                   new byte[0],
                                                   new byte[0],
                                                   Instant.now().plus(1, ChronoUnit.DAYS));
    }
}
