package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-LAM-01: No complex logic in lambdas.
///
/// Flags lambdas that contain:
///
///   - Control flow statements (if, switch, try)
///   - Multiple statements in block body (2+ semicolons)
///
public class CstLambdaComplexityRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-LAM-01";
    private static final int MAX_STATEMENTS = 1;

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        return findAllLambdas(root).stream()
                      .filter(this::hasComplexLogic)
                      .map(lambda -> createDiagnostic(lambda, ctx));
    }

    private boolean hasComplexLogic(Cursor lambda) {
        var lambdaText = text(lambda);
        return hasControlFlow(lambdaText) || hasMultipleStatements(lambdaText);
    }

    private boolean hasControlFlow(String lambdaText) {
        return lambdaText.contains("if ") || lambdaText.contains("if(") ||
        lambdaText.contains("switch ") || lambdaText.contains("switch(") ||
        lambdaText.contains("try ") || lambdaText.contains("try{");
    }

    private boolean hasMultipleStatements(String lambdaText) {
        // Only check block lambdas (contain { })
        int braceStart = lambdaText.indexOf('{');
        if (braceStart < 0) {
            return false;
        }
        int braceEnd = lambdaText.lastIndexOf('}');
        if (braceEnd <= braceStart) {
            return false;
        }
        // Count semicolons in block body
        var blockBody = lambdaText.substring(braceStart + 1, braceEnd);
        var statementCount = countStatements(blockBody);
        return statementCount > MAX_STATEMENTS;
    }

    private int countStatements(String blockBody) {
        int count = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = 0; i < blockBody.length(); i++) {
            char c = blockBody.charAt(i);
            if (inString) {
                if (c == stringChar && (i == 0 || blockBody.charAt(i - 1) != '\\')) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
            } else if (c == ';') {
                count++;
            }
        }
        return count;
    }

    private Diagnostic createDiagnostic(Cursor lambda, LintContext ctx) {
        var lambdaText = text(lambda);
        var message = hasControlFlow(lambdaText)
                      ? "Lambda contains control flow - extract to a method"
                      : "Lambda contains multiple statements - extract to a method";
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(lambda),
                                     startColumn(lambda),
                                     message,
                                     "Lambdas should be simple expressions. Extract complex logic to named methods.");
    }
}
