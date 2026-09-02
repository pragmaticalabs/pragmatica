package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-STY-09 nested-ternary rule, including the JBCT-LAM-03 (ternary-in-lambda) overlap.
class CstNestedTernaryRuleTest {
    private static final String RULE_ID = "JBCT-STY-09";
    private static final String LAMBDA_TERNARY = "JBCT-LAM-03";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_ternary_nested_in_else_branch() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(int x) {
                        return x > 0 ? "a" : x > 5 ? "b" : "c";
                    }
                }
                """));
    }

    @Test
    void detects_ternary_nested_in_then_branch() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(int x) {
                        return x > 0 ? (x > 5 ? "a" : "b") : "c";
                    }
                }
                """));
    }

    @Test
    void reports_chain_once() {
        var diagnostics = ruleDiagnostics("""
                package org.example;
                class Foo {
                    Object run(int x) {
                        return x > 0 ? "a" : x > 5 ? "b" : x > 9 ? "c" : "d";
                    }
                }
                """);

        assertEquals(1, diagnostics.size());
    }

    @Test
    void no_false_positive_on_single_ternary() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run(int x) {
                        return x > 0 ? "a" : "b";
                    }
                }
                """));
    }

    @Test
    void nested_ternary_inside_lambda_is_owned_by_lam03_not_sty09() {
        var source = """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> x > 0 ? (x > 5 ? "a" : "b") : "c");
                    }
                }
                """;

        assertFalse(hasRule(source), "STY-09 must defer ternaries inside lambdas to LAM-03");
        assertTrue(lint(source).stream()
                               .anyMatch(diagnostic -> diagnostic.ruleId()
                                                                 .equals(LAMBDA_TERNARY)));
    }

    @Test
    void single_ternary_inside_lambda_triggers_only_lam03() {
        var source = """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> x > 0 ? "a" : "b");
                    }
                }
                """;

        assertFalse(hasRule(source));
        assertTrue(lint(source).stream()
                               .anyMatch(diagnostic -> diagnostic.ruleId()
                                                                 .equals(LAMBDA_TERNARY)));
    }

    private boolean hasRule(String source) {
        return !ruleDiagnostics(source).isEmpty();
    }

    private List<Diagnostic> ruleDiagnostics(String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId()
                                                           .equals(RULE_ID))
                           .toList();
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
