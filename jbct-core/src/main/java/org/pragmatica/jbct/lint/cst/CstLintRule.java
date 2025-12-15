package org.pragmatica.jbct.lint.cst;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;

import java.util.stream.Stream;

/**
 * Interface for CST-based JBCT lint rules.
 *
 * Each rule analyzes a CST and produces zero or more diagnostics.
 */
public interface CstLintRule {

    /**
     * Get the rule ID (e.g., "JBCT-RET-01").
     */
    String ruleId();

    /**
     * Get a short description of what this rule checks.
     */
    String description();

    /**
     * Analyze a CST root node and return any diagnostics.
     *
     * @param root   the root CST node (CompilationUnit)
     * @param source the original source code
     * @param ctx    the lint context providing configuration
     * @return stream of diagnostics found
     */
    Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx);
}
