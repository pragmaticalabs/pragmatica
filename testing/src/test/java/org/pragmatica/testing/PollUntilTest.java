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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void pollUntil_missesTheDeadline_reportsNegativeMarginAndBothValues() {
        var clock = new AtomicLong();
        var counter = new AtomicInteger();

        var report = PollUntil.pollUntil(counter::incrementAndGet, value -> false, DEADLINE, INTERVAL, clock::get, clock::addAndGet);

        assertThat(report.met()).isFalse();
        assertThat(report.polls()).isEqualTo(4);
        assertThat(report.firstValue()).isEqualTo(1);
        assertThat(report.finalValue()).isEqualTo(4);
        assertThat(report.elapsed()).isEqualTo(Duration.ofMillis(60));
        assertThat(report.margin()).isEqualTo(Duration.ofMillis(-10));
        assertThat(report.margin().isNegative()).as("a miss overran the deadline").isTrue();
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
            .hasMessageContaining("elapsed=60 ms")
            .hasMessageContaining("margin=-10 ms");
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
}
