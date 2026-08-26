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
        void flags_two_business_entries_alongside_a_scheduled_hook() {
            // Exempting the hook must not hide a genuinely dual-entry interface.
            assertTrue(hasRule("""
                    package org.example;
                    public interface SweepHolds {
                        record Request(String tenant) {}
                        record Response(int swept) {}
                        static SweepHolds sweepHolds() { return r -> null; }
                        Promise<Response> execute(Request request);
                        Promise<Response> executeAgain(Request request);
                        @Heartbeat
                        Promise<Unit> sweep();
                    }
                    """));
        }

        @Test
        void flags_unqualified_zero_parameter_hook_as_a_second_entry() {
            // Without a qualifier annotation the method is an ordinary second entry method.
            assertTrue(hasRule("""
                    package org.example;
                    public interface SweepHolds {
                        record Request(String tenant) {}
                        record Response(int swept) {}
                        static SweepHolds sweepHolds() { return r -> null; }
                        Promise<Response> execute(Request request);
                        Promise<Unit> sweep();
                    }
                    """));
        }

        @Test
        void flags_missing_request_response_when_the_parameter_annotation_is_not_a_qualifier() {
            // @Deprecated is not a subscription qualifier — the fact-consumer exemption must not fire.
            assertTrue(hasRule("""
                    package org.example;
                    public interface ReleaseSeat {
                        static ReleaseSeat releaseSeat() { return e -> null; }
                        Promise<Unit> execute(@Deprecated SeatReleased event);
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
        void clean_on_fact_consumer_with_a_qualified_parameter() {
            // The subscription contract IS the request type; a synthetic Request wrapper around the
            // published fact would be pure indirection (#647).
            assertFalse(hasRule("""
                    package org.example;
                    public interface ReleaseSeat {
                        Promise<Unit> execute(@SeatEvents SeatReleased event);

                        static ReleaseSeat releaseSeat() {
                            return event -> Promise.unitPromise();
                        }
                    }
                    """));
        }

        @Test
        void clean_on_scheduled_slice_with_an_entry_and_a_qualified_hook() {
            // The zero-parameter Promise<Unit> hook is the Scheduled contract's shape, not a second
            // entry method — splitting it out would fragment one use case into two deployables.
            assertFalse(hasRule("""
                    package org.example;
                    public interface SweepHolds {
                        record Request(String tenant) {}
                        record Response(int swept) {}

                        Promise<Response> execute(Request request);

                        @Heartbeat
                        Promise<Unit> sweep();

                        static SweepHolds sweepHolds() {
                            return request -> Promise.success(new Response(0));
                        }
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
    /// Fail-closed gate on the fact-consumer exemption (pre-merge field review): an incidental
    /// annotation on a parameter that IS the Request shape must not exempt the missing nested pair.
    @org.junit.jupiter.api.Test
    void annotatedRequestTypedParameter_isNotAFactConsumer() {
        var diagnostics = lint("""
                               package demo;
                               import org.pragmatica.aether.slice.annotation.Slice;
                               @Slice
                               public interface RegisterUser {
                                   Promise<Unit> execute(@Traced CreateRequest request);
                                   static RegisterUser registerUser() { return request -> null; }
                               }
                               """);

        org.assertj.core.api.Assertions.assertThat(diagnostics)
                                       .anyMatch(d -> d.message().contains("no nested Request/Response"));
    }

}
