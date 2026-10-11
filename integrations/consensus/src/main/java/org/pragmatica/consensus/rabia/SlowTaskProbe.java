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
package org.pragmatica.consensus.rabia;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Names the call that holds the single consensus apply worker (#1117).
///
/// `RabiaEngine` logs `SLOW-APPLY` when a task finishes, so a task that blocks for ten seconds is silent for ten
/// seconds and, once it finishes, says how long it took but never where. The worker brackets each task with
/// [#begin] / [#end]; a watchdog thread calls [#check] and receives the worker's stack the first time one task has
/// been running past the threshold. A task is reported once, however long it runs.
final class SlowTaskProbe {
    private static final int MAX_FRAMES = 30;

    private final AtomicReference<Running> running = new AtomicReference<>();
    private volatile long reportedStart;

    @Contract
    void begin() {
        running.set(new Running(Thread.currentThread(), System.nanoTime()));
    }

    @Contract
    void end() {
        running.set(null);
    }

    /// The report for a task that has been running at least `thresholdNanos` at `nowNanos` and has not been reported
    /// yet; empty when the worker is idle, the task is still young, or its stack has already been reported.
    Option<String> check(long nowNanos, long thresholdNanos) {
        var current = running.get();

        if (current == null || nowNanos - current.startedNanos() < thresholdNanos || reportedStart == current.startedNanos()) {
            return Option.none();
        }

        reportedStart = current.startedNanos();

        return Option.some(report(current, nowNanos));
    }

    private static String report(Running current, long nowNanos) {
        var builder = new StringBuilder("ms=").append((nowNanos - current.startedNanos()) / 1_000_000L)
                                              .append(" thread=")
                                              .append(current.worker().getName())
                                              .append(" stack:");
        var frames = current.worker().getStackTrace();

        for (var index = 0; index < Math.min(frames.length, MAX_FRAMES); index++) {
            builder.append("\n\tat ").append(frames[index]);
        }

        return builder.toString();
    }

    private record Running(Thread worker, long startedNanos) {}
}
