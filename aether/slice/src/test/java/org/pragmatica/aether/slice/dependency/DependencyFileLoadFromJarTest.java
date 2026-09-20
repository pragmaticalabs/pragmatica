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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.pragmatica.aether.slice.SliceClassLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// #1357: the throwaway loader that reads a slice's dependency file used to be built per resolve and
/// never closed, so each resolution held the jar's file handle until the loader was garbage-collected.
/// `DependencyFile.loadFromJar` closes it before returning. `load` maps an unreadable resource to an
/// EMPTY dependency file, so a loader closed BEFORE the read would return empty rather than fail —
/// which is why every test here asserts the parsed content, not just presence.
class DependencyFileLoadFromJarTest {
    private static final String SLICE_CLASS = "com.example.EchoSlice";
    private static final String DEPENDENCIES_ENTRY = "META-INF/dependencies/" + SLICE_CLASS;

    private static final String DEPENDENCIES = """
                                               [slices]
                                               org.example:pricing-service:^1.0.0
                                               org.example:inventory-service:^2.0.0
                                               """;

    @TempDir
    Path tempDir;

    @Test
    void loadFromJar_readsTheDependencyFileFromTheSliceJar() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"), DEPENDENCIES_ENTRY, DEPENDENCIES);

        DependencyFile.loadFromJar(SLICE_CLASS,
                                   jar,
                                   ClassLoader.getPlatformClassLoader())
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.slices()).hasSize(2));
    }

    @Test
    void loadFromJar_jarWithoutADependencyFile_isEmpty() throws IOException {
        var jar = writeJar(tempDir.resolve("bare.jar"), "META-INF/unrelated.txt", "nothing here");

        DependencyFile.loadFromJar(SLICE_CLASS,
                                   jar,
                                   ClassLoader.getPlatformClassLoader())
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.isEmpty()).isTrue());
    }

    /// #1372: a dependency file that is PRESENT but cannot be parsed refuses the load through the jar path
    /// too, naming the jar (the throwaway loader knows its jar) and the parse error.
    @Test
    void loadFromJar_malformedDependencyFile_refusesTheLoad_namingTheJarAndTheCause() throws IOException {
        var jar = writeJar(tempDir.resolve("malformed.jar"), DEPENDENCIES_ENTRY, "[bogus-section]\n");

        DependencyFile.loadFromJar(SLICE_CLASS,
                                   jar,
                                   ClassLoader.getPlatformClassLoader())
                      .onSuccess(file -> Assertions.fail("must refuse; got " + file))
                      .onFailure(cause -> assertThat(cause.message()).contains(DEPENDENCIES_ENTRY)
                                                                     .contains(jar.toString())
                                                                     .contains("Unknown section"));
    }

    /// The read itself failing (an I/O error after the resource was found) is the other unreadable shape.
    @Test
    void loadClosing_dependencyFileWhoseReadFails_refusesTheLoad_andStillClosesTheLoader() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"), DEPENDENCIES_ENTRY, DEPENDENCIES);
        var loader = new FailingReadLoader(jar);

        DependencyFile.loadClosing(SLICE_CLASS, loader)
                      .onSuccess(file -> Assertions.fail("must refuse; got " + file))
                      .onFailure(cause -> assertThat(cause.message()).contains(DEPENDENCIES_ENTRY)
                                                                     .contains(jar.toString())
                                                                     .contains("disk gone"));
        assertThat(loader.closed.get()).as("a refused load still closes the loader").isTrue();
    }

    @Test
    void loadClosing_closesTheLoader_afterTheDependencyFileWasRead() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"), DEPENDENCIES_ENTRY, DEPENDENCIES);
        var loader = new RecordingLoader(jar);

        DependencyFile.loadClosing(SLICE_CLASS, loader)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.slices()).hasSize(2));
        assertThat(loader.closed.get()).as("loader closed before loadClosing returned").isTrue();
    }

    @Test
    void loadClosing_failedClose_failsTheLoad() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"), DEPENDENCIES_ENTRY, DEPENDENCIES);
        var loader = new FailingCloseLoader(jar);

        DependencyFile.loadClosing(SLICE_CLASS, loader)
                      .onSuccessRun(() -> Assertions.fail("a failed close must fail the load"))
                      .onFailure(cause -> assertThat(cause.message()).contains("close refused"));
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

    private static final class RecordingLoader extends SliceClassLoader {
        final AtomicBoolean closed = new AtomicBoolean();

        RecordingLoader(URL jar) {
            super(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    /// Finds the resource, then fails the read: `StreamOps.readBytes` maps the IOException to `ReadFailed`.
    private static final class FailingReadLoader extends SliceClassLoader {
        final AtomicBoolean closed = new AtomicBoolean();

        FailingReadLoader(URL jar) {
            super(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
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

    private static final class FailingCloseLoader extends SliceClassLoader {
        FailingCloseLoader(URL jar) {
            super(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
        }

        @Override
        public void close() throws IOException {
            super.close();

            throw new IOException("close refused");
        }
    }
}
