// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.factorymismatch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.jbct.slice.SliceProcessor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The factory-detection diagnostic rule (#662, refining #605/#643).
///
/// A request-record method that is *factory-shaped* — static, component-count arity, returning
/// `Result` of the record — but whose parameter types fail the component match is a near-miss the
/// author almost certainly meant as the validating factory. Falling back to the canonical
/// constructor there silently skips validation: the exact failure mode #643 fixed. #662 keeps the
/// fallback but makes it visible: the processor emits a "looks like a validating factory but does
/// not match" WARNING at the near-miss declaration.
///
/// The match itself is semantic (`Types.isSameType`) rather than `TypeMirror.toString()`
/// equality, so a purely cosmetic spelling difference — a type-use annotation on a factory
/// parameter — no longer disables validation. Boxing is accepted in one direction only: a boxed
/// factory parameter over a primitive component (the call site boxes, a total conversion); the
/// reverse would auto-unbox a possibly-null accessor into an NPE and is refused as a near-miss.
///
/// This test drives the real `SliceProcessor` through the platform `javac` against a fixture slice
/// compiled with the module's full framework classpath, so both the diagnostics and the
/// compilability of the generated routes are asserted from one compilation.
class FactoryMismatchWarningTest {
    private static final String PACKAGE_DIR = "com/example/factorymismatch";

    private static boolean success;
    private static List<Diagnostic<? extends JavaFileObject>> warnings;
    private static String generatedRoutes;

    @BeforeAll
    static void compileFixtureSlice(@TempDir Path tempDir) throws Exception {
        var sourceDir = Files.createDirectories(tempDir.resolve("src").resolve(PACKAGE_DIR));
        var classesDir = Files.createDirectories(tempDir.resolve("classes"));
        var generatedDir = Files.createDirectories(tempDir.resolve("generated"));

        Files.createDirectories(classesDir.resolve(PACKAGE_DIR));
        Files.writeString(classesDir.resolve(PACKAGE_DIR).resolve("routes.toml"), ROUTES_TOML);

        var sources = List.of(Files.writeString(sourceDir.resolve("FixtureSlice.java"), SLICE),
                              Files.writeString(sourceDir.resolve("EchoResponse.java"), ECHO_RESPONSE),
                              Files.writeString(sourceDir.resolve("Mark.java"), MARK),
                              Files.writeString(sourceDir.resolve("MismatchRequest.java"), MISMATCH_REQUEST),
                              Files.writeString(sourceDir.resolve("ExactRequest.java"), EXACT_REQUEST),
                              Files.writeString(sourceDir.resolve("AnnotatedRequest.java"), ANNOTATED_REQUEST),
                              Files.writeString(sourceDir.resolve("BoxedParamRequest.java"), BOXED_PARAM_REQUEST),
                              Files.writeString(sourceDir.resolve("PrimitiveParamRequest.java"), PRIMITIVE_PARAM_REQUEST));

        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generatedDir));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());

            var options = List.of("--release", Integer.toString(Runtime.version().feature()),
                                  "--enable-preview");
            var task = compiler.getTask(null,
                                        fileManager,
                                        collector,
                                        options,
                                        null,
                                        fileManager.getJavaFileObjectsFromPaths(sources));
            task.setProcessors(List.of(new SliceProcessor()));
            success = task.call();
        }

        warnings = collector.getDiagnostics()
                            .stream()
                            .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.WARNING)
                            .toList();
        generatedRoutes = Files.readString(generatedDir.resolve(PACKAGE_DIR)
                                                       .resolve("FixtureSliceRoutes.java"));
    }

    /// The classpath this test runs on: the module's framework dependencies plus the processor.
    /// Surefire may hide it behind a booter jar, in which case `surefire.test.class.path` carries
    /// the real entry list.
    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path",
                                           System.getProperty("java.class.path"));
        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }

    private static String warningsMentioning(String recordName) {
        return warnings.stream()
                       .map(diagnostic -> diagnostic.getMessage(null))
                       .filter(message -> message.contains(recordName))
                       .reduce("", (left, right) -> left + right + "\n");
    }

    @Nested
    class NearMissFactory {
        @Test
        void mismatchedParameterType_emitsFactoryFoundButUnmatchedWarning() {
            assertThat(warningsMentioning("MismatchRequest"))
                .as("a factory-shaped method whose parameter types fail the component match"
                   + " must produce a visible diagnostic, not a silent validation skip")
                .contains("mismatchRequest")
                .contains("does not match")
                .contains("int")
                .contains("long");
        }

        @Test
        void mismatchedParameterType_fallsBackToCanonicalPathThatCompiles() {
            assertThat(success).as("canonical-constructor fallback must still compile").isTrue();
            assertThat(generatedRoutes).doesNotContain("MismatchRequest.mismatchRequest(");
        }
    }

    @Nested
    class ExactFactory {
        @Test
        void matchingFactory_isUsedWithNoWarning() {
            assertThat(generatedRoutes).contains("ExactRequest.exactRequest(request.url())");
            assertThat(warningsMentioning("ExactRequest")).isEmpty();
        }
    }

    @Nested
    class AnnotatedFactoryParameter {
        @Test
        void typeUseAnnotationOnFactoryParameter_isCosmeticAndStillMatches() {
            assertThat(generatedRoutes)
                .as("a type-use annotation changes the spelling, not the type; the factory must"
                   + " still be detected and used")
                .contains("AnnotatedRequest.annotatedRequest(request.url())");
            assertThat(warningsMentioning("AnnotatedRequest")).isEmpty();
        }
    }

    @Nested
    class BoxedFactoryParameterOverPrimitiveComponent {
        @Test
        void boxedParameterOverPrimitiveComponent_matchesWithNoWarning() {
            assertThat(generatedRoutes)
                .as("a boxed factory parameter over a primitive component is a total conversion"
                   + " (the accessor returns the primitive, the call site boxes); the factory"
                   + " must be detected and used — this pins the boxing clause: delete it and"
                   + " this match textual/semantic-fails")
                .contains("BoxedParamRequest.boxedParamRequest(request.url(), request.ttlSeconds())");
            assertThat(warningsMentioning("BoxedParamRequest")).isEmpty();
        }
    }

    @Nested
    class PrimitiveFactoryParameterOverBoxedComponent {
        @Test
        void primitiveParameterOverBoxedComponent_isNotSilentlyMatched_warnsAsNearMiss() {
            assertThat(generatedRoutes)
                .as("a primitive factory parameter over a boxed component must NOT match: the"
                   + " accessor can return null (absent JSON field) and the call site's"
                   + " auto-unboxing would turn it into an NPE -> 500 instead of the typed 400"
                   + " the factory path exists to produce")
                .doesNotContain("PrimitiveParamRequest.primitiveParamRequest(");
            assertThat(warningsMentioning("PrimitiveParamRequest"))
                .as("the refused near-miss must be visible, not a silent validation skip")
                .contains("primitiveParamRequest")
                .contains("does not match");
        }
    }

    private static final String ROUTES_TOML = """
        prefix = "/api/mismatch"

        [routes]
        create = "POST /create"
        exact = "POST /exact"
        annotated = "POST /annotated"
        boxed = "POST /boxed"
        primitive = "POST /primitive"

        [errors]
        default = 500
        """;

    private static final String SLICE = """
        package com.example.factorymismatch;

        import org.pragmatica.aether.slice.annotation.Slice;
        import org.pragmatica.lang.Promise;

        @Slice
        public interface FixtureSlice {
            Promise<EchoResponse> create(MismatchRequest request);
            Promise<EchoResponse> exact(ExactRequest request);
            Promise<EchoResponse> annotated(AnnotatedRequest request);
            Promise<EchoResponse> boxed(BoxedParamRequest request);
            Promise<EchoResponse> primitive(PrimitiveParamRequest request);

            static FixtureSlice fixtureSlice() {
                return new FixtureSlice() {
                    @Override
                    public Promise<EchoResponse> create(MismatchRequest request) {
                        return Promise.success(new EchoResponse(request.url()));
                    }

                    @Override
                    public Promise<EchoResponse> exact(ExactRequest request) {
                        return Promise.success(new EchoResponse(request.url()));
                    }

                    @Override
                    public Promise<EchoResponse> annotated(AnnotatedRequest request) {
                        return Promise.success(new EchoResponse(request.url()));
                    }

                    @Override
                    public Promise<EchoResponse> boxed(BoxedParamRequest request) {
                        return Promise.success(new EchoResponse(request.url()));
                    }

                    @Override
                    public Promise<EchoResponse> primitive(PrimitiveParamRequest request) {
                        return Promise.success(new EchoResponse(request.url()));
                    }
                };
            }
        }
        """;

    private static final String ECHO_RESPONSE = """
        package com.example.factorymismatch;

        public record EchoResponse(String value) {}
        """;

    private static final String MARK = """
        package com.example.factorymismatch;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        @Target(ElementType.TYPE_USE)
        @Retention(RetentionPolicy.SOURCE)
        public @interface Mark {}
        """;

    /// Factory-shaped near-miss: static, arity 2, returns `Result<MismatchRequest>`, but the
    /// second parameter is `long` where the component is `int`.
    private static final String MISMATCH_REQUEST = """
        package com.example.factorymismatch;

        import org.pragmatica.lang.Result;

        public record MismatchRequest(String url, int ttlSeconds) {
            public static Result<MismatchRequest> mismatchRequest(String url, long ttlSeconds) {
                return Result.success(new MismatchRequest(url, (int) ttlSeconds));
            }
        }
        """;

    private static final String EXACT_REQUEST = """
        package com.example.factorymismatch;

        import org.pragmatica.lang.Result;

        public record ExactRequest(String url) {
            public static Result<ExactRequest> exactRequest(String url) {
                return Result.success(new ExactRequest(url));
            }
        }
        """;

    /// The factory parameter's type spelling differs from the component's only by a type-use
    /// annotation — the same type to `Types.isSameType`, a mismatch to `toString()` equality.
    private static final String ANNOTATED_REQUEST = """
        package com.example.factorymismatch;

        import org.pragmatica.lang.Result;

        public record AnnotatedRequest(String url) {
            public static Result<AnnotatedRequest> annotatedRequest(@Mark String url) {
                return Result.success(new AnnotatedRequest(url));
            }
        }
        """;

    /// Boxed factory parameter (`Integer`) over a primitive component (`int`): the safe boxing
    /// direction — the accessor returns `int`, the generated call site boxes, the conversion is
    /// total. Must be detected as the validating factory.
    private static final String BOXED_PARAM_REQUEST = """
        package com.example.factorymismatch;

        import org.pragmatica.lang.Result;

        public record BoxedParamRequest(String url, int ttlSeconds) {
            public static Result<BoxedParamRequest> boxedParamRequest(String url, Integer ttlSeconds) {
                return Result.success(new BoxedParamRequest(url, ttlSeconds));
            }
        }
        """;

    /// Primitive factory parameter (`int`) over a boxed component (`Integer`): the unsafe boxing
    /// direction — the accessor can return null (absent JSON field) and the call site's
    /// auto-unboxing would NPE. Must be refused and reported as a near-miss.
    private static final String PRIMITIVE_PARAM_REQUEST = """
        package com.example.factorymismatch;

        import org.pragmatica.lang.Result;

        public record PrimitiveParamRequest(String url, Integer ttlSeconds) {
            public static Result<PrimitiveParamRequest> primitiveParamRequest(String url, int ttlSeconds) {
                return Result.success(new PrimitiveParamRequest(url, ttlSeconds));
            }
        }
        """;
}
