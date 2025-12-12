package org.pragmatica.jbct.lint;

import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;

import java.util.List;

/**
 * Interface for JBCT code linting operations.
 */
public interface Linter {

    /**
     * Lint a source file and return all diagnostics.
     *
     * @param source the source file to lint
     * @return Result containing list of diagnostics, or an error
     */
    Result<List<Diagnostic>> lint(SourceFile source);

    /**
     * Check if a source file passes all enabled rules.
     *
     * @param source the source file to check
     * @return Result containing true if no errors found, false otherwise
     */
    Result<Boolean> check(SourceFile source);

    /**
     * Get the context used by this linter.
     */
    LintContext context();
}
