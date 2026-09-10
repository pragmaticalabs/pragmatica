package org.pragmatica.jbct.parser;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;


/// JLS 8.1.1 / 8.3.1 / 8.4.3: modifiers and annotations may appear in ANY order, interleaved.
/// The grammar used to require `Annotation* Modifier*`, so a modifier keyword following an
/// annotation had nowhere to go — after an annotation the only continuation was `Type`, and
/// `static` is not a type.
///
/// **This rejected valid Java, and #977 made that dangerous.** Before #977 an unparseable file was
/// silently counted as passed, so the bug was a wrong-PASS nobody saw. After #977 an unparseable
/// file fails the build, which turns the same bug into a wrong-FAIL on correct input — and `jbct`
/// is published, so an external user in this shape gets a broken build from an rc4 upgrade. The
/// idiom is the JDK's own: `java.base` uses it in `Charset` and `StringConcatFactory`.
///
/// A parser that rejects valid Java is not strict, it is wrong; shipping that as a build failure is
/// worse than the silence it replaced.
///
/// **The negative cases matter as much as the positive ones.** The fix admits an annotation into
/// the modifier list only when a modifier keyword actually follows it
/// (`&(Annotation+ Modifier) Annotation`), so an annotation sitting immediately before the type
/// still belongs to `Type` and every previously-parsing input keeps its exact CST shape. Measured
/// rather than argued: formatting all 3369 `java.base` files with the old and the new parser
/// produced byte-identical output for 3367 of them, the two exceptions being the two files that
/// previously failed to parse at all.
class InterleavedModifierAnnotationTest {
    private final Java25Parser parser = new Java25Parser();

    /// Every string here is `javac`-validated. The first two are the shapes that used to fail; the
    /// rest are the orderings that already worked and must keep working.
    @ParameterizedTest
    @ValueSource(strings = {
        // --- the regression: a keyword modifier AFTER an annotation ---
        "class T { private @Deprecated static int x = 1; }",
        "class T { public @Deprecated static int m() { return 1; } }",
        // a RUN of annotations before the modifier, which a single-annotation lookahead would miss
        "class T { private @Deprecated @SuppressWarnings(\"x\") static int x = 1; }",
        // several modifiers on either side of the annotation
        "class T { protected @Deprecated final static synchronized int m() { return 1; } }",
        // the same shape in every member context the grammar declares separately
        "interface T { public @Deprecated static int m() { return 1; } }",
        "record T(int a) { private @Deprecated static int C = 1; }",
        "@interface T { public @Deprecated static int C = 1; }",
        "class T { private @Deprecated static class Inner {} }",
        "class T { private @Deprecated static void m() {} }",
        // --- orderings that already parsed, pinned so the fix cannot regress them ---
        "class T { private @Deprecated int x = 1; }",
        "class T { @Deprecated private static int x = 1; }",
        "class T { private static @Deprecated int x = 1; }",
        "class T { public static @Deprecated int m() { return 1; } }",
        "class T { @Deprecated public static int m() { return 1; } }",
        "@Deprecated abstract class T {}",
        "class T {}"
    })
    void parse_acceptsEveryValidModifierAnnotationOrdering(String source) {
        assertThat(parser.parse(source)
                         .isSuccess())
                  .withFailMessage("valid Java rejected by the parser: %s", source)
                  .isTrue();
    }

    /// The exact declaration from `java.base/java/nio/charset/Charset.java:622`, which is how this
    /// was found. `@Stable` is a JDK-internal annotation, so it is spelled as a plain name here —
    /// the parser resolves nothing, and the shape is what is under test.
    @Test
    void parse_acceptsTheJdkDeclarationThatSurfacedThisBug() {
        assertThat(parser.parse("class Charset { private @Stable static Charset defaultCharset; }")
                         .isSuccess())
                  .isTrue();
    }

    /// The guard is `&(Annotation+ Modifier)`, and these two cases are why it is written that way
    /// rather than as a plain `(Annotation / Modifier)*`.
    ///
    /// Under the naive form BOTH sources below would parse — so "it parses" cannot tell the two
    /// implementations apart, and a test asserting only that would pass against the wrong one. What
    /// separates them is WHERE the annotation lands: the naive form would pull `@Deprecated` out of
    /// the type and into the modifier list in the no-modifier case too, silently changing the CST
    /// under every lint rule that reads a type annotation. Span containment is the falsifiable form
    /// of that distinction.
    @Test
    void parse_keepsAnAnnotationInsideTheTypeWhenNoModifierFollowsIt() {
        assertThat(annotationSitsInsideTheType("class T { private @Deprecated int x = 1; }"))
                  .withFailMessage("the annotation was pulled out of Type — the lookahead guard is gone")
                  .isTrue();
    }

    /// The mirror case, which is the one the fix newly admits: here a modifier DOES follow, so the
    /// annotation belongs to the modifier list and must NOT be inside the type. Asserted as the
    /// opposite outcome of the same predicate, so a predicate that always returned one answer would
    /// fail one of the pair.
    @Test
    void parse_movesAnAnnotationOutOfTheTypeWhenAModifierFollowsIt() {
        assertThat(annotationSitsInsideTheType("class T { private @Deprecated static int x = 1; }"))
                  .withFailMessage("the annotation stayed inside Type — it cannot, a modifier follows it")
                  .isFalse();
    }

    /// True when every `Annotation` node's span is contained in some `Type` node's span.
    private boolean annotationSitsInsideTheType(String source) {
        var root = parser.parse(source)
                         .onFailure(cause -> {
                             throw new AssertionError("fixture did not parse: " + cause.message());
                         })
                         .unwrap();
        var all = new ArrayList<Cursor>();

        collect(root, all);

        var types = all.stream()
                       .filter(node -> node.kindIs(RuleKind.TYPE))
                       .toList();
        var annotations = all.stream()
                             .filter(node -> node.kindIs(RuleKind.ANNOTATION))
                             .toList();

        assertThat(annotations).as("fixture must contain an annotation for this test to mean anything")
                               .isNotEmpty();

        return annotations.stream()
                          .allMatch(annotation -> types.stream()
                                                       .anyMatch(type -> type.spanStart() <= annotation.spanStart()
                                                                        && annotation.spanEnd() <= type.spanEnd()));
    }

    /// `descendants()` is declared on `Cursor.Branch`, not on `Cursor`, so the walk is explicit.
    private static void collect(Cursor node, List<Cursor> out) {
        out.add(node);

        if (node instanceof Cursor.Branch branch) {
            branch.children()
                  .forEach(child -> collect(child, out));
        }
    }
}
