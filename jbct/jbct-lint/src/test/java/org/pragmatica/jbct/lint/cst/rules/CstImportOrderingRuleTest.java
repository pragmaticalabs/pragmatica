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

/// JBCT-STY-06 import-ordering rule.
///
/// Book order: java/javax → org.pragmatica → third-party → project, static imports last
/// in the same grouping order.
class CstImportOrderingRuleTest {
    private static final String RULE_ID = "JBCT-STY-06";

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

    private List<Diagnostic> orderingDiagnostics(String source) {
        return lint(source).stream()
                           .filter(d -> d.ruleId().equals(RULE_ID))
                           .toList();
    }

    @Test
    void detects_pragmatica_before_java_atOffendingLine() {
        var diagnostics = orderingDiagnostics("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<String> process(List<String> input) {
                        return Result.success(input.toString());
                    }
                }
                """);
        assertEquals(1, diagnostics.size());
        // The out-of-order import is `java.util.List` on line 3.
        assertEquals(3, diagnostics.get(0).line());
    }

    @Test
    void detects_thirdParty_before_pragmatica() {
        var diagnostics = orderingDiagnostics("""
                package com.example.usecase.test;
                import java.util.List;
                import org.slf4j.Logger;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(List<String> input, Logger log) {
                        return Result.success(input.toString());
                    }
                }
                """);
        assertFalse(diagnostics.isEmpty());
    }

    @Test
    void detects_outOfOrder_staticImports() {
        var diagnostics = orderingDiagnostics("""
                package com.example.usecase.test;
                import java.util.List;
                import org.pragmatica.lang.Result;
                import static org.pragmatica.lang.Result.success;
                import static java.util.Objects.requireNonNull;
                public class Test {
                    public Result<String> process(List<String> input) {
                        requireNonNull(input);
                        return success(input.toString());
                    }
                }
                """);
        assertFalse(diagnostics.isEmpty());
    }

    @Test
    void allows_bookOrderedImports() {
        var diagnostics = orderingDiagnostics("""
                package com.example.usecase.test;
                import java.util.List;
                import java.util.Map;
                import javax.annotation.Nonnull;
                import org.pragmatica.lang.Result;
                import org.slf4j.Logger;
                import com.example.domain.User;
                public class Test {
                    public Result<String> process(@Nonnull List<String> input, Map<String, String> m, Logger log, User u) {
                        return Result.success(input.toString());
                    }
                }
                """);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void allows_bookOrderedStaticImports() {
        var diagnostics = orderingDiagnostics("""
                package com.example.usecase.test;
                import java.util.List;
                import org.pragmatica.lang.Result;
                import static java.util.Objects.requireNonNull;
                import static org.pragmatica.lang.Result.success;
                public class Test {
                    public Result<String> process(List<String> input) {
                        requireNonNull(input);
                        return success(input.toString());
                    }
                }
                """);
        assertTrue(diagnostics.isEmpty());
    }
}
