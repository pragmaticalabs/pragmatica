// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.jsonfallback;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The slice processor's JSON fallback path, driven through the platform `javac` with the real
/// `SliceProcessor`: a route type the generator refuses gets a compiler NOTE and stays on Jackson, the refusal
/// leaves nothing behind for other roots (a recursive graph under a refused root used to leave a definition
/// pointing at a record that never got one, and the emitter crashed), and a slice with no supported route type
/// at all gets no codec holder and the pre-codec routes factory (whose convenience overload now defaults to the
/// value-object mapper, as the codec path's does).
class JsonCodecFallbackDiagnosticsTest {
    private static boolean success;
    private static List<String> notes;
    private static List<String> errors;
    private static Path generatedDir;

    @BeforeAll
    static void compileFixtures(@TempDir Path tempDir) throws Exception {
        var sourceDir = Files.createDirectories(tempDir.resolve("src"));
        var classesDir = Files.createDirectories(tempDir.resolve("classes"));
        generatedDir = Files.createDirectories(tempDir.resolve("generated"));

        writeResource(classesDir, "com/example/rollbackfixture/routes.toml", ROLLBACK_ROUTES);
        writeResource(classesDir, "com/example/nocodecfixture/routes.toml", NO_CODEC_ROUTES);
        var sources = SOURCES.entrySet()
                             .stream()
                             .map(entry -> writeSource(sourceDir, entry.getKey(), entry.getValue()))
                             .toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generatedDir));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());
            var options = List.of("--release", Integer.toString(Runtime.version().feature()), "--enable-preview");
            var task = compiler.getTask(null, fileManager, collector, options, null, fileManager.getJavaFileObjectsFromPaths(sources));

            task.setProcessors(List.of(new SliceProcessor()));
            success = task.call();
        }

        notes = messages(collector, Diagnostic.Kind.NOTE);
        errors = messages(collector, Diagnostic.Kind.ERROR);
    }

    @Nested
    class RefusedRootWithRecursiveGraph {
        @Test
        void compiles_andTheRefusedRootGetsANote() {
            assertThat(success).as(String.join("\n", errors)).isTrue();
            assertThat(notes).anyMatch(note -> note.contains("Route 'rootA': com.example.rollbackfixture.RootA uses the Jackson fallback")
                                               && note.contains("Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty"));
        }

        @Test
        void routesAreGenerated_andOtherRootsStillGetCodecs() throws Exception {
            var holder = generated("com/example/rollbackfixture/RollbackSliceRoutesJsonCodecs.java");

            assertThat(Files.exists(generatedDir.resolve("com/example/rollbackfixture/RollbackSliceRoutes.java"))).isTrue();
            assertThat(holder).contains("com.example.rollbackfixture.OkRequest.class")
                              .contains("com.example.rollbackfixture.Plain.class");
        }

        @Test
        void nothingAnalysedUnderTheRefusedRootIsLeftBehind() throws Exception {
            // Not vacuous: the holder is complete (it registers the supported roots) and still lacks them.
            assertThat(generated("com/example/rollbackfixture/RollbackSliceRoutesJsonCodecs.java"))
                .contains("com.example.rollbackfixture.OkRequest.class")
                .doesNotContain("com.example.rollbackfixture.B.class")
                .doesNotContain("com.example.rollbackfixture.RootA");
        }

        @Test
        void containerRefusedForItsElement_noteCarriesTheElementsReason() {
            assertThat(notes).anyMatch(note -> note.contains("Route 'nested': com.example.rollbackfixture.NestedRequest uses the Jackson fallback: "
                                                             + "component items: java.util.List<com.example.rollbackfixture.Bad>: "
                                                             + "com.example.rollbackfixture.Bad: Jackson annotation @com.fasterxml.jackson.annotation.JsonProperty"));
        }

        @Test
        void valueObjectWhoseFactoryTheHolderCannotCall_staysOnJackson() {
            assertThat(notes).anyMatch(note -> note.contains("Route 'code'")
                                               && note.contains("value object factory code is not accessible from package 'com.example.rollbackfixture'"));
        }
    }

    @Nested
    class NoSupportedRouteType {
        @Test
        void noCodecHolderIsWritten() {
            assertThat(Files.exists(generatedDir.resolve("com/example/nocodecfixture/NoCodecSliceRoutesJsonCodecs.java"))).isFalse();
            assertThat(notes).anyMatch(note -> note.contains("Route 'wrap': com.example.nocodecfixture.Wrapper uses the Jackson fallback"));
        }

        @Test
        void routesFactory_isThePreCodecForm_withValueObjectMapperDefault() throws Exception {
            var routes = generated("com/example/nocodecfixture/NoCodecSliceRoutes.java");

            assertThat(routes).contains(PRE_CODEC_FACTORY)
                              .doesNotContain("jsonCodec")
                              .doesNotContain("GeneratedJsonCodec")
                              .doesNotContain("JsonCodecs");
        }
    }

    private static String generated(String relative) throws Exception {
        return Files.readString(generatedDir.resolve(relative));
    }

    private static void writeResource(Path classesDir, String relative, String content) throws Exception {
        var target = classesDir.resolve(relative);

        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static Path writeSource(Path sourceDir, String relative, String content) {
        try {
            var target = sourceDir.resolve(relative);

            Files.createDirectories(target.getParent());
            return Files.writeString(target, content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> messages(DiagnosticCollector<JavaFileObject> collector, Diagnostic.Kind kind) {
        return collector.getDiagnostics()
                        .stream()
                        .filter(diagnostic -> diagnostic.getKind() == kind)
                        .map(diagnostic -> diagnostic.getMessage(null))
                        .toList();
    }

    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }

    /// What `RouteSourceGenerator` wrote for every slice before it generated JSON codecs, except the one owner-ruled
    /// change shared with the codec path: `create(slice)` defaults to `valueObjectJsonMapper()`, the mapper the node
    /// passes, rather than `defaultJsonMapper()`.
    private static final String PRE_CODEC_FACTORY = """
            @Override
            public SliceRouter create(NoCodecSlice slice) {
                return create(slice, JsonMapper.valueObjectJsonMapper());
            }

            @Override
            public SliceRouter create(NoCodecSlice slice, JsonMapper jsonMapper) {
                var routes = new NoCodecSliceRoutes(slice);
                return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper);
            }

            @Override
            public SliceRouter create(NoCodecSlice slice, JsonMapper jsonMapper, RouteMountMode mountMode) {
                var routes = new NoCodecSliceRoutes(slice);
                return SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper, mountMode);
            }

        """;

    private static final String ROLLBACK_ROUTES = """
        prefix = "/api/rollback"

        [routes]
        rootA = "POST /a"
        ok = "POST /ok"
        code = "POST /code"
        nested = "POST /nested"

        [errors]
        default = 500
        """;

    private static final String NO_CODEC_ROUTES = """
        prefix = "/api/nocodec"

        [routes]
        drop = "DELETE /drop/{id}"
        wrap = "POST /wrap"

        [errors]
        default = 500
        """;

    private static final Map<String, String> SOURCES = Map.ofEntries(
        Map.entry("com/example/rollbackfixture/RollbackSlice.java", """
            package com.example.rollbackfixture;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface RollbackSlice {
                Promise<Plain> rootA(RootA request);
                Promise<Plain> ok(OkRequest request);
                Promise<Plain> code(CodeRequest request);
                Promise<Plain> nested(NestedRequest request);

                static RollbackSlice rollbackSlice() {
                    return new RollbackSlice() {
                        @Override
                        public Promise<Plain> rootA(RootA request) {
                            return Promise.success(new Plain("a"));
                        }

                        @Override
                        public Promise<Plain> ok(OkRequest request) {
                            return Promise.success(new Plain(request.v()));
                        }

                        @Override
                        public Promise<Plain> code(CodeRequest request) {
                            return Promise.success(new Plain("c"));
                        }

                        @Override
                        public Promise<Plain> nested(NestedRequest request) {
                            return Promise.success(new Plain("n"));
                        }
                    };
                }
            }
            """),
        // B is analysed (and refers back to RootA) before RootA's second component is refused.
        Map.entry("com/example/rollbackfixture/RootA.java", """
            package com.example.rollbackfixture;

            public record RootA(B b, Bad bad) {}
            """),
        Map.entry("com/example/rollbackfixture/B.java", """
            package com.example.rollbackfixture;

            import org.pragmatica.lang.Option;

            public record B(Option<RootA> back) {}
            """),
        Map.entry("com/example/rollbackfixture/Bad.java", """
            package com.example.rollbackfixture;

            import com.fasterxml.jackson.annotation.JsonProperty;

            public record Bad(@JsonProperty("x") String v) {}
            """),
        // Refused only through its list element: the note must say why the element was refused.
        Map.entry("com/example/rollbackfixture/NestedRequest.java", """
            package com.example.rollbackfixture;

            import java.util.List;

            public record NestedRequest(List<Bad> items) {}
            """),
        Map.entry("com/example/rollbackfixture/OkRequest.java", """
            package com.example.rollbackfixture;

            public record OkRequest(String v) {}
            """),
        Map.entry("com/example/rollbackfixture/Plain.java", """
            package com.example.rollbackfixture;

            public record Plain(String v) {}
            """),
        Map.entry("com/example/rollbackfixture/CodeRequest.java", """
            package com.example.rollbackfixture;

            import com.example.rollbackfixture.other.Code;

            public record CodeRequest(Code code) {}
            """),
        // A value object (Jackson treats it as one through reflection) whose factory the holder, generated in
        // the slice's package, cannot call.
        Map.entry("com/example/rollbackfixture/other/Code.java", """
            package com.example.rollbackfixture.other;

            import org.pragmatica.lang.Result;

            public record Code(String value) {
                static Result<Code> code(String value) {
                    return Result.success(new Code(value));
                }
            }
            """),
        Map.entry("com/example/nocodecfixture/NoCodecSlice.java", """
            package com.example.nocodecfixture;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;

            @Slice
            public interface NoCodecSlice {
                Promise<Unit> drop(DropRequest request);
                Promise<Unit> wrap(Wrapper request);

                static NoCodecSlice noCodecSlice() {
                    return new NoCodecSlice() {
                        @Override
                        public Promise<Unit> drop(DropRequest request) {
                            return Promise.unitPromise();
                        }

                        @Override
                        public Promise<Unit> wrap(Wrapper request) {
                            return Promise.unitPromise();
                        }
                    };
                }
            }
            """),
        Map.entry("com/example/nocodecfixture/DropRequest.java", """
            package com.example.nocodecfixture;

            public record DropRequest(String id) {}
            """),
        Map.entry("com/example/nocodecfixture/Wrapper.java", """
            package com.example.nocodecfixture;

            import com.fasterxml.jackson.annotation.JsonProperty;

            public record Wrapper(@JsonProperty("w") String w) {}
            """));
}
