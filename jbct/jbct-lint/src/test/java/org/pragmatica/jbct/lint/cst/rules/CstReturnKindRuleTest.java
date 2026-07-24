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

class CstReturnKindRuleTest {
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
    void flags_void_in_business_method() {
        var diagnostics = lint("""
                package org.example;
                interface Svc {
                    void doThing();
                }
                """);
        assertTrue(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-01")));
    }

    @Test
    void exempts_void_test_method() {
        var diagnostics = lint("""
                package org.example;
                class SvcTest {
                    @Test
                    void doThing_works() {
                    }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(d -> d.ruleId().equals("JBCT-RET-01")));
    }
}
