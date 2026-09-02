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

/// JBCT-ARCH-01 dependency-direction rule.
class CstArchDirectionRuleTest {
    private static final String RULE_ID = "JBCT-ARCH-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class Violations {
        @Test
        void detects_domainImportingAdapter() {
            assertTrue(hasRule("""
                    package com.example.domain.user;
                    import com.example.adapter.persistence.Row;
                    class User {}
                    """));
        }

        @Test
        void detects_domainImportingApplication() {
            assertTrue(hasRule("""
                    package com.example.domain.user;
                    import com.example.application.register.RegisterService;
                    class User {}
                    """));
        }

        @Test
        void detects_applicationImportingAdapter() {
            assertTrue(hasRule("""
                    package com.example.application.register;
                    import com.example.adapter.persistence.Row;
                    class RegisterService {}
                    """));
        }

        @Test
        void detects_domainImportingFramework() {
            assertTrue(hasRule("""
                    package com.example.domain.user;
                    import org.springframework.stereotype.Component;
                    class User {}
                    """));
        }

        @Test
        void detects_fullyQualifiedHigherLayerReference() {
            assertTrue(hasRule("""
                    package com.example.domain.user;
                    class User {
                        com.example.adapter.persistence.Row row;
                    }
                    """));
        }
    }

    @Nested
    class NearMisses {
        @Test
        void allows_applicationImportingDomain() {
            assertFalse(hasRule("""
                    package com.example.application.register;
                    import com.example.domain.user.User;
                    class RegisterService {}
                    """));
        }

        @Test
        void allows_adapterImportingDomainAndApplication() {
            assertFalse(hasRule("""
                    package com.example.adapter.persistence;
                    import com.example.domain.user.User;
                    import com.example.application.register.RegisterService;
                    class UserRow {}
                    """));
        }

        @Test
        void allows_sameLayerImport() {
            assertFalse(hasRule("""
                    package com.example.domain.user;
                    import com.example.domain.order.Order;
                    class User {}
                    """));
        }

        @Test
        void ignores_unclassifiableFilePackage() {
            assertFalse(hasRule("""
                    package com.example.util;
                    import com.example.adapter.persistence.Row;
                    class Helper {}
                    """));
        }

        @Test
        void ignores_unclassifiableReferencedPackage() {
            assertFalse(hasRule("""
                    package com.example.domain.user;
                    import java.util.List;
                    class User {}
                    """));
        }

        @Test
        void ignores_thirdPartyPackageWithLayerKeyword() {
            assertFalse(hasRule("""
                    package com.example.application.register;
                    import org.springframework.integration.Message;
                    class RegisterService {}
                    """));
        }

        @Test
        void ignores_thirdPartyDomainImportOutsideFrameworkList() {
            assertFalse(hasRule("""
                    package com.example.domain.user;
                    import org.acmelib.adapter.Client;
                    class User {}
                    """));
        }

        @Test
        void ignores_crossGroupInHouseModule() {
            assertFalse(hasRule("""
                    package com.foo.domain.user;
                    import com.bar.adapter.persistence.Row;
                    class User {}
                    """));
        }
    }

    @Test
    void exempts_excludedPackage() {
        var excluded = CstLinter.cstLinter(LintContext.lintContext(List.of("com.example.domain.**")));
        var diagnostics = excluded.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.domain.user;
                import com.example.adapter.persistence.Row;
                class User {}
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
                package com.example.domain.user;
                class User {
                    com.example.adapter.persistence.Row run() { return null; }
                }
                """), "baseline fully-qualified higher-layer reference should trigger JBCT-ARCH-01");

        assertFalse(hasRule("""
                package com.example.domain.user;
                class User {
                    @SuppressWarnings("JBCT-ARCH-01")
                    com.example.adapter.persistence.Row run() { return null; }
                }
                """), "@SuppressWarnings(\"JBCT-ARCH-01\") should suppress it");
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
