package org.pragmatica.jbct.lint.cst.shape;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier.ShapeVerdict;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.jbct.parser.CstNodes.findAll;
import static org.pragmatica.jbct.parser.CstNodes.findAllLambdas;
import static org.pragmatica.jbct.parser.CstNodes.findAllMethods;
import static org.pragmatica.jbct.parser.CstNodes.findFirstMethod;

/// Coverage for [MethodShapeClassifier]: at least one method fixture per shape (all six), plus
/// MIXED, UNCLASSIFIED, the legal-composition case (an extracted fork-join *called* as a sequencer
/// step is a Sequencer, never MIXED), the factory-returns-lambda unwrap, abstract-method exclusion,
/// and the [ShapeCensus] aggregation over a parsed root and over a source-file collection (#448).
class MethodShapeClassifierTest {
    private static void assertShape(MethodShape expected, String body) {
        assertEquals(Option.some(expected), classify(body).map(ShapeVerdict::shape));
    }

    private static Option<ShapeVerdict> classify(String body) {
        return findFirstMethod(parse("class C {\n" + body + "\n}")).flatMap(MethodShapeClassifier::classify);
    }

    private static Cursor parse(String source) {
        return new Java25Parser().parse(source)
                                 .onFailure(cause -> fail("parse failed: " + cause.message()))
                                 .unwrap();
    }

    /// Phase-2 MIXED (#448): a composition whose chain passes an inline lambda carrying its own
    /// structural pattern.
    ///
    /// Phase 1 scoped MIXED to a fork-join head plus a stream pipeline, leaving the richer nestings
    /// to JBCT-PAT-02 / LAM-03 / NEST-01. The bucket was then unreachable in practice — 0 across
    /// 49,460 methods in seven codebases, including deliberate violations — so a measured
    /// `MIXED = 0` read as conformance when it was the instrument's silence, and `lint` and
    /// `shape-census` gave contradictory verdicts on the same method.
    ///
    /// The three violation cases below are the reported reproduction verbatim. The two controls are
    /// what keep the bucket meaning "JBCT code mixing JBCT patterns" rather than "not written in the
    /// vocabulary".
    @Nested
    class NestedPatternMixing {
        @Test
        void classify_forkJoinInsideSequencerLambda_isMixed() {
            assertShape(MethodShape.MIXED,
                        """
                        Promise<R> m(Request request) {
                            return validate(request)
                                .flatMap(this::loadAccount)
                                .flatMap(acc -> Promise.all(fetchLimits(acc), fetchHistory(acc)).map(this::combine))
                                .map(this::toResponse);
                        }
                        """);
        }

        @Test
        void classify_conditionInsideSequencerLambda_isMixed() {
            assertShape(MethodShape.MIXED,
                        """
                        Promise<R> m(Request request) {
                            return validate(request)
                                .flatMap(v -> v.isPremium() ? premiumPath(v) : standardPath(v))
                                .map(this::toResponse);
                        }
                        """);
        }

        @Test
        void classify_iterationInsideSequencerLambda_isMixed() {
            assertShape(MethodShape.MIXED,
                        """
                        Promise<R> m(Request request) {
                            return validate(request)
                                .map(v -> v.items().stream().filter(Item::isActive).toList())
                                .flatMap(this::persistAll)
                                .map(this::toResponse);
                        }
                        """);
        }

        /// The regression guard. Method references ARE the fix the book prescribes, so extraction
        /// must never be penalised — if this goes MIXED the rule punishes the correction.
        @Test
        void classify_sameShapesExtractedToMethodReferences_staysSequencer() {
            assertShape(MethodShape.SEQUENCER,
                        """
                        Promise<R> m(Request request) {
                            return validate(request)
                                .flatMap(this::loadAccount)
                                .flatMap(this::gatherInParallel)
                                .map(this::toResponse);
                        }
                        """);
        }

        /// Imperative code is not mixing patterns; it is not written in the vocabulary. If MIXED
        /// absorbed it, the bucket would measure "is this JBCT code" and swamp any cross-codebase
        /// comparison — in the run that found this bug, 48,000 external methods would have gone
        /// almost entirely to MIXED.
        @Test
        void classify_maximalImperativeBody_staysUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED,
                        """
                        String m(Order order, int mode) {
                            var out = new StringBuilder();
                            if (order == null) { return ""; } else { out.append("x"); }
                            for (var line : order.lines()) { out.append(line.sku()); }
                            int i = 0;
                            while (i < 3) { out.append(i++); }
                            switch (mode) { case 1 -> out.append("one"); default -> out.append("other"); }
                            return out.toString();
                        }
                        """);
        }

        /// A lambda whose body is a one-link chain classifies LEAF, and a plain step is the
        /// CONFORMANT case. This is where the classifier and JBCT-NEST-01 legitimately diverge:
        /// NEST-01 flags any nested monadic operation, which is a broader claim than "carries a
        /// structural pattern". Promoting this would make MIXED mean what NEST-01 means and would
        /// pull conformant multi-argument lambdas into the bucket.
        @Test
        void classify_lambdaWhoseBodyIsAPlainStep_staysSequencer() {
            assertShape(MethodShape.SEQUENCER,
                        """
                        Promise<R> m(String raw) {
                            return load(raw)
                                .flatMap(id -> CollateralId.collateralId(id).map(Option::some))
                                .map(this::toResponse);
                        }
                        """);
        }
    }

    @Nested
    class SixShapes {
        @Test
        void classify_atomicCall_isLeaf() {
            assertShape(MethodShape.LEAF, "Object m(Id id) { return Promise.lift(E::new, () -> dsl.fetch(id)); }");
        }

        @Test
        void classify_rootTernary_isCondition() {
            assertShape(MethodShape.CONDITION, "Object m(O o) { return o.isPremium() ? premium(o) : standard(o); }");
        }

        @Test
        void classify_streamAggregation_isIteration() {
            assertShape(MethodShape.ITERATION, "Object m(List raw) { return Result.allOf(raw.stream().map(X::parse).toList()); }");
        }

        @Test
        void classify_topLevelStreamPipeline_isIteration() {
            assertShape(MethodShape.ITERATION, "Object m(List raw) { return raw.stream().map(X::parse).toList(); }");
        }

        @Test
        void classify_multiStepChain_isSequencer() {
            assertShape(MethodShape.SEQUENCER, "Object m(R r) { return validate(r).async().flatMap(check).flatMap(save); }");
        }

        @Test
        void classify_parallelJoinWithCombine_isForkJoin() {
            assertShape(MethodShape.FORK_JOIN, "Object m(Id id) { return Promise.all(fetchA(id), fetchB(id)).map(this::merge); }");
        }

        @Test
        void classify_bareParallelJoin_isForkJoin() {
            assertShape(MethodShape.FORK_JOIN, "Object m(Id id) { return Result.all(a(id), b(id)); }");
        }

        @Test
        void classify_withDecoratorReturningLambda_isAspect() {
            assertShape(MethodShape.ASPECT, "static Fn1 withTimeout(TimeSpan t, Fn1 step) { return input -> step.apply(input).timeout(t); }");
        }

        @Test
        void classify_lambdaApplyingInjectedParamAndDecorating_isAspect() {
            assertShape(MethodShape.ASPECT, "static Fn1 traced(Fn1 step) { return input -> step.apply(input).timeout(t); }");
        }
    }

    @Nested
    class Residual {
        @Test
        void classify_joinAndStreamAtSameAltitude_isMixed() {
            assertShape(MethodShape.MIXED, "Object m() { return Result.all(base(), limit()).map(this::ctx).stream().map(this::apply).toList(); }");
        }

        @Test
        void classify_multiStatementBody_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "Object m() { audit(x); return notify(x); }");
        }

        @Test
        void classify_loopStatement_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "void m(List xs) { for (var x : xs) { process(x); } }");
        }
    }

    /// Phase-2 reach (#448): a multi-statement body classifies by its composition-root tail when every
    /// leading statement is skippable preamble (a pure local declaration, a narrow guard, or a single
    /// logger call). The FP guard keeps a mutating-initializer local UNCLASSIFIED; a genuinely
    /// imperative leading statement (side effect / reassignment) stays UNCLASSIFIED with a precise reason.
    @Nested
    class PreambleReach {
        @Test
        void classify_localsThenForkJoin_isForkJoin() {
            assertShape(MethodShape.FORK_JOIN, "Object m(Id id) { var a = fetchA(id); var b = fetchB(id); return Result.all(a, b).map(this::merge); }");
        }

        @Test
        void classify_localThenMapChain_isSequencer() {
            assertShape(MethodShape.SEQUENCER, "Object m(R r) { var valid = validate(r); return valid.map(this::enrich).flatMap(this::save); }");
        }

        /// Locks the absorbed-head-call recovery ([MethodShapeClassifier#extractSpine]): a three-link
        /// chain on a variable receiver (`a.map(f).map(g).flatMap(h)`) — whose leading `.map` the v6
        /// PRIMARY folds into the head text — counts all three combinators and reads SEQUENCER both as
        /// a single-statement body and as a preamble tail, not LEAF.
        @Test
        void classify_threeLinkChainOnVariableReceiver_isSequencer() {
            assertShape(MethodShape.SEQUENCER, "Object m(A a) { return a.map(f).map(g).flatMap(h); }");
            assertShape(MethodShape.SEQUENCER, "Object m(R r) { var a = seed(r); return a.map(f).map(g).flatMap(h); }");
        }

        @Test
        void classify_localThenSingleCall_isLeaf() {
            assertShape(MethodShape.LEAF, "Object m() { var x = compute(); return x.transform(); }");
        }

        @Test
        void classify_mutatingInitializerLocal_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "Object m(Id id) { var seen = cache.put(id, id); return lookup(id).map(this::render); }");
        }

        @Test
        void classify_guardThenComposedTail_isSequencer() {
            assertShape(MethodShape.SEQUENCER, "Object m(R r) { if (r.bad()) return fail(); return validate(r).flatMap(a).flatMap(b); }");
        }

        @Test
        void classify_guardThrowThenComposedTail_isForkJoin() {
            assertShape(MethodShape.FORK_JOIN, "Object m(Id id) { if (id == null) throw new E(); return Promise.all(fetchA(id), fetchB(id)).map(this::merge); }");
        }

        @Test
        void classify_leadingLogThenComposedTail_isSequencer() {
            assertShape(MethodShape.SEQUENCER, "Object m(R r) { log.info(\"start\"); return validate(r).flatMap(a).flatMap(b); }");
        }

        @Test
        void classify_twoSideEffectStatements_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "void m(X x) { audit(x); notify(x); }");
        }

        @Test
        void classify_reassignmentBeforeTail_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "Object m(R r) { var acc = seed(r); acc = acc.plus(delta()); return acc.build(); }");
        }

        @Test
        void classify_bodyEndingInLocalDeclaration_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "void m() { var a = compute(); var b = enrich(a); }");
        }
    }

    @Nested
    class LegalComposition {
        /// An extracted fork-join CALLED as a sequencer step (behind a method reference) reads as a
        /// plain flatMap link — a Sequencer, never MIXED. The violation is nesting inside a lambda,
        /// not calling across altitudes.
        @Test
        void classify_extractedForkJoinAsSequencerStep_isSequencerNotMixed() {
            assertShape(MethodShape.SEQUENCER, "Object m(R r) { return validate(r).flatMap(this::fetchBoth).flatMap(this::finish); }");
        }

        /// A use-case factory returning a lambda is classified by the lambda's body composition.
        @Test
        void classify_factoryReturningLambdaChain_isSequencer() {
            assertShape(MethodShape.SEQUENCER, "static UC uc(Check c, Save s) { return request -> validate(request).flatMap(c).flatMap(s); }");
        }
    }

    /// Phase-3 argument-lambda descent primitive (#448): [MethodShapeClassifier#classifyLambdaBody]
    /// runs a lambda body through the same decision table as a method body, and
    /// [MethodShapeClassifier#chainLambdaLinks] exposes the lambda arguments of a chain paired with
    /// their link names — the descent [MethodShapeClassifier#extractSpine] discards. These are the
    /// shared primitive the absorbed JBCT-ZONE-03 / JBCT-NEST-01 / JBCT-PAT-02 facets build on.
    @Nested
    class LambdaDescent {
        private static ShapeVerdict descendFirstLambda(String methodSrc) {
            var root = parse("class C {\n" + methodSrc + "\n}");

            return MethodShapeClassifier.classifyLambdaBody(findAllMethods(root).getFirst(),
                                                            findAllLambdas(root).getFirst());
        }

        private static Cursor topChain(String methodSrc) {
            return findAll(parse("class C {\n" + methodSrc + "\n}"), RuleKind.POSTFIX).getFirst();
        }

        @Test
        void classifyLambdaBody_multiStepChain_isSequencer() {
            assertEquals(MethodShape.SEQUENCER,
                         descendFirstLambda("Object m() { return outer.flatMap(x -> a(x).map(f).flatMap(g)); }").shape());
        }

        @Test
        void classifyLambdaBody_singleCall_isLeaf() {
            assertEquals(MethodShape.LEAF, descendFirstLambda("Object m() { return outer.map(x -> x.trim()); }").shape());
        }

        @Test
        void classifyLambdaBody_nestedForkJoin_isForkJoin() {
            assertEquals(MethodShape.FORK_JOIN,
                         descendFirstLambda("Object m() { return outer.flatMap(x -> Result.all(a(x), b(x)).map(this::merge)); }").shape());
        }

        @Test
        void classifyLambdaBody_rootTernaryBody_isCondition() {
            assertEquals(MethodShape.CONDITION,
                         descendFirstLambda("Object m() { return outer.map(x -> x.ok() ? p(x) : q(x)); }").shape());
        }

        @Test
        void classifyLambdaBody_blockBodyWithComposedTail_isSequencer() {
            assertEquals(MethodShape.SEQUENCER,
                         descendFirstLambda("Object m() { return outer.flatMap(x -> { var y = seed(x); return y.map(f).flatMap(g); }); }").shape());
        }

        @Test
        void chainLambdaLinks_pairsFlatMapWithArgumentLambda() {
            var links = MethodShapeClassifier.chainLambdaLinks(topChain("Object m(R r) { return validate(r).flatMap(x -> a(x).map(f)); }"));

            assertEquals(1, links.size());
            assertEquals("flatMap", links.getFirst().link());
        }

        @Test
        void chainLambdaLinks_recoversAbsorbedHeadCallLinkName() {
            var links = MethodShapeClassifier.chainLambdaLinks(topChain("Object m() { return value.map(x -> x.trim()); }"));

            assertEquals(1, links.size());
            assertEquals("map", links.getFirst().link());
        }

        @Test
        void chainLambdaLinks_ignoresMethodReferenceArguments() {
            assertThat(MethodShapeClassifier.chainLambdaLinks(topChain("Object m(R r) { return validate(r).flatMap(this::save).map(X::wrap); }"))).isEmpty();
        }
    }

    @Nested
    class Exclusions {
        @Test
        void classify_abstractInterfaceMethod_isExcluded() {
            var method = findFirstMethod(parse("interface Step { Promise<X> apply(Y y); }"));

            assertThat(method.flatMap(MethodShapeClassifier::classify)
                             .isEmpty()).isTrue();
        }
    }

    @Nested
    class Census {
        @Test
        void census_mixedFile_tallizesHistogramAndResidualRate() {
            var root = parse("""
                class Foo {
                    Object leaf() { return compute(); }
                    Object seq() { return validate().flatMap(a).flatMap(b); }
                    Object bad() { audit(x); return notify(x); }
                }
                """);
            var report = ShapeCensus.census(root);

            assertEquals(3, report.totalMethods());
            assertEquals(1, report.count(MethodShape.LEAF));
            assertEquals(1, report.count(MethodShape.SEQUENCER));
            assertEquals(1, report.count(MethodShape.UNCLASSIFIED));
            assertThat(report.residualRate()).isCloseTo(1.0 / 3, within(1e-9));
        }

        @Test
        void census_render_includesTotalAndResidual() {
            var report = ShapeCensus.census(parse("class Foo { Object leaf() { return compute(); } }"));

            assertThat(report.render()).contains("1 methods")
                                       .contains("LEAF")
                                       .contains("residual");
        }

        @Test
        void census_countsEveryConcreteMethodAcrossNestedTypes() {
            var root = parse("""
                class Foo {
                    Object a() { return x(); }
                    static class Bar {
                        Object b() { return y().flatMap(f).flatMap(g); }
                    }
                }
                """);

            assertEquals(2, findAllMethods(root).size());
            assertEquals(2, ShapeCensus.census(root).totalMethods());
        }

        @Test
        void census_sourceFileCollection_parsesAndTalliesAcrossFiles() {
            var files = List.of(SourceFile.sourceFile(Path.of("A.java"), "class A { Object m() { return compute(); } }"),
                                SourceFile.sourceFile(Path.of("B.java"), "class B { Object m() { return v().flatMap(a).flatMap(b); } }"));
            var report = ShapeCensus.census(files);

            assertEquals(2, report.totalMethods());
            assertEquals(1, report.count(MethodShape.LEAF));
            assertEquals(1, report.count(MethodShape.SEQUENCER));
        }
    }
}
