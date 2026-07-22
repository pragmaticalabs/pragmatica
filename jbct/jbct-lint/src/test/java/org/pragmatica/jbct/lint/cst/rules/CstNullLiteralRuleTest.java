package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-RET-08 null-literal rule (null argument + defensive null comparison), including the
/// JBCT-RET-06 (nullable-parameter) overlap.
class CstNullLiteralRuleTest {
    private static final String RULE_ID = "JBCT-RET-08";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void detects_null_as_sole_argument() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return compute(null);
                    }
                }
                """));
    }

    @Test
    void detects_null_as_later_argument() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run() {
                        return build(name, null, tail);
                    }
                }
                """));
    }

    @Test
    void exempts_or_null_adapter() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run(Option<String> opt) {
                        return opt.or(null);
                    }
                }
                """));
    }

    @Test
    void exempts_orElse_null_jdk_bridge() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run(java.util.Optional<String> opt) {
                        return opt.orElse(null);
                    }
                }
                """));
    }

    @Test
    void exempts_atomic_compareAndSet_and_getAndSet_sentinels() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    void run(java.util.concurrent.atomic.AtomicReference<String> ref) {
                        ref.compareAndSet(null, "x");
                        var prev = ref.getAndSet(null);
                    }
                }
                """));
    }

    @Test
    void still_flags_null_arg_to_common_named_setter() {
        // Common JDK-boundary names are deliberately NOT exempted — a real business null argument
        // to a method named `set` must still be flagged.
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    void run(Config c) {
                        c.set(null);
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_return_null() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run() {
                        return null;
                    }
                }
                """));
    }

    @Test
    void no_false_positive_on_null_inside_string_literal() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    String run() {
                        return format("value is null");
                    }
                }
                """));
    }

    @Test
    void nested_type_method_argument_reports_once() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void outer() {
                        Object o = new Object() {
                            Object go() {
                                return compute(null);
                            }
                        };
                    }
                }
                """).stream()
                    .filter(diagnostic -> diagnostic.ruleId()
                                                    .equals(RULE_ID))
                    .toList();

        assertEquals(1, diagnostics.size());
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
