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
    void analyze_vectorReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.Vector;
                class Handler {
                    Object run(Result<Vector<String>> r) {
                        return r.map(Vector::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_blockingDequeReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.concurrent.BlockingDeque;
                class Handler {
                    Object run(Result<BlockingDeque<String>> r) {
                        return r.map(BlockingDeque::getLast);
                    }
                }
                """));
    }

    @Test
    void analyze_concurrentSkipListSetReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.concurrent.ConcurrentSkipListSet;
                class Handler {
                    Object run(Result<ConcurrentSkipListSet<String>> r) {
                        return r.map(ConcurrentSkipListSet::getFirst);
                    }
                }
                """));
    }

    @Test
    void analyze_primitiveIteratorOfIntReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.PrimitiveIterator;
                class Handler {
                    Object run(Result<PrimitiveIterator.OfInt> r) {
                        return r.map(PrimitiveIterator.OfInt::next);
                    }
                }
                """));
    }

    @Test
    void analyze_primitiveIteratorOfLongReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.PrimitiveIterator;
                class Handler {
                    Object run(Result<PrimitiveIterator.OfLong> r) {
                        return r.map(PrimitiveIterator.OfLong::next);
                    }
                }
                """));
    }

    @Test
    void analyze_primitiveIteratorOfDoubleReference_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.PrimitiveIterator;
                class Handler {
                    Object run(Result<PrimitiveIterator.OfDouble> r) {
                        return r.map(PrimitiveIterator.OfDouble::next);
                    }
                }
                """));
    }

    /// Pins the same-file receiver guard: a type declared in this file under a JDK receiver's NAME (`List`) is resolved by its
    /// own body, here a total `getFirst`, not guessed at by name. Without the guard the name rule flags it.
    @Test
    void analyze_sameFileTypeNamedLikeAJdkReceiver_withTotalGetFirst_isClean() {
        assertEquals(0L, count("""
                package org.example;
                class Handler {
                    record List(String a) {
                        String getFirst() {
                            return a;
                        }
                    }
                    Object run(Result<List> r) {
                        return r.map(List::getFirst);
                    }
                }
                """));
    }

    /// A field initializer has no method around it; the success mapper of a constant is as exposed as any other.
    @Test
    void analyze_referenceInFieldInitializer_flags() {
        assertEquals(1L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    static final Object FIRST = Option.some(List.of()).map(List::getFirst);
                }
                """));
    }

    @Test
    void analyze_totalReferenceInFieldInitializer_isClean() {
        assertEquals(0L, count("""
                package org.example;
                import java.util.List;
                class Handler {
                    static final Object SIZE = Option.some(List.of()).map(List::size);
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

    /// #3065: a `getLast` dominated by an `isEmpty()` guard on the same receiver is total.
    @Test
    void analyze_getLastGuardedByIsEmptyTernary_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var segments = wire.items();
                        return segments.isEmpty() ? Option.none() : Option.some(segments.getLast());
                    }
                }
                """));
    }

    /// #3065: a `getFirst` dominated by a `size() == 1 &&` guard on the same receiver is total.
    @Test
    void analyze_getFirstGuardedBySizeCheck_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.map(Handler::isSingle);
                    }
                    static boolean isSingle(Wire wire) {
                        var branches = wire.items();
                        return branches.size() == 1 && branches.getFirst().isEmpty();
                    }
                }
                """));
    }

    /// #3065 control: the guard is on ANOTHER receiver, so the accessor is still partial.
    @Test
    void analyze_getLastGuardOnOtherReceiver_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var segments = wire.items();
                        var others = wire.others();
                        return others.isEmpty() ? Option.none() : Option.some(segments.getLast());
                    }
                }
                """));
    }

    /// #3065 control: the guard comes AFTER the accessor, so it does not dominate it.
    @Test
    void analyze_getLastGuardAfterAccessor_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var segments = wire.items();
                        var last = segments.getLast();
                        return segments.isEmpty() ? Option.none() : Option.some(last);
                    }
                }
                """));
    }

    /// #3065 control: a guarded accessor does not excuse another partial operation in the same body.
    @Test
    void analyze_guardedGetLastPlusThrow_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var segments = wire.items();
                        if (wire.broken()) {
                            throw new IllegalStateException("broken");
                        }
                        return segments.isEmpty() ? Option.none() : Option.some(segments.getLast());
                    }
                }
                """));
    }

    /// #3065 control: the accessor receiver is qualified (`wire.items`), a different expression from the guarded `items`.
    @Test
    void analyze_getLastOnQualifiedReceiver_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var items = wire.others();
                        return items.isEmpty() ? Option.none() : Option.some(wire.items.getLast());
                    }
                }
                """));
    }

    /// #3065 control: the accessor receiver is a call result, which the text guard does not resolve, so it stays partial.
    @Test
    void analyze_getLastOnCallResultReceiver_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        return wire.items().isEmpty() ? Option.none() : Option.some(wire.items().getLast());
                    }
                }
                """));
    }

    /// #3065 control: only `getFirst` / `getLast` are guardable; an indexed `get(0)` stays partial.
    @Test
    void analyze_indexedGetUnderEmptyGuard_flags() {
        assertTrue(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var segments = wire.items();
                        return segments.isEmpty() ? Option.none() : Option.some(segments.get(0));
                    }
                }
                """));
    }

    /// #3065 a guarded accessor whose `.getLast()` starts on a new line is still guarded.
    @Test
    void analyze_getLastReceiverOnPreviousLine_isClean() {
        assertFalse(hasRule("""
                package org.example;
                class Handler {
                    Object run(Option<Wire> o) {
                        return o.flatMap(Handler::lastName);
                    }
                    static Option<String> lastName(Wire wire) {
                        var segments = wire.items();
                        return segments.isEmpty() ? Option.none() : Option.some(segments
                                .getLast());
                    }
                }
                """));
    }

    /// #3065 polarity: `if (!xs.isEmpty()) { xs.getFirst() }` reaches the accessor on the non-empty side.
    @Test
    void analyze_polarityIfNotEmptyBlock_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (!xs.isEmpty()) {
                    return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    /// #3065 polarity: `if (xs.isEmpty()) return ...;` then the accessor: early exit.
    @Test
    void analyze_polarityEarlyExitReturn_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: early exit through a block whose last statement returns.
    @Test
    void analyze_polarityEarlyExitBlock_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) {
                    return Option.none();
                }
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: the early exit is guarded, but the `throw` is itself a partial operation, so the body still flags.
    @Test
    void analyze_polarityEarlyExitThrow_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (xs.isEmpty()) {
                    throw new IllegalStateException("empty");
                }
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: `!xs.isEmpty() ? acc : d`.
    @Test
    void analyze_polarityTernaryNegatedThen_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return !xs.isEmpty() ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: `if (xs.size() > 0)` is the non-empty side.
    @Test
    void analyze_polaritySizePositiveIf_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.size() > 0) {
                    return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    /// #3065 polarity: `size() > 0 ? acc : d`.
    @Test
    void analyze_polaritySizePositiveTernary_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() > 0 ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: `size() == 0 ? d : acc`.
    @Test
    void analyze_polaritySizeZeroTernaryElse_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() == 0 ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: `!xs.isEmpty() && acc`.
    @Test
    void analyze_polarityNotEmptyAndChain_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return !xs.isEmpty() && xs.getFirst().isEmpty();
                """)));
    }

    /// #3065 polarity: empty side of an `if`: must fire.
    @Test
    void analyze_polarityEmptySideIf_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (xs.isEmpty()) {
                    return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    /// #3065 polarity: empty side, single statement: must fire.
    @Test
    void analyze_polarityEmptySideSingleStatement_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.some(xs.getFirst());
                return Option.none();
                """)));
    }

    /// #3065 polarity: `isEmpty() ? acc : d`: the accessor is on the empty side.
    @Test
    void analyze_polarityEmptySideTernaryThen_flags() {
        assertTrue(hasRule(polarityFixture("""
                return xs.isEmpty() ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: `!isEmpty() ? d : acc`: the accessor is on the empty side.
    @Test
    void analyze_polarityNegatedTernaryElse_flags() {
        assertTrue(hasRule(polarityFixture("""
                return !xs.isEmpty() ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: `size() == 0 ? acc : d`.
    @Test
    void analyze_polaritySizeZeroTernaryThen_flags() {
        assertTrue(hasRule(polarityFixture("""
                return xs.size() == 0 ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: `isEmpty() && acc` evaluates the accessor on the empty side.
    @Test
    void analyze_polarityEmptyAndChain_flags() {
        assertTrue(hasRule(polarityFixture("""
                return xs.isEmpty() && xs.getFirst().isEmpty();
                """)));
    }

    /// #3065 polarity: compound `||` condition: polarity undecidable, keeps firing.
    @Test
    void analyze_polarityCompoundOrCondition_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (!xs.isEmpty() || wire.broken()) {
                    return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    /// #3065 polarity: negated compound condition: keeps firing.
    @Test
    void analyze_polarityNegatedCompoundEarlyExit_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (!(wire.broken() || xs.isEmpty())) {
                    return Option.none();
                }
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: the `if (empty)` body does not leave, so the accessor is reachable when empty.
    @Test
    void analyze_polarityConditionalEarlyExit_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (xs.isEmpty()) {
                    wire.log();
                }
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: the early exit guards only its inner block.
    @Test
    void analyze_polarityEarlyExitInClosedBlock_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (wire.broken()) {
                    if (xs.isEmpty()) return Option.none();
                }
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: `else if (empty) return` is conditional on the first test.
    @Test
    void analyze_polarityElseIfEarlyExit_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (wire.broken()) {
                    wire.log();
                } else if (xs.isEmpty()) return Option.none();
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: the accessor sits after an `||` and is reachable when the guard is false.
    @Test
    void analyze_polarityAndChainEscapedByOr_flags() {
        assertTrue(hasRule(polarityFixture("""
                return xs.size() == 1 && wire.broken() || xs.getFirst().isEmpty();
                """)));
    }

    /// #3065 polarity: the guard is only part of the condition, so the accessor is reachable when empty.
    @Test
    void analyze_polarityTernaryCompoundCondition_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.broken() || !xs.isEmpty() ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: the guard names `wire.xs`, a different expression from `xs`.
    @Test
    void analyze_polarityGuardOnQualifiedName_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.xs.isEmpty() ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: a nested ternary in the empty branch does not end it.
    @Test
    void analyze_polarityNestedTernary_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.isEmpty() ? wire.broken() ? Option.none() : Option.none() : Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: `size() >= 1 ? acc : d`.
    @Test
    void analyze_polaritySizeAtLeastOne_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() >= 1 ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: `size() != 0 ? acc : d`.
    @Test
    void analyze_polaritySizeNotZero_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() != 0 ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: `size() < 1 ? d : acc`.
    @Test
    void analyze_polaritySizeBelowOne_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() < 1 ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: `size() <= 0 ? d : acc`.
    @Test
    void analyze_polaritySizeAtMostZero_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() <= 0 ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: the `&&` guard names `wire.xs`, a different expression from `xs`.
    @Test
    void analyze_polarityQualifiedSizeGuardAndChain_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.xs.size() > 0 && xs.getFirst().isEmpty();
                """)));
    }

    /// #3065 polarity: the accessor is in a nested branch of the EMPTY side.
    @Test
    void analyze_polarityNestedTernaryAccessorInEmptyBranch_flags() {
        assertTrue(hasRule(polarityFixture("""
                return xs.isEmpty() ? wire.broken() ? Option.none() : Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: a `::` method reference inside the `&&` chain is not a ternary.
    @Test
    void analyze_polarityAndChainWithMethodReference_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return xs.size() == 1 && wire.apply(String::trim) && xs.getFirst().isEmpty();
                """)));
    }

    /// #3065 polarity: a NON-EMPTY-side guard that comes after the accessor does not dominate it.
    @Test
    void analyze_polarityNonEmptyGuardAfterAccessor_flags() {
        assertTrue(hasRule(polarityFixture("""
                var first = xs.getFirst();
                return !xs.isEmpty() && first.isEmpty() ? Option.none() : Option.none();
                """)));
    }

    /// #3065 polarity: `size() >= 0` is always true, so it proves nothing about emptiness.
    @Test
    void analyze_polaritySizeAtLeastZero_flags() {
        assertTrue(hasRule(polarityFixture("""
                return xs.size() >= 0 ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    /// #3065 polarity: the non-empty `if` ended before the accessor (braced statement).
    @Test
    void analyze_polarityAccessorAfterNonEmptyIfBlock_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (!xs.isEmpty()) {
                    wire.touch();
                }
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 polarity: the non-empty `if` ended before the accessor (single statement).
    @Test
    void analyze_polarityAccessorAfterNonEmptyIfSingleStatement_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (!xs.isEmpty()) wire.touch();
                return Option.some(xs.getFirst());
                """)));
    }

    /// #3065 undecidable classes (receiver written after the guard, old-style case labels, guard as operand of a comparison or
    /// arithmetic, comma-separated arguments, `||` before a chain) keep firing; the *_isClean forms stay exempt.
    @Test
    void analyze_polarityReceiverReassignedAfterEarlyExit_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                xs = wire.other();
                return Option.some(xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityReceiverMutatedAfterEarlyExit_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                xs.clear();
                return Option.some(xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityReceiverReassignedInsideNonEmptyIf_flags() {
        assertTrue(hasRule(polarityFixture("""
                if (!xs.isEmpty()) {
                    xs = wire.other();
                    return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    @Test
    void analyze_polarityEarlyExitThenOldStyleCaseLabel_flags() {
        assertTrue(hasRule(polarityFixture("""
                switch (wire.kind()) {
                    case 1:
                        wire.touch();
                        if (xs.isEmpty()) return Option.none();
                        wire.touch();
                        break;
                    case 2:
                        return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    @Test
    void analyze_polarityReturnThenOldStyleCaseLabel_flags() {
        assertTrue(hasRule(polarityFixture("""
                switch (wire.kind()) {
                    case 1:
                        wire.touch();
                        if (xs.isEmpty()) return Option.none();
                        return Option.none();
                    case 2:
                        return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    @Test
    void analyze_polarityGuardComparedToNonLiteralTernary_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.flag() == xs.isEmpty() ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityNegatedGuardComparedToNonLiteralTernary_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.flag() == !xs.isEmpty() ? Option.some(xs.getFirst()) : Option.none();
                """)));
    }

    @Test
    void analyze_polarityNegatedGuardComparedToNonLiteralAndChain_flags() {
        assertTrue(hasRule(polarityFixture("""
                return Option.option(wire.flag() == !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityArithmeticBeforeSizeGuardAndChain_flags() {
        assertTrue(hasRule(polarityFixture("""
                return Option.option(wire.count() - xs.size() > 0 && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityGuardAndAccessorAreSeparateArguments_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.pick(!xs.isEmpty() && wire.flag(), xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityDisjunctionBeforeAndChain_flags() {
        assertTrue(hasRule(polarityFixture("""
                return Option.option(wire.flag() || !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityGuardedAssignmentTernary_isClean() {
        assertFalse(hasRule(polarityFixture("""
                var first = xs.isEmpty() ? "" : xs.getFirst();
                return Option.some(first);
                """)));
    }

    @Test
    void analyze_polarityAndChainAfterOtherConjunct_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(wire.flag() && !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityReadMemberBetweenGuardAndAccessor_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                wire.touch(xs.size());
                return Option.some(xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityEarlyExitThenArrowSwitchArm_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                return switch (wire.kind()) {
                    case 1 -> Option.some(xs.getFirst());
                    default -> Option.none();
                };
                """)));
    }

    @Test
    void analyze_polarityBitAndBeforeGuardAndChain_flags() {
        assertTrue(hasRule(polarityFixture("""
                return Option.option(wire.count() & !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityEarlyExitThenDefaultLabel_flags() {
        assertTrue(hasRule(polarityFixture("""
                switch (wire.kind()) {
                    case 1:
                        wire.touch();
                        if (xs.isEmpty()) return Option.none();
                        break;
                    default:
                        return Option.some(xs.getFirst());
                }
                return Option.none();
                """)));
    }

    @Test
    void analyze_polarityTernaryGuardAfterAndOperand_flags() {
        assertTrue(hasRule(polarityFixture("""
                return wire.flag() && xs.isEmpty() ? Option.none() : Option.some(xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityReceiverComparedBetweenGuardAndAccessor_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                wire.touch(xs == wire.other());
                return Option.some(xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityAndChainAccessorInsideCallArguments_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(!xs.isEmpty() && wire.test(wire.flag(), xs.getFirst()));
                """)));
    }

    @Test
    void analyze_polarityGuardedTernaryAsLaterArgument_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.some(wire.pick(wire.flag(), xs.isEmpty() ? "" : xs.getFirst()));
                """)));
    }

    @Test
    void analyze_polarityGuardedTernaryInsideOuterTernaryThenBranch_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.some(wire.flag() ? xs.isEmpty() ? "" : xs.getFirst() : "");
                """)));
    }

    @Test
    void analyze_polarityGuardedTernaryInElseBranchOfOuterTernary_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.some(wire.flag() ? "" : xs.isEmpty() ? "" : xs.getFirst());
                """)));
    }

    @Test
    void analyze_polarityGuardAfterLambdaArrow_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(wire.any(w -> !xs.isEmpty() && xs.getFirst().isEmpty()));
                """)));
    }

    @Test
    void analyze_polarityGuardedTernaryAfterYield_isClean() {
        assertFalse(hasRule(polarityFixture("""
                var s = switch (wire.kind()) {
                    case 1 -> { yield xs.isEmpty() ? "" : xs.getFirst(); }
                    default -> "";
                };
                return Option.some(s);
                """)));
    }

    @Test
    void analyze_polarityParenthesizedDisjunctionBeforeAndChain_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option((wire.flag() || wire.other()) && !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityDisjunctionOutsideEnclosingCall_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(wire.flag() || wire.test(!xs.isEmpty() && xs.getFirst().isEmpty()));
                """)));
    }

    @Test
    void analyze_polarityDisjunctionInEarlierStatement_isClean() {
        assertFalse(hasRule(polarityFixture("""
                var f = wire.flag() || wire.other();
                return Option.option(wire.test(f) && !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityDisjunctionInEarlierArgument_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(wire.pick(wire.flag() || wire.other(), wire.test(true) && !xs.isEmpty() && xs.getFirst().isEmpty()));
                """)));
    }

    @Test
    void analyze_polarityDisjunctionInTernaryCondition_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(wire.flag() || wire.other() ? wire.test(true) && !xs.isEmpty() && xs.getFirst().isEmpty() : false);
                """)));
    }

    @Test
    void analyze_polarityBitOrBeforeAndChain_isClean() {
        assertFalse(hasRule(polarityFixture("""
                return Option.option(wire.flag() | wire.other() && !xs.isEmpty() && xs.getFirst().isEmpty());
                """)));
    }

    @Test
    void analyze_polarityEarlyExitThenArrowArmWithTernary_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                return switch (wire.kind()) {
                    case 1 -> Option.some(wire.flag() ? "x" : xs.getFirst());
                    default -> Option.none();
                };
                """)));
    }

    @Test
    void analyze_polarityEarlyExitThenArrowArmWithMethodReference_isClean() {
        assertFalse(hasRule(polarityFixture("""
                if (xs.isEmpty()) return Option.none();
                return switch (wire.kind()) {
                    case 1 -> wire.map(Handler::lastName, xs.getFirst());
                    default -> Option.none();
                };
                """)));
    }

    /// Source whose referenced `lastName` has body `body` over the plain-identifier receiver `xs`.
    private static String polarityFixture(String body) {
        return "package org.example;\n"
               + "class Handler {\n"
               + "    Object run(Option<Wire> o) {\n"
               + "        return o.flatMap(Handler::lastName);\n"
               + "    }\n"
               + "    static Option<String> lastName(Wire wire) {\n"
               + "        var xs = wire.items();\n"
               + body.indent(8)
               + "    }\n"
               + "}\n";
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
