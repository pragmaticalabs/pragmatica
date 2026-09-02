package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-VAL-01 boolean-validation-method rule.
class CstBooleanValidationRuleTest {
    private static final String RULE_ID = "JBCT-VAL-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void flags_boolean_isValid_on_value_object() {
            assertTrue(hasRule("""
                    package org.example;
                    record Email(String value) {
                        static Result<Email> email(String raw) {
                            return Result.success(new Email(raw));
                        }
                        boolean isValid() {
                            return value.contains("@");
                        }
                    }
                    """));
        }

        @Test
        void flags_boolean_validate_on_value_object() {
            assertTrue(hasRule("""
                    package org.example;
                    record Email(String value) {
                        static Result<Email> email(String raw) {
                            return Result.success(new Email(raw));
                        }
                        boolean validate() {
                            return value.contains("@");
                        }
                    }
                    """));
        }
    }

    @Nested
    class CleanCases {
        @Test
        void clean_when_only_result_factory() {
            assertFalse(hasRule("""
                    package org.example;
                    record Email(String value) {
                        static Result<Email> email(String raw) {
                            return Result.success(new Email(raw));
                        }
                    }
                    """));
        }

        @Test
        void does_not_flag_result_returning_validate() {
            assertFalse(hasRule("""
                    package org.example;
                    record Email(String value) {
                        static Result<Email> email(String raw) {
                            return Result.success(new Email(raw));
                        }
                        Result<Email> validate() {
                            return email(value);
                        }
                    }
                    """));
        }

        @Test
        void does_not_flag_in_unclassified_file() {
            assertFalse(hasRule("""
                    package org.example;
                    class FormBinder {
                        boolean isValid() {
                            return true;
                        }
                    }
                    """));
        }
    }

    @Test
    void suppressed_by_annotation() {
        assertFalse(hasRule("""
                package org.example;
                @SuppressWarnings("JBCT-VAL-01")
                record Email(String value) {
                    static Result<Email> email(String raw) {
                        return Result.success(new Email(raw));
                    }
                    boolean isValid() {
                        return value.contains("@");
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
