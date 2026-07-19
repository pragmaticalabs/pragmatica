package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.lint.layer.LayerConfig;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-ARCH-04 slice-internal-import rule.
class CstArchSliceInternalRuleTest {
    private static final String RULE_ID = "JBCT-ARCH-04";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void detects_importOfAnotherSliceInternal() {
            assertTrue(hasRule("""
                    package com.example.usecase.registeruser;
                    import com.example.usecase.loginuser.internal.Token;
                    class RegisterUser {}
                    """));
        }

        @Test
        void detects_deepInternalOfAnotherSlice() {
            assertTrue(hasRule("""
                    package com.example.usecase.registeruser;
                    import com.example.usecase.loginuser.internal.token.Token;
                    class RegisterUser {}
                    """));
        }

        @Test
        void detects_fullyQualifiedReferenceIntoAnotherSliceInternal() {
            assertTrue(hasRule("""
                    package com.example.usecase.registeruser;
                    class RegisterUser {
                        com.example.usecase.loginuser.internal.Token token;
                    }
                    """));
        }
    }

    @Nested
    class NearMisses {
        @Test
        void allows_importOfAnotherSliceRootSurface() {
            assertFalse(hasRule("""
                    package com.example.usecase.registeruser;
                    import com.example.usecase.loginuser.LoginUseCase;
                    class RegisterUser {}
                    """));
        }

        @Test
        void allows_sameSliceInternalImport() {
            assertFalse(hasRule("""
                    package com.example.usecase.registeruser;
                    import com.example.usecase.registeruser.internal.Token;
                    class RegisterUser {}
                    """));
        }

        @Test
        void ignores_importWithNoSliceClassification() {
            assertFalse(hasRule("""
                    package com.example.usecase.registeruser;
                    import com.example.domain.user.User;
                    class RegisterUser {}
                    """));
        }
    }

    @Test
    void explicitSliceGlobs_flagCrossSliceInternal() {
        var config = LayerConfig.layerConfig(List.of(),
                                             List.of(),
                                             List.of(),
                                             List.of(),
                                             List.of("com.example.svc.*"));
        var configured = CstLinter.cstLinter(LintContext.defaultContext()
                                                        .withLayers(config));
        var diagnostics = configured.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.svc.orders;
                import com.example.svc.billing.internal.Ledger;
                class Orders {}
                """))
                                    .onFailure(cause -> fail("Parse failed: " + cause.message()))
                                    .or(List.of());

        assertTrue(diagnostics.stream()
                              .anyMatch(diagnostic -> diagnostic.ruleId()
                                                                .equals(RULE_ID)),
                   "explicit slice globs should flag a cross-slice internal import");
    }

    @Test
    void exempts_excludedPackage() {
        var excluded = CstLinter.cstLinter(LintContext.lintContext(List.of("com.example.usecase.**")));
        var diagnostics = excluded.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.usecase.registeruser;
                import com.example.usecase.loginuser.internal.Token;
                class RegisterUser {}
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
                class RegisterUser {
                    com.example.usecase.loginuser.internal.Token run() { return null; }
                }
                """), "baseline cross-slice internal reference should trigger JBCT-ARCH-04");

        assertFalse(hasRule("""
                package com.example.usecase.registeruser;
                class RegisterUser {
                    @SuppressWarnings("JBCT-ARCH-04")
                    com.example.usecase.loginuser.internal.Token run() { return null; }
                }
                """), "@SuppressWarnings(\"JBCT-ARCH-04\") should suppress it");
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
