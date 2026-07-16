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
}
