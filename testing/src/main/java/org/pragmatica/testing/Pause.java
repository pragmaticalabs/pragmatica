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
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Contract;


/// The shared pause for tests: park the calling thread until a deadline, whatever its interrupt flag says (#3386).
///
/// A bare `LockSupport.parkNanos` is not a sleep. It returns at once whenever the calling thread's interrupt flag is set (and the
/// call does not clear the flag), and early on a spurious wakeup or a left-over unpark permit. A test that sleeps with ONE such call
/// therefore waits for nothing on a thread an earlier test left interrupted, and asserts state that has not happened yet (#3384:
/// 500 ms and 100 ms sleeps that took 9 ms and 5 ms).
///
/// This pause keeps three promises, each pinned by `PauseTest`:
///  - it does not return before the span has elapsed, however the flag stood on entry and however often a park returns early;
///  - it does not spin: the caller's flag is taken off the thread for the wait (a set flag makes every park return at once, so a
///    re-parking loop around a set flag burns a core until the deadline), and one park serves the whole span;
///  - it keeps the flag: an interrupt that stood on entry, or arrived while pausing, is back on the thread when the pause returns,
///    so the caller still sees it. The pause never invents an interrupt and never swallows one.
///
/// Use it only where a plain pause is what the test means (a quiet period, pacing, "nothing happens"). A test that waits for a state it
/// then asserts waits for THAT state: [PollUntil]. The flake scan (`FlakeScanTest`, kind `PARK_SLEEP`) refuses a `parkNanos` call that
/// no loop encloses, in every test source of the repository.
public final class Pause {
    private Pause() {}

    /// Parks the calling thread for `span`: returns no earlier, keeps the interrupt flag. A zero or negative span returns at once.
    @Contract
    public static void pause(Duration span) {
        pause(span, System::nanoTime, LockSupport::parkNanos);
    }

    /// The injectable form: `clock` reads nanoseconds, `park` parks the calling thread for at most the given nanoseconds and may
    /// return early, as `LockSupport.parkNanos` does (at once while the thread's flag is set).
    @Contract
    public static void pause(Duration span, LongSupplier clock, LongConsumer park) {
        var total = span.toNanos();

        if (total > 0) {
            var started = clock.getAsLong();
            var carried = Thread.interrupted();
            var arrived = parkThrough(total, started, clock, park);

            restore(carried || arrived);
        }
    }

    /// Parks until `total` nanoseconds have passed since `started`; reports whether an interrupt arrived meanwhile (cleared again, so the
    /// next park waits instead of returning at once).
    private static boolean parkThrough(long total, long started, LongSupplier clock, LongConsumer park) {
        var arrived = false;
        var remaining = total;

        while (remaining > 0) {
            park.accept(remaining);
            arrived |= Thread.interrupted();
            remaining = total - (clock.getAsLong() - started);
        }

        return arrived;
    }

    @Contract
    private static void restore(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
