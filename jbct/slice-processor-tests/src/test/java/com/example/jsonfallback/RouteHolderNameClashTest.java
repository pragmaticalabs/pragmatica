// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.jsonfallback;

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

/// A user type already named like a slice's JSON codec holder (`<Slice>RoutesJsonCodecs`) is one clean compile error
/// naming both and the way out, through the platform `javac` with the real `SliceProcessor`, instead of the Filer's
/// "attempt to recreate" exception text.
class RouteHolderNameClashTest {
    private static final String ROUTES = """
        prefix = "/api/clash"

        [routes]
        ok = "POST /ok"

        [errors]
        default = 500
        """;

    private static final String SLICE = """
        package com.example.clashfixture;

        import org.pragmatica.aether.slice.annotation.Slice;
        import org.pragmatica.lang.Promise;

        @Slice
        public interface ClashSlice {
            Promise<Reply> ok(Ask request);

            static ClashSlice clashSlice() {
                return request -> Promise.success(new Reply(request.v()));
            }
        }
        """;

    @Test
    void userTypeNamedLikeTheRouteHolder_isOneCleanError(@TempDir Path tempDir) throws Exception {
        var classes = Files.createDirectories(tempDir.resolve("classes"));
        var source = tempDir.resolve("src/com/example/clashfixture");

        Files.createDirectories(classes.resolve("com/example/clashfixture"));
        Files.writeString(classes.resolve("com/example/clashfixture/routes.toml"), ROUTES);
        Files.createDirectories(source);
        var sources = List.of(Files.writeString(source.resolve("ClashSlice.java"), SLICE),
                              Files.writeString(source.resolve("Ask.java"), "package com.example.clashfixture;\n\npublic record Ask(String v) {}\n"),
                              Files.writeString(source.resolve("Reply.java"), "package com.example.clashfixture;\n\npublic record Reply(String v) {}\n"),
                              Files.writeString(source.resolve("ClashSliceRoutesJsonCodecs.java"),
                                                "package com.example.clashfixture;\n\npublic class ClashSliceRoutesJsonCodecs {}\n"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        boolean success;

        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(Files.createDirectories(tempDir.resolve("generated"))));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());
            var options = List.of("--release", Integer.toString(Runtime.version().feature()), "--enable-preview");
            var task = compiler.getTask(null, fileManager, collector, options, null, fileManager.getJavaFileObjectsFromPaths(sources));

            task.setProcessors(List.of(new SliceProcessor()));
            success = task.call();
        }

        var errors = collector.getDiagnostics()
                              .stream()
                              .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                              .map(diagnostic -> diagnostic.getMessage(null))
                              .toList();

        assertThat(success).isFalse();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("Cannot generate com.example.clashfixture.ClashSliceRoutesJsonCodecs for com.example.clashfixture.ClashSlice")
                                     .contains("the type com.example.clashfixture.ClashSliceRoutesJsonCodecs already exists")
                                     .doesNotContain("\tat ")
                                     .doesNotContain("Exception");
    }

    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }
}
