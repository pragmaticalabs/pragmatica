package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-STY-03: No fully qualified class names in code.
///
/// Use `@SuppressWarnings("JBCT-STY-03")` for unavoidable cases.
public class CstFullyQualifiedNameRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-03";
    // Pattern to detect FQCN like java.util.List or com.example.Foo
    private static final Pattern FQCN_PATTERN = Pattern.compile("\\b([a-z][a-z0-9]*\\.)+[A-Z][a-zA-Z0-9]*\\b");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Find FQCN in method bodies (not imports or package declaration)
        return findAllMethods(root).stream()
                             .flatMap(method -> findFqcnInMethod(method, ctx));
    }

    /// A qualified name spelled inside a string literal or a comment is DATA, not code — most
    /// often a fixture in a test that feeds Java source to the linter itself. Masking the
    /// non-code spans first is what the sibling rules already do; without it this rule reports
    /// every `import` line written inside a text block.
    private Stream<Diagnostic> findFqcnInMethod(Cursor method, LintContext ctx) {
        var methodText = MapperSafety.blankNonCode(text(method));
        var matcher = FQCN_PATTERN.matcher(methodText);

        return Stream.iterate(matcher.find(),
                              found -> found,
                              found -> matcher.find())
                     .map(_ -> matcher.group())
                     .map(fqcn -> createDiagnostic(method, fqcn, ctx))
                     .limit(1);
    }

    private Diagnostic createDiagnostic(Cursor method, String fqcn, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Fully qualified name '" + fqcn + "' - use import instead",
                                     "FQCNs reduce readability. Add an import and use the simple name.");
    }

    @SuppressWarnings("unused")
    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
