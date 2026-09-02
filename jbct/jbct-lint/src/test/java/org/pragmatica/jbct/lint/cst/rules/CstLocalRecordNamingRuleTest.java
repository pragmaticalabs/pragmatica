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

/// JBCT-NAM-04 local-record naming rule.
class CstLocalRecordNamingRuleTest {
    private static final String RULE_ID = "JBCT-NAM-04";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_pascal_case_local_record() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        record Cache(int x) {}
                        return new Cache(0);
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_lowercase_local_record() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        record cache(int x) {}
                        return new cache(0);
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_top_level_record() {
        assertFalse(hasRule("""
                package org.example;
                record Money(long cents) {}
                """));
    }

    @Test
    void no_false_positive_on_class_body_nested_record() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    record Money(long cents) {}
                }
                """));
    }

    @Test
    void suppressed_by_suppress_warnings_annotation() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-NAM-04")
                    Object run() {
                        record Cache(int x) {}
                        return new Cache(0);
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
