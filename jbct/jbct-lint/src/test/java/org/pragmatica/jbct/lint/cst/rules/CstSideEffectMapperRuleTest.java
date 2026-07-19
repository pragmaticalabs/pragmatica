package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-SIDE-01 side-effect-in-mapper rule (INFO).
class CstSideEffectMapperRuleTest {
    private static final String RULE_ID = "JBCT-SIDE-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void flags_logging_call_in_map_lambda() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> log.info(x));
                    }
                }
                """));
    }

    @Test
    void flags_save_call_in_filter_lambda() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return items.filter(x -> repo.save(x));
                    }
                }
                """));
    }

    @Test
    void clean_on_method_reference() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(String::trim);
                    }
                }
                """));
    }

    @Test
    void clean_on_pure_transformation_lambda() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> x.trim());
                    }
                }
                """));
    }

    @Test
    void does_not_flag_side_effect_in_terminal_onSuccess() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return items.onSuccess(x -> log.info(x));
                    }
                }
                """));
    }

    @Test
    void severity_is_info() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> log.info(x));
                    }
                }
                """).stream()
                    .filter(diagnostic -> diagnostic.ruleId()
                                                    .equals(RULE_ID))
                    .toList();

        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.INFO, diagnostics.getFirst()
                                                         .severity());
    }

    @Test
    void suppressed_by_annotation() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-SIDE-01")
                    Object run() {
                        return items.map(x -> log.info(x));
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
