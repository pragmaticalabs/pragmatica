package org.pragmatica.jbct.derive.gate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.model.DomainShape;
import org.pragmatica.jbct.derive.model.QuestionId;
import org.pragmatica.jbct.derive.model.ScopeKind;
import org.pragmatica.jbct.derive.model.Verdict;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The entry gate — normalize stage of the pipeline (SPEC.md §4), implemented as validation over a
/// parsed [AnswerSheet]. It rejects the book's named fake-answer forms ([GateErrorCode]) using the
/// book's vocabulary; each finding is a generic [Diagnostic] anchored at the offending sheet line.
///
/// `UNKNOWN` rows are valid input — they pass the gate and (in Phase B) propagate as UNKNOWN
/// pressure, never guessed. A clean sheet yields no findings.
public sealed interface EntryGate permits EntryGate.unused {
    record unused() implements EntryGate {}

    Set<String> CLOCK_SHAPES = Set.of("system-clock", "requester-clock");
    Set<String> LOAD_SHAPES = Set.of("volume", "contention", "burst", "deadline");
    Set<String> Q6_KINDS = Set.of("audit", "replay", "residency", "mandate");
    List<String> BUNDLED_PHRASES = List.of("full history", "team independence", "everything");
    Set<String> BARE_ILITIES = Set.of("scalability",
                                      "scalable",
                                      "availability",
                                      "available",
                                      "highly available",
                                      "high availability",
                                      "reliability",
                                      "reliable",
                                      "performance",
                                      "performant",
                                      "security",
                                      "secure",
                                      "resilience",
                                      "resilient",
                                      "robustness",
                                      "robust",
                                      "maintainability",
                                      "maintainable",
                                      "flexibility",
                                      "flexible",
                                      "elasticity",
                                      "elastic",
                                      "efficiency",
                                      "efficient",
                                      "durability",
                                      "durable",
                                      "consistency");

    /// Run the entry gate over a parsed sheet, returning every gate finding in sheet order.
    static List<Diagnostic> check(AnswerSheet sheet) {
        return Stream.concat(sheet.rows().stream().flatMap(row -> rowFindings(sheet, row)),
                             missingDomainShapes(sheet).stream())
                     .toList();
    }

    /// Run the gate and wrap the outcome in a [Verdict] (findings plus rows-checked count).
    static Verdict verdict(AnswerSheet sheet) {
        return new Verdict(sheet.source(), sheet.rows().size(), check(sheet));
    }

    private static Stream<Diagnostic> rowFindings(AnswerSheet sheet, AnswerRow row) {
        return row.isAnswered()
               ? answeredFindings(sheet, row)
               : Stream.of();
    }

    private static Stream<Diagnostic> answeredFindings(AnswerSheet sheet, AnswerRow row) {
        return Stream.of(unpriced(sheet, row),
                         unscoped(sheet, row),
                         untriaged(sheet, row),
                         missingShape(sheet, row),
                         bareIlity(sheet, row),
                         undecomposed(sheet, row))
                     .flatMap(Option::stream);
    }

    // ---- UNPRICED: Q1/Q2 answer without a price (SPEC.md §4) ----

    private static Option<Diagnostic> unpriced(AnswerSheet sheet, AnswerRow row) {
        return requiresPrice(row.question()) && row.price().isEmpty()
               ? some(finding(sheet, row, GateErrorCode.UNPRICED, row.question().name() + " " + row.scope().display()))
               : none();
    }

    // ---- UNSCOPED: system scope where a narrower scope is demanded ----

    private static Option<Diagnostic> unscoped(AnswerSheet sheet, AnswerRow row) {
        return requiresNarrowScope(row.question()) && row.scope().isSystem()
               ? some(finding(sheet, row, GateErrorCode.UNSCOPED, row.question().name() + " answered at 'system' scope"))
               : none();
    }

    // ---- UNTRIAGED: time answer without clock (F22); failure answer not target-vs-observed (F23) ----

    private static Option<Diagnostic> untriaged(AnswerSheet sheet, AnswerRow row) {
        return switch (row.question()) {
            case Q1 -> untriagedClock(sheet, row);
            case Q2 -> untriagedFailure(sheet, row);
            default -> none();
        };
    }

    private static Option<Diagnostic> untriagedClock(AnswerSheet sheet, AnswerRow row) {
        return hasShapeIn(row, CLOCK_SHAPES)
               ? none()
               : some(finding(sheet, row, GateErrorCode.UNTRIAGED, "Q1 time answer lacks requester-vs-system clock (F22)"));
    }

    private static Option<Diagnostic> untriagedFailure(AnswerSheet sheet, AnswerRow row) {
        return hasTargetBasis(row)
               ? none()
               : some(finding(sheet, row, GateErrorCode.UNTRIAGED, failureDetail(row)));
    }

    private static String failureDetail(AnswerRow row) {
        return isObservedBasis(row)
               ? "Q2 cites an observed failure as a target (F23)"
               : "Q2 failure answer not triaged target-vs-observed (F23)";
    }

    // ---- MISSING_SHAPE: Q5 load answer without volume/contention/burst/deadline ----

    private static Option<Diagnostic> missingShape(AnswerSheet sheet, AnswerRow row) {
        return row.question() == QuestionId.Q5 && !hasShapeIn(row, LOAD_SHAPES)
               ? some(finding(sheet, row, GateErrorCode.MISSING_SHAPE, "Q5 load answer without a demand shape"))
               : none();
    }

    // ---- BARE_ILITY: a bare quality word is not an answer ----

    private static Option<Diagnostic> bareIlity(AnswerSheet sheet, AnswerRow row) {
        return BARE_ILITIES.contains(normalize(row.statement()))
               ? some(finding(sheet, row, GateErrorCode.BARE_ILITY, "'" + row.statement() + "'"))
               : none();
    }

    // ---- UNDECOMPOSED: Q6 without audit/replay/residency/mandate kind; bundled answers ----

    private static Option<Diagnostic> undecomposed(AnswerSheet sheet, AnswerRow row) {
        return needsKind(row)
               ? some(finding(sheet, row, GateErrorCode.UNDECOMPOSED, "Q6 external-constraint row without a kind (audit/replay/residency/mandate)"))
               : bundled(sheet, row);
    }

    private static Option<Diagnostic> bundled(AnswerSheet sheet, AnswerRow row) {
        return isBundled(row.statement())
               ? some(finding(sheet, row, GateErrorCode.UNDECOMPOSED, "bundled answer: '" + row.statement() + "'"))
               : none();
    }

    // ---- MISSING_DOMAIN_SHAPE: an effectful (operation-scoped) row without its domain-shape row ----

    private static List<Diagnostic> missingDomainShapes(AnswerSheet sheet) {
        var declared = sheet.domainShapes().stream().map(DomainShape::operation).collect(Collectors.toSet());

        return firstByOperation(sheet).entrySet()
                                      .stream()
                                      .filter(entry -> !declared.contains(entry.getKey()))
                                      .map(entry -> finding(sheet,
                                                            entry.getValue(),
                                                            GateErrorCode.MISSING_DOMAIN_SHAPE,
                                                            "operation:" + entry.getKey()))
                                      .toList();
    }

    private static Map<String, AnswerRow> firstByOperation(AnswerSheet sheet) {
        return sheet.rows()
                    .stream()
                    .filter(row -> row.scope().kind() == ScopeKind.OPERATION)
                    .collect(Collectors.toMap(row -> row.scope().name(),
                                              row -> row,
                                              (first, second) -> first,
                                              LinkedHashMap::new));
    }

    // ---- shared predicates ----

    private static boolean requiresPrice(QuestionId question) {
        return question == QuestionId.Q1 || question == QuestionId.Q2;
    }

    private static boolean requiresNarrowScope(QuestionId question) {
        return switch (question) {
            case Q1, Q2, Q3, Q4, Q5 -> true;
            default -> false;
        };
    }

    private static boolean needsKind(AnswerRow row) {
        return row.question() == QuestionId.Q6 && !hasKind(row);
    }

    private static boolean hasKind(AnswerRow row) {
        return row.kind().map(String::toLowerCase).filter(Q6_KINDS::contains).isPresent();
    }

    private static boolean hasShapeIn(AnswerRow row, Set<String> allowed) {
        return row.shape().map(String::toLowerCase).filter(allowed::contains).isPresent();
    }

    private static boolean hasTargetBasis(AnswerRow row) {
        return row.basis().map(String::toLowerCase).filter("target"::equals).isPresent();
    }

    private static boolean isObservedBasis(AnswerRow row) {
        return row.basis().map(String::toLowerCase).filter("observed"::equals).isPresent();
    }

    private static boolean isBundled(String statement) {
        var lower = statement.toLowerCase();

        return BUNDLED_PHRASES.stream().anyMatch(lower::contains);
    }

    private static String normalize(String statement) {
        return statement.trim().toLowerCase().replaceAll("[.!]+$", "");
    }

    private static Diagnostic finding(AnswerSheet sheet, AnswerRow row, GateErrorCode code, String context) {
        return Diagnostic.diagnostic(code.name(),
                                     DiagnosticSeverity.ERROR,
                                     sheet.source(),
                                     row.line(),
                                     0,
                                     code.summary(),
                                     context + " (" + code.card() + ")");
    }
}
