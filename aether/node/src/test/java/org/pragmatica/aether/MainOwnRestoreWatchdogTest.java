// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.OwnRestoreWatchdog;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1468 — what `Main` adds around [OwnRestoreWatchdog]: the exit code, the bound it passes, the FATAL line, the
/// flush before the halt, and the call that arms it before `start()` is awaited. The halt is an injected
/// `IntConsumer`, so the exit code is asserted without killing the surefire fork.
///
/// The arming call sits in `startNodeAndWait`, which needs a started [AetherNode]; it is pinned by reading the
/// source with comments stripped and whitespace removed (the approach of `NodeDepartureWiringTest`), so the pin says
/// the call is PRESENT and ordered before `node.start()`, not that it ran.
class MainOwnRestoreWatchdogTest {
    private static final String LOGGER_NAME = "org.pragmatica.aether.Main";
    private static final org.pragmatica.lang.Cause UNREADABLE = Causes.cause("snapshot is corrupt and cannot be decoded");

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("MainOwnRestoreCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ERROR, null);
        loggerConfig.setLevel(Level.ERROR);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    void exitCode_isSeventyFour_theDocumentedOperatorContract() {
        assertThat(AetherNode.EXIT_CONSENSUS_RESTORE_FAILED).isEqualTo(74);
    }

    @Test
    void exit_haltsWithCodeSeventyFour() {
        var events = new CopyOnWriteArrayList<String>();
        var watchdog = Main.ownRestoreWatchdog(Option::none, Option::none, () -> events.add("flush"), code -> events.add("halt " + code));

        watchdog.exit().accept(UNREADABLE);

        assertThat(events).contains("halt 74");
    }

    @Test
    void exit_logsTheFatalNamingTheCauseAndTheCode_thenFlushesBeforeTheHalt() {
        var events = new CopyOnWriteArrayList<String>();
        var watchdog = Main.ownRestoreWatchdog(Option::none,
                                               Option::none,
                                               () -> events.add("flush after " + appender.capturedErrors().size() + " errors"),
                                               code -> events.add("halt " + code));

        watchdog.exit().accept(UNREADABLE);

        assertThat(appender.capturedErrors()).as("the FATAL line is written before the process disappears")
                                             .singleElement()
                                             .satisfies(message -> assertThat(message).startsWith("FATAL: this node could not restore its own persisted consensus history")
                                                                                      .contains(UNREADABLE.message())
                                                                                      .contains("code 74"));
        assertThat(events).as("halt performs no appender flush of its own: the flush runs after the FATAL and before the halt")
                          .containsExactly("flush after 1 errors", "halt 74");
    }

    @Test
    void exit_stillHalts_whenTheFlushThrows() {
        var halted = new CopyOnWriteArrayList<Integer>();
        var watchdog = Main.ownRestoreWatchdog(Option::none, Option::none, MainOwnRestoreWatchdogTest::throwingFlush, halted::add);

        try {
            watchdog.exit().accept(UNREADABLE);
        } catch (IllegalStateException expected) {
            // the flush failure propagates; what matters is that the halt was not preempted by it
        }

        assertThat(halted).as("a wedged or failing appender must not keep a fenced node alive").containsExactly(74);
    }

    @Test
    void watchdog_isBoundedAtOneHundredTwentySeconds_andSamplesTheNodesAccessor() {
        var watchdog = Main.ownRestoreWatchdog(() -> Option.some(UNREADABLE), Option::none, () -> {}, _ -> {});

        assertThat(watchdog.boundMillis()).isEqualTo(120_000L).isEqualTo(OwnRestoreWatchdog.BOUND.millis());
        assertThat(watchdog.failure().get()).isEqualTo(Option.some(UNREADABLE));
    }

    @Test
    void startNodeAndWait_armsTheWatchdog_beforeAwaitingStart() {
        assertThat(mainCode()).contains("armOwnRestoreWatchdog(node);node.start()");
    }

    @Test
    void armOwnRestoreWatchdog_samplesTheNodesAccessor_andHaltsTheRealJvm() {
        assertThat(mainCode()).contains("ownRestoreWatchdog(node::ownRestoreFailure,node::restoredStateSaveFailure,Main::flushLogs,Runtime.getRuntime()::halt).arm(OwnRestoreWatchdog.CHECK_INTERVAL);");
    }

    private static final org.pragmatica.lang.Cause DISK_FULL = Causes.cause("no space left on device");

    @Test
    void exitCode_forAFailedRepersist_isSeventyFive_distinctFromSeventyFour() {
        assertThat(AetherNode.EXIT_CONSENSUS_STATE_PERSIST_FAILED).isEqualTo(75)
                                                                  .isNotEqualTo(AetherNode.EXIT_CONSENSUS_RESTORE_FAILED);
    }

    @Test
    void exit_forAFailedRepersist_haltsWithCodeSeventyFive_afterItsOwnFatal_andTheFlush() {
        var events = new CopyOnWriteArrayList<String>();
        var watchdog = Main.ownRestoreWatchdog(Option::none,
                                               () -> Option.some(DISK_FULL),
                                               () -> events.add("flush after " + appender.capturedErrors().size() + " errors"),
                                               code -> events.add("halt " + code));

        watchdog.exit().accept(OwnRestoreWatchdog.Fence.StatePersistFailed.FACTORY.apply(DISK_FULL.message()));

        assertThat(appender.capturedErrors()).singleElement()
                                             .satisfies(message -> assertThat(message).startsWith("FATAL: this node restored consensus state but could not persist it")
                                                                                      .contains(DISK_FULL.message())
                                                                                      .contains("code 75")
                                                                                      .doesNotContain("code 74"));
        assertThat(events).containsExactly("flush after 1 errors", "halt 75");
    }

    @Test
    void exit_forAFailedRepersist_stillHalts_whenTheFlushThrows() {
        var halted = new CopyOnWriteArrayList<Integer>();
        var watchdog = Main.ownRestoreWatchdog(Option::none, Option::none, MainOwnRestoreWatchdogTest::throwingFlush, halted::add);

        try {
            watchdog.exit().accept(OwnRestoreWatchdog.Fence.StatePersistFailed.FACTORY.apply(DISK_FULL.message()));
        } catch (IllegalStateException expected) {
            // the flush failure propagates; the halt must not be preempted by it
        }

        assertThat(halted).containsExactly(75);
    }

    @Test
    void watchdog_samplesTheRepersistAccessor_andTagsItAsTheRepersistFence() {
        var watchdog = Main.ownRestoreWatchdog(Option::none, () -> Option.some(DISK_FULL), () -> {}, _ -> {});

        assertThat(watchdog.failure().get().map(org.pragmatica.lang.Cause::message)).isEqualTo(Option.some(DISK_FULL.message()));
        assertThat(watchdog.failure().get().fold(() -> null, c -> c)).isInstanceOf(OwnRestoreWatchdog.Fence.StatePersistFailed.class);
    }

    /// W9: the production clock converts nanoseconds to milliseconds. A wrong divisor moves the bound by orders of
    /// magnitude (a 1_000 divisor would reach the 120 s bound after 120 ms), and nothing else exercises it.
    @Test
    void nanosToMillis_divides_byOneMillion() {
        assertThat(Main.nanosToMillis(5_000_000_000L)).isEqualTo(5_000L);
        assertThat(Main.nanosToMillis(1_999_999L)).isEqualTo(1L);
    }

    @Test
    void productionClock_advancesOneMillisecondPerMillisecond() throws InterruptedException {
        var watchdog = Main.ownRestoreWatchdog(Option::none, Option::none, () -> {}, _ -> {});
        var before = watchdog.nowMillis().getAsLong();

        Thread.sleep(200);
        var elapsed = watchdog.nowMillis().getAsLong() - before;

        assertThat(elapsed).as("a 200 ms sleep must read as about 200 ms on the watchdog's clock").isBetween(190L, 5_000L);
    }

    private static void throwingFlush() {
        throw new IllegalStateException("appender wedged");
    }

    /// `Main.java` with line comments removed and all whitespace stripped. An unreadable file fails loudly,
    /// because a pin that scans nothing always passes.
    private static String mainCode() {
        var file = sourceRoot().resolve("org/pragmatica/aether/Main.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(MainOwnRestoreWatchdogTest.class.getProtectionDomain()
                                                                      .getCodeSource()
                                                                      .getLocation()
                                                                      .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.ERROR, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedErrors() {
            return List.copyOf(messages);
        }
    }
}
