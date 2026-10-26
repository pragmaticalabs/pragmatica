// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.pragmatica.aether.http.RouteTable;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Totality of [AppHttpState]: the full state x event matrix, one cell per pair. A cell is a transition to a state
/// (the FINAL state, after the transition action's own republish), a side effect without a transition (`HANDLED`), or an
/// `IGNORED` event whose reason is written next to the cell. The matrix is checked against the sealed state hierarchy and
/// the event vocabulary by reflection, so a state or an event added without a row fails here, and an event a state
/// stopped handling fails its cell.
class AppHttpStateTotalityTest {

    private static final class FakeServer implements HttpServer {
        private final AtomicBoolean stopped = new AtomicBoolean();

        @Override
        public int port() {
            return 1;
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

    private enum Kind {
        TRANSITION,
        HANDLED,
        IGNORED
    }

    private record Cell(Kind kind, Class<? extends AppHttpState> target, String reason, boolean retires) {
        static Cell to(Class<? extends AppHttpState> target) {
            return new Cell(Kind.TRANSITION, target, "", false);
        }

        /// A replacement that also closes the pair the state held (a rotation report replacing it).
        Cell retiring() {
            return new Cell(kind, target, reason, true);
        }

        static Cell closes(String reason) {
            return new Cell(Kind.HANDLED, null, reason, false);
        }

        static Cell ignored(String reason) {
            return new Cell(Kind.IGNORED, null, reason, false);
        }
    }

    /// Everything one cell dispatches: the event plus the listeners it carries (to see whether they were closed).
    private record Probe(ClusterFsmEvent event, List<FakeServer> carried) {}

    private static final List<String> EVENTS = List.of("StartRequested",
                                                       "H1Ready",
                                                       "H3Ready",
                                                       "RouteTablePublished",
                                                       "CertRotationRequested",
                                                       "CertRotationApplied",
                                                       "StopRequested",
                                                       "Shutdown",
                                                       "QuorumEstablished",
                                                       "QuorumDisappeared",
                                                       "LeaderChange",
                                                       "NodeAdded",
                                                       "NodeGone");

    private static final Set<Class<?>> COVERED_EVENT_TYPES = Set.of(AppHttpEvents.StartRequested.class,
                                                                    AppHttpEvents.H1Ready.class,
                                                                    AppHttpEvents.H3Ready.class,
                                                                    AppHttpEvents.RouteTablePublished.class,
                                                                    AppHttpEvents.CertRotationRequested.class,
                                                                    AppHttpEvents.CertRotationApplied.class,
                                                                    AppHttpEvents.StopRequested.class,
                                                                    ClusterFsmEvent.Shutdown.class,
                                                                    ClusterFsmEvent.QuorumEstablished.class,
                                                                    ClusterFsmEvent.QuorumDisappeared.class,
                                                                    ClusterFsmEvent.LeaderChange.class,
                                                                    ClusterFsmEvent.NodeAdded.class,
                                                                    ClusterFsmEvent.NodeGone.class);

    private static final String ALREADY_STOPPED = "already stopped: stopping again is idempotent";
    private static final String NO_LISTENER = "no listener bound: nothing to route, rotate or quiesce; a later bind re-reads route table and quorum";
    private static final String ALREADY_STARTED = "already started or starting";
    private static final String NO_ROUTES_YET = "no route table yet: no routes are served, which is what Quiesced means; the previous pair stays until Applied adopts the new one";
    private static final String ROTATION_IN_FLIGHT = "a rotation is already in flight and carries the route table";
    private static final String ALREADY_QUIESCED = "already quiesced";
    private static final String ALREADY_ESTABLISHED = "quorum already established";
    private static final String NOT_LISTENER_LIFECYCLE = "says nothing about the listeners; never dispatched to this FSM by the adapter";
    private static final String QUIESCED_ROTATION = "no routes to carry; the previous pair stays until Applied replaces it";
    private static final String SURPLUS = "a listener this state already holds: the reported one is surplus and is closed";

    private static final Map<Class<? extends AppHttpState>, List<Cell>> MATRIX = matrix();

    private static Map<Class<? extends AppHttpState>, List<Cell>> matrix() {
        var rows = new LinkedHashMap<Class<? extends AppHttpState>, List<Cell>>();
        var leaderNodeEvents = List.of(Cell.ignored(NOT_LISTENER_LIFECYCLE),
                                       Cell.ignored(NOT_LISTENER_LIFECYCLE),
                                       Cell.ignored(NOT_LISTENER_LIFECYCLE));

        rows.put(AppHttpState.Stopped.class,
                 row(leaderNodeEvents,
                     Cell.to(AppHttpState.Starting.class),
                     Cell.closes("orphan: stop() won the race against an in-flight bind (#1456)"),
                     Cell.closes("orphan: stop() won the race against an in-flight bind (#1456)"),
                     Cell.ignored(NO_LISTENER),
                     Cell.ignored(NO_LISTENER),
                     Cell.closes("orphan: stop() won the race against an in-flight rotation (#2418)"),
                     Cell.ignored(ALREADY_STOPPED),
                     Cell.ignored(ALREADY_STOPPED),
                     Cell.ignored(NO_LISTENER),
                     Cell.ignored(NO_LISTENER)));
        rows.put(AppHttpState.Starting.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored("nothing bound yet; republishStateAfterBind re-reads the table after the bind"),
                     Cell.ignored("nothing bound yet to rotate"),
                     Cell.closes("a rotation overlapping an in-flight start: the start owns the listener slot"),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.ignored("nothing bound yet; republishStateAfterBind re-reads quorum after the bind"),
                     Cell.ignored("nothing bound yet to quiesce")));
        rows.put(AppHttpState.H1Only.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.closes(SURPLUS),
                     Cell.to(AppHttpState.Dual.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(NO_ROUTES_YET),
                     Cell.to(AppHttpState.RouteReady.class).retiring(),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(NO_ROUTES_YET)));
        rows.put(AppHttpState.H3Only.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.to(AppHttpState.Dual.class),
                     Cell.closes(SURPLUS),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(NO_ROUTES_YET),
                     Cell.to(AppHttpState.RouteReady.class).retiring(),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(NO_ROUTES_YET)));
        rows.put(AppHttpState.Dual.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.closes(SURPLUS),
                     Cell.closes(SURPLUS),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(NO_ROUTES_YET),
                     Cell.to(AppHttpState.RouteReady.class).retiring(),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(NO_ROUTES_YET)));
        rows.put(AppHttpState.RouteReady.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.to(AppHttpState.CertRotating.class),
                     Cell.to(AppHttpState.RouteReady.class).retiring(),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.ignored(ALREADY_ESTABLISHED),
                     Cell.to(AppHttpState.Quiesced.class)));
        rows.put(AppHttpState.CertRotating.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.closes(SURPLUS),
                     Cell.closes(SURPLUS),
                     Cell.to(AppHttpState.CertRotating.class),
                     Cell.ignored(ROTATION_IN_FLIGHT),
                     Cell.to(AppHttpState.RouteReady.class).retiring(),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.ignored(ROTATION_IN_FLIGHT),
                     Cell.to(AppHttpState.Quiesced.class)));
        rows.put(AppHttpState.Quiesced.class,
                 row(leaderNodeEvents,
                     Cell.ignored(ALREADY_STARTED),
                     Cell.to(AppHttpState.Quiesced.class),
                     Cell.to(AppHttpState.Quiesced.class),
                     Cell.ignored(ALREADY_QUIESCED),
                     Cell.ignored(QUIESCED_ROTATION),
                     Cell.to(AppHttpState.Quiesced.class).retiring(),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.Stopped.class),
                     Cell.to(AppHttpState.RouteReady.class),
                     Cell.ignored(ALREADY_QUIESCED)));
        return rows;
    }

    private static List<Cell> row(List<Cell> trailing, Cell... first) {
        var cells = new ArrayList<>(Arrays.asList(first));

        cells.addAll(trailing);
        return List.copyOf(cells);
    }

    private static Probe probe(String event) {
        var carried = new FakeServer();

        return switch (event) {
            case "StartRequested" -> new Probe(new AppHttpEvents.StartRequested(), List.of());
            case "H1Ready" -> new Probe(new AppHttpEvents.H1Ready(carried), List.of(carried));
            case "H3Ready" -> new Probe(new AppHttpEvents.H3Ready(carried), List.of(carried));
            case "RouteTablePublished" -> new Probe(new AppHttpEvents.RouteTablePublished(RouteTable.empty()), List.of());
            case "CertRotationRequested" -> new Probe(new AppHttpEvents.CertRotationRequested(dummyBundle()), List.of());
            case "CertRotationApplied" -> appliedProbe(carried);
            case "StopRequested" -> new Probe(new AppHttpEvents.StopRequested(), List.of());
            case "Shutdown" -> new Probe(new ClusterFsmEvent.Shutdown(), List.of());
            case "QuorumEstablished" -> new Probe(new ClusterFsmEvent.QuorumEstablished(), List.of());
            case "QuorumDisappeared" -> new Probe(new ClusterFsmEvent.QuorumDisappeared(), List.of());
            case "LeaderChange" -> new Probe(new ClusterFsmEvent.LeaderChange(Option.none(), false), List.of());
            case "NodeAdded" -> new Probe(new ClusterFsmEvent.NodeAdded(NodeId.nodeId("n").unwrap(), List.of()), List.of());
            case "NodeGone" -> new Probe(new ClusterFsmEvent.NodeGone(NodeId.nodeId("n").unwrap(), List.of()), List.of());
            default -> throw new AssertionError("no probe for " + event);
        };
    }

    private static Probe appliedProbe(FakeServer carried) {
        var carriedH3 = new FakeServer();

        return new Probe(new AppHttpEvents.CertRotationApplied(Option.some(carried), Option.some(carriedH3), RouteTable.empty()),
                         List.of(carried, carriedH3));
    }

    private static AppHttpState stateOf(Class<? extends AppHttpState> type,
                                        AppHttpContext ctx,
                                        FakeServer held1,
                                        FakeServer held3) {
        if (type == AppHttpState.Stopped.class) return ctx.stopped();
        if (type == AppHttpState.Starting.class) return ctx.starting();
        if (type == AppHttpState.H1Only.class) return new AppHttpState.H1Only(ctx, held1);
        if (type == AppHttpState.H3Only.class) return new AppHttpState.H3Only(ctx, held3);
        if (type == AppHttpState.Dual.class) return new AppHttpState.Dual(ctx, held1, held3);
        if (type == AppHttpState.RouteReady.class) return new AppHttpState.RouteReady(ctx, Option.some(held1), Option.some(held3), RouteTable.empty());
        if (type == AppHttpState.CertRotating.class) return new AppHttpState.CertRotating(ctx, Option.some(held1), Option.some(held3), dummyBundle(), RouteTable.empty());
        if (type == AppHttpState.Quiesced.class) return new AppHttpState.Quiesced(ctx, Option.some(held1), Option.some(held3));
        throw new AssertionError("no fixture for " + type);
    }

    @Test
    void matrix_coversEveryStateAndEveryEvent() {
        var states = Arrays.stream(AppHttpState.class.getPermittedSubclasses()).map(Class::getName).toList();
        var rows = MATRIX.keySet().stream().map(Class::getName).toList();

        assertThat(rows).as("a state without a row (or a row for a removed state)").containsExactlyInAnyOrderElementsOf(states);
        MATRIX.forEach((state, cells) -> assertThat(cells).as("row of %s", state.getSimpleName()).hasSize(EVENTS.size()));

        var vocabulary = Stream.concat(Arrays.stream(AppHttpEvents.class.getClasses()), Arrays.stream(ClusterFsmEvent.class.getClasses()))
                               .map(Class::getName)
                               .distinct()
                               .toList();
        var covered = COVERED_EVENT_TYPES.stream().map(Class::getName).toList();

        assertThat(vocabulary).as("an event without a column").containsExactlyInAnyOrderElementsOf(covered);
        assertThat(EVENTS).hasSize(covered.size());
    }

    @Test
    void matrix_everyIgnoredCellStatesItsReason() {
        MATRIX.values()
              .stream()
              .flatMap(List::stream)
              .filter(cell -> cell.kind() == Kind.IGNORED)
              .forEach(cell -> assertThat(cell.reason()).isNotBlank());
    }

    @TestFactory
    List<DynamicTest> matrix_everyCellBehavesAsDeclared() {
        var tests = new ArrayList<DynamicTest>();

        MATRIX.forEach((state, cells) -> {
            for (int i = 0; i < cells.size(); i++) {
                var event = EVENTS.get(i);
                var cell = cells.get(i);

                tests.add(DynamicTest.dynamicTest(state.getSimpleName() + " x " + event, () -> check(state, event, cell)));
            }
        });
        return tests;
    }

    private static void check(Class<? extends AppHttpState> stateType, String eventName, Cell cell) {
        var held1 = new FakeServer();
        var held3 = new FakeServer();
        var probe = probe(eventName);
        Function<Fsm<AppHttpState, ClusterFsmEvent>, AppHttpState> initial = fsm -> stateOf(stateType, new AppHttpContext(fsm), held1, held3);
        var harness = FsmTestHarness.<AppHttpState, ClusterFsmEvent>harness("app-http-totality-test", initial);

        harness.dispatch(probe.event());

        var ignored = harness.ignored().stream().filter(i -> i.event() == probe.event()).count();
        var handled = harness.handled().stream().filter(h -> h.event() == probe.event()).count();

        assertThat(ignored).as("ignored count").isEqualTo(cell.kind() == Kind.IGNORED ? 1 : 0);
        assertThat(handled).as("handled count").isEqualTo(cell.kind() == Kind.HANDLED ? 1 : 0);
        if (cell.kind() == Kind.TRANSITION) {
            assertThat(harness.state()).isInstanceOf(cell.target());
        }
        if (cell.kind() == Kind.IGNORED) {
            assertThat(harness.state()).isInstanceOf(stateType);
        }
        if (isListenerReport(eventName)) {
            heldBy(stateType, held1, held3).forEach(server -> assertThat(server.isStopped()).as("held listener closed")
                                                                                          .isEqualTo(cell.retires()));
        }
        probe.carried().forEach(server -> assertThat(server.isStopped()).as("carried listener closed")
                                                                          .isEqualTo(cell.kind() == Kind.HANDLED));
    }

    private static boolean isListenerReport(String eventName) {
        return eventName.equals("H1Ready") || eventName.equals("H3Ready") || eventName.equals("CertRotationApplied");
    }

    /// The listeners the state under test holds (the fixture pair, narrowed to what each state's record carries).
    private static List<FakeServer> heldBy(Class<? extends AppHttpState> type, FakeServer held1, FakeServer held3) {
        if (type == AppHttpState.Stopped.class || type == AppHttpState.Starting.class) return List.of();
        if (type == AppHttpState.H1Only.class) return List.of(held1);
        if (type == AppHttpState.H3Only.class) return List.of(held3);
        return List.of(held1, held3);
    }

    private static CertificateBundle dummyBundle() {
        return CertificateBundle.certificateBundle(new byte[0],
                                                   new byte[0],
                                                   new byte[0],
                                                   Instant.now().plus(1, ChronoUnit.DAYS));
    }
}
