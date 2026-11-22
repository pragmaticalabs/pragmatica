package org.pragmatica.jbct.cli.skeleton;

import org.junit.jupiter.api.Test;

import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.declarations;
import static org.assertj.core.api.Assertions.assertThat;


/// Annotations are listed wherever they sit among the modifiers, in source order.
class SkeletonAnnotationsTest {
    private static final String SOURCE = """
        class A {
            public @Cfg(v = {"q"}) String ret() { return ""; }

            @One public @Two final int f;

            public @Init A() {}
        }
        """;

    private static final String RECORD = """
        record R(int x) {
            @Init @Cfg public R {}
        }
        """;

    @Test
    void anAnnotationAfterAModifier_isListedOnAMethod() {
        assertThat(annotationsOf("ret")).containsExactly("@Cfg");
    }

    @Test
    void annotationsBeforeAndBetweenModifiers_areListedInSourceOrder() {
        assertThat(annotationsOf("f")).containsExactly("@One", "@Two");
    }

    @Test
    void anAnnotationAfterAModifier_isListedOnAConstructor() {
        assertThat(annotationsOf("A")).containsExactly("@Init");
    }

    @Test
    void annotationsOnACompactConstructor_areListedInItsEntry() {
        var compact = declarations(RECORD, true).getFirst()
                                                .children()
                                                .getFirst();

        assertThat(compact.signature()).isEqualTo("(compact)");
        assertThat(compact.annotations()).containsExactly("@Init", "@Cfg");
    }

    private static java.util.List<String> annotationsOf(String name) {
        return declarations(SOURCE, true).getFirst()
                           .children()
                           .stream()
                           .filter(declaration -> declaration.name()
                                                             .equals(name))
                           .findFirst()
                           .orElseThrow()
                           .annotations();
    }
}
