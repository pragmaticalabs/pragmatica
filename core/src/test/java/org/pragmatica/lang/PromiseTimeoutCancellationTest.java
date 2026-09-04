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

package org.pragmatica.lang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #749/#750 follow-up: `.timeout()`'s delayed-failure task used to be scheduled and then forgotten --
/// [Promise#timeout(io.pragmatica.lang.io.TimeSpan)] discarded the [java.util.concurrent.ScheduledFuture]
/// returned by the scheduler, so a promise that resolved microseconds after `.timeout()` was attached still
/// left its failure task sitting in [Promise.AsyncExecutor]'s queue for the full timeout duration. Harmless
/// per-promise (a late `.fail()` on an already-resolved promise is a CAS no-op), but unbounded in aggregate:
/// a server minting thousands of 30-second-timeout promises per second would retain thousands of dead
/// entries -- each holding a closure and a context-propagation snapshot -- at any given moment.
///
/// Assertions read [Promise.AsyncExecutor#pendingTimeoutCount()] (the scheduler's own delay-queue size)
/// immediately after scheduling and immediately after resolving, with no wait in between: this is a
/// same-thread, synchronous check, not a polled one, so a leftover task from an unrelated test elsewhere in
/// this JVM cannot satisfy it -- only a same-instant coincidental fire of an unrelated task could interfere,
/// and this suite runs single-threaded/sequential (no surefire or JUnit5 parallelism configured for `core`).
class PromiseTimeoutCancellationTest {
    @Test
    void timeout_earlyCompletion_cancelsAndPurgesScheduledTask() {
        var pendingBefore = AsyncExecutor.INSTANCE.pendingTimeoutCount();

        var promise = Promise.<Unit>promise();
        promise.timeout(timeSpan(30).seconds());

        assertThat(AsyncExecutor.INSTANCE.pendingTimeoutCount())
            .as("attaching .timeout() must queue exactly one delayed failure task")
            .isEqualTo(pendingBefore + 1);

        promise.succeed(unit());

        assertThat(AsyncExecutor.INSTANCE.pendingTimeoutCount())
            .as("early completion must cancel the scheduled failure task AND purge it from the queue immediately -- "
                + "not merely mark it cancelled for later reclamation at its original fire time")
            .isEqualTo(pendingBefore);
    }

    @Test
    void timeout_earlyFailure_cancelsAndPurgesScheduledTask() {
        var pendingBefore = AsyncExecutor.INSTANCE.pendingTimeoutCount();

        var promise = Promise.<Unit>promise();
        promise.timeout(timeSpan(30).seconds());

        promise.fail(new org.pragmatica.lang.io.CoreError.Fault("application failure, unrelated to the timeout"));

        assertThat(AsyncExecutor.INSTANCE.pendingTimeoutCount())
            .as("early application-side failure must cancel the scheduled failure task just like a success would")
            .isEqualTo(pendingBefore);
    }
}
