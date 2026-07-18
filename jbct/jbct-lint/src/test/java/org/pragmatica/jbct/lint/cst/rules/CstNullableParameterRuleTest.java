package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-RET-06: nullable-parameter detection — false-positive surface hardening.
class CstNullableParameterRuleTest {
    private static final String RULE_ID = "JBCT-RET-06";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void analyze_nullCheckInStringLiteral_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run(String x) {
                        return log("x == null");
                    }
                }
                """));
    }

    @Test
    void analyze_nullCheckInComment_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run(String x) {
                        // x == null is checked elsewhere
                        return x.trim();
                    }
                }
                """));
    }

    @Test
    void analyze_qualifiedFieldMatchingParamName_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run(Config cfg, Timeout timeout) {
                        if (cfg.timeout == null) {
                            return "";
                        }
                        return timeout.toString();
                    }
                }
                """));
    }

    @Test
    void analyze_nullEqualsQualifiedField_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run(Config cfg, Timeout timeout) {
                        if (null == cfg.timeout) {
                            return "";
                        }
                        return timeout.toString();
                    }
                }
                """));
    }

    @Test
    void analyze_genuineParamNullCheck_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    String run(Timeout timeout) {
                        if (timeout == null) {
                            return "";
                        }
                        return timeout.toString();
                    }
                }
                """));
    }

    @Test
    void analyze_genuineNullEqualsParam_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    String run(Timeout timeout) {
                        if (null == timeout) {
                            return "";
                        }
                        return timeout.toString();
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
        var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);

        return linter.lint(sourceFile)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
