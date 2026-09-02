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

/// R-C (JBCT-TOT-03): Jackson wire-record accessors dereferencing possibly-null components.
class CstWireRecordTotalityRuleTest {
    private static final String RULE_ID = "JBCT-TOT-03";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void analyze_unguardedComponentDereference_flags() {
        assertTrue(hasRule("""
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    String first() {
                        return items().getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_streamOnComponent_flags() {
        assertTrue(hasRule("""
                package org.example;
                record Wire(@JsonProperty java.util.List<String> items) {
                    long count() {
                        return items.stream().count();
                    }
                }
                """));
    }

    @Test
    void analyze_jacksonXmlRecord_flags() {
        assertTrue(hasRule("""
                package org.example;
                record Wire(@JacksonXmlProperty(localName = "row") java.util.List<String> rows) {
                    String first() {
                        return rows.get(0);
                    }
                }
                """));
    }

    @Test
    void analyze_nullEqualsGuard_isClean() {
        assertFalse(hasRule("""
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    String first() {
                        return items == null ? "" : items().getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_optionOptionGuard_isClean() {
        assertFalse(hasRule("""
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    int first() {
                        return Option.option(items).map(list -> list.size()).or(0);
                    }
                }
                """));
    }

    @Test
    void analyze_componentReturnedDirectly_isClean() {
        assertFalse(hasRule("""
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    java.util.List<String> all() {
                        return items;
                    }
                }
                """));
    }

    @Test
    void analyze_primitiveComponent_isClean() {
        assertFalse(hasRule("""
                package org.example;
                record Wire(@JsonProperty("size") int size) {
                    int doubled() {
                        return size * 2;
                    }
                }
                """));
    }

    @Test
    void analyze_nonJacksonRecord_isClean() {
        assertFalse(hasRule("""
                package org.example;
                record Wire(java.util.List<String> items) {
                    String first() {
                        return items().getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_similarlyNamedGuard_stillFlags() {
        assertTrue(hasRule("""
                package org.example;
                record Wire(@JsonProperty java.util.List<String> c) {
                    String first(java.util.List<String> cAbc) {
                        return cAbc != null ? c.getFirst() : "";
                    }
                }
                """));
    }

    @Test
    void analyze_guardOnlyInComment_stillFlags() {
        assertTrue(hasRule("""
                package org.example;
                record Wire(@JsonProperty java.util.List<String> items) {
                    String first() {
                        // items == null is handled upstream
                        return items.getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_suppressWarnings_suppressesRule() {
        assertFalse(hasRule("""
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    @SuppressWarnings("JBCT-TOT-03")
                    String first() {
                        return items().getFirst();
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
