/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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
package org.pragmatica.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/// Pins the wait helper's reporting on both outcomes, with the clock and the pause injected so no assertion depends on real time.
class PollUntilTest {
    private static final Duration DEADLINE = Duration.ofMillis(50);
    private static final Duration INTERVAL = Duration.ofMillis(20);

    @Test
    void pollUntil_holdsOnTheFirstPoll_reportsOnePollAndFullMargin() {
        var clock = new AtomicLong();

        var report = PollUntil.pollUntil(() -> 7, value -> value == 7, DEADLINE, INTERVAL, clock::get, clock::addAndGet);

        assertThat(report.met()).isTrue();
        assertThat(report.polls()).isEqualTo(1);
        assertThat(report.firstValue()).isEqualTo(7);
        assertThat(report.finalValue()).isEqualTo(7);
        assertThat(report.elapsed()).isEqualTo(Duration.ZERO);
        assertThat(report.margin()).isEqualTo(DEADLINE);
    }

    @Test
    void pollUntil_holdsOnTheThirdPoll_reportsFirstAndFinalValueElapsedAndPositiveMargin() {
        var clock = new AtomicLong();
        var counter = new AtomicInteger();

        var report = PollUntil.pollUntil(counter::incrementAndGet, value -> value == 3, DEADLINE, INTERVAL, clock::get, clock::addAndGet);

        assertThat(report.met()).isTrue();
        assertThat(report.polls()).isEqualTo(3);
        assertThat(report.firstValue()).isEqualTo(1);
        assertThat(report.finalValue()).isEqualTo(3);
        assertThat(report.elapsed()).isEqualTo(Duration.ofMillis(40));
        assertThat(report.margin()).isEqualTo(Duration.ofMillis(10));
    }

    @Test
    void pollUntil_missesTheDeadline_endsExactlyAtTheDeadlineWithZeroMargin() {
        var clock = new AtomicLong();
        var counter = new AtomicInteger();

        var report = PollUntil.pollUntil(counter::incrementAndGet, value -> false, DEADLINE, INTERVAL, clock::get, clock::addAndGet);

        assertThat(report.met()).isFalse();
        assertThat(report.polls()).isEqualTo(4);
        assertThat(report.firstValue()).isEqualTo(1);
        assertThat(report.finalValue()).isEqualTo(4);
        assertThat(report.elapsed()).isEqualTo(Duration.ofMillis(50));
        assertThat(report.margin()).isEqualTo(Duration.ZERO);
    }

    @Test
    void assertMet_onAMiss_failsWithEveryObservation() {
        var clock = new AtomicLong();
        var counter = new AtomicInteger();
        var report = PollUntil.pollUntil(counter::incrementAndGet, value -> false, DEADLINE, INTERVAL, clock::get, clock::addAndGet);

        assertThatThrownBy(() -> report.assertMet("the counter reaches 9"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("MISSED: the counter reaches 9")
            .hasMessageContaining("first=1")
            .hasMessageContaining("final=4")
            .hasMessageContaining("polls=4")
            .hasMessageContaining("elapsed=50 ms")
            .hasMessageContaining("margin=0 ms");
    }

    @Test
    void assertMet_onAHit_returnsTheReportAndDescribesTheMargin() {
        var clock = new AtomicLong();
        var report = PollUntil.pollUntil(() -> 1, value -> true, DEADLINE, INTERVAL, clock::get, clock::addAndGet).assertMet("one");

        assertThat(report.describe("one")).isEqualTo("met: one (first=1, final=1, polls=1, elapsed=0 ms, margin=50 ms)");
    }

    @Test
    void pollUntil_realClock_aMissEndsAtOrPastTheDeadline() {
        var report = PollUntil.pollUntil(() -> 0, value -> false, Duration.ofMillis(30), Duration.ofMillis(5));

        assertThat(report.met()).isFalse();
        assertThat(report.margin()).as("the loop only exits once the deadline has passed").isLessThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void pollUntil_realClock_aHitReportsPositiveMargin() {
        var counter = new AtomicInteger();

        var report = PollUntil.pollUntil(counter::incrementAndGet, value -> value >= 3, Duration.ofSeconds(30)).assertMet("three polls");

        assertThat(report.polls()).isEqualTo(3);
        assertThat(report.margin().isPositive()).isTrue();
    }

    @Test
    void pollUntil_pausesAreMinOfIntervalAndTimeLeft_soTheWaitEndsAtTheDeadline() {
        var clock = new AtomicLong();
        var pauses = new ArrayList<Long>();

        PollUntil.pollUntil(() -> 0, value -> false, DEADLINE, INTERVAL, clock::get, nanos -> {
            pauses.add(nanos);
            clock.addAndGet(nanos);
        });

        assertThat(pauses).as("20 ms, 20 ms, then only the 10 ms that are left, never the full interval")
                          .containsExactly(Duration.ofMillis(20).toNanos(), Duration.ofMillis(20).toNanos(), Duration.ofMillis(10).toNanos());
    }

    @Test
    void pollUntil_atTheDeadlineExactly_stopsAndDoesNotPollAgain() {
        var clock = new AtomicLong();
        var counter = new AtomicInteger();

        var report = PollUntil.pollUntil(() -> {
            if (counter.incrementAndGet() > 10) {
                throw new IllegalStateException("polled again at or past the deadline: `<` became `<=`");
            }
            return 0;
        }, value -> false, DEADLINE, INTERVAL, clock::get, clock::addAndGet);

        assertThat(report.polls()).isEqualTo(4);
        assertThat(report.margin()).isEqualTo(Duration.ZERO);
    }

    @Test
    void pollUntil_aZeroOrNegativeInterval_isRejectedBeforeAnyPoll() {
        var polled = new AtomicInteger();

        assertThatIllegalArgumentException().isThrownBy(() -> PollUntil.pollUntil(polled::incrementAndGet, value -> true, DEADLINE, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> PollUntil.pollUntil(polled::incrementAndGet, value -> true, DEADLINE, Duration.ofMillis(-1)));
        assertThat(polled).hasValue(0);
    }

    @Test
    void defaultInterval_isTenMilliseconds() {
        assertThat(PollUntil.DEFAULT_INTERVAL).isEqualTo(Duration.ofMillis(10));
    }

    @Test
    void pollUntil_realPause_theDefaultPauseActuallyWaits() {
        var counter = new AtomicInteger();

        var report = PollUntil.pollUntil(counter::incrementAndGet, value -> false, Duration.ofMillis(300), Duration.ofMillis(100));

        assertThat(report.polls()).as("a no-op pause would poll millions of times; a 100 ms pause polls about 4").isBetween(2, 20);
        assertThat(report.margin()).as("the wait ran to the deadline").isLessThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void pollUntil_aBlockingSupplier_failsAtTheDeadlineWithTheReportAndIsCancelled() throws InterruptedException {
        var cancelled = new CountDownLatch(1);

        assertThatThrownBy(() -> PollUntil.pollUntil(() -> blockUnlessCancelled(cancelled), value -> true, Duration.ofMillis(200)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("supplier blocked past the deadline")
            .hasMessageContaining("polls=0");
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).as("the blocked supplier was interrupted").isTrue();
    }

    @Test
    void pollUntil_anInterruptedWaiter_restoresTheFlagAndFailsAtOnce() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> PollUntil.pollUntil(() -> blockUnlessCancelled(new CountDownLatch(1)), value -> true, Duration.ofSeconds(5)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("interrupted while waiting");
            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt flag is restored").isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void pollUntil_aSupplierException_propagatesRawOnTheFirstThrow() {
        var failure = new IllegalStateException("boom");
        var polls = new AtomicInteger();

        assertThatThrownBy(() -> PollUntil.pollUntil(() -> {
            polls.incrementAndGet();
            throw failure;
        }, value -> true, Duration.ofSeconds(5))).isSameAs(failure);
        assertThat(polls).as("no retry").hasValue(1);
    }

    private static int blockUnlessCancelled(CountDownLatch cancelled) {
        try {
            new CountDownLatch(1).await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            cancelled.countDown();
        }
        return 0;
    }
}
