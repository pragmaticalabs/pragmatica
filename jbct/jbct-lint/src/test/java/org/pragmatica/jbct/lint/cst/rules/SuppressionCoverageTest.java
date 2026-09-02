package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// One test per suppression path recognized by `SuppressionExtractor` (#454).
///
/// Each test proves the path is non-vacuous: the same code triggers the rule at baseline and is
/// clean once annotated. Paths covered: `@SuppressWarnings` single ID, list form, and `"all"`,
/// plus the intent annotations `@Contract` (all rules), `@TerminalOperation` (JBCT-PAT-03), and
/// `@NullReturn` (JBCT-RET-03).
class SuppressionCoverageTest {
    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void suppressWarnings_singleRuleId_suppressesThatRule() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    public void run() {}
                }
                """, "JBCT-RET-01"), "baseline void method should trigger JBCT-RET-01");

        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-RET-01")
                    public void run() {}
                }
                """, "JBCT-RET-01"), "@SuppressWarnings(\"JBCT-RET-01\") should suppress it");
    }

    @Test
    void suppressWarnings_listForm_suppressesEachListedRule() {
        var suppressed = lint("""
                package org.example;
                class Foo {
                    @SuppressWarnings({"JBCT-RET-01", "JBCT-LAM-02"})
                    public void run() {
                        items.forEach(x -> { log(x); });
                    }
                }
                """);

        assertFalse(containsRule(suppressed, "JBCT-RET-01"), "list form should suppress JBCT-RET-01");
        assertFalse(containsRule(suppressed, "JBCT-LAM-02"), "list form should suppress JBCT-LAM-02");

        var baseline = lint("""
                package org.example;
                class Foo {
                    public void run() {
                        items.forEach(x -> { log(x); });
                    }
                }
                """);

        assertTrue(containsRule(baseline, "JBCT-RET-01"), "baseline should trigger JBCT-RET-01");
        assertTrue(containsRule(baseline, "JBCT-LAM-02"), "baseline should trigger JBCT-LAM-02");
    }

    @Test
    void suppressWarnings_all_suppressesEveryRule() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    public void run() {}
                }
                """, "JBCT-RET-01"), "baseline void method should trigger JBCT-RET-01");

        assertFalse(hasRule("""
                package org.example;
                @SuppressWarnings("all")
                class Foo {
                    public void run() {}
                }
                """, "JBCT-RET-01"), "@SuppressWarnings(\"all\") should suppress every rule");
    }

    @Test
    void contract_suppressesAllRulesOnMethod() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    public void run() {}
                }
                """, "JBCT-RET-01"), "baseline void method should trigger JBCT-RET-01");

        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @Contract
                    public void run() {}
                }
                """, "JBCT-RET-01"), "@Contract should suppress JBCT-RET-01");
    }

    @Test
    void terminalOperation_suppressesBlockingAwait() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    void run() {
                        fetchData().await();
                    }
                }
                """, "JBCT-PAT-03"), "baseline .await() should trigger JBCT-PAT-03");

        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @TerminalOperation
                    void run() {
                        fetchData().await();
                    }
                }
                """, "JBCT-PAT-03"), "@TerminalOperation should suppress JBCT-PAT-03");
    }

    @Test
    void nullReturn_suppressesNullReturn() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    String run() {
                        return null;
                    }
                }
                """, "JBCT-RET-03"), "baseline return null should trigger JBCT-RET-03");

        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @NullReturn
                    String run() {
                        return null;
                    }
                }
                """, "JBCT-RET-03"), "@NullReturn should suppress JBCT-RET-03");
    }

    private boolean hasRule(String source, String ruleId) {
        return containsRule(lint(source), ruleId);
    }

    private boolean containsRule(List<Diagnostic> diagnostics, String ruleId) {
        return diagnostics.stream()
                          .anyMatch(diagnostic -> diagnostic.ruleId()
                                                            .equals(ruleId));
    }

    private List<Diagnostic> lint(String source) {
        var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);

        return linter.lint(sourceFile)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
