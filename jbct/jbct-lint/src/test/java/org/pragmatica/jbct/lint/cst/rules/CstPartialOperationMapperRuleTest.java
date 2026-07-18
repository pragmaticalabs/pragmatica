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

/// R-A (JBCT-TOT-01): partial operations inside carrier mapper lambdas.
class CstPartialOperationMapperRuleTest {
    private static final String RULE_ID = "JBCT-TOT-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void analyze_getFirstInMapLambda_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> wire.items().getFirst());
                    }
                }
                """));
    }

    @Test
    void analyze_orElseThrowInFlatMapLambda_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Holder> r) {
                        return r.flatMap(h -> h.maybeValue().orElseThrow());
                    }
                }
                """));
    }

    @Test
    void analyze_indexedGetInFilterLambda_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.filter(wire -> wire.items().get(0) != null);
                    }
                }
                """));
    }

    @Test
    void analyze_explicitThrowInMapBlockLambda_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> { throw new IllegalStateException("x"); });
                    }
                }
                """));
    }

    @Test
    void analyze_partialOpInReplaceResultLambda_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(Promise<Wire> p) {
                        return p.replaceResult(() -> queue.iterator().next());
                    }
                }
                """));
    }

    @Test
    void analyze_totalMapLambda_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> wire.name().trim());
                    }
                }
                """));
    }

    @Test
    void analyze_streamPipelineIndexedGet_isExempt() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run(java.util.List<Row> rows) {
                        return rows.stream().map(row -> row.cells().get(0)).toList();
                    }
                }
                """));
    }

    @Test
    void analyze_streamCollectPipeline_isExempt() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run(java.util.List<Row> rows) {
                        return rows.stream().filter(row -> row.cells().getFirst() != null).collect(toList());
                    }
                }
                """));
    }

    @Test
    void analyze_partialOpInNonMapperCall_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run(java.util.List<Wire> list) {
                        return list.removeIf(wire -> wire.items().getFirst() == null);
                    }
                }
                """));
    }

    @Test
    void analyze_partialOpInNestedNonMapperLambda_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> register(inner -> inner.items().getFirst()));
                    }
                }
                """));
    }

    @Test
    void analyze_streamMarkerInsideStringLiteral_stillFlags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> render(wire.items().getFirst(), "rows.toList()"));
                    }
                }
                """));
    }

    @Test
    void analyze_fieldInitializerMapperWithUnrelatedStreamElsewhere_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Foo {
                    static final Object FIRST = source().map(wire -> wire.items().getFirst());
                    static Object other(java.util.List<String> list) {
                        return list.stream().map(x -> x).toList();
                    }
                }
                """));
    }

    @Test
    void analyze_suppressWarnings_suppressesRule() {
        assertFalse(hasRule("""
                package org.example;
                class Foo {
                    @SuppressWarnings("JBCT-TOT-01")
                    Object run(Result<Wire> r) {
                        return r.map(wire -> wire.items().getFirst());
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
