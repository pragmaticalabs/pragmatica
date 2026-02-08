package org.pragmatica.jbct.lint;

import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;

import java.util.List;

/**
 * JBCT linter implementation.
 *
 * Analyzes Java source code for JBCT compliance using a set of configurable rules.
 *
 * Uses CST-based linter for accurate trivia-preserving analysis.
 */
public class JbctLinter {
    private final CstLinter delegate;

    private JbctLinter(LintContext context) {
        this.delegate = CstLinter.cstLinter(context);
    }

    /**
     * Factory method with default context and all rules.
     */
    public static JbctLinter jbctLinter() {
        return new JbctLinter(LintContext.defaultContext());
    }

    /**
     * Factory method with custom context.
     */
    public static JbctLinter jbctLinter(LintContext context) {
        return new JbctLinter(context);
    }

    public Result<List<Diagnostic>> lint(SourceFile source) {
        return delegate.lint(source);
    }

    public Result<Boolean> check(SourceFile source) {
        return delegate.check(source);
    }
}
