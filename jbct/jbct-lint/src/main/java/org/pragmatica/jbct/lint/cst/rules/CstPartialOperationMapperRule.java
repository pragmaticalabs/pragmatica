package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-TOT-01 (R-A): No partial operations inside a carrier mapper lambda.
///
/// A lambda argument to `map` / `flatMap` / `filter` / `replaceResult` / `fold` must be total.
/// A partial accessor (`getFirst()`, `getLast()`, `get(<index>)`, `get()`, `orElseThrow(...)`,
/// `iterator().next()`) or an explicit `throw` inside such a lambda throws instead of failing
/// the carrier — the #483 hang. Make the mapper total or lift the failure to a typed `Cause`.
///
/// Stream pipelines share the `map`/`filter`/`flatMap` names; a mapper whose enclosing chain
/// carries a Stream source/collector marker is exempted to spare the JBCT Iteration pattern.
public class CstPartialOperationMapperRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-TOT-01";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllLambdas(root).stream()
                             .flatMap(lambda -> checkLambda(root, source, lambda, ctx));
    }

    private Stream<Diagnostic> checkLambda(Cursor root, String source, Cursor lambda, LintContext ctx) {
        if (!MapperSafety.containsPartialOperation(bodyExcludingNestedLambdas(lambda))) {
            return Stream.empty();
        }

        return MapperSafety.enclosingCallName(source, lambda.spanStart())
                           .filter(MapperSafety.MAPPER_METHODS::contains)
                           .filter(callName -> !streamExempt(callName, root, lambda))
                           .map(callName -> createDiagnostic(lambda, ctx))
                           .stream();
    }

    private boolean streamExempt(String callName, Cursor root, Cursor lambda) {
        return MapperSafety.STREAM_SHARED.contains(callName) && MapperSafety.isStreamPipeline(enclosingChain(root, lambda));
    }

    /// Text of the nearest enclosing statement, falling back to the enclosing member for a
    /// field/static-initializer lambda that has no statement ancestor. Scoping the fallback to the
    /// member (never the whole file) keeps a Stream marker in some *unrelated* member from
    /// exempting this mapper. Empty string when neither ancestor exists (never exempt).
    private String enclosingChain(Cursor root, Cursor lambda) {
        return findAncestor(root, lambda, RuleKind.BLOCK_STMT).orElse(findAncestor(root, lambda, RuleKind.MEMBER))
                             .map(CstNodes::text)
                             .or("");
    }

    /// Lambda text with every nested lambda's span blanked, so a partial op belonging to an inner
    /// lambda (passed to some other, possibly-total call) is not attributed to this mapper.
    private String bodyExcludingNestedLambdas(Cursor lambda) {
        var base = lambda.spanStart();
        var sb = new StringBuilder(text(lambda));

        for (var nested : findAllLambdas(lambda)) {
            if (nested.idx() == lambda.idx() && nested.cst() == lambda.cst()) {
                continue;
            }

            blankRange(sb, nested.spanStart() - base, nested.spanEnd() - base);
        }

        return sb.toString();
    }

    private void blankRange(StringBuilder sb, int from, int to) {
        for (var i = Math.max(0, from); i < Math.min(sb.length(), to); i++) {
            var c = sb.charAt(i);

            if (c != '\n' && c != '\r') {
                sb.setCharAt(i, ' ');
            }
        }
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     "Partial operation in mapper lambda - make the mapper total or lift to a typed Cause",
                                     "A partial accessor (getFirst/getLast/get(index)/orElseThrow/iterator().next) or "
                                    + "an explicit throw inside a map/flatMap/filter/replaceResult/fold lambda throws "
                                    + "instead of failing the carrier, which can hang a Promise (#483).")
                         .withExample("""
            // Before: partial accessor inside the mapper
            promise.map(wire -> wire.items().getFirst());

            // After: total mapper, failure lifted to a Cause
            promise.flatMap(wire -> Option.option(wire.items())
                                          .flatMap(items -> items.stream().findFirst())
                                          .toResult(EMPTY_ITEMS)
                                          .async());
            """);
    }
}
