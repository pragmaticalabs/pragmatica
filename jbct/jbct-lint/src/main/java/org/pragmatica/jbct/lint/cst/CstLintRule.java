package org.pragmatica.jbct.lint.cst;

import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.parser.Cursor;


/// Interface for CST-based JBCT lint rules.
///
/// Each rule analyzes a CST and produces zero or more diagnostics.
public interface CstLintRule {
    /// Get the rule ID (e.g., "JBCT-RET-01").
    String ruleId();

    /// Analyze a CST root cursor and return any diagnostics.
    ///
    /// @param root   the root cursor (CompilationUnit)
    /// @param source the original source code
    /// @param ctx    the lint context providing configuration
    /// @return stream of diagnostics found
    Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx);

    /// Whether the linter's generic line-range suppression (`@SuppressWarnings`, `@Contract`,
    /// `@TerminalOperation`, `@NullReturn` on ANY enclosing declaration) applies to this rule's
    /// diagnostics. A rule that resolves its own, narrower exemption returns `false` — JBCT-EX-03
    /// accepts a mark only on the catch's nearest enclosing method (#1247), so a class-level,
    /// local-variable or parameter mark must not silence it.
    default boolean usesScopedSuppression() {
        return true;
    }
}
