package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-SHAPE-01 (MIXED) / JBCT-SHAPE-02 (UNCLASSIFIED) census rules (INFO). The six pure shapes
/// stay silent — only the two residual verdicts produce diagnostics. SHAPE-02 is default-disabled,
/// so the linter here force-enables all rules to exercise it.
class CstShapeRulesTest {
    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext()
                                                .withConfig(LintConfig.lintConfig(LintConfig.DEFAULT.ruleSeverities(),
                                                                                  Set.of(),
                                                                                  LintConfig.DEFAULT.failOnWarning())));
    }

    @Nested
    class ShapeMixed {
        private static final String RULE_ID = "JBCT-SHAPE-01";

        @Test
        void flags_joinAndStreamAtSameAltitude() {
            assertTrue(hasRule(RULE_ID, method("return Result.all(a(), b()).map(this::ctx).stream().map(this::f).toList();")));
        }

        @Test
        void severity_is_info() {
            var diagnostics = only(RULE_ID, method("return Result.all(a(), b()).map(this::ctx).stream().map(this::f).toList();"));

            assertEquals(1, diagnostics.size());
            assertEquals(DiagnosticSeverity.INFO, diagnostics.getFirst().severity());
        }

        @Test
        void clean_on_pure_forkJoin() {
            assertFalse(hasRule(RULE_ID, method("return Promise.all(a(id), b(id)).map(this::merge);")));
        }

        @Test
        void clean_on_sequencer() {
            assertFalse(hasRule(RULE_ID, method("return validate(r).flatMap(check).flatMap(save);")));
        }

        @Test
        void suppressed_by_annotation() {
            assertFalse(hasRule(RULE_ID, suppressed(RULE_ID, "return Result.all(a(), b()).map(this::ctx).stream().map(this::f).toList();")));
        }
    }

    @Nested
    class ShapeUnclassified {
        private static final String RULE_ID = "JBCT-SHAPE-02";

        @Test
        void flags_multiStatementBody() {
            assertTrue(hasRule(RULE_ID, method("var x = compute(); return x.transform();")));
        }

        @Test
        void flags_loopStatement() {
            assertTrue(hasRule(RULE_ID, "package org.example;\nclass Foo {\n  void run(List xs) {\n    for (var x : xs) { process(x); }\n  }\n}\n"));
        }

        @Test
        void severity_is_info() {
            var diagnostics = only(RULE_ID, method("var x = compute(); return x.transform();"));

            assertEquals(1, diagnostics.size());
            assertEquals(DiagnosticSeverity.INFO, diagnostics.getFirst().severity());
        }

        @Test
        void clean_on_single_return_leaf() {
            assertFalse(hasRule(RULE_ID, method("return compute();")));
        }

        @Test
        void clean_on_sequencer() {
            assertFalse(hasRule(RULE_ID, method("return compute().map(this::finish).flatMap(this::save);")));
        }

        @Test
        void suppressed_by_annotation() {
            assertFalse(hasRule(RULE_ID, suppressed(RULE_ID, "var x = compute(); return x.transform();")));
        }
    }

    private static String method(String body) {
        return "package org.example;\nclass Foo {\n  Object run(R r) {\n    " + body + "\n  }\n}\n";
    }

    private static String suppressed(String ruleId, String body) {
        return "package org.example;\nclass Foo {\n  @SuppressWarnings(\"" + ruleId + "\")\n  Object run(R r) {\n    " + body + "\n  }\n}\n";
    }

    private boolean hasRule(String ruleId, String source) {
        return lint(source).stream()
                           .anyMatch(diagnostic -> diagnostic.ruleId().equals(ruleId));
    }

    private List<Diagnostic> only(String ruleId, String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId().equals(ruleId))
                           .toList();
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
