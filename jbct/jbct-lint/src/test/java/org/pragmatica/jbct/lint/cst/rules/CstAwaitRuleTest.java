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

class CstAwaitRuleTest {
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
    void detects_await_in_expression_statement() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        fetchData().await();
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-PAT-03")));
    }

    @Test
    void detects_await_in_assignment() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        var result = fetchData().await();
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-PAT-03")));
    }

    @Test
    void suppressed_by_terminal_operation_annotation() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    @TerminalOperation
                    void run() {
                        fetchData().await();
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-PAT-03")));
    }

    @Test
    void suppressed_by_class_level_terminal_operation() {
        var diagnostics = lint("""
                package org.example;
                @TerminalOperation
                class Foo {
                    void run() {
                        fetchData().await();
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-PAT-03")));
    }

    @Test
    void no_false_positive_on_await_with_arguments() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-PAT-03")));
    }

    @Test
    void no_false_positive_without_await() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    void run() {
                        fetchData().map(String::trim);
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-PAT-03")));
    }
}
