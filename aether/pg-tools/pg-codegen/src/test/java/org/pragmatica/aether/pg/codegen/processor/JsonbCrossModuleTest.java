// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `@Jsonb` on a record compiled in another module: the record is compiled first, without the processor, and the
/// persistence interface second, with that output on its class path. The processor then sees the marker only through
/// the class file, which is why `@Jsonb` has `CLASS` retention (`SOURCE` would drop it there).
class JsonbCrossModuleTest {
    private static final String PROFILE = """
        package xmod;

        import org.pragmatica.lang.Option;

        public record Profile(String name, Option<String> nick) {}
        """;
    private static final String DOCUMENT_ROW = """
        package xmod;

        import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
        import org.pragmatica.lang.Option;

        public record DocumentRow(long id, @Jsonb Profile profile, @Jsonb Option<Profile> previous) {}
        """;
    private static final String REPO = """
        package test;

        import org.pragmatica.aether.resource.db.PgSql;
        import org.pragmatica.lang.Option;
        import org.pragmatica.lang.Promise;
        import xmod.DocumentRow;

        @PgSql
        public interface CrossRepo {
            Promise<Option<DocumentRow>> findById(long id);
        }
        """;

    @TempDir
    Path tempDir;

    @Test
    void markerOnRecordFromAnotherCompilation_isSeenThroughTheClassFile() throws Exception {
        var model = compileModel("model", DOCUMENT_ROW);
        var diagnostics = new StringBuilder();
        var generated = tempDir.resolve("repo-gen");

        assertThat(javac(tempDir.resolve("repo"), generated, model, true, "test/CrossRepo.java", REPO, diagnostics)).as(diagnostics.toString())
                                                                                                                  .isTrue();
        assertThat(Files.readString(generated.resolve("test/CrossRepoFactory.java"))).contains("PROFILE_JSON = jsonCodec(xmod.Profile.class)")
                                                                                     .contains("text.decode(PROFILE_JSON)");
    }

    /// Control: the same record without the marker is rejected, so the test above passes only because the marker
    /// survived compilation.
    @Test
    void unmarkedRecordFromAnotherCompilation_isRejected() throws Exception {
        var model = compileModel("control", DOCUMENT_ROW.replace("@Jsonb ", ""));
        var diagnostics = new StringBuilder();

        assertThat(javac(tempDir.resolve("control-repo"), tempDir.resolve("control-gen"), model, true, "test/CrossRepo.java", REPO, diagnostics))
            .isFalse();
        assertThat(diagnostics.toString()).contains("mark a json/jsonb column @Jsonb");
    }

    private Path compileModel(String name, String documentRow) throws Exception {
        var out = tempDir.resolve(name);
        var diagnostics = new StringBuilder();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        Files.createDirectories(out);
        var options = List.of("-d", out.toString(), "-classpath", System.getProperty("java.class.path"), "-proc:none", "--release", "25");
        var sources = List.<JavaFileObject>of(new TestCompilationHelper.InMemoryJavaFileObject("xmod/Profile.java", PROFILE),
                                              new TestCompilationHelper.InMemoryJavaFileObject("xmod/DocumentRow.java", documentRow));

        try (var files = compiler.getStandardFileManager(collector, null, null)) {
            var ok = compiler.getTask(null, files, collector, options, null, sources).call();
            collector.getDiagnostics().forEach(d -> diagnostics.append(d.getMessage(null)).append('\n'));
            assertThat(ok).as(diagnostics.toString()).isTrue();
        }

        return out;
    }

    private static boolean javac(Path out, Path generated, Path model, boolean process, String fileName, String source,
                                 StringBuilder diagnostics) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        Files.createDirectories(out);
        Files.createDirectories(generated);
        var options = new ArrayList<>(List.of("-d", out.toString(), "-s", generated.toString(),
                                              "-classpath", System.getProperty("java.class.path") + File.pathSeparator + model,
                                              "-Xlint:none", "--release", "25", "-proc:only"));

        try (var files = compiler.getStandardFileManager(collector, null, null)) {
            var task = compiler.getTask(null, files, collector, options, null,
                                        List.of(new TestCompilationHelper.InMemoryJavaFileObject(fileName, source)));
            if (process) {
                task.setProcessors(List.of(new QueryAnnotationProcessor()));
            }
            var ok = task.call();
            collector.getDiagnostics().forEach(d -> diagnostics.append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n'));
            return ok;
        }
    }
}
