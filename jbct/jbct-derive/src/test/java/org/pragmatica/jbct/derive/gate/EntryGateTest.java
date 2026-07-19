package org.pragmatica.jbct.derive.gate;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.parse.SheetParser;
import org.pragmatica.jbct.lint.Diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

/// The entry gate rejects the book's named fake-answer forms (SPEC.md §4), each with the book's
/// own vocabulary as the message. One minimal sheet per form, defective in exactly one way.
class EntryGateTest {
    private static final String META = """
        schema_version = "0.1"
        [meta]
        system = "fixture"
        era    = "now"
        mode   = "greenfield"
        """;

    private static List<Diagnostic> gate(String body) {
        return SheetParser.parse(META + body, "fixture.toml")
                          .map(EntryGate::check)
                          .onFailure(cause -> { throw new AssertionError("fixture did not parse: " + cause.message()); })
                          .or(List.of());
    }

    private static Diagnostic only(String body) {
        var findings = gate(body);

        assertThat(findings).hasSize(1);

        return findings.getFirst();
    }

    @Test
    void check_rejectsUnpriced_whenQ1RowLacksPrice() {
        var finding = only("""
            [[answers.q1]]
            scope     = "path:search"
            statement = "P95 under 200ms at peak"
            shape     = "system-clock"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.UNPRICED.name());
        assertThat(finding.message()).isEqualTo(GateErrorCode.UNPRICED.summary());
        assertThat(finding.message()).contains("53rd-minute");
    }

    @Test
    void check_rejectsUnscoped_whenQ3RowAtSystemScope() {
        var finding = only("""
            [[answers.q3]]
            scope     = "system"
            statement = "7-year retention with RPO of 5 minutes"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.UNSCOPED.name());
        assertThat(finding.message()).isEqualTo(GateErrorCode.UNSCOPED.summary());
    }

    @Test
    void check_rejectsUndecomposed_whenQ6RowLacksKind() {
        var finding = only("""
            [[answers.q6]]
            scope     = "system"
            statement = "we need full history"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.UNDECOMPOSED.name());
        assertThat(finding.message()).isEqualTo(GateErrorCode.UNDECOMPOSED.summary());
        assertThat(finding.details()).contains("audit");
    }

    @Test
    void check_rejectsUndecomposed_whenTeamIndependenceBundled() {
        var finding = only("""
            [[answers.q7]]
            scope     = "system"
            statement = "we want team independence"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.UNDECOMPOSED.name());
        assertThat(finding.details()).contains("team independence");
    }

    @Test
    void check_rejectsUntriaged_whenTimeAnswerLacksClock() {
        var finding = only("""
            [[answers.q1]]
            scope     = "path:search"
            statement = "P95 under 200ms at peak"
            price     = "latency is conversion"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.UNTRIAGED.name());
        assertThat(finding.details()).contains("F22");
    }

    @Test
    void check_rejectsUntriaged_whenFailureCitesObservedAsTarget() {
        var finding = only("""
            [[answers.q2]]
            scope     = "operation:pay"
            statement = "we hit 99.98% last year"
            basis     = "observed"
            price     = "outage cost"
            status    = "answered"

            [[domain_shape]]
            operation = "pay"
            inverse   = "refund"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.UNTRIAGED.name());
        assertThat(finding.details()).contains("F23");
        assertThat(finding.details()).contains("observed failure as a target");
    }

    @Test
    void check_rejectsBareIlity_whenStatementIsBareQuality() {
        var finding = only("""
            [[answers.q8]]
            scope     = "system"
            statement = "highly available"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.BARE_ILITY.name());
        assertThat(finding.message()).isEqualTo(GateErrorCode.BARE_ILITY.summary());
    }

    @Test
    void check_rejectsMissingShape_whenLoadAnswerLacksShape() {
        var finding = only("""
            [[answers.q5]]
            scope     = "path:search"
            statement = "10k requests per second at peak"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.MISSING_SHAPE.name());
        assertThat(finding.message()).isEqualTo(GateErrorCode.MISSING_SHAPE.summary());
    }

    @Test
    void check_rejectsMissingDomainShape_whenEffectfulOperationHasNoShapeRow() {
        var finding = only("""
            [[answers.q1]]
            scope     = "operation:ship"
            statement = "ship within 24 hours"
            shape     = "requester-clock"
            price     = "the customer waits for delivery"
            status    = "answered"
            """);

        assertThat(finding.ruleId()).isEqualTo(GateErrorCode.MISSING_DOMAIN_SHAPE.name());
        assertThat(finding.details()).contains("operation:ship");
    }

    @Test
    void check_passesUnknownRows_withoutFinding() {
        var findings = gate("""
            [[answers.q1]]
            scope     = "path:search"
            statement = "no published render budget"
            status    = "UNKNOWN"
            """);

        assertThat(findings).isEmpty();
    }

    @Test
    void check_pointsAtRowLine_forOffendingRow() {
        var finding = only("""
            [[answers.q5]]
            scope     = "path:search"
            statement = "10k requests per second at peak"
            status    = "answered"
            """);

        // META is 5 lines; the [[answers.q5]] header is line 6.
        assertThat(finding.line()).isEqualTo(6);
    }

    @Test
    void check_pointsAtSecondRow_whenFirstRowValid() {
        var findings = gate("""
            [[answers.q5]]
            scope     = "path:a"
            statement = "1k requests per second"
            shape     = "volume"
            status    = "answered"

            [[answers.q5]]
            scope     = "path:b"
            statement = "2k requests per second"
            status    = "answered"
            """);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().line()).isEqualTo(12);
    }
}
