package org.pragmatica.jbct.format;

import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.format.JbctFormatter.jbctFormatter;

/// `new Class<?>[0]` was reformatted to `new Class< ?>[0]` (#621).
///
/// `?` sits in the spaced-operator set because of the ternary, and that spacing is normally
/// suppressed by `typeContextDepth`. The array-creation path leaves that depth at 0, so the
/// wildcard was printed as a ternary operator. The declaration form `Class<?>[] field` goes through
/// a path that does raise the depth and was never affected — which is why the two forms are pinned
/// separately here: they exercise different code and only one of them regressed.
///
/// The output still compiled (Java permits whitespace inside type arguments) and was idempotent, so
/// nothing caught it until a full build reformatted the one file in the corpus that uses the shape.
class WildcardSpacingTest {
    private final JbctFormatter formatter = jbctFormatter();

    @Test
    void format_keepsWildcardGlued_inArrayCreation() {
        assertThat(format("    Object a = new Class<?>[0];"))
                  .contains("new Class<?>[0]")
                  .doesNotContain("< ?");
    }

    @Test
    void format_keepsWildcardGlued_inMultiDimensionalArrayCreation() {
        assertThat(format("    Object a = new Class<?>[0][0];"))
                  .contains("new Class<?>[0][0]")
                  .doesNotContain("< ?");
    }

    @Test
    void format_keepsWildcardGlued_inArrayCreationWithInitializer() {
        assertThat(format("    Object a = new Class<?>[]{};"))
                  .contains("new Class<?>[]")
                  .doesNotContain("< ?");
    }

    /// Only the FIRST wildcard was affected — the one directly after `<`. The second sits after a
    /// comma and was always correct, so this pins that the fix did not disturb it.
    @Test
    void format_keepsBothWildcardsGlued_inTwoArgumentArrayCreation() {
        assertThat(format("    Object a = new java.util.Map<?, ?>[0];"))
                  .contains("new java.util.Map<?, ?>[0]")
                  .doesNotContain("< ?");
    }

    /// The declaration form always worked. Pinned so a fix for the creation form cannot break it.
    @Test
    void format_keepsWildcardGlued_inFieldDeclaration() {
        assertThat(format("    Class<?>[] a = null;"))
                  .contains("Class<?>[] a")
                  .doesNotContain("< ?");
    }

    /// The `?` really is the ternary operator elsewhere, and must keep its spacing.
    @Test
    void format_stillSpacesTernaryOperator() {
        var formatted = format("""
                    int f(boolean flag) {
                        return flag ? 1 : 2;
                    }
                """);

        assertThat(formatted).contains("?");
        assertThat(formatted).doesNotContain("flag? 1");
    }

    private String format(String body) {
        var result = new String[] {null};

        formatter.format(new SourceFile(Path.of("T.java"),
                                        "package com.example;\n\npublic class T {\n" + body + "\n}\n"))
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(formatted -> result[0] = formatted.content());

        return result[0];
    }
}
