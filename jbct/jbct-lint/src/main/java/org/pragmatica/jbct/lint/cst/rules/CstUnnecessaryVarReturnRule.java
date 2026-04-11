package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-STY-07: Unnecessary intermediate variable before return.
///
/// Detects `var x = expr; return x;` where `x` is not referenced elsewhere.
/// Suggests returning the expression directly.
public class CstUnnecessaryVarReturnRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STY-07";
    private static final Pattern VAR_DECL_PATTERN = Pattern.compile("^\\s*(?:var|final\\s+var)\\s+(\\w+)\\s*=");
    private static final Pattern RETURN_VAR_PATTERN = Pattern.compile("^\\s*return\\s+(\\w+)\\s*;\\s*$");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class)
                .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                .map(qn -> text(qn, source))
                .or("");
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        return findAllMethods(root).stream()
                      .flatMap(method -> analyzeMethod(root, method, source, ctx));
    }

    private Stream<Diagnostic> analyzeMethod(CstNode root, CstNode method, String source, LintContext ctx) {
        var statements = findAllStatements(method);
        var diagnostics = Stream.<Diagnostic>builder();
        for (int i = 0; i < statements.size() - 1; i++) {
            var current = statements.get(i);
            var next = statements.get(i + 1);
            var currentText = text(current, source).trim();
            var nextText = text(next, source).trim();
            var varMatcher = VAR_DECL_PATTERN.matcher(currentText);
            var returnMatcher = RETURN_VAR_PATTERN.matcher(nextText);
            if (!varMatcher.find() || !returnMatcher.find()) {
                continue;
            }
            var varName = varMatcher.group(1);
            var returnedName = returnMatcher.group(1);
            if (!varName.equals(returnedName)) {
                continue;
            }
            // Check that the variable is not referenced elsewhere in the method
            var methodText = text(method, source);
            var varRefPattern = Pattern.compile("\\b" + Pattern.quote(varName) + "\\b");
            var varRefMatcher = varRefPattern.matcher(methodText);
            var refCount = 0;
            while (varRefMatcher.find()) {
                refCount++;
            }
            // Expect exactly 2 references: the declaration and the return
            if (refCount <= 2) {
                diagnostics.add(createDiagnostic(root, current, varName, source, ctx));
            }
        }
        return diagnostics.build();
    }

    private Diagnostic createDiagnostic(CstNode root, CstNode stmt, String varName, String source, LintContext ctx) {
        var methodName = findAncestor(root, stmt, RuleId.Member.class)
                .flatMap(md -> childByRule(md, RuleId.Identifier.class))
                .map(id -> text(id, source))
                .or("(unknown)");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Unnecessary variable '" + varName + "' in method '" + methodName
                                     + "' — return the expression directly",
                                     "The variable is only used to hold a value that is immediately returned. "
                                     + "Remove the intermediate variable and return the expression directly.")
                         .withExample("""
            // Before: unnecessary intermediate variable
            var result = computeValue();
            return result;

            // After: return directly
            return computeValue();
            """);
    }

    private static java.util.List<CstNode> findAllMethods(CstNode root) {
        return findAll(root, RuleId.Member.class);
    }
}
