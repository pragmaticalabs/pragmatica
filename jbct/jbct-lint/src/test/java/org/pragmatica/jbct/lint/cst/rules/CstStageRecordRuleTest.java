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

/// JBCT-STAGE-01 stage-record deep-chain rule.
class CstStageRecordRuleTest {
    private static final String RULE_ID = "JBCT-STAGE-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void flags_three_hop_chain() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return ctx.request().request().request().userId();
                    }
                }
                """));
    }

    @Test
    void clean_on_two_hop_chain() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return ctx.request().request().userId();
                    }
                }
                """));
    }

    @Test
    void ignores_chain_in_comment() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        // request().request().request() is the smell
                        return ctx.request().userId();
                    }
                }
                """));
    }

    @Test
    void ignores_chain_in_string_literal() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run() {
                        return "request().request().request()";
                    }
                }
                """));
    }

    @Test
    void suppressed_by_annotation() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-STAGE-01")
                    Object run() {
                        return ctx.request().request().request().userId();
                    }
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
