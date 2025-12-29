package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-RET-03: Never return null.
 *
 * JBCT code never returns null. Use Option<T> for optional values.
 */
public class CstNullReturnRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-03";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/fundamentals/four-return-kinds.md";

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        // Get package name
        var packageName = findFirst(root, RuleId.PackageDecl.class)
                          .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                          .map(qn -> text(qn, source))
                          .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        // Find all return statements that return null
        return findAll(root, RuleId.Stmt.class)
               .stream()
               .filter(stmt -> isReturnNull(stmt, source))
               .map(stmt -> createDiagnostic(root, stmt, source, ctx));
    }

    private boolean isReturnNull(CstNode stmt, String source) {
        // Check if this is "return null;"
        var stmtText = text(stmt, source)
                       .trim();
        if (!stmtText.startsWith("return")) {
            return false;
        }
        // Check if it contains "null" literal
        return findFirst(stmt, RuleId.Literal.class)
               .map(lit -> text(lit, source)
                           .trim())
               .filter("null"::equals)
               .isPresent();
    }

    private Diagnostic createDiagnostic(CstNode root, CstNode stmt, String source, LintContext ctx) {
        var line = startLine(stmt);
        var column = startColumn(stmt);
        // Find enclosing method name
        var methodName = findAncestor(root, stmt, RuleId.MethodDecl.class)
                         .flatMap(md -> childByRule(md, RuleId.Identifier.class))
                         .map(id -> text(id, source))
                         .or("(unknown)");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     column,
                                     "Method '" + methodName + "' returns null; use Option<T> instead",
                                     "JBCT code never returns null. Use Option.none() for absent values, "
                                     + "Option.some(value) for present values.")
                         .withExample("""
            // Before: returning null
            public User findUser(UserId id) {
                User user = repository.findById(id);
                if (user == null) return null;  // Don't return null
                return user;
            }

            // After: using Option
            public Option<User> findUser(UserId id) {
                return Option.option(repository.findById(id));
            }
            """)
                         .withDocLink(DOC_LINK);
    }
}
