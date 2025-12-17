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
public class JbctLinter implements Linter {

    private final LintContext context;
    private final CstLinter delegate;

    private JbctLinter(LintContext context) {
        this.context = context;
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

    @Override
    public Result<List<Diagnostic>> lint(SourceFile source) {
        return delegate.lint(source);
    }

    @Override
    public Result<Boolean> check(SourceFile source) {
        return delegate.check(source);
    }

    @Override
    public LintContext context() {
        return context;
    }
}
