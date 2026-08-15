package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-EX-02: Don't use orElseThrow().
///
/// Exception-based control flow is forbidden. Use Result/Option composition.
public class CstOrElseThrowRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-EX-02";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAll(root, this::spellsOrElseThrow).stream()
                      .map(node -> createDiagnostic(node, ctx));
    }

    /// Exactly one node spells the call, but which one depends on its position in the chain.
    /// A chained `....findFirst().orElseThrow()` spells it as a `PostOp` (expression) or a
    /// `ChainOp` (statement); only a direct `x.orElseThrow()` keeps it inside the `Primary`'s
    /// qualified name. Matching the narrowest spelling yields one diagnostic per call — a
    /// `contains` test against `Primary` alone missed every chained call, and against every
    /// node kind would re-report the same call on each enclosing expression level.
    private boolean spellsOrElseThrow(Cursor node) {
        if (node.kindIsAny(RuleKind.POST_OP, RuleKind.CHAIN_OP)) {
            return text(node).trim()
                             .startsWith(".orElseThrow");
        }

        return node.kindIs(RuleKind.QUALIFIED_NAME) && text(node).contains(".orElseThrow");
    }

    private Diagnostic createDiagnostic(Cursor node, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "orElseThrow() bypasses JBCT error handling",
                                     "Use Result/Option composition instead of throwing exceptions. "
                                    + "Exceptions break the functional pipeline.")
                         .withExample("""
            // Before: using orElseThrow
            User user = findUser(id).orElseThrow();

            // After: using composition
            return findUser(id)
                .map(this::processUser)
                .orElse(defaultUser);
            """);
    }
}
