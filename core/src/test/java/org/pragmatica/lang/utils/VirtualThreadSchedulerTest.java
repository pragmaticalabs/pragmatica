/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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

package org.pragmatica.lang.utils;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class VirtualThreadSchedulerTest {

    private static TimeSpan ms(long millis) {
        return timeSpan(millis).millis();
    }

    // ---- G1: cancel before dispatch prevents the run -------------------------------------

    @Test
    void cancel_beforeDispatch_bodyNeverRuns() throws InterruptedException {
        var scheduler = new VirtualThreadScheduler();
        try {
            var ran = new AtomicBoolean(false);
            var handle = scheduler.schedule(() -> ran.set(true), ms(200));

            Thread.sleep(50);
            assertThat(handle.cancel(false)).isTrue();

            Thread.sleep(400); // well past the original 200ms deadline
            assertThat(ran.get()).as("cancelled one-shot must never run").isFalse();
            assertThat(handle.isCancelled()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }

    // ---- G2: cancel stops all future occurrences -----------------------------------------

    @Test
    void cancel_periodic_stopsFutureOccurrences() throws InterruptedException {
        var scheduler = new VirtualThreadScheduler();
        try {
            var count = new AtomicInteger(0);
            var handle = scheduler.scheduleAtFixedRate(count::incrementAndGet, ms(100));

            Thread.sleep(350); // ~3 runs
            handle.cancel(false);
            var atCancel = count.get();
            assertThat(atCancel).as("at least one run before cancel").isGreaterThanOrEqualTo(1);

            Thread.sleep(400); // would be ~4 more runs if not cancelled
            assertThat(count.get()).as("no runs after cancel").isEqualTo(atCancel);
        } finally {
            scheduler.shutdown();
        }
    }

    // ---- Non-overlap: a slow body never overlaps itself ----------------------------------

    @Test
    void periodic_slowBody_neverOverlaps() throws InterruptedException {
        var scheduler = new VirtualThreadScheduler();
        try {
            var concurrent = new AtomicInteger(0);
            var maxConcurrent = new AtomicInteger(0);

            var handle = scheduler.scheduleAtFixedRate(() -> {
                var inside = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(inside, Math::max);
                sleepQuietly(300);
                concurrent.decrementAndGet();
            }, ms(50));

            Thread.sleep(1500); // interval 50ms, body 300ms => heavy overlap pressure
            handle.cancel(false);

            assertThat(maxConcurrent.get())
                .as("non-overlap guard: at most one concurrent invocation")
                .isLessThanOrEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    // ---- Microbench: VT holds cadence under load that starves STPE -----------------------

    @Test
    void schedulerStarvation_vtHoldsCadence_stpeDoesNot() throws InterruptedException {
        var cores = Runtime.getRuntime().availableProcessors();
        var pool = Math.max(cores, 8);
        var detectors = 20;     // M
        var noisyTenants = 24;  // K (> pool, so STPE workers are all consumed)
        var detectInterval = ms(500);
        var runMillis = 16_000L;

        System.out.printf("%n=== Scheduler starvation bench (cores=%d, STPE pool=%d, detectors=%d, noisy=%d, run=%ds) ===%n",
                          cores, pool, detectors, noisyTenants, runMillis / 1000);

        var stpeLag = runStpe(pool, detectors, noisyTenants, detectInterval, runMillis);
        var vtLag = runVt(detectors, noisyTenants, detectInterval, runMillis);

        printRow("STPE", stpeLag);
        printRow("VT  ", vtLag);

        // Machine-independent hard assertion on VT only.
        var vtMax = max(vtLag);
        assertThat(vtMax)
            .as("VT detection max-lag must stay tight under noisy-tenant load (ms)")
            .isLessThan(250.0);
    }

    private static List<Long> runStpe(int pool, int detectors, int noisy, TimeSpan interval, long runMillis)
        throws InterruptedException {
        var lags = new ConcurrentLinkedQueue<Long>();
        var stpe = new ScheduledThreadPoolExecutor(pool);
        try {
            scheduleDetectorsStpe(stpe, detectors, interval, lags);
            scheduleNoiseStpe(stpe, noisy, interval);
            Thread.sleep(runMillis);
        } finally {
            stpe.shutdownNow();
        }
        return new ArrayList<>(lags);
    }

    private static void scheduleDetectorsStpe(ScheduledThreadPoolExecutor stpe, int detectors,
                                              TimeSpan interval, ConcurrentLinkedQueue<Long> lags) {
        for (var i = 0; i < detectors; i++) {
            var expected = new long[]{System.nanoTime() + interval.nanos()};
            stpe.scheduleAtFixedRate(() -> recordDetectorLag(expected, interval, lags),
                                     interval.millis(), interval.millis(), TimeUnit.MILLISECONDS);
        }
    }

    private static void scheduleNoiseStpe(ScheduledThreadPoolExecutor stpe, int noisy, TimeSpan interval) {
        for (var i = 0; i < noisy; i++) {
            stpe.scheduleAtFixedRate(VirtualThreadSchedulerTest::noisyBody,
                                     0, interval.millis(), TimeUnit.MILLISECONDS);
        }
    }

    private static List<Long> runVt(int detectors, int noisy, TimeSpan interval, long runMillis)
        throws InterruptedException {
        var lags = new ConcurrentLinkedQueue<Long>();
        var vt = new VirtualThreadScheduler();
        try {
            scheduleDetectorsVt(vt, detectors, interval, lags);
            scheduleNoiseVt(vt, noisy, interval);
            Thread.sleep(runMillis);
        } finally {
            vt.shutdown();
        }
        return new ArrayList<>(lags);
    }

    private static void scheduleDetectorsVt(VirtualThreadScheduler vt, int detectors,
                                            TimeSpan interval, ConcurrentLinkedQueue<Long> lags) {
        for (var i = 0; i < detectors; i++) {
            var expected = new long[]{System.nanoTime() + interval.nanos()};
            vt.scheduleAtFixedRate(() -> recordDetectorLag(expected, interval, lags), interval);
        }
    }

    private static void scheduleNoiseVt(VirtualThreadScheduler vt, int noisy, TimeSpan interval) {
        for (var i = 0; i < noisy; i++) {
            vt.scheduleAtFixedRate(VirtualThreadSchedulerTest::noisyBody, interval);
        }
    }

    private static void recordDetectorLag(long[] expected, TimeSpan interval, ConcurrentLinkedQueue<Long> lags) {
        var now = System.nanoTime();
        lags.add(now - expected[0]);
        expected[0] += interval.nanos();
    }

    // 2-4s blocking body: starves a fixed STPE pool, harmless to a VT-per-task executor.
    private static void noisyBody() {
        sleepQuietly(2000 + (long) (Math.random() * 2000));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printRow(String label, List<Long> lagNanos) {
        System.out.printf("%s detection-lag  samples=%-5d  p50=%8.1fms  p99=%8.1fms  max=%8.1fms%n",
                          label, lagNanos.size(), pct(lagNanos, 50), pct(lagNanos, 99), max(lagNanos));
    }

    private static double pct(List<Long> nanos, int p) {
        if (nanos.isEmpty()) {
            return 0.0;
        }
        var sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        var idx = (int) Math.min(sorted.size() - 1L, Math.round((p / 100.0) * (sorted.size() - 1)));
        return sorted.get(idx) / 1_000_000.0;
    }

    private static double max(List<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).max().orElse(0L) / 1_000_000.0;
    }
}
