package org.pragmatica.jbct.doc;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderExtractorTest {
    private static final String LICENSE = """
        /*
         *  Copyright (c) 2020-2025 Someone.
         *  Licensed under the Apache License, Version 2.0
         */
        """;

    @Test
    void extract_stripsLicenseAndPrefixes_forClassHeader() {
        var content = LICENSE + """
            package org.pragmatica.lang;

            import java.util.List;

            /// First line of documentation.
            ///
            /// Second paragraph.
            public sealed interface Verify {
            }
            """;
        HeaderExtractor.extract(content, "Verify")
                       .onFailure(cause -> Assertions.fail(cause.message()))
                       .onSuccess(markdown -> assertThat(markdown).isEqualTo(
                           "First line of documentation.\n\nSecond paragraph."));
    }

    @Test
    void extract_handlesPackageInfo_headerBeforePackageStatement() {
        var content = LICENSE + """
            /// Production-ready value objects for common types.
            ///
            /// Check this package first.
            package org.pragmatica.lang.vo;
            """;
        HeaderExtractor.extract(content, "org.pragmatica.lang.vo")
                       .onFailure(cause -> Assertions.fail(cause.message()))
                       .onSuccess(markdown -> assertThat(markdown).startsWith("Production-ready value objects")
                                                                  .contains("Check this package first."));
    }

    @Test
    void extract_skipsAnnotation_betweenHeaderAndDeclaration() {
        var content = """
            package org.pragmatica.lang;

            /// Documented type.
            @SuppressWarnings("unchecked")
            public final class Thing {
            }
            """;
        HeaderExtractor.extract(content, "Thing")
                       .onFailure(cause -> Assertions.fail(cause.message()))
                       .onSuccess(markdown -> assertThat(markdown).isEqualTo("Documented type."));
    }

    @Test
    void extract_fails_whenNoHeaderPresent() {
        var content = """
            package org.pragmatica.lang;

            public class NoDoc {
            }
            """;
        HeaderExtractor.extract(content, "NoDoc")
                       .onSuccess(markdown -> Assertions.fail("Expected failure but got: " + markdown));
    }

    @Test
    void extractWithApi_appendsSignatureSection() {
        var content = """
            package org.pragmatica.lang;

            /// Documented type.
            public sealed interface Verify {
                static <T> Result<T> ensure(T value, Predicate<T> predicate) {
                    return null;
                }

                default String describe() {
                    return "x";
                }
            }
            """;
        HeaderExtractor.extractWithApi(content, "Verify")
                       .onFailure(cause -> Assertions.fail(cause.message()))
                       .onSuccess(markdown -> assertThat(markdown).contains("## Public API")
                                                                  .contains("static <T> Result<T> ensure(T value, "
                                                                            + "Predicate<T> predicate)")
                                                                  .contains("default String describe()"));
    }

    @Test
    void extractWithApi_ignoresSignaturesInsideHeader() {
        var content = """
            package org.pragmatica.lang;

            ///     static Result<T> notAnApi(T value) {
            /// Documented type.
            public sealed interface Verify {
                public static Result<Unit> real() {
                    return null;
                }
            }
            """;
        HeaderExtractor.extractWithApi(content, "Verify")
                       .onFailure(cause -> Assertions.fail(cause.message()))
                       .onSuccess(markdown -> assertThat(markdown).contains("- `public static Result<Unit> real()`")
                                                                  .doesNotContain("- `static Result<T> notAnApi"));
    }
}
