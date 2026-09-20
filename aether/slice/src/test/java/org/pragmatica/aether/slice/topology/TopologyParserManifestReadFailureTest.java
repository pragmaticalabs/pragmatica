// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topology;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// s25-inv1277: a manifest read that FAILS (as opposed to a jar that ships no manifest) was logged at
/// DEBUG, so a poisoned jar cache silently dropped every topology of the slice. It is a WARN now,
/// naming the artifact and carrying the cause.
class TopologyParserManifestReadFailureTest {
    private static final String ARTIFACT = "org.example:probe:1.0.0";
    private static final String CAUSE = "java.lang.IllegalStateException: zip file closed";

    public interface ProbeService extends Slice {}

    public static final class ProbeSlice implements ProbeService {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    @Test
    void manifestReadFailure_warnsOnce_namingTheArtifactAndTheCause() throws Exception {
        var warnings = parseCapturingWarnings(new ThrowingResourceLoader(true));
        var withCause = warnings.stream().filter(line -> line.contains(CAUSE)).toList();

        // Unfixed: DEBUG, so no WARN carries the cause at all.
        assertThat(withCause).describedAs("exactly one WARN carries the cause; captured: %s", warnings).hasSize(1);
        assertThat(withCause.getFirst()).contains(ARTIFACT).contains("META-INF/slice/ProbeService.manifest");
    }

    /// The quiet branch: no manifest is `null` from the loader, not a failure, and must not WARN as
    /// one (the pre-existing "no topology found" WARN is a different message and stays).
    @Test
    void absentManifest_isNotAReadFailure_andStaysQuiet() throws Exception {
        var warnings = parseCapturingWarnings(new ThrowingResourceLoader(false));

        assertThat(warnings).describedAs("captured: %s", warnings).noneMatch(line -> line.startsWith("Could not read"));
    }

    private static List<String> parseCapturingWarnings(ThrowingResourceLoader loader) throws Exception {
        var slice = (Slice) loader.loadClass(ProbeSlice.class.getName())
                                  .getDeclaredConstructor()
                                  .newInstance();
        var warnings = new ArrayList<String>();
        var detach = capturingWarnings(warnings);

        try {
            assertThat(TopologyParser.parse(slice, ARTIFACT).isEmpty()).isTrue();
        } finally {
            detach.run();
        }

        return warnings;
    }

    /// Defines [ProbeSlice] itself (child-first for that one name) so the slice's defining loader is
    /// this one, and either fails every resource read the way a poisoned jar cache does or answers
    /// `null` the way a jar with no manifest does.
    private static final class ThrowingResourceLoader extends ClassLoader {
        private final boolean failing;

        ThrowingResourceLoader(boolean failing) {
            super(TopologyParserManifestReadFailureTest.class.getClassLoader());
            this.failing = failing;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!ProbeSlice.class.getName().equals(name)) {
                return super.loadClass(name, resolve);
            }

            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);

                if (loaded != null) {
                    return loaded;
                }

                var bytes = classBytes(name);

                return defineClass(name, bytes, 0, bytes.length);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (failing) {
                throw new IllegalStateException("zip file closed");
            }

            return null;
        }

        private byte[] classBytes(String name) throws ClassNotFoundException {
            try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }

                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    /// Capture WARN lines of the parser's logger; the returned runnable detaches the appender. The
    /// filter on the logger NAME keeps other loggers' WARNs out.
    private static Runnable capturingWarnings(List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        var loggerConfig = config.getLoggerConfig(TopologyParser.class.getName());
        var appender = new AbstractAppender("manifest-warn-capture", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && TopologyParser.class.getName().equals(event.getLoggerName())) {
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
}
