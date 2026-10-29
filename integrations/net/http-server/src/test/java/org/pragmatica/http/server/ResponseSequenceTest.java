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
package org.pragmatica.http.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2484: the sequence's own contract, with several threads: whichever thread completes a slot, the writes run in slot order,
/// exactly once, and a slot reserved concurrently is not lost. The executor has four threads on purpose: the sequence makes no
/// assumption about its executor (in the server it is the channel's single-threaded event loop), so the locks that keep the
/// queue consistent are exercised here.
class ResponseSequenceTest {
    private static final int SLOTS = 2000;
    private final ScheduledExecutorService loop = Executors.newScheduledThreadPool(4);
    private final ScheduledExecutorService completers = Executors.newScheduledThreadPool(8);

    @AfterEach
    void stop() {
        loop.shutdownNow();
        completers.shutdownNow();
    }

    @Test
    void completedFromManyThreadsInAnyOrder_writesRunInSlotOrder_exactlyOnce() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(0).millis());
        var slots = IntStream.range(0, SLOTS).mapToObj(_ -> sequence.reserve()).toList();
        var written = Collections.synchronizedList(new ArrayList<Integer>());
        var done = new CountDownLatch(SLOTS);
        var order = new ArrayList<>(IntStream.range(0, SLOTS).boxed().toList());

        Collections.shuffle(order, new java.util.Random(7));
        order.forEach(index -> completers.execute(() -> sequence.complete(slots.get(index), () -> record(written, done, index), false)));

        assertThat(done.await(20, TimeUnit.SECONDS)).as("every write ran").isTrue();
        assertThat(written).containsExactlyElementsOf(IntStream.range(0, SLOTS).boxed().toList());
    }

    @Test
    void slotsReservedFromManyThreadsAtOnce_areNotLost() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(0).millis());
        var threads = 8;
        var reserved = Collections.synchronizedList(new ArrayList<ResponseSequence.Slot>());
        var reserving = new CountDownLatch(threads);

        for (var t = 0; t < threads; t++) {
            completers.execute(() -> reserveMany(sequence, reserved, reserving));
        }

        assertThat(reserving.await(20, TimeUnit.SECONDS)).isTrue();

        var writes = new CountDownLatch(threads * SLOTS);

        reserved.forEach(slot -> sequence.complete(slot, writes::countDown, false));

        assertThat(writes.await(20, TimeUnit.SECONDS)).as("all %d reserved slots were written", threads * SLOTS).isTrue();
    }

    @Test
    void aSlotThatIsNotReady_holdsTheReadyOnesBehindIt() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(0).millis());
        var first = sequence.reserve();
        var second = sequence.reserve();
        var written = Collections.synchronizedList(new ArrayList<String>());
        var both = new CountDownLatch(2);

        sequence.complete(second, () -> record(written, both, "second"), false);
        Thread.sleep(200);

        assertThat(written).as("nothing is written while the first slot is open").isEmpty();

        sequence.complete(first, () -> record(written, both, "first"), false);

        assertThat(both.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(written).containsExactly("first", "second");
    }

    @Test
    void aWriteThatClosesTheConnection_endsTheSequence() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(0).millis());
        var closing = sequence.reserve();
        var behind = sequence.reserve();
        var written = Collections.synchronizedList(new ArrayList<String>());
        var first = new CountDownLatch(1);

        sequence.complete(behind, () -> written.add("behind"), false);
        sequence.complete(closing, () -> record(written, first, "closing"), true);

        assertThat(first.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        assertThat(written).containsExactly("closing");
    }

    @Test
    void aSlotNotCompletedInTime_isCompletedByItsGuard_andALateCompletionIsIgnored() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(100).millis());
        var slot = sequence.reserve();
        var written = Collections.synchronizedList(new ArrayList<String>());
        var guarded = new CountDownLatch(1);

        sequence.guard(slot, () -> sequence.complete(slot, () -> record(written, guarded, "timeout"), true));

        assertThat(guarded.await(10, TimeUnit.SECONDS)).as("the guard answered").isTrue();
        sequence.complete(slot, () -> written.add("late"), false);
        Thread.sleep(200);
        assertThat(written).containsExactly("timeout");
    }

    @Test
    void aSlotCompletedBeforeItsTimeout_isNotAnsweredByTheGuard() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(300).millis());
        var slot = sequence.reserve();
        var written = Collections.synchronizedList(new ArrayList<String>());
        var done = new CountDownLatch(1);

        sequence.guard(slot, () -> sequence.complete(slot, () -> written.add("timeout"), true));
        sequence.complete(slot, () -> record(written, done, "answer"), false);

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(600);
        assertThat(written).containsExactly("answer");
    }

    @Test
    void aSecondCompletionOfAPendingSlot_isIgnored_theFirstOneCounts() throws InterruptedException {
        var sequence = new ResponseSequence(loop, timeSpan(0).millis());
        var first = sequence.reserve();
        var second = sequence.reserve();
        var written = Collections.synchronizedList(new ArrayList<String>());
        var all = new CountDownLatch(2);

        sequence.complete(second, () -> record(written, all, "answer"), false);
        sequence.complete(second, () -> record(written, all, "late"), false);
        sequence.complete(first, () -> record(written, all, "first"), false);

        assertThat(all.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        assertThat(written).containsExactly("first", "answer");
    }

    @Test
    void aSlotCompletedBeforeItsTimeout_cancelsItsTimer() {
        var timers = new java.util.concurrent.ScheduledThreadPoolExecutor(1);

        timers.setRemoveOnCancelPolicy(true);

        try {
            var sequence = new ResponseSequence(timers, timeSpan(60).seconds());
            var slot = sequence.reserve();

            sequence.guard(slot, () -> {});
            assertThat(timers.getQueue()).as("the timer is scheduled").hasSize(1);
            sequence.complete(slot, () -> {}, false);

            assertThat(timers.getQueue().stream().filter(task -> task instanceof java.util.concurrent.ScheduledFuture<?> f && f.getDelay(TimeUnit.SECONDS) > 30)).as("the 60 s timer is cancelled").isEmpty();
        } finally {
            timers.shutdownNow();
        }
    }

    private static void reserveMany(ResponseSequence sequence, List<ResponseSequence.Slot> into, CountDownLatch finished) {
        for (var i = 0; i < SLOTS; i++) {
            into.add(sequence.reserve());
        }
        finished.countDown();
    }

    private static <T> void record(List<T> written, CountDownLatch done, T value) {
        written.add(value);
        done.countDown();
    }
}
