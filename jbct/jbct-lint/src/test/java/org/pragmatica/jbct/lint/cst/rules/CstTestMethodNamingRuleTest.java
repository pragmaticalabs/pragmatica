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

/// JBCT-NAM-05 test-method naming rule.
class CstTestMethodNamingRuleTest {
    private static final String RULE_ID = "JBCT-NAM-05";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_single_segment_test_name() {
        assertTrue(hasRule("""
                package org.example;
                class FooTest {
                    @Test void run() {}
                }
                """));
    }

    @Test
    void detects_two_segment_test_name() {
        assertTrue(hasRule("""
                package org.example;
                class FooTest {
                    @Test void run_works() {}
                }
                """));
    }

    @Test
    void no_false_positive_on_three_segment_test_name() {
        assertFalse(hasRule("""
                package org.example;
                class FooTest {
                    @Test void run_returns_value() {}
                }
                """));
    }

    @Test
    void ignores_non_test_methods() {
        assertFalse(hasRule("""
                package org.example;
                class FooTest {
                    @Test void run_returns_value() {}
                    void helper() {}
                }
                """));
    }

    @Test
    void inactive_in_files_without_tests() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    void run() {}
                }
                """));
    }

    private boolean hasRule(String source) {
        return lint(source).stream()
                           .anyMatch(diagnostic -> diagnostic.ruleId()
                                                             .equals(RULE_ID));
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
