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
package org.pragmatica.dht;

import java.util.concurrent.ScheduledFuture;
import java.util.function.BiFunction;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The absent-grace timer of an opted-in read ([ReadOptions#absentGrace]). R empty answers are in: the
/// remaining original replica(s) get at most the grace window, never longer than what is left of the read's
/// own deadline, and then the read reports absent. The timer is cancelled as soon as the read settles by any
/// other route (a value found, every replica answered, a failure), so a settled read leaves nothing scheduled.
///
/// The scheduler is a parameter so the delay and the cancellation are observable without sleeping.
public sealed interface AbsentGrace {
    /// Schedule the grace timer for `collector`.
    ///
    /// @param collector     the read's collector; resolved with its best (empty) value when the timer fires
    /// @param settled       the read's promise; settling it cancels the timer
    /// @param options       the read's options, carrying the grace
    /// @param deadlineNanos absolute `System.nanoTime()` of the read's own deadline
    /// @param scheduler     `(task, delay) -> future`, production passes `SharedScheduler::schedule`
    static Unit schedule(QuorumCollector<Option<byte[]>> collector,
                         Promise<Option<byte[]>> settled,
                         ReadOptions options,
                         long deadlineNanos,
                         BiFunction<Runnable, TimeSpan, ScheduledFuture<?>> scheduler) {
        var timer = scheduler.apply(collector::resolveWithBest, graceDelay(options, deadlineNanos));
        var _ = settled.onResultRun(() -> timer.cancel(false));

        return unit();
    }

    /// `min(grace, remaining deadline)`, never negative.
    static TimeSpan graceDelay(ReadOptions options, long deadlineNanos) {
        var remaining = deadlineNanos - System.nanoTime();

        return timeSpan(Math.max(0,
                                 Math.min(options.absentGrace().nanos(),
                                          remaining))).nanos();
    }

    record unused() implements AbsentGrace {}
}
