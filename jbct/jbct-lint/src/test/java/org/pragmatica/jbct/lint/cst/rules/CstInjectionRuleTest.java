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

/// JBCT-INJ-01 constructor/factory-injection rule.
class CstInjectionRuleTest {
    private static final String RULE_ID = "JBCT-INJ-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void flags_non_final_instance_field_in_step_impl() {
            assertTrue(hasRule("""
                    package org.example;
                    interface CheckEmail {
                        Result<String> apply(String email);
                    }
                    class CheckEmailImpl implements CheckEmail {
                        private Repo repo;
                        public Result<String> apply(String email) {
                            return repo.find(email);
                        }
                    }
                    """));
        }

        @Test
        void flags_setter_in_step_impl() {
            assertTrue(hasRule("""
                    package org.example;
                    interface CheckEmail {
                        Result<String> apply(String email);
                    }
                    class CheckEmailImpl implements CheckEmail {
                        private final Repo repo = null;
                        public void setRepo(Repo repo) {
                            System.out.println(repo);
                        }
                        public Result<String> apply(String email) {
                            return repo.find(email);
                        }
                    }
                    """));
        }

        @Test
        void flags_setter_on_record_impl() {
            // A record cannot trip the field path (final components) but a setX method does trip the setter path.
            assertTrue(hasRule("""
                    package org.example;
                    interface CheckEmail {
                        Result<String> apply(String email);
                    }
                    record CheckEmailImpl(Repo repo) implements CheckEmail {
                        public Result<String> apply(String email) {
                            return repo.find(email);
                        }
                        public void setRepo(Repo repo) {
                            System.out.println(repo);
                        }
                    }
                    """));
        }
    }

    @Nested
    class CleanCases {
        @Test
        void clean_on_final_field_constructor_injection() {
            assertFalse(hasRule("""
                    package org.example;
                    interface CheckEmail {
                        Result<String> apply(String email);
                    }
                    class CheckEmailImpl implements CheckEmail {
                        private final Repo repo;
                        CheckEmailImpl(Repo repo) {
                            this.repo = repo;
                        }
                        public Result<String> apply(String email) {
                            return repo.find(email);
                        }
                    }
                    """));
        }

        @Test
        void does_not_flag_impl_of_out_of_file_interface() {
            // AutoCloseable is not declared in this file, so the impl's role is not single-file
            // determinable — a legitimate stateful adapter must not be flagged.
            assertFalse(hasRule("""
                    package org.example;
                    class Cache implements AutoCloseable {
                        private int entries;
                        public void setEntries(int entries) {
                            this.entries = entries;
                        }
                        public void close() {}
                    }
                    """));
        }

        @Test
        void does_not_flag_record_error_variant() {
            assertFalse(hasRule("""
                    package org.example;
                    sealed interface RegError extends Cause {
                        record HashingFailed(Throwable cause) implements RegError {}
                    }
                    """));
        }

        @Test
        void does_not_flag_test_class() {
            assertFalse(hasRule("""
                    package org.example;
                    interface CheckEmail {
                        Result<String> apply(String email);
                    }
                    class CheckEmailTest implements CheckEmail {
                        private int state;
                        @Test
                        void apply_works_forInput() {}
                        public Result<String> apply(String email) {
                            return null;
                        }
                    }
                    """));
        }
    }

    @Test
    void suppressed_by_annotation() {
        assertFalse(hasRule("""
                package org.example;
                interface CheckEmail {
                    Result<String> apply(String email);
                }
                @SuppressWarnings("JBCT-INJ-01")
                class CheckEmailImpl implements CheckEmail {
                    private Repo repo;
                    public Result<String> apply(String email) {
                        return repo.find(email);
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
