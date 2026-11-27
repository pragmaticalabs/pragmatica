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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the pause helper against the defect it exists for (#3386): a `parkNanos` returns at once while the calling thread's interrupt flag
/// is set. The clock and the park are injected, and the injected park behaves as the JVM's does (it returns at once, without advancing the
/// clock, while the calling thread's flag is set), so no assertion depends on real time except the one real-clock control.
/// The class timeout turns a helper that never reaches its deadline into a red test, not a hang.
@Timeout(20)
class PauseTest {
    private static final Duration SPAN = Duration.ofMillis(100);
    private static final int SPIN_CAP = 1_000;

    /// A clock and a park that model `LockSupport.parkNanos` on the calling thread. `chunk` bounds how long one park really waits (a park
    /// that returns early without any flag, as a spurious wakeup does); the park count is capped so a spinning helper fails instead of hanging.
    private static final class ModelledThread {
        private final AtomicLong now = new AtomicLong();
        private final AtomicInteger parks = new AtomicInteger();
        private final long chunk;

        ModelledThread(long chunk) {
            this.chunk = chunk;
        }

        long now() {
            return now.get();
        }

        void park(long nanos) {
            if (parks.incrementAndGet() > SPIN_CAP) {
                throw new AssertionError("the helper spun: more than " + SPIN_CAP + " parks without the clock reaching the deadline");
            }
            if (!Thread.currentThread().isInterrupted()) {
                now.addAndGet(Math.min(nanos, chunk));
            }
        }
    }

    @AfterEach
    void clearTheFlag() {
        Thread.interrupted();
    }

    private static ModelledThread wholeSpanParks() {
        return new ModelledThread(Long.MAX_VALUE);
    }

    @Test
    void pause_flagSetOnEntry_doesNotReturnBeforeTheSpan() {
        var thread = wholeSpanParks();

        Thread.currentThread().interrupt();
        Pause.pause(SPAN, thread::now, thread::park);

        assertThat(thread.now()).as("the clock at return: the span has elapsed although the flag was set").isGreaterThanOrEqualTo(SPAN.toNanos());
    }

    @Test
    void pause_flagSetOnEntry_doesNotSpin_oneParkServesTheSpan() {
        var thread = wholeSpanParks();

        Thread.currentThread().interrupt();
        Pause.pause(SPAN, thread::now, thread::park);

        assertThat(thread.parks).as("parks with the flag set on entry").hasValue(1);
    }

    @Test
    void pause_flagSetOnEntry_keepsTheCallersFlag() {
        var thread = wholeSpanParks();

        Thread.currentThread().interrupt();
        Pause.pause(SPAN, thread::now, thread::park);

        assertThat(Thread.currentThread().isInterrupted()).as("the caller's flag after the pause").isTrue();
    }

    @Test
    void pause_noFlag_leavesNoFlag_andParksOnce() {
        var thread = wholeSpanParks();

        Pause.pause(SPAN, thread::now, thread::park);

        assertThat(Thread.currentThread().isInterrupted()).as("a flag the helper invented").isFalse();
        assertThat(thread.parks).hasValue(1);
        assertThat(thread.now()).isGreaterThanOrEqualTo(SPAN.toNanos());
    }

    @Test
    void pause_aParkThatReturnsEarly_parksAgainForTheRest() {
        var thread = new ModelledThread(Duration.ofMillis(30).toNanos());

        Pause.pause(SPAN, thread::now, thread::park);

        assertThat(thread.now()).isGreaterThanOrEqualTo(SPAN.toNanos());
        assertThat(thread.parks).as("100 ms in parks of at most 30 ms").hasValue(4);
    }

    @Test
    void pause_aParkThatReturnsEarlyUnderASetFlag_doesNotSpin_andKeepsTheFlag() {
        var thread = new ModelledThread(Duration.ofMillis(30).toNanos());

        Thread.currentThread().interrupt();
        Pause.pause(SPAN, thread::now, thread::park);

        assertThat(thread.now()).isGreaterThanOrEqualTo(SPAN.toNanos());
        assertThat(thread.parks).hasValue(4);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void pause_anInterruptArrivingWhilePausing_doesNotSpin_andIsKept() {
        var thread = wholeSpanParks();
        var arrivals = new AtomicInteger();

        Pause.pause(SPAN, thread::now, nanos -> {
            if (arrivals.getAndIncrement() == 0) {
                Thread.currentThread().interrupt();
                thread.now.addAndGet(nanos / 4);
            } else {
                thread.park(nanos);
            }
        });

        assertThat(thread.now()).isGreaterThanOrEqualTo(SPAN.toNanos());
        assertThat(arrivals).as("one park cut short by the interrupt, one for the rest").hasValue(2);
        assertThat(Thread.currentThread().isInterrupted()).as("the interrupt that arrived is not swallowed").isTrue();
    }

    @Test
    void pause_zeroOrNegativeSpan_doesNotParkAndLeavesTheFlagAlone() {
        var thread = wholeSpanParks();

        Thread.currentThread().interrupt();
        Pause.pause(Duration.ZERO, thread::now, thread::park);
        Pause.pause(Duration.ofMillis(-5), thread::now, thread::park);

        assertThat(thread.parks).hasValue(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void pause_realClock_flagSet_parksTheWholeSpan_andKeepsTheFlag() {
        var span = Duration.ofMillis(50);
        var started = System.nanoTime();

        Thread.currentThread().interrupt();
        Pause.pause(span);

        var parkedFor = System.nanoTime() - started;

        assertThat(parkedFor).as("nanoseconds parked with the flag set: park-to-deadline never returns early").isGreaterThanOrEqualTo(span.toNanos());
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
