/*
 *  Copyright (c) 2023-2025 Sergiy Yevtushenko.
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

package org.pragmatica.usercode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1311 — THE PIN for what happens when a Throwable escapes a Promise continuation.
///
/// Before the fix, `PromiseImpl.AsyncExecutor.runAsync(Runnable)` submitted continuations through
/// `ExecutorService.submit` and discarded the Future, so a Throwable thrown by a continuation on an
/// executor thread vanished: the dependent promise never resolved, nothing was logged, every event
/// handler queued behind the thrower on the same promise was skipped, and a thread already parked in
/// `await()` on the source promise was never unparked. #1258 and #1297 each met this as a flag set
/// before a callback that was never cleared. Contracted behaviour, per the #1311 ruling:
///
/// 1. the dependent promise FAILS with a [CoreError.Exception] whose message names the continuation
///    kind and the top stack frame of the escape, and whose `cause()` is the escaped Throwable;
/// 2. the escape is logged at ERROR through the `org.pragmatica.lang.Promise` logger, with the origin;
/// 3. a [VirtualMachineError] is rethrown AFTER 1 and 2 — the dependent still fails, the log line is
///    still written, but the error is not hidden behind a failed Result.
///
/// The `StackOverflowError` cases use a real unbounded recursion and the `OutOfMemoryError` case asks
/// the JVM for an array it cannot address (`Requested array size exceeds VM limit`), so the JVM itself
/// raises both; a `new StackOverflowError()` thrown by hand would only prove that `catch` works.
///
/// **This class lives outside `org.pragmatica.lang` on purpose.** The origin named in the Cause is the
/// first frame whose class is not under `java.`/`jdk.`/`sun.`/`org.pragmatica.lang.`, so a pin that asserts
/// "the origin is my frame" has to be shaped like a caller — in a caller's package.
///
/// **This class is an instrument, so it carries a positive control**: [#SENTINEL] is emitted through the
/// same logger the appender is bound to and asserted present, so a detached appender fails the class
/// instead of passing the absence assertions vacuously. Capture follows `RetryLoggingBoundTest`.
@Timeout(60)
class PromiseContinuationEscapeTest {
    private static final String LOGGER_NAME = Promise.class.getName();
    private static final String SENTINEL = "positive control: PromiseContinuationEscapeTest appender is attached";
    private static final String ESCAPED_FRAGMENT = "escaped";
    private static final String THIS_CLASS = PromiseContinuationEscapeTest.class.getSimpleName();

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("PromiseContinuationEscapeCapture");
        appender.start();

        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(Level.INFO);
        loggerConfig.addAppender(appender, null, null);
        ctx.updateLoggers();
        LogManager.getLogger(LOGGER_NAME).info(SENTINEL);
        assertThat(appender.messages()).as("positive control: the capture must see the Promise logger").contains(SENTINEL);
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    // ---- 1. AssertionError: contained (dependent fails, logged, NOT rethrown) -------------------------

    /// The #1311 shape itself: the source resolves on an executor thread, the mapper throws. Before the
    /// fix `dependent.await(2s)` returned a Timeout and the appender saw nothing.
    @Test
    void assertionErrorInMap_resolvedOnExecutor_failsDependentAndLogsOrigin() {
        var source = Promise.<Integer>promise();
        var dependent = source.map(_ -> {
            throw new AssertionError("boom-map-async");
        });

        source.async(promise -> promise.succeed(1));

        var result = dependent.await(timeSpan(2).seconds());

        assertEscape(result, "replaceResult", "boom-map-async");
        assertErrorLogged("replaceResult", "boom-map-async");
    }

    /// Same defect on a user thread: before the fix the AssertionError came out of `succeed()` and the
    /// dependent stayed unresolved.
    @Test
    void assertionErrorInMap_resolvedOnCallerThread_failsDependentAndDoesNotEscapeResolve() {
        var source = Promise.<Integer>promise();
        var dependent = source.map(_ -> {
            throw new AssertionError("boom-map-inline");
        });

        source.succeed(1);

        assertThat(dependent.isResolved()).as("dependent must be resolved by the time succeed() returns").isTrue();
        assertEscape(dependent.await(), "replaceResult", "boom-map-inline");
        assertErrorLogged("replaceResult", "boom-map-inline");
    }

    @Test
    void assertionErrorInFlatMap_resolvedOnExecutor_failsDependentAndLogsOrigin() {
        var source = Promise.<Integer>promise();
        var dependent = source.flatMap(_ -> {
            throw new AssertionError("boom-flatmap");
        });

        source.async(promise -> promise.succeed(1));

        assertEscape(dependent.await(timeSpan(2).seconds()), "fold", "boom-flatmap");
        assertErrorLogged("fold", "boom-flatmap");
    }

    /// Already-resolved promise: the continuation runs synchronously on the caller. Before the fix the
    /// AssertionError propagated out of `map()`; the behaviour must not depend on whether the source
    /// resolved before or after the continuation was attached.
    @Test
    void assertionErrorInMap_onResolvedPromise_returnsFailedPromiseInsteadOfThrowing() {
        var dependent = Promise.success(1).map(_ -> {
            throw new AssertionError("boom-map-resolved");
        });

        assertEscape(dependent.await(), "replaceResult", "boom-map-resolved");
        assertErrorLogged("replaceResult", "boom-map-resolved");
    }

    @Test
    void assertionErrorInFlatMap_onResolvedPromise_returnsFailedPromiseInsteadOfThrowing() {
        var dependent = Promise.success(1).flatMap(_ -> {
            throw new AssertionError("boom-flatmap-resolved");
        });

        assertEscape(dependent.await(), "fold", "boom-flatmap-resolved");
        assertErrorLogged("fold", "boom-flatmap-resolved");
    }

    @Test
    void assertionErrorInOnResult_onResolvedPromise_isLoggedAndDoesNotThrow() {
        var promise = Promise.success(1);
        var returned = promise.onResult(_ -> {
            throw new AssertionError("boom-onresult-resolved");
        });

        assertThat(returned).isSameAs(promise);
        assertErrorLogged("onResult", "boom-onresult-resolved");
    }

    /// The #1258 shape: handlers on the same promise run in one executor task. Before the fix the first
    /// handler's throw skipped every handler queued behind it, so the flag-clearing one never ran.
    @Test
    void assertionErrorInOnResult_doesNotSkipHandlersQueuedBehindIt() throws InterruptedException {
        var source = Promise.<Integer>promise();
        var flag = new AtomicBoolean(true);
        var released = new CountDownLatch(1);

        source.onResult(_ -> {
            throw new AssertionError("boom-onresult-first");
        });
        source.onResult(_ -> {
            flag.set(false);
            released.countDown();
        });

        source.succeed(1);

        assertThat(released.await(2, TimeUnit.SECONDS)).as("the handler queued behind the thrower must still run").isTrue();
        assertThat(flag).isFalse();
        assertErrorLogged("onResult", "boom-onresult-first");
    }

    /// The #1297 shape: a flag set before the chain, cleared by a continuation on the DEPENDENT promise.
    /// Before the fix the dependent never resolved and the flag stayed set forever.
    @Test
    void flagSetBeforeThrowingMap_isReleasedByDependentContinuation() throws InterruptedException {
        var inFlight = new AtomicBoolean(true);
        var released = new CountDownLatch(1);
        var source = Promise.<Integer>promise();

        source.map(_ -> {
                  throw new AssertionError("boom-inflight");
              })
              .onResult(_ -> {
                  inFlight.set(false);
                  released.countDown();
              });

        source.async(promise -> promise.succeed(1));

        assertThat(released.await(2, TimeUnit.SECONDS)).as("flag must be released, not wedged").isTrue();
        assertThat(inFlight).isFalse();
    }

    /// A thread already parked in `await()` on the SOURCE had its join skipped when a sequential
    /// continuation claimed in the same batch threw. Before the fix it parked forever.
    @Test
    void throwingMap_doesNotStrandThreadAlreadyAwaitingTheSource() throws InterruptedException {
        var source = Promise.<Integer>promise();
        var awaited = new AtomicReference<Result<Integer>>();
        var parked = new CountDownLatch(1);
        var waiter = Thread.ofPlatform().name("s1311-waiter").start(() -> {
            parked.countDown();
            awaited.set(source.await());
        });

        assertThat(parked.await(2, TimeUnit.SECONDS)).isTrue();
        spinUntilParked(waiter);
        source.map(_ -> {
            throw new AssertionError("boom-strand");
        });

        source.succeed(1);

        waiter.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(waiter.isAlive()).as("the awaiting thread must be unparked").isFalse();
        assertThat(awaited.get()).isEqualTo(Result.success(1));
    }

    /// `Promise.promise(consumer)` / `async(consumer)`: the consumer is entrusted with resolving the
    /// promise it receives. Before the fix a throw inside it left the promise unresolved forever.
    @Test
    void assertionErrorInAsyncConsumer_failsThePromiseItWasGiven() {
        var promise = Promise.<Integer>promise(_ -> {
            throw new AssertionError("boom-async-consumer");
        });

        assertEscape(promise.await(timeSpan(2).seconds()), "async", "boom-async-consumer");
        assertErrorLogged("async", "boom-async-consumer");
    }

    @Test
    void assertionErrorInDelayedAsyncConsumer_failsThePromiseItWasGiven() {
        var promise = Promise.<Integer>promise(timeSpan(10).millis(), _ -> {
            throw new AssertionError("boom-delayed-consumer");
        });

        assertEscape(promise.await(timeSpan(2).seconds()), "async", "boom-delayed-consumer");
        assertErrorLogged("async", "boom-delayed-consumer");
    }

    // ---- 2. VirtualMachineError: dependent fails, logged, THEN rethrown ------------------------------

    @Test
    void stackOverflowInMap_resolvedOnCallerThread_failsDependentLogsAndRethrows() {
        var source = Promise.<Integer>promise();
        var dependent = source.map(PromiseContinuationEscapeTest::recurseForever);

        assertThatThrownBy(() -> source.succeed(1))
            .as("a VirtualMachineError is rethrown after the dependent fails and the escape is logged")
            .isInstanceOf(StackOverflowError.class);

        assertThat(dependent.isResolved()).as("the dependent must have failed BEFORE the rethrow").isTrue();
        assertVmeEscape(dependent.await(), "replaceResult", StackOverflowError.class);
        assertErrorLogged("replaceResult", "StackOverflowError");
    }

    /// The executor path: the rethrown error leaves the virtual thread through its uncaught-exception
    /// handler. Before the fix `submit()` kept it in a discarded Future and the handler saw nothing.
    /// The error passes TWO guards on its way out — the mapper's, then the `async` consumer's, since
    /// `succeed()` rethrew it into that consumer — and each logs it with the same origin frame; that
    /// is one event logged at every level it escaped, pinned here so it is not mistaken for two.
    @Test
    void stackOverflowInMap_resolvedOnExecutor_failsDependentLogsAndReachesUncaughtHandler() throws InterruptedException {
        var uncaught = new CopyOnWriteArrayList<Throwable>();
        var reached = new CountDownLatch(1);
        var previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((_, error) -> {
            uncaught.add(error);
            reached.countDown();
        });

        try {
            var source = Promise.<Integer>promise();
            var dependent = source.map(PromiseContinuationEscapeTest::recurseForever);

            source.async(promise -> promise.succeed(1));

            assertVmeEscape(dependent.await(timeSpan(2).seconds()), "replaceResult", StackOverflowError.class);
            assertThat(reached.await(2, TimeUnit.SECONDS)).as("the rethrown error must reach the thread's uncaught handler").isTrue();
            assertThat(uncaught).hasSize(1);
            assertThat(uncaught.getFirst()).isInstanceOf(StackOverflowError.class);
            assertErrorLogged("replaceResult", "StackOverflowError");
            assertErrorLogged("async", "StackOverflowError");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void stackOverflowInOnResult_onResolvedPromise_isLoggedAndRethrown() {
        var promise = Promise.success(1);

        assertThatThrownBy(() -> promise.onResult(_ -> recurseForever(0)))
            .isInstanceOf(StackOverflowError.class);

        assertErrorLogged("onResult", "StackOverflowError");
    }

    @Test
    void outOfMemoryInMap_resolvedOnCallerThread_failsDependentLogsAndRethrows() {
        var source = Promise.<Integer>promise();
        var dependent = source.map(_ -> unaddressableArray());

        assertThatThrownBy(() -> source.succeed(1)).isInstanceOf(OutOfMemoryError.class);

        assertThat(dependent.isResolved()).isTrue();
        assertVmeEscape(dependent.await(), "replaceResult", OutOfMemoryError.class);
        assertErrorLogged("replaceResult", "OutOfMemoryError");
    }

    // ---- 2b. rev1362 pins: VME on already-resolved paths, and a VME must not strand the batch -----------

    /// rev1362 P1a. Before the fix the already-resolved `map` failed + logged but did NOT rethrow, so the
    /// VME semantics depended on whether the source resolved before or after `map` was attached.
    @Test
    void stackOverflowInMap_onResolvedPromise_isLoggedAndRethrown() {
        var promise = Promise.success(1);

        assertThatThrownBy(() -> promise.map(PromiseContinuationEscapeTest::recurseForever))
            .isInstanceOf(StackOverflowError.class);

        assertErrorLogged("replaceResult", "StackOverflowError");
    }

    /// rev1362 P1b.
    @Test
    void stackOverflowInFlatMap_onResolvedPromise_isLoggedAndRethrown() {
        var promise = Promise.success(1);

        assertThatThrownBy(() -> promise.flatMap(value -> {
                recurseForever(value);

                return Promise.success(value);
            }))
            .isInstanceOf(StackOverflowError.class);

        assertErrorLogged("fold", "StackOverflowError");
    }

    /// rev1362 P2. `processActions` CAS-claims the whole batch; before the fix the VME left the loop at
    /// the thrower, so the sibling map never resolved and the thread parked in `await()` on the (resolved)
    /// source was never unparked — the ticket's wedge re-created for a StackOverflowError in one mapper.
    @Test
    void stackOverflowInMap_onExecutor_siblingMapAndAwaiterStillComplete() throws InterruptedException {
        var previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((_, _) -> {});

        try {
            var source = Promise.<Integer>promise();
            var awaited = new AtomicReference<Result<Integer>>();
            var parked = new CountDownLatch(1);
            var waiter = Thread.ofPlatform().name("s1311-waiter-p2").start(() -> {
                parked.countDown();
                awaited.set(source.await());
            });

            assertThat(parked.await(2, TimeUnit.SECONDS)).isTrue();
            spinUntilParked(waiter);

            var thrower = source.map(PromiseContinuationEscapeTest::recurseForever);
            var sibling = source.map(value -> value + 1);

            source.async(promise -> promise.succeed(1));

            assertVmeEscape(thrower.await(timeSpan(2).seconds()), "replaceResult", StackOverflowError.class);
            assertThat(sibling.await(timeSpan(2).seconds())).as("sibling map of the same source must still resolve")
                      .isEqualTo(Result.success(2));
            waiter.join(TimeUnit.SECONDS.toMillis(2));
            assertThat(waiter.isAlive()).as("thread parked in await() on the resolved source must be unparked").isFalse();
            assertThat(awaited.get()).isEqualTo(Result.success(1));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    /// rev1362 P2b: the same for event handlers — a handler queued behind an overflowing one still runs.
    @Test
    void stackOverflowInOnResult_onExecutor_handlerQueuedBehindStillRuns() throws InterruptedException {
        var previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((_, _) -> {});

        try {
            var source = Promise.<Integer>promise();
            var second = new CountDownLatch(1);

            source.onResult(_ -> recurseForever(0));
            source.onResult(_ -> second.countDown());
            source.async(promise -> promise.succeed(1));

            assertThat(second.await(2, TimeUnit.SECONDS)).as("handler queued behind the overflowing one must still run").isTrue();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    /// rev1362 P7: the caller-thread variant — `succeed()` rethrows the VME, but only AFTER the sibling
    /// map has resolved and the awaiter has been unparked.
    @Test
    void stackOverflowInMap_resolvedOnCallerThread_siblingAndAwaiterCompleteBeforeTheRethrow() throws InterruptedException {
        var source = Promise.<Integer>promise();
        var parked = new CountDownLatch(1);
        var waiter = Thread.ofPlatform().name("s1311-waiter-p7").start(() -> {
            parked.countDown();
            source.await();
        });

        assertThat(parked.await(2, TimeUnit.SECONDS)).isTrue();
        spinUntilParked(waiter);

        var thrower = source.map(PromiseContinuationEscapeTest::recurseForever);
        var sibling = source.map(value -> value * 10);

        assertThatThrownBy(() -> source.succeed(1)).isInstanceOf(StackOverflowError.class);

        assertThat(sibling.isResolved()).as("sibling map must have resolved before the rethrow").isTrue();
        assertThat(sibling.await()).isEqualTo(Result.success(10));
        assertVmeEscape(thrower.await(), "replaceResult", StackOverflowError.class);
        waiter.join(TimeUnit.SECONDS.toMillis(2));
        assertThat(waiter.isAlive()).as("awaiter must be unparked before the rethrow").isFalse();
    }

    /// rev1362 P4: the origin must name the continuation, not the JDK throw site. A mapper that calls
    /// `Integer.parseInt` throws from `NumberFormatException.forInputString`; before the fix that was the
    /// frame in the Cause message and the user's frame was absent.
    @Test
    void originFrame_whenMapperThrowsFromInsideAJdkCall_namesTheContinuation() {
        var source = Promise.<Integer>promise();
        var dependent = source.map(_ -> Integer.parseInt("not-a-number"));

        source.async(promise -> promise.succeed(1));

        var result = dependent.await(timeSpan(2).seconds());

        assertThat(result).isInstanceOf(Result.Failure.class);

        var message = ((Result.Failure<?>) result).cause().message();

        assertThat(message).contains("replaceResult")
                  .contains(THIS_CLASS)
                  .as("the JDK frame must not be the named origin")
                  .doesNotContain("continuation at java.base");
    }

    // ---- 3. Promise.lift*: the mapper converts exceptions, a VirtualMachineError still escapes --------

    /// `Promise.lift` routes through `Result.lift`, whose `catch (Throwable)` used to convert a
    /// StackOverflowError into a Cause with no guard ever seeing it: no log, no rethrow, a plausible-looking
    /// failed promise. `Result.lift` now rethrows a VirtualMachineError (`ResultLiftVirtualMachineErrorTest`),
    /// so the error leaves the lift into the `async` guard like any other escape.
    @Test
    void stackOverflowInLift_failsPromiseLogsAndIsNotConvertedSilently() {
        var promise = Promise.lift(() -> recurseForever(0));

        assertVmeEscape(promise.await(timeSpan(2).seconds()), "async", StackOverflowError.class);
        assertErrorLogged("async", "StackOverflowError");
    }

    /// Control for the test above: an ordinary exception inside `lift` is still the mapper's business —
    /// converted by `Causes::fromThrowable`, not logged, not treated as an escape.
    @Test
    void exceptionInLift_isStillMappedByTheLiftMapper_andNotLogged() {
        var promise = Promise.lift(() -> {
            throw new IllegalStateException("lift-control");
        });

        var result = promise.await(timeSpan(2).seconds());

        assertThat(result).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) result).cause()).isNotInstanceOf(CoreError.Exception.class);
        assertThat(((Result.Failure<?>) result).cause().message()).contains("lift-control");
        assertThat(appender.events().stream().filter(event -> event.level() == Level.ERROR).toList())
            .as("a mapped exception is not an escape and must not be logged")
            .isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static int recurseForever(int depth) {
        return recurseForever(depth + 1) + 1;
    }

    /// The JVM raises this OutOfMemoryError itself, without exhausting the heap: the requested array
    /// size exceeds what any HotSpot heap can address.
    private static long[] unaddressableArray() {
        return new long[Integer.MAX_VALUE];
    }

    private static void spinUntilParked(Thread thread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static void assertEscape(Result<?> result, String continuation, String message) {
        assertThat(result).as("expected an escape failure").isInstanceOf(Result.Failure.class);

        var cause = ((Result.Failure<?>) result).cause();

        assertThat(cause).isInstanceOf(CoreError.Exception.class);
        assertThat(cause.message())
            .contains(continuation)
            .contains(message)
            .as("origin: the top frame of the escape, which is this test class")
            .contains(THIS_CLASS);
        assertThat(((CoreError.Exception) cause).cause())
            .isInstanceOf(AssertionError.class)
            .hasMessage(message);
    }

    private static void assertVmeEscape(Result<?> result, String continuation, Class<? extends VirtualMachineError> type) {
        assertThat(result).as("expected an escape failure").isInstanceOf(Result.Failure.class);

        var cause = ((Result.Failure<?>) result).cause();

        assertThat(cause).isInstanceOf(CoreError.Exception.class);
        assertThat(cause.message()).contains(continuation).contains(type.getSimpleName());
        assertThat(((CoreError.Exception) cause).cause()).isInstanceOf(type);
    }

    private void assertErrorLogged(String continuation, String fragment) {
        var errors = appender.events()
                             .stream()
                             .filter(event -> event.level() == Level.ERROR)
                             .filter(event -> event.message().contains(ESCAPED_FRAGMENT))
                             .filter(event -> event.message().contains(fragment))
                             .filter(event -> event.message().contains(continuation + " continuation"))
                             .toList();

        assertThat(errors).as("exactly one ERROR line for the %s escape; all events: %s", continuation, appender.events()).hasSize(1);
        assertThat(errors.getFirst().message()).as("origin: the top frame of the escape, which is this test class").contains(THIS_CLASS);
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
