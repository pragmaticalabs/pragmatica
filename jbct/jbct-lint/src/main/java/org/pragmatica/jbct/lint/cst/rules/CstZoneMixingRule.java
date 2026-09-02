package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier;
import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier.ZoneMixing;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ZONE-03: No zone mixing in sequencer chains.
///
/// Sequencer chains (flatMap/map sequences) should maintain consistent
/// abstraction at Zone 2 level. Zone 3 operations should be wrapped
/// in Zone 2 step interfaces, not called directly in chains.
///
/// Thin delegator (#448): detection is the [MethodShapeClassifier#mapperChainZoneMixings] facet —
/// the same two regexes (lambda-call and method-reference forms) run over
/// `MapperSafety.blankNonCode`-masked method text, so a verb spelled inside a string or comment no
/// longer fires. This rule owns only the gate and the diagnostic.
public class CstZoneMixingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ZONE-03";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return MethodShapeClassifier.mapperChainZoneMixings(root)
                                    .stream()
                                    .map(mixing -> createDiagnostic(mixing, ctx));
    }

    private Diagnostic createDiagnostic(ZoneMixing mixing, LintContext ctx) {
        var verbList = String.join(", ", mixing.verbs());

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(mixing.method())),
                                     startColumn(anchorOf(mixing.method())),
                                     "Zone mixing in chain - Zone 3 verbs found: " + verbList,
                                     "Sequencer chains should use Zone 2 methods. "
                                    + "Wrap Zone 3 operations ('" + verbList
                                    + "') in step interfaces. "
                                    + "Example: Instead of .flatMap(x -> x.parseData()), "
                                    + "use .flatMap(processData::apply) where ProcessData is a step interface.");
    }
}
