package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-PAT-03: Blocking `.await()` call.
///
/// `.await()` blocks the calling thread. In async runtime code, compose with
/// `.map()`/`.flatMap()` instead. Annotate the enclosing method with
/// `@TerminalOperation` if blocking is intentional.
public class CstAwaitRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-PAT-03";
    private static final Pattern AWAIT_PATTERN = Pattern.compile("\\.await\\s*\\(\\s*\\)");
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

        return findAllStatements(root).stream()
                                .filter(this::containsAwait)
                                .map(stmt -> createDiagnostic(root, stmt, ctx));
    }

    private boolean containsAwait(Cursor stmt) {
        return AWAIT_PATTERN.matcher(text(stmt)).find();
    }

    private Diagnostic createDiagnostic(Cursor root, Cursor stmt, LintContext ctx) {
        var methodName = findAncestor(root, stmt, RuleKind.MEMBER).map(member -> extractMethodName(text(member)))
                                     .or("(unknown)");

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Blocking .await() call in method '" + methodName + "'",
                                     ".await() blocks the calling thread. Compose with .map()/.flatMap() instead. "
                                    + "Annotate with @TerminalOperation if blocking is intentional (CLI, lifecycle, background thread).")
                         .withExample("""
            // Before: blocking
            var result = fetchUser(id).await();
            return processUser(result);

            // After: non-blocking composition
            return fetchUser(id)
                .map(this::processUser);

            // If blocking is intentional:
            @TerminalOperation
            Result<User> fetchUserSync(UserId id) {
                return fetchUser(id).await();
            }
            """);
    }

    private static String extractMethodName(String memberText) {
        // Find the first identifier followed by '(' — the method declaration.
        // Skips type names (handled by the `\\b` boundary and pattern shape).
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
