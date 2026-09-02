package org.pragmatica.jbct.derive.pipeline;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.Inline;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.pipeline.Resolve.ResolveResult;
import org.pragmatica.jbct.derive.result.Halt;
import org.pragmatica.jbct.derive.result.JudgmentPoint;
import org.pragmatica.jbct.derive.result.RecoveryAssignment.RecoveryClass;
import org.pragmatica.jbct.derive.result.VectorPosition;
import org.pragmatica.jbct.derive.result.VectorPosition.Resolution;

import static org.assertj.core.api.Assertions.assertThat;

/// Resolve stage: recovery from domain shape (design-out first, ties emitted), discrete forced
/// moves (event-based, audit-log, scope-exclusion), ceiling pressures deferred as judgment points,
/// and the conflict rule's contradiction halt.
class ResolveTest {
    private static final String HEAD = """
        schema_version = "0.1"
        [meta]
        system = "t"
        era    = "e"
        mode   = "greenfield"
        """;

    @Test
    void resolve_designsOut_forAppendOnlyOperation() {
        var result = resolve(HEAD + domainShape("accept", "none", false, "\"append-only\""));

        assertThat(recoveryClass(result, "accept")).isEqualTo(RecoveryClass.DESIGN_OUT);
    }

    @Test
    void resolve_choosesBer_forDefinedInverseNoReshape() {
        var result = resolve(HEAD + domainShape("charge", "refund or void the payment", false, "\"none\""));

        assertThat(recoveryClass(result, "charge")).isEqualTo(RecoveryClass.BER);
    }

    @Test
    void resolve_emitsTie_forDefinedInverseAndDecay() {
        var result = resolve(HEAD + domainShape("hold", "release the hold", true, "\"none\""));

        assertThat(recoveryClass(result, "hold")).isEqualTo(RecoveryClass.TIE);
        assertThat(hasJudgment(result, JudgmentPoint.Kind.RECOVERY_TIE)).isTrue();
    }

    @Test
    void resolve_forcesEventBased_forBurst() {
        var result = resolve(HEAD + q5("operation:accept", "modal deadline peak", "deadline"));
        var substrate = position(result, Axis.SUBSTRATE);

        assertThat(substrate.resolution()).isEqualTo(Resolution.FORCED);
        assertThat(substrate.value()).contains("event-based");
    }

    @Test
    void resolve_forcesAuditLog_forAuditKind_notEventSourced() {
        var result = resolve(HEAD + q6("data-class:filings", "FOI duty", "audit", ""));
        var state = position(result, Axis.STATE);

        assertThat(state.resolution()).isEqualTo(Resolution.FORCED);
        assertThat(state.value()).contains("audit-log-as-data");
        assertThat(state.value()).doesNotContain("event-sourced");
    }

    @Test
    void resolve_splitsScope_forMandateExclusion_withFourPrices() {
        var result = resolve(HEAD + q6("data-class:card-data", "PCI — never sees card data", "mandate", ""));
        var topology = position(result, Axis.TOPOLOGY);

        assertThat(topology.resolution()).isEqualTo(Resolution.FORCED);
        assertThat(topology.value()).contains("card-data");
        assertThat(result.decisions().stream().anyMatch(decision -> decision.costs().contains("a contract")
                                                                  && decision.costs().contains("an operational seam"))).isTrue();
    }

    @Test
    void resolve_defersReadWrite_andEmitsRungJudgment() {
        var result = resolve(HEAD + q1("path:render", "50ms budget", "system-clock"));

        assertThat(position(result, Axis.READ_WRITE).resolution()).isEqualTo(Resolution.DEFERRED);
        assertThat(hasJudgment(result, JudgmentPoint.Kind.RUNG_DEPTH)).isTrue();
    }

    @Test
    void resolve_defersPersistence_andEmitsPartitionKeyJudgment() {
        var result = resolve(HEAD + q5("path:reads", "twelve billion reads", "volume"));

        assertThat(position(result, Axis.PERSISTENCE).resolution()).isEqualTo(Resolution.DEFERRED);
        assertThat(hasJudgment(result, JudgmentPoint.Kind.PARTITION_KEY)).isTrue();
    }

    @Test
    void resolve_haltsContradiction_forAuditAndReplaySameScope() {
        var result = resolve(HEAD
                             + q6("data-class:ledger", "audit trail", "audit", "")
                             + q6("data-class:ledger", "reconstruct as of a past rule version", "replay", ""));

        assertThat(result.halts().stream().anyMatch(halt -> halt.kind() == Halt.Kind.CONTRADICTION)).isTrue();
        assertThat(result.halts().stream().flatMap(halt -> halt.renegotiationMenu().stream()).toList()).isNotEmpty();
        assertThat(hasJudgment(result, JudgmentPoint.Kind.CONTRADICTION_CHOICE)).isTrue();
    }

    @Test
    void resolve_haltsContradiction_whenMandateStrikesAForcedValue() {
        var result = resolve(HEAD
                             + q5("operation:accept", "modal deadline peak", "deadline")
                             + q6("system", "the intake must answer synchronously", "mandate", "strikes = [\"substrate:event-based\"]"));

        assertThat(result.halts().stream().anyMatch(halt -> halt.kind() == Halt.Kind.CONTRADICTION)).isTrue();
    }

    @Test
    void resolve_strikeDoesNotCollide_forDifferentValue_exactMatch() {
        var result = resolve(HEAD
                             + q5("operation:accept", "modal deadline peak", "deadline")
                             + q6("system", "prefer a particular broker", "mandate", "strikes = [\"substrate:kafka\"]"));

        // burst forces substrate=event-based; the strike is on 'kafka' (not a substring collision) -> no halt.
        assertThat(result.halts()).isEmpty();
    }

    @Test
    void resolve_splitsSecondaryPath_keepsPrimaryPath() {
        var result = resolve(HEAD
                             + q1("path:main", "P95 under 50ms", "system-clock")
                             + q5("path:extra", "divergent volume path", "volume"));
        var topology = position(result, Axis.TOPOLOGY);

        assertThat(topology.resolution()).isEqualTo(Resolution.FORCED);
        assertThat(topology.value()).contains("path:extra");
        assertThat(topology.value()).doesNotContain("path:main");
        assertThat(hasJudgment(result, JudgmentPoint.Kind.TOPOLOGY_SHAPE)).isTrue();
    }

    @Test
    void resolve_emitsTargetJudgment_forUnknownBudget() {
        var result = resolve(HEAD + """
            [[answers.q2]]
            scope     = "operation:incorporate"
            statement = "no criticality tier found"
            status    = "UNKNOWN"
            """);

        assertThat(hasJudgment(result, JudgmentPoint.Kind.TARGET_SETTING)).isTrue();
    }

    @Test
    void resolve_keepsNull_whenNothingPresses() {
        var result = resolve(HEAD + domainShape("noop", "none", false, "\"append-only\""));

        assertThat(position(result, Axis.SUBSTRATE).resolution()).isEqualTo(Resolution.NULL_KEPT);
        assertThat(position(result, Axis.STATE).value()).isEqualTo("current-state");
    }

    private static ResolveResult resolve(String toml) {
        AnswerSheet sheet = Inline.sheet(toml);

        return Resolve.resolve(sheet, Prune.prune(sheet), Press.press(sheet));
    }

    private static RecoveryClass recoveryClass(ResolveResult result, String operation) {
        return result.recovery()
                     .stream()
                     .filter(assignment -> assignment.operation().equals(operation))
                     .findFirst()
                     .orElseThrow()
                     .recoveryClass();
    }

    private static VectorPosition position(ResolveResult result, Axis axis) {
        return result.vector().stream().filter(vectorPosition -> vectorPosition.axis() == axis).findFirst().orElseThrow();
    }

    private static boolean hasJudgment(ResolveResult result, JudgmentPoint.Kind kind) {
        return result.judgmentPoints().stream().anyMatch(judgment -> judgment.kind() == kind);
    }

    private static String q1(String scope, String statement, String shape) {
        return """
            [[answers.q1]]
            scope     = "%s"
            statement = "%s"
            shape     = "%s"
            price     = "stated"
            status    = "answered"
            """.formatted(scope, statement, shape);
    }

    private static String q5(String scope, String statement, String shape) {
        return """
            [[answers.q5]]
            scope     = "%s"
            statement = "%s"
            shape     = "%s"
            status    = "answered"
            """.formatted(scope, statement, shape);
    }

    private static String q6(String scope, String statement, String kind, String extra) {
        return """
            [[answers.q6]]
            scope     = "%s"
            statement = "%s"
            kind      = "%s"
            status    = "answered"
            %s
            """.formatted(scope, statement, kind, extra);
    }

    private static String domainShape(String operation, String inverse, boolean decays, String reshapeable) {
        return """
            [[domain_shape]]
            operation   = "%s"
            inverse     = "%s"
            decays      = %s
            reshapeable = [%s]
            """.formatted(operation, inverse, decays, reshapeable);
    }
}
