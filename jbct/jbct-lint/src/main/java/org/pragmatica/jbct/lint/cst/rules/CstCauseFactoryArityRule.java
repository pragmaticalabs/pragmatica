package org.pragmatica.jbct.lint.cst.rules;

import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-CAUSE-04: the R1 arity equation — template conversions == forNValues' N == target record
/// components − 1.
///
/// The equation is checked at every `forXValues` call whose template is a string literal at the
/// call site; the third term applies when the causeFactory is a constructor reference to a
/// same-file cause record. A template built by concatenation or referenced from a constant is
/// skipped (documented FN — an off-idiom shape CAUSE-07 or review catches), which is also what
/// excludes the overload DECLARATIONS themselves: a parameter list's `String template` is not a
/// literal.
///
/// The value-discarding form is the equation's most important catch: a message-only record built
/// with a value-formatting rung bakes the value into prose instead of retaining it.
public class CstCauseFactoryArityRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-CAUSE-04";
    private static final Pattern CTOR_REF = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*::\\s*new");

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
        var componentCounts = new HashMap<String, Integer>();

        for (var record : CauseHierarchies.causeRecords(root, causeNames)) {
            componentCounts.put(DeclSupport.declName(record),
                                CauseHierarchies.recordComponentNames(record).size());
        }

        var masked = MapperSafety.blankNonCode(source);
        var diagnostics = Stream.<Diagnostic>builder();

        for (var call : CauseHierarchies.factoryCalls(source, masked)) {
            var template = CauseHierarchies.leadingStringLiteral(call.args().getFirst().raw());

            if (template.isEmpty() || call.args().size() < 2) {
                continue;
            }

            var conversions = CauseHierarchies.conversionCount(template);

            if (conversions != call.valueArity()) {
                diagnostics.add(diagnostic(call.offset(), source, ctx,
                                           "Template has " + conversions + " conversion(s) but the factory supplies "
                                          + call.valueArity() + " value(s)"));
                continue;
            }

            var ctorRef = CTOR_REF.matcher(call.args().get(1).raw());

            if (ctorRef.find()) {
                var components = componentCounts.get(ctorRef.group(1));

                if (components != null && components - 1 != call.valueArity()) {
                    diagnostics.add(diagnostic(call.offset(), source, ctx,
                                               "Record '" + ctorRef.group(1) + "' retains " + (components - 1)
                                              + " value(s) but the factory formats " + call.valueArity()
                                              + " — the formatted value must be a component (R1)"));
                }
            }
        }

        return diagnostics.build();
    }

    private Diagnostic diagnostic(int offset, String source, LintContext ctx, String message) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     CauseHierarchies.lineAt(source, offset),
                                     CauseHierarchies.columnAt(source, offset),
                                     message,
                                     "R1: template placeholders == factory value-arity == record components - 1. "
                                    + "A message that formats a value the record does not carry bakes data into prose.");
    }
}
