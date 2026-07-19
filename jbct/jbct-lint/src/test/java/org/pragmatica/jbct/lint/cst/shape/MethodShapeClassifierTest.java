package org.pragmatica.jbct.lint.cst.shape;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.cst.shape.MethodShapeClassifier.ShapeVerdict;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
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
            assertShape(MethodShape.UNCLASSIFIED, "Object m() { var x = compute(); return x.transform(); }");
        }

        @Test
        void classify_loopStatement_isUnclassified() {
            assertShape(MethodShape.UNCLASSIFIED, "void m(List xs) { for (var x : xs) { process(x); } }");
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
                    Object bad() { var x = f(); return x.y(); }
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
