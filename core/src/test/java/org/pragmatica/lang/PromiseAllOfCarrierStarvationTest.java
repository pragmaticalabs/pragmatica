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
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Reproduces #749/#750: [Promise#timeout(io.pragmatica.lang.io.TimeSpan)][Promise#timeout] schedules its
/// delayed-failure task on the same shared virtual-thread executor used for every other `.async()` offload
/// in the codebase ([Promise.AsyncExecutor]). If that pool's carriers are all pinned by unrelated CPU-bound
/// work, the timeout task cannot be dispatched at all, so a "bounded" operation is not actually bounded.
///
/// This test drives the exact shape [org.pragmatica.aether.ember.EmberCluster#stop()] uses in production:
/// N never-resolving promises, each wrapped in `.timeout(...)`, aggregated with [Promise#allOf(java.util.Collection)]
/// and reduced with `.map(...)` — not a bare `.timeout()` call, because `allOf`'s aggregation callback is what
/// determines whether the observable completion is itself starvation-safe (see #749 condition 1 analysis).
class PromiseAllOfCarrierStarvationTest {
    private static final AtomicLong SINK = new AtomicLong();

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void allOf_perNodeTimeout_firesWithinBoundDespiteCarrierSaturation() throws InterruptedException {
        var perNodeTimeout = timeSpan(800).millis();
        var nodeCount = 5;
        var busySpinHoldMillis = 3000L;
        var boundMillis = 2000L; // must stay well under busySpinHoldMillis to discriminate fixed vs. starved

        var carriers = Math.max(Runtime.getRuntime().availableProcessors(), 8);
        var saturators = carriers + 4; // guarantee every carrier is pinned regardless of scheduling order

        var stop = new AtomicBoolean(false);
        // Only `carriers`-many non-yielding tasks can ever be mounted at once: the +4 margin exists so
        // that WHICHEVER `carriers` of the `saturators` submissions win a carrier first, all carriers end
        // up pinned regardless of scheduling order -- the margin itself is never expected to start running
        // before the timed section (its virtual threads sit queued in the carrier pool with nothing to
        // mount them until a busy-spinner exits, which only happens after `stop` is set below). Gating on
        // `saturators` starts instead of `carriers` starts would wait on a precondition that cannot occur.
        var started = new CountDownLatch(carriers);
        var finished = new CountDownLatch(saturators);

        // Pin every virtual-thread carrier with non-yielding CPU-bound work, dispatched through the exact
        // same offload path (CompletionOnResult -> AsyncExecutor.INSTANCE.runAsync) that .timeout() shares.
        IntStream.range(0, saturators).forEach(_ -> {
            var gate = Promise.<Unit>promise();
            gate.onSuccessRun(() -> {
                started.countDown();
                busySpin(stop);
                finished.countDown();
            });
            gate.succeed(unit());
        });

        assertThat(started.await(5, TimeUnit.SECONDS))
            .as("all %d carriers must be pinned by a running saturator before the timed section begins", carriers)
            .isTrue();

        try {
            var nodeStops = IntStream.range(0, nodeCount)
                                      .mapToObj(_ -> Promise.<Unit>promise())
                                      .map(p -> p.timeout(perNodeTimeout))
                                      .toList();

            var start = System.nanoTime();
            var result = Promise.allOf(nodeStops)
                                 .map(_ -> unit())
                                 .await(timeSpan(busySpinHoldMillis + 5000).millis());
            var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(result.isSuccess())
                .as("allOf(...).map(...) must settle once every per-node timeout fires: %s", result)
                .isTrue();
            assertThat(elapsedMillis)
                .as("bounded stop() must resolve near its own %dms bound, not stall for the full %dms carrier-saturation window",
                    perNodeTimeout.millis(), busySpinHoldMillis)
                .isLessThan(boundMillis);
        } finally {
            stop.set(true);
            assertThat(finished.await(5, TimeUnit.SECONDS))
                .as("saturator cleanup: all busy-spin tasks must observe the stop flag and exit")
                .isTrue();
        }
    }

    private static void busySpin(AtomicBoolean stop) {
        var x = 0L;
        while (!stop.get()) {
            x = x * 1103515245L + 12345L;
        }
        SINK.addAndGet(x);
    }
}
