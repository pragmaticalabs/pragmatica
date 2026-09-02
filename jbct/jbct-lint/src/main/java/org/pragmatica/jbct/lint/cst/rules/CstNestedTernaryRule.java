package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-STY-09: No nested ternaries.
///
/// Flags a ternary whose condition, then-branch, or else-branch contains another ternary
/// (`a ? b : c ? d : e`, `a ? (b ? c : d) : e`). Only the outermost ternary of a nested chain is
/// reported, so `a ? b : c ? d : e ? f : g` yields one diagnostic.
///
/// Overlap with JBCT-LAM-03 (no ternary in a lambda): LAM-03 owns every ternary that sits inside
/// a lambda, so STY-09 deliberately skips any ternary with a lambda ancestor and does not count a
/// nested ternary that is reached only by crossing a lambda boundary. A nested ternary inside a
/// lambda is therefore reported by LAM-03 alone — never double-flagged.
///
/// A `Ternary` grammar node is emitted for many non-ternary expressions that merely pass through
/// the precedence chain; only a `Branch` with more than one child is an actual `? :` operator, so
/// pass-through nodes are filtered out.
///
/// FP surface: `x ? y : f(a ? b : c)` (a ternary in a call argument of an operand) is treated as
/// nesting. FN surface: a nested ternary separated from its parent by a lambda is left to LAM-03.
public class CstNestedTernaryRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-09";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAll(root, RuleKind.TERNARY).stream()
                      .filter(this::isActualTernary)
                      .filter(ternary -> !insideLambda(ternary))
                      .filter(this::hasNestedTernary)
                      .filter(ternary -> !hasTernaryAncestor(ternary))
                      .map(ternary -> createDiagnostic(ternary, ctx));
    }

    private boolean isActualTernary(Cursor node) {
        return node.kindIs(RuleKind.TERNARY) && node instanceof Cursor.Branch branch && branch.children()
                                                                                              .count() > 1;
    }

    private boolean insideLambda(Cursor ternary) {
        return ternary.ancestors()
                      .anyMatch(ancestor -> ancestor.kindIs(RuleKind.LAMBDA));
    }

    private boolean hasTernaryAncestor(Cursor ternary) {
        return ternary.ancestors()
                      .anyMatch(this::isActualTernary);
    }

    private boolean hasNestedTernary(Cursor ternary) {
        return children(ternary).stream()
                                .anyMatch(this::containsTernaryWithoutCrossingLambda);
    }

    private boolean containsTernaryWithoutCrossingLambda(Cursor node) {
        if (node.kindIs(RuleKind.LAMBDA)) {
            return false;
        }

        if (isActualTernary(node)) {
            return true;
        }

        return children(node).stream()
                             .anyMatch(this::containsTernaryWithoutCrossingLambda);
    }

    private Diagnostic createDiagnostic(Cursor ternary, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(ternary),
                                     startColumn(ternary),
                                     "Nested ternary operator - extract to a named method or use pattern matching",
                                     "Nested ternaries are hard to read. Extract the inner decision to a named method "
                                    + "or use a switch expression.");
    }
}
