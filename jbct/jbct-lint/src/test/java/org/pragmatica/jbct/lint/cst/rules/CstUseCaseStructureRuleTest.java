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

/// JBCT-UC-02 use-case interface structure rule.
class CstUseCaseStructureRuleTest {
    private static final String RULE_ID = "JBCT-UC-02";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void flags_missing_static_factory() {
            assertTrue(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        record Response(String id) {}
                        Result<Response> execute(Request request);
                    }
                    """));
        }

        @Test
        void flags_request_response_declared_outside() {
            assertTrue(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        static RegisterUser registerUser() { return r -> null; }
                        Result<Response> execute(Request request);
                    }
                    """));
        }

        @Test
        void flags_more_than_one_entry_method() {
            assertTrue(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        record Response(String id) {}
                        static RegisterUser registerUser() { return r -> null; }
                        Result<Response> execute(Request request);
                        Result<Response> executeAgain(Request request);
                    }
                    """));
        }

        @Test
        void flags_static_method_not_returning_use_case_type() {
            // A static method that does not return the interface's own type is not a factory.
            assertTrue(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        record Response(String id) {}
                        static String describe() { return "reg"; }
                        Result<Response> execute(Request request);
                    }
                    """));
        }
    }

    @Nested
    class CleanCases {
        @Test
        void clean_on_complete_use_case() {
            assertFalse(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        record Response(String id) {}
                        static RegisterUser registerUser() {
                            return request -> null;
                        }
                        Result<Response> execute(Request request);
                    }
                    """));
        }

        @Test
        void does_not_flag_a_value_object() {
            assertFalse(hasRule("""
                    package org.example;
                    public record Email(String value) {
                        public static Result<Email> email(String raw) {
                            return Result.success(new Email(raw));
                        }
                    }
                    """));
        }

        @Test
        void does_not_flag_a_step_interface() {
            assertFalse(hasRule("""
                    package org.example;
                    public interface CheckEmail {
                        Promise<ValidRequest> apply(ValidRequest request);
                    }
                    """));
        }
    }

    @Test
    void suppressed_by_annotation() {
        assertFalse(hasRule("""
                package org.example;
                @SuppressWarnings("JBCT-UC-02")
                public interface RegisterUser {
                    record Request(String email) {}
                    record Response(String id) {}
                    Result<Response> execute(Request request);
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
