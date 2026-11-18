package org.pragmatica.jbct.cli.skeleton;

import org.junit.jupiter.api.Test;

import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.declarations;
import static org.assertj.core.api.Assertions.assertThat;


/// Annotations are listed wherever they sit among the modifiers, in source order.
class SkeletonAnnotationsTest {
    private static final String SOURCE = """
        class A {
            public @Cfg(v = {"q"}) String ret() { return ""; }

            @One public @Two final @Three int f;

            public @Mid class Inner {}
        }
        """;

    @Test
    void anAnnotationAfterAModifier_isListedOnAMethod() {
        assertThat(annotationsOf("ret")).containsExactly("@Cfg");
    }

    @Test
    void annotationsBeforeBetweenAndAfterModifiers_areListedInSourceOrder() {
        assertThat(annotationsOf("f")).containsExactly("@One", "@Two", "@Three");
    }

    @Test
    void anAnnotationAfterAModifier_isListedOnAType() {
        assertThat(annotationsOf("Inner")).containsExactly("@Mid");
    }

    private static java.util.List<String> annotationsOf(String name) {
        return declarations(SOURCE, true).getFirst()
                                         .children()
                                         .stream()
                                         .filter(declaration -> declaration.name().equals(name))
                                         .findFirst()
                                         .orElseThrow()
                                         .annotations();
    }
}
