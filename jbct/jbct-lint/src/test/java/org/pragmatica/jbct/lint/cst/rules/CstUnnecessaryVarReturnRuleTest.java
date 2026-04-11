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

class CstUnnecessaryVarReturnRuleTest {
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
    void detects_var_immediately_returned() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run() {
                        var result = computeValue();
                        return result;
                    }
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-07")));
    }

    @Test
    void no_false_positive_when_var_used_elsewhere() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run() {
                        var result = computeValue();
                        log.info("computed: {}", result);
                        return result;
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-07")));
    }

    @Test
    void no_false_positive_when_different_var_returned() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run() {
                        var result = computeValue();
                        return otherValue;
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-07")));
    }

    @Test
    void no_false_positive_on_direct_return() {
        var diagnostics = lint("""
                package org.example;
                class Foo {
                    String run() {
                        return computeValue();
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-STY-07")));
    }
}
