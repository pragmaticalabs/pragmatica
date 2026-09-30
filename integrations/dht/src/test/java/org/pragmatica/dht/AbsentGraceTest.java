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

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class AbsentGraceTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private record Fixture(ScheduledThreadPoolExecutor executor,
                           AtomicReference<TimeSpan> delay,
                           AtomicReference<ScheduledFuture<?>> timer,
                           Promise<Option<String>> settled,
                           QuorumCollector<Option<String>> collector) {}

    private static Fixture fixture() {
        Promise<Option<String>> settled = Promise.promise();

        return new Fixture(new ScheduledThreadPoolExecutor(1),
                           new AtomicReference<>(),
                           new AtomicReference<>(),
                           settled,
                           QuorumCollector.graceCollector(2, 3, settled, _ -> Unit.unit()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void schedule(Fixture f, ReadOptions options, long deadlineNanos) {
        AbsentGrace.schedule((QuorumCollector) f.collector(),
                             (Promise) f.settled(),
                             options,
                             deadlineNanos,
                             (task, delay) -> {
                                 f.delay().set(delay);
                                 var future = f.executor().schedule(task, delay.nanos(), TimeUnit.NANOSECONDS);
                                 f.timer().set(future);
                                 return future;
                             });
    }

    @Test
    void schedule_usesTheGrace_whenTheDeadlineIsFarAway() {
        var f = fixture();

        schedule(f, ReadOptions.absentGrace(timeSpan(1).seconds()), System.nanoTime() + 60 * SECOND);

        assertThat(f.delay().get().nanos()).isEqualTo(SECOND);
        f.executor().shutdownNow();
    }

    @Test
    void schedule_capsTheGraceAtTheRemainingDeadline() {
        var f = fixture();

        // grace 10s, but only ~200ms of the read's deadline is left
        schedule(f, ReadOptions.absentGrace(timeSpan(10).seconds()), System.nanoTime() + 200_000_000L);

        assertThat(f.delay().get().nanos()).isLessThanOrEqualTo(200_000_000L).isGreaterThanOrEqualTo(0L);
        f.executor().shutdownNow();
    }

    @Test
    void schedule_neverSchedulesANegativeDelay_pastTheDeadline() {
        var f = fixture();

        schedule(f, ReadOptions.absentGrace(timeSpan(10).seconds()), System.nanoTime() - SECOND);

        assertThat(f.delay().get().nanos()).isZero();
        f.executor().shutdownNow();
    }

    @Test
    void schedule_cancelsTheTimer_whenTheReadSettlesEarly() {
        var f = fixture();

        schedule(f, ReadOptions.absentGrace(timeSpan(30).seconds()), System.nanoTime() + 60 * SECOND);
        assertThat(f.timer().get().isCancelled()).isFalse();
        f.settled().succeed(Option.some("found"));

        // promise callbacks run on the promise's executor, so the cancel lands just after succeed returns
        var deadline = System.nanoTime() + SECOND;
        while (!f.timer().get().isCancelled() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(f.timer().get().isCancelled()).isTrue();
        f.executor().shutdownNow();
    }
}
