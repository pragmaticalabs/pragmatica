package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-RET-03: Never return null.
///
/// JBCT code never returns null. Use Option<T> for optional values.
/// Annotate with @NullReturn if returning null is intentional (side-effect fold branches,
/// JDK callback contracts like Map.computeIfPresent).
public class CstNullReturnRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-03";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/skills/jbct/fundamentals/four-return-kinds.md";
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
        // Find all return statements that return null
        return findAllStatements(root).stream()
                      .filter(this::isReturnNull)
                      .map(stmt -> createDiagnostic(root, stmt, ctx));
    }

    private boolean isReturnNull(Cursor stmt) {
        // Check if this is "return null;"
        var stmtText = text(stmt).trim();
        if (!stmtText.startsWith("return")) {
            return false;
        }
        // Check for "return null;" pattern — the null literal may be wrapped by Primary
        return stmtText.matches("return\\s+null\\s*;");
    }

    private Diagnostic createDiagnostic(Cursor root, Cursor stmt, LintContext ctx) {
        var line = startLine(stmt);
        var column = startColumn(stmt);
        // Find enclosing method name
        var methodName = findAncestor(root, stmt, RuleKind.MEMBER)
            .map(member -> extractMethodName(text(member)))
            .or("(unknown)");
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     column,
                                     "Method '" + methodName + "' returns null; use Option<T> instead",
                                     "JBCT code never returns null. Use Option.none() for absent values, "
                                     + "Option.some(value) for present values. "
                                     + "Annotate with @NullReturn if null return is intentional.")
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

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);
        return matcher.find() ? matcher.group(1) : "(unknown)";
    }
}
