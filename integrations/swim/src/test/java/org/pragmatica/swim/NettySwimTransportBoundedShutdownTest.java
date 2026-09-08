// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.swim;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/// #929 — the SWIM transport shutdown must be BOUNDED, and must not claim success when it did not
/// complete.
///
/// ## What was wrong
///
/// `stopChannel` used bare `.sync()` on both the channel close and the event-loop-group shutdown,
/// then logged `"SWIM transport stopped"` unconditionally. Netty's own `shutdownGracefully()`
/// timeout could not save it: that timeout is enforced by `confirmShutdown()`, which runs ON the
/// event loop, so a wedged loop cannot enforce its own timeout. When a deadlocked SWIM event loop
/// (#929's root cause) could neither run the pending close task nor reach `confirmShutdown()`, the
/// wait ran until JUnit's 8-minute lifecycle backstop interrupted it — and the success line was
/// logged anyway, over a transport that was never stopped. Dumps show the `nioEventLoopGroup` thread
/// still RUNNABLE ten seconds after that line. #727, #749 and #750 each read that line and looked
/// past the wedge underneath it.
///
/// ## The honesty pin
///
/// [`#shutdownThatCannotComplete_reportsFailureAndDoesNotLogSuccess`] asserts the ABSENCE of the
/// success line. An absence assertion is worthless without a control that produces the thing, so
/// [`#healthyShutdown_succeedsAndLogsSuccess`] runs the same appender against a shutdown that DOES
/// complete and requires the line to appear. If the appender ever stops seeing this logger, the
/// control goes red rather than the pin going quietly vacuous.
///
/// The wedge is a real one: the transport is given an external single-thread `EventLoopGroup`, bound
/// while the loop is free, and the loop is then occupied by a task outlasting the shutdown bound, so
/// the channel-close future genuinely cannot complete.
class NettySwimTransportBoundedShutdownTest {
    private static final String LOGGER_NAME = NettySwimTransport.class.getName();
    private static final String SUCCESS_LINE = "SWIM transport stopped";
    private static final String FAILURE_LINE = "SWIM transport shutdown did NOT complete";
    private static final int WEDGED_PORT = 19731;
    private static final int HEALTHY_PORT = 19732;
    /// Must outlast `NettySwimTransport.SHUTDOWN_TIMEOUT_MS` (5 s) so the bound is what ends the
    /// wait, not the wedge clearing.
    private static final long WEDGE_MS = 9_000L;
    private static final long WEDGE_ARMED_BOUND_MS = 5_000L;
    private static final long GROUP_CLEANUP_TIMEOUT_MS = 10_000L;

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;
    /// Every group this test creates, shut down in [`#tearDown`] whatever the outcome. A Netty
    /// `NioEventLoopGroup` runs NON-daemon threads: a group left alive by a failing assertion keeps
    /// the surefire fork from exiting, which is how a first cut of this class turned a 6 s run into
    /// a 17-minute one under mutation. Cleanup that only runs on the happy path is not cleanup.
    private final List<EventLoopGroup> groups = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("NettySwimTransportBoundedShutdownCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
        groups.forEach(group -> group.shutdownGracefully(0L, GROUP_CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        groups.clear();
    }

    @Test
    void shutdownThatCannotComplete_reportsFailureAndDoesNotLogSuccess() {
        var group = eventLoopGroup();
        var transport = externalGroupTransport(group);

        assertThat(transport.start(WEDGED_PORT, (_, _) -> {}).await().isSuccess())
                .as("instrument check: the transport must bind before the loop is wedged")
                .isTrue();

        wedge(group);

        var result = transport.stop().await();

        assertThat(result.isFailure()).as("a shutdown whose event loop cannot run the close task must "
                                          + "report FAILURE, not success (#929)")
                                      .isTrue();
        assertThat(cause(result)).as("and the failure must name the bound that elapsed")
                                 .isInstanceOf(SwimError.ShutdownTimeout.class);
        assertThat(appender.eventsMentioning(SUCCESS_LINE))
                .as("the success line must NOT be logged on a shutdown that did not complete — that "
                    + "unconditional line is what hid this defect across #727/#749/#750")
                .isEmpty();
        assertThat(appender.eventsMentioning(FAILURE_LINE))
                .as("the failure must be logged instead")
                .hasSize(1);
    }

    /// Positive control for the absence assertion above, and for the appender itself: an
    /// unobstructed shutdown succeeds AND logs the success line exactly once. Uses the INTERNAL
    /// event loop group, so this also covers `shutdownGroup` and the dropped quiet period — the
    /// wedged case above must supply an external group in order to wedge it, which skips that half.
    @Test
    void healthyShutdown_succeedsAndLogsSuccess() {
        var transport = ownGroupTransport();

        assertThat(transport.start(HEALTHY_PORT, (_, _) -> {}).await().isSuccess()).isTrue();

        var result = transport.stop().await();

        assertThat(result.isSuccess()).as("an unobstructed shutdown must succeed").isTrue();
        assertThat(appender.eventsMentioning(SUCCESS_LINE))
                .as("control: the appender CAN see this logger's success line, so its absence above "
                    + "is a real absence rather than a blind instrument")
                .hasSize(1);
        assertThat(appender.eventsMentioning(FAILURE_LINE))
                .as("and a healthy shutdown must not report failure")
                .isEmpty();
    }

    private EventLoopGroup eventLoopGroup() {
        var group = new NioEventLoopGroup(1);

        groups.add(group);

        return group;
    }

    private static SwimTransport externalGroupTransport(EventLoopGroup group) {
        return NettySwimTransport.nettySwimTransport(mock(Serializer.class),
                                                     mock(Deserializer.class),
                                                     GossipEncryptor.none(),
                                                     group)
                                 .unwrap();
    }

    /// Transport owning its own event loop group — `stopChannel` therefore also shuts the group
    /// down, so nothing is left running.
    private static SwimTransport ownGroupTransport() {
        return NettySwimTransport.nettySwimTransport(mock(Serializer.class),
                                                     mock(Deserializer.class),
                                                     GossipEncryptor.none())
                                 .unwrap();
    }

    /// Occupy the group's only event loop for longer than the shutdown bound, and wait until the
    /// task has actually started — otherwise the shutdown could win the race and the probe would
    /// measure nothing.
    private static void wedge(EventLoopGroup group) {
        var armed = new CountDownLatch(1);

        group.execute(() -> {
            armed.countDown();
            sleepQuietly(WEDGE_MS);
        });

        try {
            assertThat(armed.await(WEDGE_ARMED_BOUND_MS, TimeUnit.MILLISECONDS))
                    .as("instrument check: the event loop must actually be occupied before stop()")
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Cause cause(Result<Unit> result) {
        return result instanceof Result.Failure<Unit>(var failure)
               ? failure
               : null;
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.ALL, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    /// In-memory log4j2 appender capturing every event on the transport's own logger.
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
            messages.add(event.getMessage().getFormattedMessage());
        }

        List<String> eventsMentioning(String fragment) {
            return messages.stream().filter(message -> message.contains(fragment)).toList();
        }
    }
}
