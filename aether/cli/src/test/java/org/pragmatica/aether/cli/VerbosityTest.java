// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The `-v` ladder and the shipped `log4j2.xml` it moves.
///
/// [ShippedConfiguration] reads the LOADED configuration object rather than the file text, so it
/// fails if the resource is not picked up at all — which is the failure mode that would otherwise
/// change default behaviour on every subcommand by the cheapest possible route (log4j2 with no
/// findable config prints a StatusLogger line on every invocation).
///
/// The configuration is loaded explicitly via [ShippedLogging#activate()]; see that type for why
/// reading the ambient test configuration would measure `test-logging` instead.
class VerbosityTest {

    @BeforeEach
    void setUp() {
        ShippedLogging.activate();
    }

    @AfterEach
    void tearDown() {
        ShippedLogging.restoreTestDefault();
    }

    @Nested
    class Ladder {

        @Test
        void verbosity_mapsRepeatCount_toTheDocumentedRung() {
            assertThat(Verbosity.verbosity(0)).isEqualTo(Verbosity.DEFAULT);
            assertThat(Verbosity.verbosity(1)).isEqualTo(Verbosity.VERBOSE);
            assertThat(Verbosity.verbosity(2)).isEqualTo(Verbosity.DEBUG);
            assertThat(Verbosity.verbosity(3)).isEqualTo(Verbosity.TRACE);
        }

        @Test
        void verbosity_mapsRungs_toTheDocumentedLevels() {
            assertThat(Verbosity.DEFAULT.level()).isEqualTo(Level.WARN);
            assertThat(Verbosity.VERBOSE.level()).isEqualTo(Level.INFO);
            assertThat(Verbosity.DEBUG.level()).isEqualTo(Level.DEBUG);
            assertThat(Verbosity.TRACE.level()).isEqualTo(Level.TRACE);
        }

        /// An extra `v` is not a usage error: TRACE is already everything there is to say.
        @Test
        void verbosity_saturatesAtTrace_beyondThreeRepeats() {
            assertThat(Verbosity.verbosity(4)).isEqualTo(Verbosity.TRACE);
            assertThat(Verbosity.verbosity(99)).isEqualTo(Verbosity.TRACE);
        }

        /// A negative count cannot arrive from picocli, but clamping both ends keeps the lookup
        /// total rather than relying on that.
        @Test
        void verbosity_clampsToDefault_forNegativeCounts() {
            assertThat(Verbosity.verbosity(-1)).isEqualTo(Verbosity.DEFAULT);
        }

        @Test
        void apply_raisesTheAetherLogger_toTheRungLevel() {
            Verbosity.DEBUG.apply();

            assertThat(loggerConfigLevel(Verbosity.AETHER_LOGGER)).isEqualTo(Level.DEBUG);
        }

        /// The ladder must not touch third-party logging: `log4j2.xml` pins Root to OFF so the CLI
        /// does not inherit every shaded library's output, and `-vvv` must not undo that.
        @Test
        void apply_leavesRootOff_evenAtTrace() {
            Verbosity.TRACE.apply();

            assertThat(configuration().getRootLogger().getLevel()).isEqualTo(Level.OFF);
        }
    }

    @Nested
    class ShippedConfiguration {

        /// Proves the resource is FOUND. With no findable configuration log4j2 falls back to a
        /// default that has no `org.pragmatica` logger and a root at ERROR, so this assertion is
        /// what stands between the branch and a StatusLogger line on every invocation.
        @Test
        void shippedConfig_isLoaded_fromTheCliResource() {
            assertThat(configuration().getConfigurationSource().getLocation())
                .describedAs("log4j2.xml must be resolved from the classpath, not defaulted")
                .isNotNull()
                .endsWith("log4j2.xml");

            assertThat(ShippedLogging.shippedUri().toString())
                .describedAs("the resolved resource must be this module's own file; a 'jar:' location "
                             + "would mean a dependency is supplying the CLI's logging configuration")
                .doesNotStartWith("jar:");
        }

        /// The one divergence from the node's configuration that matters most: the CLI's stdout is
        /// DATA. A diagnostic line on stdout corrupts every pipeline that parses command output.
        @Test
        void consoleAppender_targetsStderr_notStdout() {
            var console = configuration().<ConsoleAppender>getAppender("Console");

            assertThat(console)
                .describedAs("the shipped configuration must declare a Console appender named 'Console'")
                .isNotNull();
            assertThat(console.getTarget()).isEqualTo(ConsoleAppender.Target.SYSTEM_ERR);
        }

        /// The shipped default must equal rung 0, or `-v` would be a level CHANGE rather than a
        /// level RAISE and a no-flag invocation would not match [Verbosity#DEFAULT].
        @Test
        void shippedConfig_defaultsAetherLogger_toTheDefaultRung() {
            assertThat(loggerConfigLevel(Verbosity.AETHER_LOGGER)).isEqualTo(Verbosity.DEFAULT.level());
        }

        @Test
        void shippedConfig_defaultsRoot_toOff() {
            assertThat(configuration().getRootLogger().getLevel()).isEqualTo(Level.OFF);
        }
    }

    private static Configuration configuration() {
        return ((LoggerContext) LogManager.getContext(false)).getConfiguration();
    }

    private static Level loggerConfigLevel(String loggerName) {
        return configuration().getLoggerConfig(loggerName).getLevel();
    }
}
