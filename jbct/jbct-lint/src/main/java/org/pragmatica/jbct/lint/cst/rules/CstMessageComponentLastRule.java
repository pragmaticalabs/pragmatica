package org.pragmatica.jbct.lint.cst.rules;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-CAUSE-03: the `message` component of a cause record occupies the final position (R2) —
/// the trailing slot is what lets the canonical constructor reference line up with the
/// `causeFactory` shapes, which pass the formatted message last. A mis-placed component compiles
/// and behaves; the defect is that the type cannot join the factory idiom, so WARNING.
public class CstMessageComponentLastRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-03";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root)) || CauseHierarchies.sanctionedLibraryPackage(packageName(root))) {
            return Stream.empty();
        }

        var causeNames = CauseHierarchies.causeInterfaceNames(root);

        return CauseHierarchies.causeRecords(root, causeNames)
                               .stream()
                               .filter(record -> {
                                   var components = CauseHierarchies.recordComponentNames(record);
                                   var index = components.indexOf("message");

                                   return index >= 0 && index != components.size() - 1;
                               })
                               .map(record -> Diagnostic.diagnostic(RULE_ID,
                                                                    ctx.severityFor(RULE_ID),
                                                                    ctx.fileName(),
                                                                    startLine(anchorOf(record)),
                                                                    startColumn(anchorOf(record)),
                                                                    "Cause record '" + DeclSupport.declName(record)
                                                                   + "': the message component belongs in the final position",
                                                                    "The causeFactory overloads pass the formatted message last, so only a "
                                                                   + "trailing message component lets the canonical constructor reference be "
                                                                   + "the factory (R2)."));
    }
}
