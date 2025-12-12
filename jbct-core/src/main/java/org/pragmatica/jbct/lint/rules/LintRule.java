package org.pragmatica.jbct.lint.rules;

import com.github.javaparser.ast.CompilationUnit;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;

import java.util.stream.Stream;

/**
 * Interface for JBCT lint rules.
 *
 * Each rule analyzes a compilation unit and produces zero or more diagnostics.
 */
public interface LintRule {

    /**
     * Get the rule ID (e.g., "JBCT-RET-01").
     */
    String ruleId();

    /**
     * Get a short description of what this rule checks.
     */
    String description();

    /**
     * Analyze a compilation unit and return any diagnostics.
     *
     * @param cu  the compilation unit to analyze
     * @param ctx the lint context providing configuration and type resolution
     * @return stream of diagnostics found
     */
    Stream<Diagnostic> analyze(CompilationUnit cu, LintContext ctx);
}
