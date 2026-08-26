package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.packageName;


/// JBCT-CAUSE-07: the single-argument template rungs (`forOneValue(String)` etc.) produce
/// anonymous, value-discarding causes; in production sources a parameterized failure is worth
/// naming and retaining (R1) through the causeFactory rungs. The single-argument forms stay
/// sanctioned in tests and scripts, and `Causes.cause(String)` is deliberately NOT flagged — the
/// line between a typed failure and a cheap string cause is a semantic judgement no CST rule can
/// decide.
///
/// A call is recognized by its template being a string literal at the call site, which is also
/// what excludes the overload declarations themselves (`String template` is not a literal).
public class CstAnonymousTemplateRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-07";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root)) || CauseHierarchies.sanctionedLibraryPackage(packageName(root))) {
            return Stream.empty();
        }

        var masked = MapperSafety.blankNonCode(source);
        var diagnostics = Stream.<Diagnostic>builder();

        for (var call : CauseHierarchies.factoryCalls(source, masked)) {
            if (call.args().size() != 1) {
                continue;
            }

            if (CauseHierarchies.leadingStringLiteral(call.args().getFirst().raw()).isEmpty()) {
                continue;
            }

            diagnostics.add(Diagnostic.diagnostic(RULE_ID,
                                                  ctx.severityFor(RULE_ID),
                                                  ctx.fileName(),
                                                  CauseHierarchies.lineAt(source, call.offset()),
                                                  CauseHierarchies.columnAt(source, call.offset()),
                                                  "Anonymous template factory in domain code; name the failure and retain its value",
                                                  "The causeFactory rungs keep the formatted value as a typed component "
                                                 + "(record InvalidEmail(String raw, String message)); the single-argument "
                                                 + "form bakes it into prose (R1)."));
        }

        return diagnostics.build();
    }
}
