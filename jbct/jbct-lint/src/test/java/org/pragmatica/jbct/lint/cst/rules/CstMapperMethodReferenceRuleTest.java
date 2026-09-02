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

/// R-B (JBCT-TOT-02): partial method references in carrier mapper position.
class CstMapperMethodReferenceRuleTest {
    private static final String RULE_ID = "JBCT-TOT-02";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void analyze_sameFileMethodWithGetFirst_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.map(Handler::firstItem);
                    }
                    static String firstItem(Wire wire) {
                        return wire.items().getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_orThrowNamedRefUnresolvable_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.flatMap(Wire::firstItemOrThrow);
                    }
                }
                """));
    }

    @Test
    void analyze_sameFileMethodWithThrow_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.filter(Handler::validate);
                    }
                    static boolean validate(Wire wire) {
                        if (wire.items().isEmpty()) {
                            throw new IllegalStateException("empty");
                        }
                        return true;
                    }
                }
                """));
    }

    @Test
    void analyze_constructorReference_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<String> r) {
                        return r.map(Wire::new);
                    }
                }
                """));
    }

    @Test
    void analyze_totalSameFileMethodReference_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.map(Handler::name);
                    }
                    static String name(Wire wire) {
                        return wire.name().trim();
                    }
                }
                """));
    }

    @Test
    void analyze_unresolvedNonOrThrowReference_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.map(External::transform);
                    }
                }
                """));
    }

    @Test
    void analyze_partialMethodReferenceOutsideMapper_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(java.util.List<Wire> list) {
                        return list.removeIf(Handler::firstItemOrThrow);
                    }
                }
                """));
    }

    @Test
    void analyze_optionalOrElseThrowReference_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Result<java.util.Optional<String>> r) {
                        return r.map(Optional::orElseThrow);
                    }
                }
                """));
    }

    @Test
    void analyze_crossTypeSameNameCollision_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class X {
                    Object run(Result<Wire> r) {
                        return r.map(X::isActive);
                    }
                    static boolean isActive(Wire w) {
                        return w.name().length() > 0;
                    }
                }
                class Y {
                    static boolean isActive(Wire w) {
                        return w.items().getFirst() != null;
                    }
                }
                """));
    }

    @Test
    void analyze_thisReferenceResolvingToSiblingType_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class X {
                    Object run(Result<Wire> r) {
                        return r.map(this::firstName);
                    }
                    String firstName(Wire w) {
                        return w.name();
                    }
                }
                class Y {
                    String firstName(Wire w) {
                        return w.items().getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_thisReferenceResolvingToEnclosingType_flags() {
        assertTrue(hasRule("""
                package org.example;
                class X {
                    Object run(Result<Wire> r) {
                        return r.map(this::firstName);
                    }
                    String firstName(Wire w) {
                        return w.items().getFirst();
                    }
                }
                """));
    }

    @Test
    void analyze_overloadedNameWithinType_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class X {
                    Object run(Result<Wire> r) {
                        return r.map(this::pick);
                    }
                    String pick(Wire w) {
                        return w.items().getFirst();
                    }
                    String pick(Wire w, int i) {
                        return w.name();
                    }
                }
                """));
    }

    @Test
    void analyze_suppressWarnings_suppressesRule() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    @SuppressWarnings("JBCT-TOT-02")
                    Object run(Result<Wire> r) {
                        return r.flatMap(Wire::firstItemOrThrow);
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
