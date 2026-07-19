package org.pragmatica.jbct.derive.pipeline;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.result.Combination;
import org.pragmatica.jbct.derive.result.Pressure;
import org.pragmatica.lang.Option;

/// Press — the pressure pass (SPEC.md §4). Each answered row is read through the Card-3 triage
/// table: its `shape` (Q1/Q5) or `kind` (Q6) selects the axis it presses and the mechanism family,
/// mechanically. A row the table maps to no move is recorded *inert* — a result, not a discard.
///
/// This is the engine's mechanizable core and the boundary of its authority: it derives WHICH axis
/// an answer presses and IN WHAT DIRECTION. It does NOT decide how far up a rung ladder the axis
/// then travels — that ceiling is judgment (SPEC.md §1), left to [Resolve] to emit.
///
/// The combination check (F24/F26) is first-class: after singles, moving pressures from two or more
/// *different* questions converging on one axis are surfaced as [Combination]s.
public sealed interface Press permits Press.unused {
    record unused() implements Press {}

    /// The pressure matrix (inert rows included) and the combination checks over it.
    record PressResult(List<Pressure> pressures, List<Combination> combinations) {
        public PressResult {
            pressures = List.copyOf(pressures);
            combinations = List.copyOf(combinations);
        }
    }

    /// Run the pressure pass over a sheet.
    static PressResult press(AnswerSheet sheet) {
        var pressures = sheet.rows()
                             .stream()
                             .filter(AnswerRow::isAnswered)
                             .flatMap(Press::pressuresOf)
                             .toList();

        return new PressResult(pressures, combinations(pressures));
    }

    // ---- per-row pressure (Card 3 triage table) ----

    private static Stream<Pressure> pressuresOf(AnswerRow row) {
        return row.contained()
               ? containedPressure(row)
               : byQuestion(row);
    }

    private static Stream<Pressure> byQuestion(AnswerRow row) {
        return switch (row.question()) {
            case Q1 -> shaped(row, Press::timeShape);
            case Q5 -> shaped(row, Press::loadShape);
            case Q6 -> kinded(row);
            default -> Stream.of();
        };
    }

    private static Stream<Pressure> containedPressure(AnswerRow row) {
        return row.shape()
                  .map(String::toLowerCase)
                  .map(shape -> Stream.of(Pressure.inert(loadAxis(shape),
                                                         "F18 thin-tier: the author states this load is contained by an existing tier — axis-invisible",
                                                         row)))
                  .or(Stream.of());
    }

    private static Axis loadAxis(String shape) {
        return switch (shape) {
            case "volume" -> Axis.PERSISTENCE;
            case "contention" -> Axis.READ_WRITE;
            case "burst", "deadline" -> Axis.SUBSTRATE;
            default -> Axis.READ_WRITE;
        };
    }

    private static Stream<Pressure> shaped(AnswerRow row, ShapeRule rule) {
        return row.shape()
                  .map(String::toLowerCase)
                  .map(shape -> rule.apply(shape, row))
                  .or(Stream.of());
    }

    private static Stream<Pressure> timeShape(String shape, AnswerRow row) {
        return switch (shape) {
            case "system-clock" -> Stream.of(Pressure.press(Axis.READ_WRITE,
                                                            "toward read-path containment (cache -> coalescing -> replicas -> separated)",
                                                            "latency-containment",
                                                            row));
            case "requester-clock" -> Stream.of(Pressure.inert(Axis.READ_WRITE,
                                                              "requester-clock: the requester waits — a business-process deadline, not a system-latency target (F22)",
                                                              row));
            default -> Stream.of();
        };
    }

    private static Stream<Pressure> loadShape(String shape, AnswerRow row) {
        return switch (shape) {
            case "volume" -> Stream.of(Pressure.press(Axis.PERSISTENCE,
                                                     "toward store scaling (hardware -> cache -> replicas -> shard)",
                                                     "volume-containment",
                                                     row));
            case "contention" -> Stream.of(Pressure.press(Axis.READ_WRITE,
                                                         "toward contention response: coalesce (read) or admit (write) — never more sharding (F15)",
                                                         "contention-containment",
                                                         row));
            case "burst", "deadline" -> Stream.of(Pressure.press(Axis.SUBSTRATE,
                                                                "toward event-based (a queue absorbs the peak)",
                                                                "burst-absorption",
                                                                row));
            default -> Stream.of();
        };
    }

    private static Stream<Pressure> kinded(AnswerRow row) {
        return row.kind()
                  .map(String::toLowerCase)
                  .map(kind -> constraintKind(kind, row))
                  .or(Stream.of());
    }

    private static Stream<Pressure> constraintKind(String kind, AnswerRow row) {
        return switch (kind) {
            case "audit" -> Stream.of(Pressure.press(Axis.STATE,
                                                    "toward current-state + audit-log-as-data (audit is not replay, F3)",
                                                    "audit-log",
                                                    row));
            case "replay" -> Stream.of(Pressure.press(Axis.STATE,
                                                     "toward event-sourced (replay is demanded)",
                                                     "replay-log",
                                                     row));
            case "residency" -> Stream.of(Pressure.press(Axis.PERSISTENCE,
                                                        "toward per-region / distributed (data residency)",
                                                        "residency-partition",
                                                        row));
            case "mandate" -> mandatePressure(row);
            default -> Stream.of();
        };
    }

    private static Stream<Pressure> mandatePressure(AnswerRow row) {
        return row.strikes().isEmpty() && !row.scope().isSystem()
               ? Stream.of(Pressure.exclude(Axis.TOPOLOGY,
                                            "split '" + row.scope().display() + "' out so the mandate never reaches the core (F20)",
                                            row))
               : Stream.of();
    }

    // ---- combination check (F24/F26) ----

    private static List<Combination> combinations(List<Pressure> pressures) {
        return Stream.of(Axis.values())
                     .map(axis -> combinationFor(axis, pressures))
                     .flatMap(Option::stream)
                     .toList();
    }

    private static Option<Combination> combinationFor(Axis axis, List<Pressure> pressures) {
        var members = pressures.stream()
                               .filter(Pressure::moves)
                               .filter(pressure -> pressure.axis() == axis)
                               .toList();

        return diverges(members)
               ? Option.some(new Combination(axis, members, combinationNote(axis)))
               : Option.none();
    }

    /// The own-shape-diverges trigger (F24/F26): two or more questions converge on the axis, OR one
    /// question presses it from two or more scopes (intra-question shape divergence counts).
    private static boolean diverges(List<Pressure> members) {
        return distinctQuestions(members) >= 2 || distinctScopes(members) >= 2;
    }

    private static long distinctQuestions(List<Pressure> members) {
        return members.stream()
                      .flatMap(pressure -> pressure.citing().stream())
                      .map(AnswerRow::question)
                      .distinct()
                      .count();
    }

    private static long distinctScopes(List<Pressure> members) {
        return members.stream()
                      .flatMap(pressure -> pressure.citing().stream())
                      .map(row -> row.scope().display())
                      .distinct()
                      .count();
    }

    private static String combinationNote(Axis axis) {
        return "two or more questions converge on " + axis.label()
             + " — evaluate the convergence before the singles (the 'own shape diverges' trigger, F24/F26)";
    }

    /// A rule mapping a lowercased shape and its row to zero or more pressures.
    interface ShapeRule {
        Stream<Pressure> apply(String shape, AnswerRow row);
    }
}
