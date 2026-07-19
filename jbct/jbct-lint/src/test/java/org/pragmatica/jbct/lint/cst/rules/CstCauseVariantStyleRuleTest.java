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

/// JBCT-SEAL-02 cause-variant-style rule.
class CstCauseVariantStyleRuleTest {
    private static final String RULE_ID = "JBCT-SEAL-02";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_empty_record_cause() {
        assertTrue(hasRule("""
                package org.example;
                sealed interface RegError extends Cause {
                    record TokenFailed() implements RegError {}
                }
                """));
    }

    @Test
    void detects_class_cause() {
        assertTrue(hasRule("""
                package org.example;
                sealed interface RegError extends Cause {
                    final class TokenFailed implements RegError {}
                }
                """));
    }

    @Test
    void no_false_positive_on_data_carrying_record_cause() {
        assertFalse(hasRule("""
                package org.example;
                sealed interface RegError extends Cause {
                    record HashingFailed(Throwable cause) implements RegError {}
                }
                """));
    }

    @Test
    void no_false_positive_on_enum_cause() {
        assertFalse(hasRule("""
                package org.example;
                sealed interface RegError extends Cause {
                    enum General implements RegError {
                        EMAIL_ALREADY_REGISTERED
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_record_implementing_non_cause_interface() {
        assertFalse(hasRule("""
                package org.example;
                sealed interface Shape {
                    record Point() implements Shape {}
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
