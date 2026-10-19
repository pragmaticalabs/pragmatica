package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /// #1568 item 1: `xs -> xs.getFirst()` is JBCT-TOT-01; the method-reference spelling of the same partial accessor
    /// must be caught too, even though `List` is an imported type the file does not declare.
    @Test
    void analyze_importedTypeGetFirstReference_flags() {
        assertTrue(hasRule("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(List::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_importedTypeGetLastReference_flags() {
        assertTrue(hasRule("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Option<List<String>> o) {
                        return o.map(List::getLast);
                    }
                }
                """));
    }

    @Test
    void analyze_iteratorNextReference_flags() {
        assertTrue(hasRule("""
                package org.example;
                import java.util.Iterator;
                class Handler {
                    Object run(Result<Iterator<String>> r) {
                        return r.map(Iterator::next);
                    }
                }
                """));
    }

    /// A reference inside an anonymous-class method was reported once per enclosing method (outer and nested): exactly one.
    @Test
    void analyze_referenceInAnonymousClassMethod_reportsOnce() {
        var count = lint("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object build() {
                        return new Object() {
                            Object headerValue(Option<List<String>> o) {
                                return o.map(List::getFirst);
                            }
                        };
                    }
                }
                """).stream()
                    .filter(diagnostic -> diagnostic.ruleId()
                                                    .equals(RULE_ID))
                    .count();

        assertEquals(1L, count);
    }

    @Test
    void analyze_dottedJdkReceiver_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(java.util.List::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_dottedIteratorReceiver_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(java.util.Iterator::next);
                    }
                }
                """));
    }

    @Test
    void analyze_nestedQualifiedReceiver_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(java.util.concurrent.ConcurrentLinkedDeque::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_genericReceiver_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(List<String>::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_nextOnOtherReceiver_isClean() {
        assertEquals(0L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(Cursor::next);
                    }
                }
                """));
    }

    @Test
    void analyze_unknownImportedReceiverGetFirst_isClean() {
        assertEquals(0L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(Pair::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_twoIdenticalReferencesOnOneLine_reportTwice() {
        assertEquals(2L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(List::getFirst).map(List::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_sameReferenceOnTwoLines_reportsTwice() {
        assertEquals(2L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        var a = r.map(List::getFirst);
                        return a.map(List::getFirst);
                    }
                }
                """));
    }

    /// D4: a same-file type's own `getFirst` is resolved by its body, not guessed from the name.
    @Test
    void analyze_sameFileTypeWithTotalGetFirst_isClean() {
        assertEquals(0L, count("""
                package org.example;
                class Handler {
                    record Pair(String a, String b) {
                        String getFirst() {
                            return a;
                        }
                    }
                    Object run(Result<Pair> r) {
                        return r.map(Pair::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_sameFileTypeWithPartialGetFirst_flags() {
        assertEquals(1L, count("""
                package org.example;
                class Handler {
                    record Wire(java.util.List<String> items) {
                        String getFirst() {
                            return items.getFirst();
                        }
                    }
                    Object run(Result<Wire> r) {
                        return r.map(Wire::getFirst);
                    }
                }
                """));
    }

    /// The reference form follows the lambda rule (JBCT-TOT-01): inside a Stream pipeline a mapper is the Iteration pattern.
    @Test
    void analyze_getFirstReferenceInStreamPipeline_isClean() {
        assertEquals(0L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(java.util.stream.Stream<List<String>> s) {
                        return s.map(List::getFirst).toList();
                    }
                }
                """));
    }

    @Test
    void analyze_streamPipelineExemptionIsScopedToItsStatement() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(java.util.stream.Stream<List<String>> s, Result<List<String>> r) {
                        var firsts = s.map(List::size).toList();
                        return r.map(List::getFirst);
                    }
                }
                """));
    }

    /// `fold`'s success mapper is its second argument.
    @Test
    void analyze_foldSecondArgumentReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.fold(cause -> null, List::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_foldSecondArgumentTotalReference_isClean() {
        assertEquals(0L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.fold(cause -> null, List::size);
                    }
                }
                """));
    }

    @Test
    void analyze_totalImportedAccessorReference_isClean() {
        assertFalse(hasRule("""
                package org.example;
                import java.util.List;
                class Handler {
                    Object run(Result<List<String>> r) {
                        return r.map(List::size);
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

    private long count(String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId()
                                                           .equals(RULE_ID))
                           .count();
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
