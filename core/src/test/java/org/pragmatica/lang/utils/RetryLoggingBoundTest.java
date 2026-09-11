/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.lang.utils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #718 — THE PIN for [Retry]'s logging volume.
///
/// The defect was not that a retry logged, it was that it logged ONCE PER ATTEMPT at WARN. With the
/// QUIC consensus send wrapper's `200 attempts x 25ms` budget that is a 200x multiplier on a hot send
/// path, through a single synchronous Console appender, on the very Netty event-loop threads whose
/// progress the retries were waiting for. Measured: ~4.2 million lines in 72 seconds, Forge missing
/// its 60s cluster-formation budget 3/3; silencing only this logger gave 4/4 healthy formations in
/// 8.4s with 84,166 backpressure events still occurring and clearing in 3.5s.
///
/// So the property under test is a BOUND, and it is structural rather than statistical: the number of
/// WARN lines one [Retry#execute] can emit is at most ONE, whatever `maxAttempts` is. That is what
/// [#warnVolume_isIndependentOfTheAttemptBudget] asserts directly, by comparing two budgets that
/// differ 40-fold.
///
/// Every assertion here reads the LEVEL of each captured event, never merely its presence. That
/// matters because the fix MOVED a line rather than deleting it: a test that only checked "no WARN
/// containing 'retrying after'" would also pass if the line had been deleted outright, which would
/// have destroyed the diagnostic this project still wants at DEBUG
/// ([#perAttemptDetail_survivesAtDebugLevel]).
///
/// **This class is an instrument, so it carries a positive control.** Three of its assertions are
/// about ABSENCE, and an empty capture list satisfies an absence assertion just as well as correct
/// production code does. [#SENTINEL] is emitted through the SAME logger the appender is bound to,
/// before each exercise, and asserted present — a detached appender, a renamed logger or a swallowed
/// setup failure therefore fails the class rather than passing it vacuously. Capture strategy follows
/// the house pattern in `BootstrapAdminKeyLegFallbackWarnTest` (`aether/node`), with one deliberate
/// divergence: the appender is attached with a `null` level so it adds no filter of its own, and the
/// LOGGER's level is the single variable each test sets. Filtering in the appender would have made
/// the DEBUG half unobservable.
@Timeout(30)
class RetryLoggingBoundTest {
    private static final String LOGGER_NAME = Retry.class.getName();
    /// Fragment of the per-attempt line — the one that used to be WARN and is now DEBUG.
    private static final String PER_ATTEMPT_FRAGMENT = "retrying after";
    /// Fragment of the give-up line. Before #718 the spent-budget path logged NOTHING, so this is new
    /// signal, not relocated signal.
    private static final String GAVE_UP_FRAGMENT = "giving up";
    /// Fragment of the pre-existing unretryable-cause line, asserted unchanged.
    private static final String TERMINAL_FRAGMENT = "TERMINAL cause";
    private static final String SENTINEL = "positive control: RetryLoggingBoundTest appender is attached";

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("RetryLoggingBoundCapture");
        appender.start();

        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, null, null);
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

    /// THE pin, at the level an operator actually runs. A 50-attempt budget that is fully spent emits
    /// ONE line: the give-up WARN. The pre-#718 code emitted 49 WARNs here.
    @Test
    void retryLoop_atDefaultInfoLevel_emitsOneWarnForFiftyAttempts() {
        useLevel(Level.INFO);
        emitSentinel();

        var attempts = exhaustBudget(50);

        assertThat(attempts)
            .describedAs("precondition: the budget must actually be spent, or nothing could have been logged")
            .isEqualTo(50);
        assertSentinelWasCaptured();
        assertThat(messagesContaining(PER_ATTEMPT_FRAGMENT))
            .describedAs("the per-attempt line must not reach a default INFO configuration at all — "
                         + "this is the 200x multiplier #718 removed")
            .isEmpty();
        assertThat(warnsContaining(GAVE_UP_FRAGMENT))
            .describedAs("exactly one give-up WARN, and it must carry the COUNT so a spent budget is "
                         + "still discoverable as an aggregate")
            .hasSize(1)
            .allMatch(message -> message.contains("50 of 50 attempts"));
    }

    /// The structural claim stated as a comparison rather than an absolute: WARN volume does not scale
    /// with the attempt budget. Two budgets 40x apart, same WARN count.
    @Test
    void warnVolume_isIndependentOfTheAttemptBudget() {
        useLevel(Level.INFO);
        emitSentinel();

        exhaustBudget(5);
        var afterSmallBudget = warnCountExcludingSentinel();

        exhaustBudget(200);
        var afterLargeBudget = warnCountExcludingSentinel();

        assertSentinelWasCaptured();
        assertThat(afterSmallBudget)
            .describedAs("a 5-attempt budget emits one give-up WARN")
            .isEqualTo(1);
        assertThat(afterLargeBudget - afterSmallBudget)
            .describedAs("a 200-attempt budget emits ONE more WARN, not 200 — the bound is per "
                         + "execute() call, not per attempt")
            .isEqualTo(1);
    }

    /// The other half of the bound, and the reason the line was demoted rather than deleted: every
    /// per-attempt line is still there, at DEBUG, for whoever is diagnosing a retry storm. Asserted by
    /// LEVEL — if the fix had deleted the line, or left it at WARN, this fails.
    @Test
    void perAttemptDetail_survivesAtDebugLevel() {
        useLevel(Level.DEBUG);
        emitSentinel();

        exhaustBudget(5);

        assertSentinelWasCaptured();
        assertThat(eventsContaining(PER_ATTEMPT_FRAGMENT))
            .describedAs("attempts 1..4 each report; the 5th takes the give-up branch instead")
            .hasSize(4);
        assertThat(eventsContaining(PER_ATTEMPT_FRAGMENT))
            .describedAs("and every one of them is DEBUG — a single WARN here reinstates the defect")
            .allMatch(event -> event.level() == Level.DEBUG);
        assertThat(warnsContaining(GAVE_UP_FRAGMENT))
            .describedAs("the give-up line stays WARN even when the detail is turned on")
            .hasSize(1);
    }

    /// A retry that SUCCEEDS must be silent at WARN. This is the shape the measured incident actually
    /// had — an 84,166-event backpressure burst that cleared itself in 3.5 seconds — so a WARN per
    /// recovered operation would reintroduce volume proportional to the burst.
    @Test
    void retryThatEventuallySucceeds_emitsNoWarnAtAll() {
        useLevel(Level.INFO);
        emitSentinel();

        var attempts = new AtomicInteger();
        var result = Retry.retry()
                          .attempts(10)
                          .strategy(Retry.BackoffStrategy.fixed().interval(timeSpan(1).millis()))
                          .execute(() -> attempts.incrementAndGet() < 3
                                         ? Causes.cause("transient").promise()
                                         : Promise.success("recovered"))
                          .await();

        assertThat(result.isSuccess())
            .describedAs("precondition: the operation must recover, or this tests the wrong branch")
            .isTrue();
        assertSentinelWasCaptured();
        assertThat(warnCountExcludingSentinel())
            .describedAs("a recovered retry is not an operator event — the burst is reported by the "
                         + "caller's own backpressure WARN, which #718 left untouched")
            .isZero();
    }

    /// The pre-existing unretryable-cause WARN is unchanged: one line, still WARN. Included because
    /// the fix touched the switch this case lives in, and because it is the symmetric partner of the
    /// give-up line — both are "we are not trying again", and both must be audible.
    @Test
    void terminalCause_stillWarnsExactlyOnce() {
        useLevel(Level.INFO);
        emitSentinel();

        var attempts = new AtomicInteger();
        var result = Retry.retry()
                          .attempts(20)
                          .strategy(Retry.BackoffStrategy.fixed().interval(timeSpan(1).millis()))
                          .execute(() -> {
                              attempts.incrementAndGet();

                              return Causes.terminal("unretryable").promise();
                          })
                          .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(attempts.get())
            .describedAs("precondition: a terminal cause stops after the first attempt")
            .isEqualTo(1);
        assertSentinelWasCaptured();
        assertThat(warnsContaining(TERMINAL_FRAGMENT))
            .describedAs("the unretryable-cause WARN is unchanged by #718")
            .hasSize(1);
        assertThat(warnsContaining(GAVE_UP_FRAGMENT))
            .describedAs("a terminal cause is not a spent budget — the two give-up lines are distinct "
                         + "and must not both fire")
            .isEmpty();
    }

    private static int exhaustBudget(int maxAttempts) {
        var attempts = new AtomicInteger();
        var result = Retry.retry()
                          .attempts(maxAttempts)
                          .strategy(Retry.BackoffStrategy.fixed().interval(timeSpan(1).millis()))
                          .execute(() -> Causes.cause("always fails " + attempts.incrementAndGet()).promise())
                          .await();

        assertThat(result.isFailure())
            .describedAs("precondition: the budget must be spent for the give-up branch to run")
            .isTrue();

        return attempts.get();
    }

    private void useLevel(Level level) {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.setLevel(level);
        ctx.updateLoggers();
    }

    /// Emits [#SENTINEL] on exactly [#LOGGER_NAME], at WARN so it survives every level this class
    /// sets, inside the same capture window the real assertions read.
    private static void emitSentinel() {
        LogManager.getLogger(LOGGER_NAME).warn(SENTINEL);
    }

    /// Paired with every assertion, and load-bearing for the three that assert ABSENCE: without it an
    /// empty capture list would satisfy them.
    private void assertSentinelWasCaptured() {
        assertThat(appender.messages())
            .describedAs("the appender must be attached to %s and the logger must be emitting, or "
                         + "these assertions examine nothing", LOGGER_NAME)
            .anyMatch(message -> message.contains(SENTINEL));
    }

    private List<CapturedEvent> eventsContaining(String fragment) {
        return appender.events()
                       .stream()
                       .filter(event -> event.message().contains(fragment))
                       .toList();
    }

    private List<String> messagesContaining(String fragment) {
        return eventsContaining(fragment).stream()
                                         .map(CapturedEvent::message)
                                         .toList();
    }

    private List<String> warnsContaining(String fragment) {
        return eventsContaining(fragment).stream()
                                         .filter(event -> event.level() == Level.WARN)
                                         .map(CapturedEvent::message)
                                         .toList();
    }

    /// WARN count with the sentinel discounted — the sentinel is itself a WARN, so a raw count would
    /// never be zero.
    private long warnCountExcludingSentinel() {
        return appender.events()
                       .stream()
                       .filter(event -> event.level() == Level.WARN)
                       .filter(event -> !event.message().contains(SENTINEL))
                       .count();
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.INFO, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    private record CapturedEvent(Level level, String message) {}

    /// In-memory log4j2 appender retaining the LEVEL alongside the message. Every level is captured;
    /// what actually reaches here is decided by the logger config the test sets, because the appender
    /// is attached with a `null` level and so adds no filter of its own.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<CapturedEvent> captured = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            captured.add(new CapturedEvent(event.getLevel(), event.getMessage().getFormattedMessage()));
        }

        List<CapturedEvent> events() {
            return List.copyOf(captured);
        }

        List<String> messages() {
            return captured.stream()
                           .map(CapturedEvent::message)
                           .toList();
        }
    }
}
