package org.pragmatica.jbct.derive.emit;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.Meta;
import org.pragmatica.jbct.derive.result.Combination;
import org.pragmatica.jbct.derive.result.DecisionRecord;
import org.pragmatica.jbct.derive.result.DeriveResult;
import org.pragmatica.jbct.derive.result.Halt;
import org.pragmatica.jbct.derive.result.JudgmentPoint;
import org.pragmatica.jbct.derive.result.Pressure;
import org.pragmatica.jbct.derive.result.RecoveryAssignment;
import org.pragmatica.jbct.derive.result.Strike;
import org.pragmatica.jbct.derive.result.VectorPosition;
import org.pragmatica.jbct.derive.result.Verification;
import org.pragmatica.jbct.lint.Diagnostic;

/// Machine output for `derive` (SPEC.md §4 emit): the full [DeriveResult] as compact JSON, for the
/// artifacts repo and for diffing runs. It renders the same content as the markdown report — vector,
/// pressure matrix (inert rows included), decision records, halts, judgment points — so the two
/// stay in step.
public sealed interface JsonReport permits JsonReport.unused {
    record unused() implements JsonReport {}

    /// Render a derive result as a single-line JSON document.
    static String render(DeriveResult result) {
        return Json.object(List.of(Json.str("source", result.source()),
                                   Json.raw("meta", meta(result.meta())),
                                   Json.num("exit_code", result.exitCode()),
                                   Json.raw("gate_findings", array(result.gateFindings(), JsonReport::finding)),
                                   Json.raw("strikes", array(result.strikes(), JsonReport::strike)),
                                   Json.raw("pressure_matrix", array(result.pressures(), JsonReport::pressure)),
                                   Json.raw("combinations", array(result.combinations(), JsonReport::combination)),
                                   Json.raw("decision_records", array(result.decisions(), JsonReport::decision)),
                                   Json.raw("derived_vector", array(result.vector(), JsonReport::position)),
                                   Json.raw("recovery", array(result.recovery(), JsonReport::recovery)),
                                   Json.raw("verifications", array(result.verifications(), JsonReport::verification)),
                                   Json.raw("halts", array(result.halts(), JsonReport::halt)),
                                   Json.raw("judgment_points", array(result.judgmentPoints(), JsonReport::judgment))));
    }

    private static String meta(Meta meta) {
        return Json.object(List.of(Json.str("system", meta.system()),
                                   Json.str("era", meta.era()),
                                   Json.str("mode", kebab(meta.mode().name()))));
    }

    private static String strike(Strike strike) {
        return Json.object(List.of(Json.str("axis", strike.axisLabel()),
                                   Json.str("value", strike.value()),
                                   Json.str("struck_by", strike.struckBy().cite())));
    }

    private static String pressure(Pressure pressure) {
        return Json.object(List.of(Json.str("axis", pressure.axis().label()),
                                   Json.str("mode", kebab(pressure.mode().name())),
                                   Json.str("direction", pressure.direction()),
                                   Json.str("mechanism", pressure.mechanism()),
                                   Json.raw("citing", Json.stringArray(cites(pressure)))));
    }

    private static String combination(Combination combination) {
        return Json.object(List.of(Json.str("axis", combination.axis().label()),
                                   Json.str("note", combination.note()),
                                   Json.raw("members", Json.stringArray(combination.members().stream().map(Pressure::citations).toList()))));
    }

    private static String decision(DecisionRecord decision) {
        return Json.object(List.of(Json.str("axis", decision.axis().label()),
                                   Json.str("position", decision.position()),
                                   Json.str("forced_by", decision.forcedBy()),
                                   Json.str("via", decision.via()),
                                   Json.str("costs", decision.costs()),
                                   Json.str("revisit_when", decision.revisitWhen())));
    }

    private static String position(VectorPosition position) {
        return Json.object(List.of(Json.str("axis", position.axis().label()),
                                   Json.str("value", position.value()),
                                   Json.str("resolution", kebab(position.resolution().name())),
                                   Json.raw("citing", Json.stringArray(position.citing()))));
    }

    private static String recovery(RecoveryAssignment assignment) {
        return Json.object(List.of(Json.str("operation", assignment.operation()),
                                   Json.str("class", kebab(assignment.recoveryClass().name())),
                                   Json.str("rationale", assignment.rationale())));
    }

    private static String verification(Verification verification) {
        return Json.object(List.of(Json.str("rule", verification.rule()),
                                   Json.str("scope", verification.scope()),
                                   Json.str("status", kebab(verification.status().name())),
                                   Json.str("detail", verification.detail())));
    }

    private static String halt(Halt halt) {
        return Json.object(List.of(Json.str("kind", kebab(halt.kind().name())),
                                   Json.str("detail", halt.detail()),
                                   Json.raw("renegotiation_menu", Json.stringArray(halt.renegotiationMenu()))));
    }

    private static String judgment(JudgmentPoint judgment) {
        return Json.object(List.of(Json.str("kind", kebab(judgment.kind().name())),
                                   Json.str("subject", judgment.subject()),
                                   Json.str("detail", judgment.detail())));
    }

    private static String finding(Diagnostic finding) {
        return Json.object(List.of(Json.str("code", finding.ruleId()),
                                   Json.num("line", finding.line()),
                                   Json.str("message", finding.message()),
                                   Json.str("details", finding.details())));
    }

    private static List<String> cites(Pressure pressure) {
        return pressure.citing().stream().map(AnswerRow::cite).toList();
    }

    private static <T> String array(List<T> items, Function<T, String> renderer) {
        return Json.array(items.stream().map(renderer).toList());
    }

    private static String kebab(String enumName) {
        return enumName.toLowerCase().replace('_', '-');
    }
}
