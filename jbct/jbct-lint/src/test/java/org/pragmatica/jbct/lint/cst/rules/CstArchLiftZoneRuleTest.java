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

/// JBCT-ARCH-02 lift-outside-adapter-zone rule.
class CstArchLiftZoneRuleTest {
    private static final String RULE_ID = "JBCT-ARCH-02";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void detects_liftInDomain() {
            assertTrue(hasRule("""
                    package com.example.domain.pricing;
                    class Pricing {
                        Object run() { return Result.lift(Err::new, () -> compute()); }
                    }
                    """));
        }

        @Test
        void detects_liftInApplication() {
            assertTrue(hasRule("""
                    package com.example.application.register;
                    class RegisterService {
                        Object run() { return Promise.lift(Err::new, () -> io()); }
                    }
                    """));
        }

        @Test
        void detects_numberedLiftVariant() {
            assertTrue(hasRule("""
                    package com.example.domain.pricing;
                    class Pricing {
                        Object run() { return Result.lift1(Err::new, enc::encode, value); }
                    }
                    """));
        }
    }

    @Nested
    class NearMisses {
        @Test
        void allows_liftInAdapter() {
            assertFalse(hasRule("""
                    package com.example.adapter.pricing;
                    class PricingRepo {
                        Object run() { return Result.lift(Err::new, () -> io()); }
                    }
                    """));
        }

        @Test
        void allows_liftInIntegrationAdapter() {
            assertFalse(hasRule("""
                    package com.example.integration.db;
                    class DbGateway {
                        Object run() { return Promise.lift(Err::new, () -> io()); }
                    }
                    """));
        }

        @Test
        void ignores_liftInUnclassifiablePackage() {
            assertFalse(hasRule("""
                    package com.example.util;
                    class Helper {
                        Object run() { return Result.lift(Err::new, () -> io()); }
                    }
                    """));
        }

        @Test
        void ignores_liftInsideStringLiteral() {
            assertFalse(hasRule("""
                    package com.example.domain.pricing;
                    class Pricing {
                        String run() { return "call lift(x) here"; }
                    }
                    """));
        }

        @Test
        void ignores_methodReferenceLift() {
            assertFalse(hasRule("""
                    package com.example.domain.pricing;
                    class Pricing {
                        Object run() { return stream.map(Result::lift); }
                    }
                    """));
        }
    }

    @Test
    void exempts_excludedPackage() {
        var excluded = CstLinter.cstLinter(LintContext.lintContext(List.of("com.example.domain.**")));
        var diagnostics = excluded.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.domain.pricing;
                class Pricing {
                    Object run() { return Result.lift(Err::new, () -> compute()); }
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
                package com.example.domain.pricing;
                class Pricing {
                    Object run() { return Result.lift(Err::new, () -> compute()); }
                }
                """), "baseline lift in domain should trigger JBCT-ARCH-02");

        assertFalse(hasRule("""
                package com.example.domain.pricing;
                class Pricing {
                    @SuppressWarnings("JBCT-ARCH-02")
                    Object run() { return Result.lift(Err::new, () -> compute()); }
                }
                """), "@SuppressWarnings(\"JBCT-ARCH-02\") should suppress it");
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
