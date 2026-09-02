package org.pragmatica.jbct.derive.pipeline;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.model.Floor;
import org.pragmatica.jbct.derive.model.QuestionId;
import org.pragmatica.jbct.derive.model.Scope;
import org.pragmatica.jbct.derive.result.Halt;
import org.pragmatica.jbct.derive.result.RecoveryAssignment;
import org.pragmatica.jbct.derive.result.VectorPosition;
import org.pragmatica.jbct.derive.result.VectorPosition.Resolution;
import org.pragmatica.jbct.derive.result.Verification;
import org.pragmatica.lang.Option;

/// Verify — the exit gate, arithmetic only (SPEC.md §4). It runs the five budget rules against the
/// user-supplied `[[floors]]`; a floor the sheet did not supply produces an explicit `UNVERIFIED`,
/// never a silently assumed default. Rules whose inputs schema v0.1 does not carry as structured
/// numbers (tail percentiles, per-component availability, a machine cost envelope) report
/// `UNVERIFIED` with the reason — an honest gap, not a pass.
///
/// The one rule that runs on today's schema is the latency decomposition: hop floors summed down a
/// path against that path's stated budget. A floor that exceeds its budget is a
/// `FLOORS_EXCEED_BUDGET` halt.
public sealed interface Verify permits Verify.unused {
    record unused() implements Verify {}

    Pattern MILLIS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*ms");

    /// The verification lines and any halts they raised.
    record VerifyResult(List<Verification> verifications, List<Halt> halts) {
        public VerifyResult {
            verifications = List.copyOf(verifications);
            halts = List.copyOf(halts);
        }
    }

    /// Run the exit-gate arithmetic against the derived vector and the sheet's floors.
    static VerifyResult verify(AnswerSheet sheet, List<VectorPosition> vector, List<RecoveryAssignment> recovery) {
        var verifications = allRules(sheet, vector, recovery);
        var halts = verifications.stream()
                                 .filter(line -> line.status() == Verification.Status.HALT)
                                 .map(Verify::toHalt)
                                 .toList();

        return new VerifyResult(verifications, halts);
    }

    private static List<Verification> allRules(AnswerSheet sheet, List<VectorPosition> vector, List<RecoveryAssignment> recovery) {
        return concat(latency(sheet),
                      List.of(tailComposition(),
                              envelopeComposition(),
                              availabilityMultiplication(),
                              mechanismBill(sheet, vector, recovery)));
    }

    // ---- Rule 1: latency decomposition down the critical path (the one rule schema v0.1 runs) ----

    private static List<Verification> latency(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(AnswerRow::isAnswered)
                    .filter(row -> row.question() == QuestionId.Q1)
                    .filter(Verify::isSystemClock)
                    .map(row -> latencyFor(sheet, row))
                    .toList();
    }

    private static boolean isSystemClock(AnswerRow row) {
        return row.shape().map(String::toLowerCase).filter("system-clock"::equals).isPresent();
    }

    private static Verification latencyFor(AnswerSheet sheet, AnswerRow row) {
        return floorFor(sheet, row.scope())
            .map(floor -> latencyWithFloor(row, floor))
            .or(Verification.unverified("latency decomposition",
                                        row.scope().display(),
                                        "floor missing — no [[floors]] for this path (never a default)"));
    }

    private static Verification latencyWithFloor(AnswerRow row, Floor floor) {
        return Option.all(floorMillis(floor), targetMillis(row.statement()))
                     .map((floorMs, targetMs) -> compareLatency(row.scope(), floorMs, targetMs))
                     .or(Verification.unverified("latency decomposition",
                                                 row.scope().display(),
                                                 "floor hops or the stated target are not numerically resolvable"));
    }

    private static Verification compareLatency(Scope scope, long floorMs, double targetMs) {
        return floorMs <= targetMs
               ? Verification.verified("latency decomposition",
                                       scope.display(),
                                       floorMs + "ms floor <= " + targetMs + "ms target (headroom " + (targetMs - floorMs) + "ms)")
               : Verification.halt("latency decomposition",
                                   scope.display(),
                                   floorMs + "ms floor exceeds the " + targetMs + "ms target");
    }

    private static Option<Floor> floorFor(AnswerSheet sheet, Scope scope) {
        return sheet.floors()
                    .stream()
                    .filter(floor -> floor.path().display().equals(scope.display()))
                    .findFirst()
                    .map(Option::some)
                    .orElseGet(Option::none);
    }

    private static Option<Long> floorMillis(Floor floor) {
        return floor.hops().stream().anyMatch(hop -> hop.p50Ms().isEmpty())
               ? Option.none()
               : Option.some(sumHops(floor));
    }

    private static long sumHops(Floor floor) {
        return floor.hops().stream().mapToLong(hop -> hop.p50Ms().or(0L)).sum();
    }

    private static Option<Double> targetMillis(String statement) {
        var matcher = MILLIS.matcher(statement);

        return matcher.find()
               ? Option.some(Double.parseDouble(matcher.group(1)))
               : Option.none();
    }

    // ---- Rules 2-4: structurally honest gaps under schema v0.1 ----

    private static Verification tailComposition() {
        return Verification.unverified("tail composition",
                                       "system",
                                       "slow-fraction-in-series and fan-out harvest need per-hop percentiles not structured in schema v0.1");
    }

    private static Verification envelopeComposition() {
        return Verification.unverified("envelope composition",
                                       "system",
                                       "correlated-peak composition needs per-component load figures not structured in schema v0.1");
    }

    private static Verification availabilityMultiplication() {
        return Verification.unverified("availability multiplication",
                                       "system",
                                       "component availabilities and named earned-independence evidence are not structured in schema v0.1");
    }

    // ---- Rule 5: the mechanism bill against the Q8 envelope ----

    private static Verification mechanismBill(AnswerSheet sheet, List<VectorPosition> vector, List<RecoveryAssignment> recovery) {
        return Verification.unverified("mechanism bill (Rule 5)",
                                       "system",
                                       mechanismCount(vector, recovery) + " standing mechanism(s) derived; Q8 envelope is prose ("
                                       + q8Envelope(sheet) + ") — the bill-vs-envelope comparison is judgment");
    }

    private static long mechanismCount(List<VectorPosition> vector, List<RecoveryAssignment> recovery) {
        return axisMoves(vector) + standingRecovery(recovery);
    }

    private static long axisMoves(List<VectorPosition> vector) {
        return vector.stream()
                     .filter(position -> position.axis() != Axis.RECOVERY)
                     .filter(position -> position.resolution() != Resolution.NULL_KEPT)
                     .count();
    }

    private static long standingRecovery(List<RecoveryAssignment> recovery) {
        return recovery.stream().filter(Verify::isStanding).count();
    }

    private static boolean isStanding(RecoveryAssignment assignment) {
        return assignment.recoveryClass() == RecoveryAssignment.RecoveryClass.FER
            || assignment.recoveryClass() == RecoveryAssignment.RecoveryClass.BER;
    }

    private static String q8Envelope(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(row -> row.question() == QuestionId.Q8)
                    .filter(AnswerRow::isAnswered)
                    .map(AnswerRow::statement)
                    .findFirst()
                    .map(Verify::truncate)
                    .orElse("UNKNOWN");
    }

    private static String truncate(String text) {
        return text.length() <= 60
               ? text
               : text.substring(0, 57) + "...";
    }

    // ---- shared ----

    private static Halt toHalt(Verification line) {
        return Halt.of(Halt.Kind.FLOORS_EXCEED_BUDGET, line.rule() + " @ " + line.scope() + ": " + line.detail());
    }

    private static List<Verification> concat(List<Verification> head, List<Verification> tail) {
        return Stream.concat(head.stream(), tail.stream()).toList();
    }
}
