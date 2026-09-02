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

/// JBCT-ARCH-03 use-case-calls-use-case rule.
class CstArchUseCaseCouplingRuleTest {
    private static final String RULE_ID = "JBCT-ARCH-03";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void detects_useCaseImportingAnotherUseCase() {
            assertTrue(hasRule("""
                    package com.example.usecase.registeruser;
                    import com.example.usecase.loginuser.LoginUseCase;
                    interface RegisterUseCase {
                        Result<String> execute(String r);
                    }
                    """));
        }

        @Test
        void detects_useCaseFieldReferencingAnotherUseCase() {
            assertTrue(hasRule("""
                    package com.example.usecase.registeruser;
                    interface RegisterUseCase {
                        LoginUseCase delegate();
                    }
                    """));
        }

        @Test
        void detects_useCaseShapedInterfaceReferencingAnotherUseCase() {
            assertTrue(hasRule("""
                    package com.example.usecase.registeruser;
                    interface RegisterUser extends UseCase.WithPromise<String, String> {
                        LoginUseCase delegate();
                    }
                    """));
        }
    }

    @Nested
    class NearMisses {
        @Test
        void allows_useCaseReferencingOnlySteps() {
            assertFalse(hasRule("""
                    package com.example.usecase.registeruser;
                    interface RegisterUseCase {
                        interface CheckEmail {
                            Result<String> apply(String r);
                        }
                        Result<String> execute(String r);
                    }
                    """));
        }

        @Test
        void allows_useCaseReferencingItself() {
            assertFalse(hasRule("""
                    package com.example.usecase.registeruser;
                    interface RegisterUseCase {
                        static RegisterUseCase registerUseCase() {
                            return r -> r;
                        }
                        String apply(String r);
                    }
                    """));
        }

        @Test
        void ignores_nonUseCaseFileReferencingUseCase() {
            assertFalse(hasRule("""
                    package com.example.config;
                    class Wiring {
                        LoginUseCase login;
                        RegisterUseCase register;
                    }
                    """));
        }
    }

    @Test
    void exempts_excludedPackage() {
        var excluded = CstLinter.cstLinter(LintContext.lintContext(List.of("com.example.usecase.**")));
        var diagnostics = excluded.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.usecase.registeruser;
                import com.example.usecase.loginuser.LoginUseCase;
                interface RegisterUseCase {
                    Result<String> execute(String r);
                }
                """))
                                  .onFailure(cause -> fail("Parse failed: " + cause.message()))
                                  .or(List.of());

        assertFalse(diagnostics.stream()
                               .anyMatch(diagnostic -> diagnostic.ruleId()
                                                                 .equals(RULE_ID)));
    }

    @Test
    void suppressed_bySuppressWarningsOnMethod() {
        assertTrue(hasRule("""
                package com.example.usecase.registeruser;
                interface RegisterUseCase {
                    LoginUseCase delegate();
                }
                """), "baseline use-case referencing another use case should trigger JBCT-ARCH-03");

        assertFalse(hasRule("""
                package com.example.usecase.registeruser;
                interface RegisterUseCase {
                    @SuppressWarnings("JBCT-ARCH-03")
                    LoginUseCase delegate();
                }
                """), "@SuppressWarnings(\"JBCT-ARCH-03\") should suppress it");
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
