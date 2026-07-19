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

/// JBCT-ORD-01 member-ordering rule.
class CstMemberOrderingRuleTest {
    private static final String RULE_ID = "JBCT-ORD-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class ValueObject {
        @Test
        void flags_factory_before_constant() {
            assertTrue(hasRule("""
                    package org.example;
                    record Money(long cents) {
                        static Result<Money> money(long cents) {
                            return Result.success(new Money(cents));
                        }
                        static final Money ZERO = new Money(0);
                    }
                    """));
        }

        @Test
        void clean_on_constants_factory_accessors_order() {
            assertFalse(hasRule("""
                    package org.example;
                    record Money(long cents) {
                        static final Money ZERO = new Money(0);
                        static Result<Money> money(long cents) {
                            return Result.success(new Money(cents));
                        }
                        long doubled() {
                            return cents * 2;
                        }
                    }
                    """));
        }

        @Test
        void ignores_nested_type_after_factory() {
            // Nested type declarations are absent from the value-object table — never order-breaking.
            assertFalse(hasRule("""
                    package org.example;
                    record Money(long cents) {
                        static Result<Money> money(long cents) {
                            return Result.success(new Money(cents));
                        }
                        record Meta(String tag) {}
                    }
                    """));
        }

        @Test
        void ignores_trailing_static_helper_not_returning_own_type() {
            // A static helper that does not produce the own type is not the factory — never order-breaking.
            assertFalse(hasRule("""
                    package org.example;
                    record Money(long cents) {
                        static Result<Money> money(long cents) {
                            return Result.success(new Money(cents));
                        }
                        long doubled() {
                            return cents * 2;
                        }
                        private static String format(long c) {
                            return Long.toString(c);
                        }
                    }
                    """));
        }
    }

    @Nested
    class UseCase {
        @Test
        void flags_execute_before_factory() {
            assertTrue(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        Result<Request> execute(Request request);
                        static RegisterUser registerUser() {
                            return request -> null;
                        }
                    }
                    """));
        }

        @Test
        void clean_on_records_steps_factory_execute_order() {
            assertFalse(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        interface CheckEmail { Result<Request> apply(Request request); }
                        static RegisterUser registerUser() {
                            return request -> null;
                        }
                        Result<Request> execute(Request request);
                    }
                    """));
        }

        @Test
        void ignores_constant_after_execute() {
            // Interface constants are absent from the use-case table — never order-breaking.
            assertFalse(hasRule("""
                    package org.example;
                    public interface RegisterUser {
                        record Request(String email) {}
                        static RegisterUser registerUser() {
                            return request -> null;
                        }
                        Result<Request> execute(Request request);
                        String TAG = "reg";
                    }
                    """));
        }
    }

    @Test
    void does_not_flag_unclassified_file() {
        assertFalse(hasRule("""
                package org.example;
                public class Service {
                    private final Repo repo;
                    Service(Repo repo) { this.repo = repo; }
                    String name() { return "x"; }
                    static final String TAG = "svc";
                }
                """));
    }

    @Test
    void suppressed_by_annotation() {
        assertFalse(hasRule("""
                package org.example;
                @SuppressWarnings("JBCT-ORD-01")
                record Money(long cents) {
                    static Result<Money> money(long cents) {
                        return Result.success(new Money(cents));
                    }
                    static final Money ZERO = new Money(0);
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
