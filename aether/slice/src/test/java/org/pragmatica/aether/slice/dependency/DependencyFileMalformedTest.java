// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.pragmatica.aether.slice.SliceClassLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// #1372: a dependency file that is PRESENT but cannot be parsed or read refuses the load with a typed
/// cause naming the jar and the underlying error. Only an ABSENT file means "dependency-free". Before
/// this, a malformed file loaded the slice as dependency-free, so it started without its declared
/// dependencies and failed further from the cause.
class DependencyFileMalformedTest {
    private static final String SLICE_CLASS = "com.example.EchoSlice";
    private static final String DEPENDENCIES_ENTRY = "META-INF/dependencies/" + SLICE_CLASS;
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    @TempDir
    Path tempDir;

    @Test
    void load_absentDependencyFile_isDependencyFree() throws IOException {
        var jar = writeJar(tempDir.resolve("bare.jar"), "META-INF/unrelated.txt", "nothing here");

        try (var loader = new SliceClassLoader(new URL[]{jar}, PLATFORM)) {
            DependencyFile.load(SLICE_CLASS, loader)
                          .onFailureRun(Assertions::fail)
                          .onSuccess(file -> assertThat(file.isEmpty()).isTrue());
        }
    }

    @Test
    void load_wellFormedDependencyFile_isParsed() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"),
                           DEPENDENCIES_ENTRY,
                           "[slices]\norg.example:pricing-service:^1.0.0\n");

        try (var loader = new SliceClassLoader(new URL[]{jar}, PLATFORM)) {
            DependencyFile.load(SLICE_CLASS, loader)
                          .onFailureRun(Assertions::fail)
                          .onSuccess(file -> assertThat(file.slices()).hasSize(1));
        }
    }

    @Test
    void load_malformedDependencyFile_refusesTheLoad_namingTheJarAndTheParseError() throws IOException {
        var jar = writeJar(tempDir.resolve("malformed.jar"), DEPENDENCIES_ENTRY, "[bogus-section]\n");

        try (var loader = new SliceClassLoader(new URL[]{jar}, PLATFORM)) {
            DependencyFile.load(SLICE_CLASS, loader)
                          .onSuccess(file -> Assertions.fail("a present-but-malformed dependency file must refuse the load; got " + file))
                          .onFailure(cause -> {
                                         assertThat(cause).isInstanceOf(DependencyFile.DependencyFileError.Unreadable.class);
                                         assertThat(cause.message()).contains(DEPENDENCIES_ENTRY)
                                                   .contains(jar.toString())
                                                   .contains("Unknown section");
                                     });
        }
    }

    @Test
    void load_dependencyFileWhoseReadFails_refusesTheLoad_namingTheJarAndTheReadError() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"),
                           DEPENDENCIES_ENTRY,
                           "[slices]\norg.example:pricing-service:^1.0.0\n");

        try (var loader = new FailingReadLoader(jar)) {
            DependencyFile.load(SLICE_CLASS, loader)
                          .onSuccess(file -> Assertions.fail("a present-but-unreadable dependency file must refuse the load; got " + file))
                          .onFailure(cause -> {
                                         assertThat(cause).isInstanceOf(DependencyFile.DependencyFileError.Unreadable.class);
                                         assertThat(cause.message()).contains(DEPENDENCIES_ENTRY)
                                                   .contains(jar.toString())
                                                   .contains("disk gone");
                                     });
        }
    }

    /// The refusal keeps the parser's cause as its origin, so the operator sees which line was wrong.
    @Test
    void load_malformedDependencyFile_carriesTheParseErrorAsOrigin() throws IOException {
        var jar = writeJar(tempDir.resolve("malformed.jar"), DEPENDENCIES_ENTRY, "[bogus-section]\n");

        try (var loader = new SliceClassLoader(new URL[]{jar}, PLATFORM)) {
            DependencyFile.load(SLICE_CLASS, loader)
                          .onSuccessRun(() -> Assertions.fail("must refuse"))
                          .onFailure(cause -> assertThat(cause.source().map(origin -> origin.message()).or("")).contains("bogus-section"));
        }
    }

    private static URL writeJar(Path path, String entry, String content) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        return path.toUri()
                   .toURL();
    }

    /// Finds the resource, then fails the read: `StreamOps.readBytes` maps the IOException to `ReadFailed`.
    private static final class FailingReadLoader extends SliceClassLoader {
        FailingReadLoader(URL jar) {
            super(new URL[]{jar}, PLATFORM);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("disk gone");
                }
            };
        }
    }
}
