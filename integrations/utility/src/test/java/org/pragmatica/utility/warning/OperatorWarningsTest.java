/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.utility.warning;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.pragmatica.utility.warning.OperatorWarningCode.CORE_ABSENCE_FENCE;
import static org.pragmatica.utility.warning.OperatorWarningCode.REPLICA_FSYNC_FAILED;

/// `raise` logs and emits from one call, and emission can never take the log line down with it (#1574).
class OperatorWarningsTest {
    private static final String LOGGER_NAME = "org.pragmatica.utility.warning.OperatorWarningsTest.site";
    private static final Logger SITE_LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private final List<OperatorWarning> emitted = new CopyOnWriteArrayList<>();
    private static final String HAND_OFF_LOGGER = OperatorWarningSink.HandOff.class.getName();

    private CapturingAppender appender;
    private CapturingAppender handOffAppender;
    private LoggerContext context;

    @BeforeEach
    void captureLog() {
        context = (LoggerContext) LogManager.getContext(false);
        appender = capture(LOGGER_NAME, "OperatorWarningsCapture");
        handOffAppender = capture(HAND_OFF_LOGGER, "OperatorWarningsHandOffCapture");
        context.updateLoggers();
    }

    @AfterEach
    void releaseLog() {
        context.getConfiguration().removeLogger(LOGGER_NAME);
        context.getConfiguration().removeLogger(HAND_OFF_LOGGER);
        context.updateLoggers();
        appender.stop();
        handOffAppender.stop();
    }

    private CapturingAppender capture(String loggerName, String appenderName) {
        var capturing = new CapturingAppender(appenderName);
        var loggerConfig = new LoggerConfig(loggerName, Level.TRACE, false);

        capturing.start();
        loggerConfig.addAppender(capturing, Level.TRACE, null);
        context.getConfiguration().addLogger(loggerName, loggerConfig);

        return capturing;
    }

    @Test
    void raise_logsAndEmits_withTheFormattedMessage() {
        OperatorWarnings.raise(SITE_LOG,
                               OperatorWarningSink.handingOffTo(emitted::add),
                               REPLICA_FSYNC_FAILED,
                               "orders[3]",
                               "sync failed for {}[{}]",
                               "orders",
                               3);

        await(() -> !emitted.isEmpty());
        assertThat(emitted).containsExactly(OperatorWarning.operatorWarning(REPLICA_FSYNC_FAILED,
                                                                            "orders[3]",
                                                                            "sync failed for orders[3]"));
        assertThat(appender.events).hasSize(1);
        assertThat(appender.events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.events.getFirst().getMessage().getFormattedMessage())
            .isEqualTo("[replica-fsync-failed] sync failed for orders[3]");
    }

    @Test
    void raise_criticalCode_logsAtError() {
        OperatorWarnings.raise(SITE_LOG, OperatorWarningSink.handingOffTo(emitted::add), CORE_ABSENCE_FENCE, "core", "fence firing");

        assertThat(appender.events).hasSize(1);
        assertThat(appender.events.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.events.getFirst().getMessage().getFormattedMessage())
            .isEqualTo("[core-absence-fence] fence firing");
    }

    /// The log is the fallback: a publisher that throws must neither propagate nor suppress the log line.
    /// The throw happens on the hand-off thread, which logs it and carries on.
    @Test
    void raise_throwingPublisher_doesNotThrow_andTheLogLineStands() {
        var throwing = OperatorWarningSink.handingOffTo(OperatorWarningsTest::explode);

        assertThatCode(() -> OperatorWarnings.raise(SITE_LOG, throwing, REPLICA_FSYNC_FAILED, "orders[3]", "sync failed"))
            .doesNotThrowAnyException();
        assertThat(appender.events).hasSize(1);
        assertThat(appender.events.getFirst().getMessage().getFormattedMessage())
            .isEqualTo("[replica-fsync-failed] sync failed");

        await(() -> !handOffAppender.events.isEmpty());
        assertThat(handOffAppender.events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(handOffAppender.events.getFirst().getMessage().getFormattedMessage())
            .contains("[replica-fsync-failed] operator warning for orders[3] not emitted")
            .contains("event log unavailable");
    }

    @Test
    void raise_logOnlySink_logsAndEmitsNothing() {
        assertThatCode(() -> OperatorWarnings.raise(SITE_LOG,
                                                    OperatorWarningSink.logOnly(),
                                                    REPLICA_FSYNC_FAILED,
                                                    "orders[3]",
                                                    "sync failed"))
            .doesNotThrowAnyException();
        assertThat(appender.events).hasSize(1);
    }

    /// #1617 R2: `raise` never waits for the publisher. A publisher that takes 2 s must not make `raise`
    /// take 2 s, because warnings are raised from SWIM, replication and the core-absence fence.
    @Test
    void raise_slowPublisher_returnsWithoutWaitingForIt() {
        var published = new CountDownLatch(1);
        var sink = OperatorWarningSink.handingOffTo(warning -> slowPublish(published));
        var started = System.nanoTime();

        OperatorWarnings.raise(SITE_LOG, sink, REPLICA_FSYNC_FAILED, "orders[3]", "sync failed");

        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("raise() returned after %d ms with a 2 s publisher", elapsedMs)
                             .isLessThan(50L);
        await(() -> published.getCount() == 0L, 5);
    }

    /// #1617 R2: a burst beyond the hand-off queue is dropped and counted, never blocking the caller; the
    /// next accepted warning reports the drop.
    @Test
    void handOff_fullQueue_dropsAndCounts_andTheNextAcceptedWarningReportsIt() throws InterruptedException {
        var release = new CountDownLatch(1);
        var inPublisher = new CountDownLatch(1);
        var publishedCount = new AtomicInteger();
        var sink = (OperatorWarningSink.HandOff) OperatorWarningSink.handingOffTo(warning -> block(inPublisher,
                                                                                                  release,
                                                                                                  publishedCount));
        var warning = OperatorWarning.operatorWarning(REPLICA_FSYNC_FAILED, "orders[3]", "sync failed");

        sink.accept(warning);
        assertThat(inPublisher.await(5, TimeUnit.SECONDS)).as("the drain thread is inside the publisher").isTrue();

        for (int i = 0; i < OperatorWarningSink.HAND_OFF_CAPACITY + 10; i++) {
            sink.accept(warning);
        }

        assertThat(sink.dropped()).as("the queue holds %d; the rest are dropped", OperatorWarningSink.HAND_OFF_CAPACITY)
                                  .isEqualTo(10L);

        release.countDown();
        // The one in flight plus the full queue, all published: the queue is empty again.
        await(() -> publishedCount.get() == 1 + OperatorWarningSink.HAND_OFF_CAPACITY, 5);
        sink.accept(warning);

        assertThat(sink.dropped()).as("an accepted warning after the drain drops nothing").isEqualTo(10L);
        assertThat(handOffAppender.events.stream()
                                         .map(event -> event.getMessage()
                                                            .getFormattedMessage()))
            .anyMatch(line -> line.startsWith("10 operator warning event(s) dropped"));
    }

    private static void slowPublish(CountDownLatch published) {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        published.countDown();
    }

    private static void block(CountDownLatch inPublisher, CountDownLatch release, AtomicInteger publishedCount) {
        inPublisher.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        publishedCount.incrementAndGet();
    }

    private static void await(BooleanSupplier condition) {
        await(condition, 2);
    }

    private static void await(BooleanSupplier condition, int seconds) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).as("condition within %d s", seconds).isTrue();
    }

    private static void explode(OperatorWarning warning) {
        throw new IllegalStateException("event log unavailable");
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
