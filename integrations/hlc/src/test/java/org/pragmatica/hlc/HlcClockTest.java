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

package org.pragmatica.hlc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.hlc.HlcTimestamp.pack;

class HlcClockTest {

    @Test
    void now_sequential_strictlyIncreasing() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", time::incrementAndGet, 500_000L).unwrap();

        var previous = clock.now();

        for (int i = 0; i < 100; i++) {
            var current = clock.now();
            assertThat(current.packed()).isGreaterThan(previous.packed());
            previous = current;
        }
    }

    @Test
    void now_samePhysicalTime_incrementsCounter() {
        var fixedTime = new AtomicLong(5_000_000L);
        var clock = HlcClock.hlcClock("node-1", fixedTime::get, 500_000L).unwrap();

        var first = clock.now();
        var second = clock.now();
        var third = clock.now();

        assertThat(first.physicalMicros()).isEqualTo(5_000_000L);
        assertThat(first.counter()).isZero();
        assertThat(second.counter()).isEqualTo(1);
        assertThat(third.counter()).isEqualTo(2);
        assertThat(second.physicalMicros()).isEqualTo(first.physicalMicros());
    }

    @Test
    void now_physicalClockAdvances_resetsCounter() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", time::get, 500_000L).unwrap();

        clock.now();
        clock.now();
        var beforeAdvance = clock.now();
        assertThat(beforeAdvance.counter()).isEqualTo(2);

        time.set(2_000_000L);
        var afterAdvance = clock.now();

        assertThat(afterAdvance.physicalMicros()).isEqualTo(2_000_000L);
        assertThat(afterAdvance.counter()).isZero();
        assertThat(afterAdvance.packed()).isGreaterThan(beforeAdvance.packed());
    }

    @Test
    void update_remoteCausality_resultGreaterThanRemote() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", time::get, 500_000L).unwrap();

        var remote = new HlcTimestamp(pack(1_200_000L, 3), "node-2");

        clock.update(remote)
             .onFailure(c -> fail("Expected success: " + c.message()))
             .onSuccess(result -> assertThat(result.packed()).isGreaterThan(remote.packed()));
    }

    @Test
    void update_driftExceeded_returnsFailure() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", time::get, 500_000L).unwrap();

        var farFuture = new HlcTimestamp(pack(2_000_000L, 0), "node-2");

        clock.update(farFuture)
             .onSuccess(_ -> fail("Expected failure for excessive drift"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(HlcError.ClockDriftExceeded.class));
    }

    @Test
    void update_counterOverflow_throwsException() {
        var fixedTime = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", fixedTime::get, 500_000L).unwrap();

        // Set remote to same physical time with counter near max
        var remote = new HlcTimestamp(pack(1_000_000L, 0xFFFE), "node-2");
        clock.update(remote)
             .onFailure(c -> fail("Expected success: " + c.message()));

        // Counter is now 0xFFFF. One more at the same physical time should overflow.
        assertThatThrownBy(clock::now).isInstanceOf(CounterOverflowException.class);
    }

    @Test
    void forceReset_resetsToCurrentPhysicalTime() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", time::get, 500_000L).unwrap();

        // Advance the clock with several events
        clock.now();
        clock.now();
        clock.now();

        time.set(3_000_000L);
        var reset = clock.forceReset();

        assertThat(reset.physicalMicros()).isEqualTo(3_000_000L);
        assertThat(reset.counter()).isZero();
        assertThat(reset.nodeId()).isEqualTo("node-1");
    }

    @Test
    void now_concurrentAccess_allTimestampsUnique() throws InterruptedException {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock("node-1", time::incrementAndGet, 500_000L).unwrap();

        Set<Long> packedValues = ConcurrentHashMap.newKeySet();
        var threadCount = 8;
        var eventsPerThread = 500;
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    packedValues.add(clock.now().packed());
                }
            });
        }

        for (var thread : threads) {
            thread.join();
        }

        assertThat(packedValues).hasSize(threadCount * eventsPerThread);
    }
}
