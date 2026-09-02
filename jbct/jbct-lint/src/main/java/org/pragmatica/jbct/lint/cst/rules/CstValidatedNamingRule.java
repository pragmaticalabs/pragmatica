package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-NAM-02: Use Valid prefix, not Validated.
public class CstValidatedNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-NAM-02";
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern RECORD_NAME_PATTERN = Pattern.compile("\\brecord\\s+(\\w+)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Check classes and records for Validated prefix
        var classDiagnostics = findAllClasses(root).stream()
                                             .map(cls -> new NamedNode(cls,
                                                                       extractName(cls, CLASS_NAME_PATTERN)))
                                             .filter(nn -> nn.name()
                                                             .startsWith("Validated"))
                                             .map(nn -> createDiagnostic(nn, ctx));
        var recordDiagnostics = findAllRecords(root).stream()
                                              .map(rec -> new NamedNode(rec,
                                                                        extractName(rec, RECORD_NAME_PATTERN)))
                                              .filter(nn -> nn.name()
                                                              .startsWith("Validated"))
                                              .map(nn -> createDiagnostic(nn, ctx));

        return Stream.concat(classDiagnostics, recordDiagnostics);
    }

    private String extractName(Cursor node, Pattern pattern) {
        var matcher = pattern.matcher(text(node));

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    private Diagnostic createDiagnostic(NamedNode nn, LintContext ctx) {
        var suggestedName = nn.name().replaceFirst("Validated", "Valid");

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(nn.node()),
                                     startColumn(nn.node()),
                                     "Type '" + nn.name() + "' should be named '" + suggestedName + "'",
                                     "JBCT uses 'Valid' prefix, not 'Validated'.");
    }

    private record NamedNode(Cursor node, String name) {}
}
