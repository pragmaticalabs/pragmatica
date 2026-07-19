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

/// JBCT-NAM-03 `*State` suffix discipline.
class CstStateSuffixRuleTest {
    private static final String RULE_ID = "JBCT-NAM-03";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_record_variant_with_state_suffix() {
        assertTrue(hasRule("""
                package org.example;
                sealed interface HoldState {
                    record HeldState() implements HoldState {}
                }
                """));
    }

    @Test
    void detects_class_variant_with_state_suffix() {
        assertTrue(hasRule("""
                package org.example;
                sealed interface BookingState {
                    final class ConfirmedState implements BookingState {}
                }
                """));
    }

    @Test
    void no_false_positive_on_bare_variant_name() {
        assertFalse(hasRule("""
                package org.example;
                sealed interface HoldState {
                    record Held() implements HoldState {}
                }
                """));
    }

    @Test
    void no_false_positive_on_the_sealed_sum_interface_itself() {
        assertFalse(hasRule("""
                package org.example;
                sealed interface HoldState {
                    record Free() implements HoldState {}
                }
                """));
    }

    @Test
    void no_false_positive_on_state_named_type_implementing_non_state_interface() {
        assertFalse(hasRule("""
                package org.example;
                record SessionState(String id) implements Serializable {}
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
