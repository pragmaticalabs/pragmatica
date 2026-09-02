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

/// JBCT-SHAPE-03 (shape<->zone cross-check, INFO). A method flags when its composition shape and its
/// name-verb's abstraction zone disagree: a Zone-3 implementation verb heading a SEQUENCER/FORK_JOIN
/// (mis-leveled up) or a Zone-2 orchestration verb heading a LEAF (mis-leveled down). Agreeing methods
/// and the non-cross-checked shapes (UNCLASSIFIED/MIXED/CONDITION/ITERATION/ASPECT) stay silent.
class CstShapeZoneRuleTest {
    private static final String RULE_ID = "JBCT-SHAPE-03";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext()
                                                .withConfig(LintConfig.lintConfig(LintConfig.DEFAULT.ruleSeverities(),
                                                                                  Set.of(),
                                                                                  LintConfig.DEFAULT.failOnWarning())));
    }

    @Nested
    class MisLeveled {
        @Test
        void flags_implVerb_onSequencer() {
            assertTrue(hasRule(method("fetchReport", "return load().map(this::enrich).flatMap(this::store);")));
        }

        @Test
        void flags_implVerb_onForkJoin() {
            assertTrue(hasRule(method("getDashboard", "return Promise.all(a(id), b(id)).map(this::merge);")));
        }

        @Test
        void flags_orchestrationVerb_onLeaf() {
            assertTrue(hasRule(method("processItem", "return compute();")));
        }

        @Test
        void severity_is_info() {
            var diagnostics = only(method("fetchReport", "return load().map(this::enrich).flatMap(this::store);"));

            assertEquals(1, diagnostics.size());
            assertEquals(DiagnosticSeverity.INFO, diagnostics.getFirst().severity());
        }

        @Test
        void suppressed_by_annotation() {
            assertFalse(hasRule(suppressed("fetchReport", "return load().map(this::enrich).flatMap(this::store);")));
        }
    }

    @Nested
    class Agreeing {
        @Test
        void clean_on_implVerb_leaf() {
            assertFalse(hasRule(method("fetchReport", "return load();")));
        }

        @Test
        void clean_on_orchestrationVerb_sequencer() {
            assertFalse(hasRule(method("processData", "return validate(r).flatMap(check).flatMap(save);")));
        }
    }

    @Nested
    class NotCrossChecked {
        @Test
        void clean_on_unclassified_zoneVerb() {
            assertFalse(hasRule(method("fetchReport", "audit(x); return notify(x);")));
        }

        @Test
        void clean_on_nonZoneVerb() {
            assertFalse(hasRule(method("run", "return load().map(this::enrich).flatMap(this::store);")));
        }
    }

    private static String method(String name, String body) {
        return "package org.example;\nclass Foo {\n  Object " + name + "(R r) {\n    " + body + "\n  }\n}\n";
    }

    private static String suppressed(String name, String body) {
        return "package org.example;\nclass Foo {\n  @SuppressWarnings(\"" + RULE_ID + "\")\n  Object " + name
               + "(R r) {\n    " + body + "\n  }\n}\n";
    }

    private boolean hasRule(String source) {
        return lint(source).stream()
                           .anyMatch(diagnostic -> diagnostic.ruleId().equals(RULE_ID));
    }

    private List<Diagnostic> only(String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId().equals(RULE_ID))
                           .toList();
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}
