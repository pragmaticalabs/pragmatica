package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-CAUSE-05: a cause record wrapping an underlying cause implements `Cause.Wrapped` with an
/// `origin` component rather than hand-declaring a `source()` override. The override is
/// functionally equivalent — the defect is divergence, plus the naming trap left armed for the
/// next author: a component CANNOT be named `source`, because the record accessor's return type
/// (`Cause`) clashes with `Cause.source()`'s (`Option<Cause>`).
public class CstWrappedCauseRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-05";
    private static final Pattern NO_PARAMS_SOURCE = Pattern.compile("\\bsource\\s*\\(\\s*\\)");

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
                               .flatMap(record -> FileTypeClassifier.directMethods(root, record).stream())
                               .filter(method -> "source".equals(FileTypeClassifier.methodName(method)))
                               .filter(method -> NO_PARAMS_SOURCE.matcher(memberDeclText(method)).find())
                               .map(method -> Diagnostic.diagnostic(RULE_ID,
                                                                    ctx.severityFor(RULE_ID),
                                                                    ctx.fileName(),
                                                                    startLine(anchorOf(method)),
                                                                    startColumn(anchorOf(method)),
                                                                    "Hand-declared source() on a cause record; declare a Cause origin component and implement Cause.Wrapped",
                                                                    "Cause.Wrapped supplies source() from the origin component. The component "
                                                                   + "cannot be named 'source' — the accessor's return type would clash with "
                                                                   + "Cause.source() — which is exactly the trap the mixin retires."));
    }
}
