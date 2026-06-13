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
import org.pragmatica.consensus.NodeId;


import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.hlc.HlcTimestamp.pack;

class HlcClockTest {

    @Test
    void defaultClock_now_isStrictlyMonotonicAcrossBurst() {
        var clock = HlcClock.hlcClock(new NodeId("n"));
        var prev = clock.now();
        for (var i = 0; i < 20_000; i++) {
            var cur = clock.now();
            assertThat(cur.compareTo(prev)).isGreaterThan(0); // strictly increasing — counter must disambiguate same-ms events
            prev = cur;
        }
    }

    @Test
    void defaultClock_physicalComponent_tracksWallClockMillis() {
        var clock = HlcClock.hlcClock(new NodeId("n"));
        var before = System.currentTimeMillis();
        var ts = clock.now();
        var after = System.currentTimeMillis();
        var physical = ts.packed() >>> 16; // raw extraction, no overflow corruption allowed
        assertThat(physical).isBetween(before, after);
    }

    @Test
    void now_sequential_strictlyIncreasing() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), time::incrementAndGet, 500_000L);

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
        var clock = HlcClock.hlcClock(new NodeId("node-1"), fixedTime::get, 500_000L);

        var first = clock.now();
        var second = clock.now();
        var third = clock.now();

        assertThat(first.physicalMillis()).isEqualTo(5_000_000L);
        assertThat(first.counter()).isZero();
        assertThat(second.counter()).isEqualTo(1);
        assertThat(third.counter()).isEqualTo(2);
        assertThat(second.physicalMillis()).isEqualTo(first.physicalMillis());
    }

    @Test
    void now_physicalClockAdvances_resetsCounter() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), time::get, 500_000L);

        clock.now();
        clock.now();
        var beforeAdvance = clock.now();
        assertThat(beforeAdvance.counter()).isEqualTo(2);

        time.set(2_000_000L);
        var afterAdvance = clock.now();

        assertThat(afterAdvance.physicalMillis()).isEqualTo(2_000_000L);
        assertThat(afterAdvance.counter()).isZero();
        assertThat(afterAdvance.packed()).isGreaterThan(beforeAdvance.packed());
    }

    @Test
    void update_remoteCausality_resultGreaterThanRemote() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), time::get, 500_000L);

        var remote = new HlcTimestamp(pack(1_200_000L, 3), new NodeId("node-2"));

        clock.update(remote)
             .onFailure(c -> fail("Expected success: " + c.message()))
             .onSuccess(result -> assertThat(result.packed()).isGreaterThan(remote.packed()));
    }

    @Test
    void update_driftExceeded_returnsFailure() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), time::get, 500_000L);

        var farFuture = new HlcTimestamp(pack(2_000_000L, 0), new NodeId("node-2"));

        clock.update(farFuture)
             .onSuccess(_ -> fail("Expected failure for excessive drift"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(HlcError.ClockDriftExceeded.class));
    }

    @Test
    void update_counterOverflow_returnsFailure() {
        var fixedTime = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), fixedTime::get, 500_000L);

        // Drive the local counter to its maximum (0xFFFF) without overflowing:
        // merging remote 0xFFFE at the same physical time yields max(0, 0xFFFE) + 1 = 0xFFFF.
        var nearMax = new HlcTimestamp(pack(1_000_000L, 0xFFFE), new NodeId("node-2"));
        clock.update(nearMax)
             .onFailure(c -> fail("Expected success: " + c.message()));

        // Local counter is now 0xFFFF. Merging another remote at 0xFFFF at the same physical time
        // pushes the merged counter to 0x10000, past the 16-bit range, surfaced as a Cause.
        var overflowing = new HlcTimestamp(pack(1_000_000L, 0xFFFF), new NodeId("node-3"));
        clock.update(overflowing)
             .onSuccess(_ -> fail("Expected failure for counter overflow"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(HlcError.CounterOverflow.class));
    }

    @Test
    void now_counterOverflow_carriesToNextPhysicalTick() {
        var fixedTime = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), fixedTime::get, 500_000L);

        // Drive the local counter to its maximum at a fixed physical time.
        var remote = new HlcTimestamp(pack(1_000_000L, 0xFFFE), new NodeId("node-2"));
        clock.update(remote)
             .onFailure(c -> fail("Expected success: " + c.message()));

        // Counter is now 0xFFFF. A further local event at the same physical time must not throw —
        // it carries into the next physical millisecond with the counter reset to zero, keeping
        // the packed value strictly monotonic.
        var next = clock.now();

        assertThat(next.physicalMillis()).isEqualTo(1_000_001L);
        assertThat(next.counter()).isZero();
    }

    @Test
    void forceReset_resetsToCurrentPhysicalTime() {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), time::get, 500_000L);

        // Advance the clock with several events
        clock.now();
        clock.now();
        clock.now();

        time.set(3_000_000L);
        var reset = clock.forceReset();

        assertThat(reset.physicalMillis()).isEqualTo(3_000_000L);
        assertThat(reset.counter()).isZero();
        assertThat(reset.nodeId()).isEqualTo(new NodeId("node-1"));
    }

    @Test
    void now_concurrentAccess_allTimestampsUnique() throws InterruptedException {
        var time = new AtomicLong(1_000_000L);
        var clock = HlcClock.hlcClock(new NodeId("node-1"), time::incrementAndGet, 500_000L);

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
