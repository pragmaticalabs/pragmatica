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

/// JBCT-MUT-01 parameter-reassignment rule.
class CstParameterReassignmentRuleTest {
    private static final String RULE_ID = "JBCT-MUT-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_simple_reassignment() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    int run(int count) {
                        count = count + 1;
                        return count;
                    }
                }
                """));
    }

    @Test
    void detects_compound_assignment() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    int run(int count) {
                        count += 1;
                        return count;
                    }
                }
                """));
    }

    @Test
    void detects_increment() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    int run(int count) {
                        count++;
                        return count;
                    }
                }
                """));
    }

    @Test
    void detects_lambda_parameter_reassignment() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    void run() {
                        java.util.List.of(1).forEach(n -> { n = n + 1; });
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_local_variable_reassignment() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    int run(int count) {
                        var total = count;
                        total = total + 1;
                        return total;
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_field_assignment() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    private int count;
                    void run(int count) {
                        this.count = count;
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_null_comparison() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    boolean run(Config count) {
                        return count == null;
                    }
                }
                """));
    }

    @Test
    void nested_type_method_reassignment_reports_once() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void outer(int q) {
                        Object o = new Object() {
                            void go(int p) {
                                p = p + 1;
                            }
                        };
                    }
                }
                """).stream()
                    .filter(diagnostic -> diagnostic.ruleId()
                                                    .equals(RULE_ID))
                    .toList();

        assertEquals(1, diagnostics.size());
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
