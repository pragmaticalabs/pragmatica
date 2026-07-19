package org.pragmatica.jbct.derive.pipeline;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.model.DomainShape;
import org.pragmatica.jbct.derive.model.QuestionId;
import org.pragmatica.jbct.derive.model.ScopeKind;
import org.pragmatica.jbct.derive.pipeline.Press.PressResult;
import org.pragmatica.jbct.derive.result.DecisionRecord;
import org.pragmatica.jbct.derive.result.Halt;
import org.pragmatica.jbct.derive.result.JudgmentPoint;
import org.pragmatica.jbct.derive.result.Pressure;
import org.pragmatica.jbct.derive.result.RecoveryAssignment;
import org.pragmatica.jbct.derive.result.Strike;
import org.pragmatica.jbct.derive.result.VectorPosition;
import org.pragmatica.jbct.derive.result.VectorPosition.Resolution;
import org.pragmatica.lang.Option;

/// Resolve — the derivation's judgment boundary (SPEC.md §4). It applies the mechanical selection
/// rules where the book is mechanical, and EMITS a [JudgmentPoint] everywhere the book names a
/// judgment (SPEC.md §1; the golden-asserted hard constraint):
///
///   - **Forced moves** — a discrete containing mechanism the engine can pick without a ceiling: a
///     burst becomes event-based (a queue), an audit demand becomes current-state + audit-log.
///   - **Scope-splits (mechanical, F20/F24)** — a mandate's scope is excluded, and a *secondary*
///     path scope (a path other than the one carrying the primary latency budget) whose shape
///     diverges from the system baseline forces a split at that boundary, paying the four split
///     prices. What each split component *becomes* (its model/product) is a [JudgmentPoint].
///   - **Deferred moves** — a magnitude/ceiling pressure (volume, latency, contention). The engine
///     records the direction, then STOPS: the rung the axis climbs to is judgment (F12/F13), and a
///     shard needs a partition key it will not guess.
///   - **Recovery** — per effectful operation from its domain shape, design-out checked first; a
///     genuine tie is emitted, never picked.
///   - **Conflict rule** — the same scope opposed with no decomposition left is a CONTRADICTION
///     halt with a priced renegotiation-menu skeleton.
public sealed interface Resolve permits Resolve.unused {
    record unused() implements Resolve {}

    /// The four named prices a scope split always pays (WORKSHEET; the boundary-cost ledger entry).
    List<String> SPLIT_PRICES = List.of("a contract", "a consistency decay", "a translation seam", "an operational seam");

    /// The full resolve output.
    record ResolveResult(List<VectorPosition> vector,
                         List<DecisionRecord> decisions,
                         List<RecoveryAssignment> recovery,
                         List<Halt> halts,
                         List<JudgmentPoint> judgmentPoints) {
        public ResolveResult {
            vector = List.copyOf(vector);
            decisions = List.copyOf(decisions);
            recovery = List.copyOf(recovery);
            halts = List.copyOf(halts);
            judgmentPoints = List.copyOf(judgmentPoints);
        }
    }

    /// One axis's contribution: its vector position, an optional decision record, the discrete
    /// value tokens it forces (for exact strike-collision detection), and any halts or judgment
    /// points the resolution emitted.
    record AxisOutcome(VectorPosition position,
                       Option<DecisionRecord> decision,
                       List<String> forcedValues,
                       List<Halt> halts,
                       List<JudgmentPoint> judgments) {}

    /// Resolve a sheet's pruned, pressed state into a derived vector and its provenance.
    static ResolveResult resolve(AnswerSheet sheet, List<Strike> strikes, PressResult press) {
        var pressures = press.pressures();
        var primary = primaryPath(sheet);
        var splitSources = pressures.stream().filter(pressure -> isSplitSource(pressure, primary)).toList();
        var outcomes = List.of(substrate(pressures),
                               state(pressures),
                               topology(splitSources),
                               readWrite(pressures),
                               persistence(pressures),
                               recoveryAxis(sheet));

        return assemble(sheet, strikes, outcomes);
    }

    private static ResolveResult assemble(AnswerSheet sheet, List<Strike> strikes, List<AxisOutcome> outcomes) {
        var vector = outcomes.stream().map(AxisOutcome::position).toList();
        var decisions = outcomes.stream().flatMap(outcome -> outcome.decision().stream()).toList();
        var halts = Stream.concat(outcomes.stream().flatMap(outcome -> outcome.halts().stream()),
                                  strikeCollisions(outcomes, strikes).stream())
                          .toList();
        var judgments = Stream.of(outcomes.stream().flatMap(outcome -> outcome.judgments().stream()).toList(),
                                  recoveryJudgments(sheet),
                                  targetJudgments(sheet),
                                  constraintJudgments(sheet))
                              .flatMap(List::stream)
                              .toList();

        return new ResolveResult(vector, decisions, recovery(sheet), halts, judgments);
    }

    // ---- strike collision: a mandate forbids the very value a demand forces (exact token match) ----

    private static List<Halt> strikeCollisions(List<AxisOutcome> outcomes, List<Strike> strikes) {
        return outcomes.stream().flatMap(outcome -> collisionsFor(outcome, strikes).stream()).toList();
    }

    private static List<Halt> collisionsFor(AxisOutcome outcome, List<Strike> strikes) {
        return strikes.stream()
                      .filter(strike -> strikeHits(outcome, strike))
                      .map(strike -> Halt.of(Halt.Kind.CONTRADICTION,
                                             "a mandate strikes " + strike.display() + " but the derivation forces it on "
                                             + outcome.position().axis().label()))
                      .toList();
    }

    private static boolean strikeHits(AxisOutcome outcome, Strike strike) {
        return strike.axis().map(axis -> axis == outcome.position().axis() && outcome.forcedValues().contains(strike.value())).or(false);
    }

    // ---- SUBSTRATE: burst forces event-based; a per-scope mix is a split ----

    private static AxisOutcome substrate(List<Pressure> pressures) {
        var burst = moving(pressures, Axis.SUBSTRATE);

        return burst.isEmpty()
               ? nullKept(Axis.SUBSTRATE)
               : substrateMoved(burst);
    }

    private static AxisOutcome substrateMoved(List<Pressure> burst) {
        var value = "direct · event-based at " + String.join(", ", scopeNames(burst));
        var decision = new DecisionRecord(Axis.SUBSTRATE,
                                          value,
                                          citations(burst),
                                          "a queue absorbs the peak (burst-absorption)",
                                          splitCost(),
                                          "a synchronous SLA is added to the intake path");

        return forced(Axis.SUBSTRATE, value, burst, decision, List.of("event-based"));
    }

    // ---- STATE: replay forces event-sourced, audit forces audit-log; both same-scope contradict ----

    private static AxisOutcome state(List<Pressure> pressures) {
        var replay = mechanism(pressures, "replay-log");
        var audit = mechanism(pressures, "audit-log");

        if (!replay.isEmpty() && !audit.isEmpty() && shareScope(replay, audit)) {
            return stateContradiction(replay, audit);
        }
        if (!replay.isEmpty()) {
            return stateForced("event-sourced", "replay is demanded (F3)", replay, List.of("event-sourced"));
        }

        return audit.isEmpty()
               ? nullKept(Axis.STATE)
               : stateForced("current-state + audit-log-as-data", "audit is not replay (F3)", audit, List.of("audit-log-as-data", "audit-log"));
    }

    private static AxisOutcome stateForced(String value, String via, List<Pressure> members, List<String> tokens) {
        var decision = new DecisionRecord(Axis.STATE,
                                          value,
                                          citations(members),
                                          via,
                                          "one standing log",
                                          "a reconstruct-as-of-a-past-version demand appears");

        return forced(Axis.STATE, value, members, decision, tokens);
    }

    private static AxisOutcome stateContradiction(List<Pressure> replay, List<Pressure> audit) {
        var members = concat(audit, replay);
        var menu = List.of("current-state + audit-log-as-data — priced: one standing log; re-enters the derivation",
                           "event-sourced — priced: full event history + projection rebuild; re-enters the derivation");
        var halt = Halt.contradiction("STATE pressed toward both audit-log and event-sourced at one scope — decompose or choose",
                                      menu);
        var judgment = JudgmentPoint.of(JudgmentPoint.Kind.CONTRADICTION_CHOICE,
                                        Axis.STATE.label(),
                                        "audit (current-state + log) versus replay (event-sourced) at the same scope — the choice is judgment");
        var position = new VectorPosition(Axis.STATE, "CONTRADICTION — see renegotiation menu", citeStrings(members), Resolution.DEFERRED);

        return new AxisOutcome(position, Option.none(), List.of(), List.of(halt), List.of(judgment));
    }

    // ---- TOPOLOGY: scope-exclusion + own-shape-diverges path splits; the shape is judgment (F21) ----

    private static Option<String> primaryPath(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(AnswerRow::isAnswered)
                    .filter(row -> row.question() == QuestionId.Q1)
                    .filter(Resolve::isSystemClock)
                    .filter(row -> row.scope().kind() == ScopeKind.PATH)
                    .map(row -> row.scope().display())
                    .findFirst()
                    .map(Option::some)
                    .orElseGet(Option::none);
    }

    private static boolean isSystemClock(AnswerRow row) {
        return row.shape().map(String::toLowerCase).filter("system-clock"::equals).isPresent();
    }

    private static boolean isSplitSource(Pressure pressure, Option<String> primary) {
        return pressure.mode() == Pressure.Mode.EXCLUDE || isSecondaryPath(pressure, primary);
    }

    private static boolean isSecondaryPath(Pressure pressure, Option<String> primary) {
        return pressure.mode() == Pressure.Mode.PRESS
            && pressure.citing().stream().anyMatch(row -> isSecondaryPathScope(row, primary));
    }

    private static boolean isSecondaryPathScope(AnswerRow row, Option<String> primary) {
        return row.scope().kind() == ScopeKind.PATH
            && !primary.map(path -> path.equals(row.scope().display())).or(false);
    }

    private static AxisOutcome topology(List<Pressure> splitSources) {
        return splitSources.isEmpty()
               ? nullKept(Axis.TOPOLOGY)
               : topologySplit(splitSources);
    }

    private static AxisOutcome topologySplit(List<Pressure> splitSources) {
        var scopes = scopeNames(splitSources);
        var value = "single deployable + split: " + String.join(", ", scopes);
        var decision = new DecisionRecord(Axis.TOPOLOGY,
                                          value,
                                          citations(splitSources),
                                          "scope-exclusion / own-shape-diverges split (F20/F24): the narrowest boundary between pressures",
                                          splitCost(),
                                          "the split component needs to share state with the core");
        var judgments = scopes.stream().map(Resolve::topologyShapeJudgment).toList();

        return new AxisOutcome(new VectorPosition(Axis.TOPOLOGY, value, citeStrings(splitSources), Resolution.FORCED),
                               Option.some(decision),
                               List.of(),
                               List.of(),
                               judgments);
    }

    private static JudgmentPoint topologyShapeJudgment(String scope) {
        return JudgmentPoint.of(JudgmentPoint.Kind.TOPOLOGY_SHAPE,
                                scope,
                                "the split boundary is mechanical (F24); what the '" + scope + "' component becomes — its model/product — is judgment");
    }

    // ---- READ_WRITE: latency/contention recorded; the rung is judgment (F12/F13) ----

    private static AxisOutcome readWrite(List<Pressure> pressures) {
        var moving = moving(pressures, Axis.READ_WRITE);

        return moving.isEmpty()
               ? nullKept(Axis.READ_WRITE)
               : deferred(Axis.READ_WRITE,
                          "unified (read-scaling pressure recorded; rung pending judgment)",
                          moving,
                          "the read-path containment chain: cache -> coalescing -> replicas -> separated",
                          "a floor proves the primary cannot contain the read",
                          readWriteJudgments(moving));
    }

    private static List<JudgmentPoint> readWriteJudgments(List<Pressure> moving) {
        return List.of(JudgmentPoint.of(JudgmentPoint.Kind.RUNG_DEPTH,
                                        Axis.READ_WRITE.label(),
                                        "how far up the read-path chain (cache / coalescing / replicas / separated) — the ceiling is judgment (F12/F13): " + citations(moving)));
    }

    // ---- PERSISTENCE: volume/residency recorded; rung + partition key are judgment ----

    private static AxisOutcome persistence(List<Pressure> pressures) {
        var moving = moving(pressures, Axis.PERSISTENCE);

        return moving.isEmpty()
               ? nullKept(Axis.PERSISTENCE)
               : deferred(Axis.PERSISTENCE,
                          "single shared (scaling pressure recorded; rung + partition key pending judgment)",
                          moving,
                          "the store-scaling chain: hardware -> per-component -> sharded -> distributed-shared",
                          "a floor proves one store's economics are exceeded (F12 ceiling)",
                          persistenceJudgments(moving));
    }

    private static List<JudgmentPoint> persistenceJudgments(List<Pressure> moving) {
        var rungDepth = JudgmentPoint.of(JudgmentPoint.Kind.RUNG_DEPTH,
                                         Axis.PERSISTENCE.label(),
                                         "how far along the store-scaling chain — the F12 ceiling is judgment: " + citations(moving));

        return hasMechanism(moving, "volume-containment")
               ? List.of(rungDepth,
                         JudgmentPoint.of(JudgmentPoint.Kind.PARTITION_KEY,
                                          Axis.PERSISTENCE.label(),
                                          "sharding needs a partition key — a domain gift the engine will not guess (Q9)"))
               : List.of(rungDepth);
    }

    // ---- RECOVERY: per effectful operation, design-out first; ties are judgment ----

    private static AxisOutcome recoveryAxis(AnswerSheet sheet) {
        var value = sheet.domainShapes().isEmpty()
                    ? "—"
                    : "per operation (see recovery assignments)";

        return new AxisOutcome(new VectorPosition(Axis.RECOVERY, value, List.of(), Resolution.FORCED),
                               Option.none(),
                               List.of(),
                               List.of(),
                               List.of());
    }

    private static List<RecoveryAssignment> recovery(AnswerSheet sheet) {
        return sheet.domainShapes().stream().map(Resolve::recoveryFor).toList();
    }

    private static RecoveryAssignment recoveryFor(DomainShape shape) {
        if (reshapesToSafe(shape)) {
            return RecoveryAssignment.of(shape.operation(),
                                         RecoveryAssignment.RecoveryClass.DESIGN_OUT,
                                         "reshapeable " + shape.reshapeable() + " — the failure is designed out (checked first)");
        }

        var ber = hasDefinedInverse(shape);
        var fer = shape.decays();

        if (ber && fer) {
            return RecoveryAssignment.tie(shape.operation(),
                                          "a defined inverse ('" + shape.inverse() + "') AND decay — BER versus FER is a judgment");
        }
        if (ber) {
            return RecoveryAssignment.of(shape.operation(),
                                         RecoveryAssignment.RecoveryClass.BER,
                                         "a defined inverse ('" + shape.inverse() + "') — per-case, residuals remain");
        }

        return fer
               ? RecoveryAssignment.of(shape.operation(),
                                       RecoveryAssignment.RecoveryClass.FER,
                                       "decays — a bounded, visible degraded window")
               : RecoveryAssignment.of(shape.operation(),
                                       RecoveryAssignment.RecoveryClass.DESIGN_OUT,
                                       "no inverse and does not decay — structurally nothing to recover");
    }

    private static List<JudgmentPoint> recoveryJudgments(AnswerSheet sheet) {
        return recovery(sheet).stream()
                              .filter(RecoveryAssignment::isTie)
                              .map(assignment -> JudgmentPoint.of(JudgmentPoint.Kind.RECOVERY_TIE,
                                                                  assignment.operation(),
                                                                  assignment.rationale()))
                              .toList();
    }

    private static boolean reshapesToSafe(DomainShape shape) {
        return shape.reshapeable().stream().map(String::toLowerCase).anyMatch(Resolve::isSafeReshape);
    }

    /// Safe reshapes that design the failure out. `status-transition` is the schema-v0.2 vocabulary
    /// for append-shaped lifecycle operations (incorporate / dissolve / restore); schema v0.1 sheets
    /// that write `none` instead fall through to BER — a documented schema gap, not engine judgment.
    private static boolean isSafeReshape(String reshape) {
        return reshape.equals("idempotent")
            || reshape.equals("commutative")
            || reshape.equals("append-only")
            || reshape.equals("status-transition");
    }

    private static boolean hasDefinedInverse(DomainShape shape) {
        return !isNone(shape.inverse()) && !shape.inverse().isBlank();
    }

    private static boolean isNone(String inverse) {
        return inverse.trim().equalsIgnoreCase("none");
    }

    // ---- judgment points that read the sheet directly ----

    private static List<JudgmentPoint> targetJudgments(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(row -> !row.isAnswered())
                    .filter(Resolve::isBudgetQuestion)
                    .map(Resolve::targetJudgment)
                    .toList();
    }

    private static boolean isBudgetQuestion(AnswerRow row) {
        return row.question() == QuestionId.Q1 || row.question() == QuestionId.Q2;
    }

    private static JudgmentPoint targetJudgment(AnswerRow row) {
        return JudgmentPoint.of(JudgmentPoint.Kind.TARGET_SETTING,
                                row.cite(),
                                "the budget is UNKNOWN — setting it is judgment, never guessed (ch. 7 register)");
    }

    private static List<JudgmentPoint> constraintJudgments(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(AnswerRow::isAnswered)
                    .filter(Resolve::isSystemMandateWithoutStrike)
                    .map(Resolve::constraintJudgment)
                    .toList();
    }

    private static boolean isSystemMandateWithoutStrike(AnswerRow row) {
        return row.question() == QuestionId.Q6
            && row.kind().map(String::toLowerCase).filter("mandate"::equals).isPresent()
            && row.strikes().isEmpty()
            && row.scope().isSystem();
    }

    private static JudgmentPoint constraintJudgment(AnswerRow row) {
        return JudgmentPoint.of(JudgmentPoint.Kind.CONSTRAINT_SHAPE,
                                row.cite(),
                                "a system-scoped mandate names no strike and no narrower scope — how it constrains the vector is judgment");
    }

    // ---- shared helpers ----

    private static List<Pressure> moving(List<Pressure> pressures, Axis axis) {
        return pressures.stream()
                        .filter(Pressure::moves)
                        .filter(pressure -> pressure.axis() == axis)
                        .toList();
    }

    private static List<Pressure> mechanism(List<Pressure> pressures, String mechanism) {
        return pressures.stream()
                        .filter(pressure -> pressure.mechanism().equals(mechanism))
                        .toList();
    }

    private static boolean hasMechanism(List<Pressure> pressures, String mechanism) {
        return pressures.stream().anyMatch(pressure -> pressure.mechanism().equals(mechanism));
    }

    private static boolean shareScope(List<Pressure> left, List<Pressure> right) {
        return scopeNames(left).stream().anyMatch(scopeNames(right)::contains);
    }

    private static List<String> scopeNames(List<Pressure> pressures) {
        return pressures.stream()
                        .flatMap(pressure -> pressure.citing().stream())
                        .map(row -> row.scope().display())
                        .distinct()
                        .toList();
    }

    private static List<String> citeStrings(List<Pressure> pressures) {
        return pressures.stream().flatMap(pressure -> pressure.citing().stream()).map(AnswerRow::cite).distinct().toList();
    }

    private static String citations(List<Pressure> pressures) {
        return String.join(", ", citeStrings(pressures));
    }

    private static String splitCost() {
        return "the boundary pays " + String.join(", ", SPLIT_PRICES);
    }

    private static List<Pressure> concat(List<Pressure> left, List<Pressure> right) {
        return Stream.concat(left.stream(), right.stream()).toList();
    }

    private static AxisOutcome nullKept(Axis axis) {
        return new AxisOutcome(new VectorPosition(axis, axis.nullValue(), List.of(), Resolution.NULL_KEPT),
                               Option.none(),
                               List.of(),
                               List.of(),
                               List.of());
    }

    private static AxisOutcome forced(Axis axis, String value, List<Pressure> members, DecisionRecord decision, List<String> forcedValues) {
        return new AxisOutcome(new VectorPosition(axis, value, citeStrings(members), Resolution.FORCED),
                               Option.some(decision),
                               forcedValues,
                               List.of(),
                               List.of());
    }

    private static AxisOutcome deferred(Axis axis,
                                        String value,
                                        List<Pressure> members,
                                        String via,
                                        String revisitWhen,
                                        List<JudgmentPoint> judgments) {
        var decision = new DecisionRecord(axis, value, citations(members), via, "the chosen rung's standing cost", revisitWhen);

        return new AxisOutcome(new VectorPosition(axis, value, citeStrings(members), Resolution.DEFERRED),
                               Option.some(decision),
                               List.of(),
                               List.of(),
                               judgments);
    }
}
