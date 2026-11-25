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

/// JBCT-ARCH-01 domain-purity rule (framework imports from a domain file).
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
        void detects_domainImportingFramework() {
            assertTrue(hasRule("""
                    package com.example.domain.user;
                    import org.springframework.stereotype.Component;
                    class User {}
                    """));
        }

        @Test
        void detects_fullyQualifiedFrameworkReference() {
            assertTrue(hasRule("""
                    package com.example.domain.user;
                    class User {
                        com.fasterxml.jackson.databind.JsonNode node;
                    }
                    """));
        }
    }

    /// The layer-rank check is retired (#2526): an upward import is no longer ARCH-01's finding.
    @Nested
    class RankCheckRetired {
        @Test
        void ignores_domainImportingAdapter() {
            assertFalse(hasRule("""
                    package com.example.domain.user;
                    import com.example.adapter.persistence.Row;
                    class User {}
                    """));
        }

        @Test
        void ignores_domainImportingTopLevelConfig() {
            assertFalse(hasRule("""
                    package com.example.domain.user;
                    import com.example.config.Wiring;
                    class User {}
                    """));
        }

        @Test
        void ignores_fullyQualifiedHigherLayerReference() {
            assertFalse(hasRule("""
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
        void allows_applicationImportingFramework() {
            assertFalse(hasRule("""
                    package com.example.application.register;
                    import org.springframework.stereotype.Component;
                    class RegisterService {}
                    """));
        }

        @Test
        void allows_domainImportingDomain() {
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
                    import org.springframework.stereotype.Component;
                    class Helper {}
                    """));
        }

        @Test
        void ignores_jdkImportFromDomain() {
            assertFalse(hasRule("""
                    package com.example.domain.user;
                    import java.util.List;
                    class User {}
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
    }

    @Test
    void exempts_excludedPackage() {
        var excluded = CstLinter.cstLinter(LintContext.lintContext(List.of("com.example.domain.**")));
        var diagnostics = excluded.lint(SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.domain.user;
                import org.springframework.stereotype.Component;
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
                    org.springframework.stereotype.Component run() { return null; }
                }
                """), "baseline fully-qualified framework reference should trigger JBCT-ARCH-01");

        assertFalse(hasRule("""
                package com.example.domain.user;
                class User {
                    @SuppressWarnings("JBCT-ARCH-01")
                    org.springframework.stereotype.Component run() { return null; }
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
