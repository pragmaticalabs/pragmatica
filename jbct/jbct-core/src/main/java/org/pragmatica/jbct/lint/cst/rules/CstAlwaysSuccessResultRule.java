package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-RET-05: Avoid methods that always return Result.success().
 */
public class CstAlwaysSuccessResultRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-05";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        return findAll(root, RuleId.MethodDecl.class).stream()
                      .filter(method -> returnsResult(method, source))
                      .filter(method -> alwaysReturnsSuccess(method, source))
                      .map(method -> createDiagnostic(method, source, ctx));
    }

    private boolean returnsResult(CstNode method, String source) {
        return childByRule(method, RuleId.Type.class).map(type -> text(type, source))
                          .filter(typeText -> typeText.startsWith("Result<"))
                          .isPresent();
    }

    private boolean alwaysReturnsSuccess(CstNode method, String source) {
        var methodText = text(method, source);
        // Check if only uses Result.success() and never failure
        boolean hasSuccess = methodText.contains("Result.success(");
        boolean hasFailure = methodText.contains("Result.failure(") ||
        methodText.contains(".result()") || // cause.result()
        methodText.contains("failure");
        return hasSuccess && !hasFailure;
    }

    private Diagnostic createDiagnostic(CstNode method, String source, LintContext ctx) {
        var methodName = childByRule(method, RuleId.Identifier.class).map(id -> text(id, source))
                                    .or("(unknown)");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' always returns Result.success(); return T directly",
                                     "If a method can never fail, wrapping in Result adds unnecessary complexity.")
                         .withExample("""
            // Before: Result that never fails
            public static Result<Config> config(String name) {
                return Result.success(new Config(name));
            }

            // After: return T directly
            public static Config config(String name) {
                return new Config(name);
            }
            """);
    }
}
