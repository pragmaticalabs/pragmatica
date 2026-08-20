package org.pragmatica.jbct.format;

import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.format.JbctFormatter.jbctFormatter;

/// A comment between an annotation and the member it annotates used to be DELETED — silently, with
/// the build green, by a formatter that rewrites files in place on every build.
///
/// The parser attaches such a comment as trivia inside the ANNOTATION node's own span, so it is no
/// node's leading trivia and the leading-comment machinery never saw it; it is not a same-line
/// trailing comment either, so the inline and trailing paths both declined it. The generic trivia
/// walk then stepped past it.
///
/// It went unnoticed because a formatting diff is normally reviewed with whitespace ignored, and
/// `git diff -w` does not distinguish a deleted comment from a re-wrapped one. The comment that
/// exposed it recorded why a field was deliberately NOT a value object — the highest-value class of
/// comment, and the one this bug destroyed.
class AnnotationCommentPreservationTest {
    private final JbctFormatter formatter = jbctFormatter();

    private static final String ALL_FOUR_STYLES = """
            package com.example;

            public class Test {
                @Option(names = "--cluster")
                /// TRIPLE-SLASH-MARKER
                // DOUBLE-SLASH-MARKER
                /* BLOCK-MARKER */
                /** JAVADOC-MARKER */
                private String clusterNameOverride;
            }
            """;

    @Test
    void format_keepsEveryCommentStyle_betweenAnnotationAndField() {
        assertThat(format(ALL_FOUR_STYLES))
                  .contains("TRIPLE-SLASH-MARKER")
                  .contains("DOUBLE-SLASH-MARKER")
                  .contains("BLOCK-MARKER")
                  .contains("JAVADOC-MARKER");
    }

    @Test
    void format_isIdempotent_forCommentsBetweenAnnotationAndField() {
        var once = format(ALL_FOUR_STYLES);

        assertThat(format(once)).isEqualTo(once);
    }

    @Test
    void format_keepsComment_betweenAnnotationAndMethod() {
        var source = """
                package com.example;

                public class Test {
                    @Override
                    // METHOD-MARKER
                    public String toString() {
                        return "x";
                    }
                }
                """;

        assertThat(format(source)).contains("METHOD-MARKER");
    }

    @Test
    void format_keepsComment_betweenStackedAnnotations() {
        var source = """
                package com.example;

                public class Test {
                    @Deprecated
                    // BETWEEN-MARKER
                    @Override
                    public String toString() {
                        return "x";
                    }
                }
                """;

        assertThat(format(source)).contains("BETWEEN-MARKER");
    }

    /// The position that always worked, pinned so a fix for the broken position cannot regress it.
    @Test
    void format_keepsComment_aboveAnnotation() {
        var source = """
                package com.example;

                public class Test {
                    /// ABOVE-MARKER
                    @Option(names = "--other")
                    private String other;
                }
                """;

        assertThat(format(source)).contains("ABOVE-MARKER");
    }

    private String format(String content) {
        var result = new String[] {null};

        formatter.format(new SourceFile(Path.of("Test.java"), content))
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(formatted -> result[0] = formatted.content());

        return result[0];
    }
}
