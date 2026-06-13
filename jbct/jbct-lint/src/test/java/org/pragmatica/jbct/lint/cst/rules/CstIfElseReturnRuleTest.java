package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CstIfElseReturnRuleTest {
    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    private List<Diagnostic> lint(String source) {
        var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);
        return linter.lint(sourceFile)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }

    @Test
    void detects_simple_if_else_return() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run(int x) {
                        if (x > 0) {
                            return "positive";
                        } else {
                            return "non-positive";
                        }
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-08")));
    }

    @Test
    void no_false_positive_on_else_if_chain() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run(int x) {
                        if (x > 10) {
                            return "high";
                        } else if (x > 0) {
                            return "low";
                        } else {
                            return "none";
                        }
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-08")));
    }

    @Test
    void no_false_positive_on_multi_statement_branches() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run(int x) {
                        if (x > 0) {
                            log.info("positive");
                            return "positive";
                        } else {
                            return "non-positive";
                        }
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-08")));
    }

    @Test
    void no_false_positive_on_if_without_else() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run(int x) {
                        if (x > 0) {
                            return "positive";
                        }
                        return "default";
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-08")));
    }

    @Test
    void no_false_positive_on_non_return_branches() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run(int x) {
                        if (x > 0) {
                            doSomething();
                        } else {
                            doOther();
                        }
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-08")));
    }
}
