package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-PAT-03: Blocking `.await()` call.
///
/// `.await()` blocks the calling thread. In async runtime code, compose with
/// `.map()`/`.flatMap()` instead. Annotate the enclosing method with
/// `@TerminalOperation` if blocking is intentional.
public class CstAwaitRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-PAT-03";
    private static final Pattern AWAIT_PATTERN = Pattern.compile("\\.await\\s*\\(\\s*\\)");

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
        return findAllStatements(root).stream()
                      .filter(stmt -> containsAwait(stmt, source))
                      .map(stmt -> createDiagnostic(root, stmt, source, ctx));
    }

    private boolean containsAwait(CstNode stmt, String source) {
        return AWAIT_PATTERN.matcher(text(stmt, source)).find();
    }

    private Diagnostic createDiagnostic(CstNode root, CstNode stmt, String source, LintContext ctx) {
        var methodName = findAncestor(root, stmt, RuleId.Member.class)
                .flatMap(md -> childByRule(md, RuleId.Identifier.class))
                .map(id -> text(id, source))
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
}
