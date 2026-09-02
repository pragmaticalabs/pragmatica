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

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DeadlineTest {
    private final AtomicLong nowNanos = new AtomicLong(0);
    private final TimeSource clock = nowNanos::get;

    private void advanceMillis(long millis) {
        nowNanos.addAndGet(millis * 1_000_000L);
    }

    @Test
    void startingNow_beforeAnyTimePasses_reportsFullBudget() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);

        assertThat(deadline.isBounded()).isTrue();
        assertThat(deadline.remaining()).isEqualTo(timeSpan(10).seconds());
    }

    @Test
    void remaining_afterTimeAdvances_shrinksByElapsed() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);

        advanceMillis(4_000);

        assertThat(deadline.remaining()).isEqualTo(timeSpan(6).seconds());
    }

    @Test
    void remaining_pastTheDeadline_clampsToZeroNotNegative() {
        var deadline = Deadline.startingNow(timeSpan(1).seconds(), clock);

        advanceMillis(5_000);

        assertThat(deadline.remaining().nanos()).isZero();
    }

    @Test
    void bounded_withAmpleRemaining_keepsTheLayerDefault() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);

        assertThat(deadline.bounded(timeSpan(5).seconds())).isEqualTo(timeSpan(5).seconds());
    }

    @Test
    void bounded_withLessRemainingThanDefault_capsAtRemaining() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);

        advanceMillis(8_000);

        assertThat(deadline.bounded(timeSpan(5).seconds())).isEqualTo(timeSpan(2).seconds());
    }

    @Test
    void remainingShare_dividesRemainingAcrossAttempts() {
        var deadline = Deadline.startingNow(timeSpan(8).seconds(), clock);

        assertThat(deadline.remainingShare(4)).isEqualTo(timeSpan(2).seconds());
    }

    @Test
    void remainingShare_pastTheDeadline_isAtLeastOneNano_neverZero() {
        var deadline = Deadline.startingNow(timeSpan(1).seconds(), clock);

        advanceMillis(5_000);

        assertThat(deadline.remainingShare(4)).isEqualTo(timeSpan(1).nanos());
    }

    @Test
    void remainingShare_withNonPositiveParts_countsAsOnePart_noArithmeticException() {
        var deadline = Deadline.startingNow(timeSpan(8).seconds(), clock);

        assertThat(deadline.remainingShare(0)).isEqualTo(timeSpan(8).seconds());
        assertThat(deadline.remainingShare(-3)).isEqualTo(timeSpan(8).seconds());
    }

    @Test
    void expired_aboveTheFloor_isFalse() {
        var deadline = Deadline.startingNow(timeSpan(1).seconds(), clock);

        assertThat(deadline.expired(timeSpan(200).millis())).isFalse();
    }

    @Test
    void expired_atOrBelowTheFloor_isTrue() {
        var deadline = Deadline.startingNow(timeSpan(1).seconds(), clock);

        advanceMillis(800);

        assertThat(deadline.expired(timeSpan(200).millis())).isTrue();
    }

    @Test
    void expired_zeroFloorForm_flipsOnlyWhenNothingRemains() {
        var deadline = Deadline.startingNow(timeSpan(1).seconds(), clock);

        assertThat(deadline.expired()).isFalse();
        advanceMillis(1_000);
        assertThat(deadline.expired()).isTrue();
    }

    @Test
    void toWireMillis_bounded_carriesRemaining() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);

        advanceMillis(3_000);

        assertThat(deadline.toWireMillis()).isEqualTo(7_000L);
    }

    @Test
    void unbounded_neverExpiresAndKeepsDefaults() {
        var deadline = Deadline.unbounded();

        assertThat(deadline.isBounded()).isFalse();
        assertThat(deadline.expired(timeSpan(Long.MAX_VALUE - 1).nanos())).isFalse();
        assertThat(deadline.bounded(timeSpan(5).seconds())).isEqualTo(timeSpan(5).seconds());
        assertThat(deadline.toWireMillis()).isEqualTo(Deadline.NO_BUDGET);
    }

    @Test
    void fromWireMillis_negative_mapsToUnbounded() {
        assertThat(Deadline.fromWireMillis(Deadline.NO_BUDGET, clock).isBounded()).isFalse();
        assertThat(Deadline.fromWireMillis(-42, clock).isBounded()).isFalse();
    }

    @Test
    void fromWireMillis_nonNegative_restartsTheBudgetOnTheLocalClock() {
        var deadline = Deadline.fromWireMillis(2_500, clock);

        assertThat(deadline.isBounded()).isTrue();
        assertThat(deadline.remaining()).isEqualTo(timeSpan(2_500).millis());
        advanceMillis(1_000);
        assertThat(deadline.remaining()).isEqualTo(timeSpan(1_500).millis());
    }

    @Test
    void current_outsideAnyScope_isUnbounded() {
        assertThat(Deadline.current().isBounded()).isFalse();
    }

    @Test
    void runWith_insideTheScope_currentReturnsTheBoundDeadline() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);

        var seen = Deadline.runWith(deadline, Deadline::current);

        assertThat(seen).isSameAs(deadline);
    }

    @Test
    void runWith_nestedScopes_innerBindingWins() {
        var outer = Deadline.startingNow(timeSpan(10).seconds(), clock);
        var inner = Deadline.startingNow(timeSpan(2).seconds(), clock);

        var seen = Deadline.runWith(outer, () -> Deadline.runWith(inner, Deadline::current));

        assertThat(seen).isSameAs(inner);
    }

    @Test
    void runWith_afterTheScopeExits_currentIsUnboundedAgain() {
        var deadline = Deadline.startingNow(timeSpan(10).seconds(), clock);
        var insideScope = Deadline.runWith(deadline, Deadline::current);

        assertThat(insideScope).isSameAs(deadline);
        assertThat(Deadline.current().isBounded()).isFalse();
    }
}
