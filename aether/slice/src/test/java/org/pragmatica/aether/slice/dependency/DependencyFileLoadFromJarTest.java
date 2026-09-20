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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.pragmatica.aether.slice.SliceClassLoader;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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

    private final List<String> warnings = new ArrayList<>();
    private Runnable detachCapture;

    @BeforeEach
    void captureWarnings() {
        detachCapture = capturingWarnings(warnings);
    }

    @AfterEach
    void detach() {
        detachCapture.run();
    }

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
        assertThat(warnings).as("an absent dependency file is the normal dependency-free slice, not a warning")
                  .isEmpty();
    }

    /// #1357 review: a dependency file that is PRESENT but cannot be parsed is still EMPTY (the contract every
    /// caller relies on), but no longer silently — the WARN names the jar and the cause.
    @Test
    void loadFromJar_malformedDependencyFile_isEmpty_andWarnsNamingTheJarAndTheCause() throws IOException {
        var jar = writeJar(tempDir.resolve("malformed.jar"), DEPENDENCIES_ENTRY, "[bogus-section]\n");

        DependencyFile.loadFromJar(SLICE_CLASS,
                                   jar,
                                   ClassLoader.getPlatformClassLoader())
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.isEmpty()).isTrue());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(DEPENDENCIES_ENTRY)
                  .contains(jar.toString())
                  .contains("Unknown section");
    }

    /// The read itself failing (an I/O error after the resource was found) is the other unreadable shape.
    @Test
    void load_dependencyFileWhoseReadFails_isEmpty_andWarns() throws IOException {
        var jar = writeJar(tempDir.resolve("slice.jar"), DEPENDENCIES_ENTRY, DEPENDENCIES);

        DependencyFile.loadClosing(SLICE_CLASS,
                                   new FailingReadLoader(jar))
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.isEmpty()).isTrue());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains(DEPENDENCIES_ENTRY).contains(jar.toString()).contains("disk gone");
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
        FailingReadLoader(URL jar) {
            super(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
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

    /// Capture WARN lines of DependencyFile's logger; the returned runnable detaches the appender. The filter
    /// on the logger NAME keeps other loggers' WARNs out (same fixture as TopologyParserManifestReadFailureTest).
    private static Runnable capturingWarnings(List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        var loggerConfig = config.getLoggerConfig(DependencyFile.class.getName());
        var appender = new AbstractAppender("dependency-file-warn-capture",
                                            null,
                                            PatternLayout.createDefaultLayout(),
                                            true,
                                            Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && DependencyFile.class.getName().equals(event.getLoggerName())) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.WARN, null);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        };
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
