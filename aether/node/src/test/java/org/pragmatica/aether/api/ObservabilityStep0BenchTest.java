// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


/// #277 STEP-0 micro-bench (manual, NOT a unit-test gate). Measures the per-invocation overhead of the
/// observability strategy-cell dispatch seam across the four postures, plus a counter-contention probe to
/// justify or refute swapping the counting facet's AtomicLong for a LongAdder. In-JVM, single-file, no JMH
/// (not in the tree). Enable by removing @Disabled, run in isolation, and read ns/op from stdout. Numbers
/// are indicative (wall-clock nanoTime bracketing after warmup, one JVM), not publication-grade.
///
/// (a) IDENTITY cell        — the OFF fast path (one volatile read + one invoke).
/// (b) counting baseline    — the metrics facet (one incrementAndGet) around the call.
/// (c) fleet baseline unsmpl — full ambient facets, context NOT sampled (sampler tick + capture, no record/log).
/// (d) fleet baseline sampld — full ambient facets, context sampled (records an InvocationNode; log suppressed OFF).
/// (e) AtomicLong vs LongAdder under 8-thread contention (+ single-thread cost).
@Disabled("manual bench — run explicitly; prints ns/op to stdout")
class ObservabilityStep0BenchTest {
    private static final String ARTIFACT = "com.example:bench-slice";
    private static final String METHOD = "op";
    private static final String NODE = "bench-node";
    private static final Object PAYLOAD = "x";
    private static final long ITERATIONS = 5_000_000L;
    private static final long WARMUP = 500_000L;
    private static final int THREADS = 8;
    private static final long PER_THREAD = 5_000_000L;

    private static long blackhole;

    @Test
    void step0_overhead_bench() throws InterruptedException {
        var previousLevel = setTraceLevel(Level.OFF);

        try {
            var identityCell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
            var countingCell = registeredCell(registry(ObservabilityBaseline.countingOnly()));
            var fleetCell = registeredCell(registry(ObservabilityBaseline.fleet(AdaptiveSampler.adaptiveSampler(Integer.MAX_VALUE),
                                                                                InvocationTraceStore.invocationTraceStore(),
                                                                                NODE,
                                                                                1)));

            System.out.println("=== #277 STEP-0 strategy-cell overhead (" + ITERATIONS + " iterations, 1 JVM) ===");
            benchCell("(a) IDENTITY cell         ", identityCell, 0, false);
            benchCell("(b) counting baseline     ", countingCell, 0, false);
            benchCell("(c) fleet baseline unsmpl ", fleetCell, 1, false);
            benchCell("(d) fleet baseline sampld ", fleetCell, 0, false);
            benchCounters();
            System.out.println("(blackhole=" + blackhole + ")");
        } finally {
            setTraceLevel(previousLevel);
        }
    }

    private static void benchCell(String label, ObservabilityStrategyCell cell, int depth, boolean sampled) {
        blackhole += loop(cell, depth, sampled, WARMUP);
        var start = System.nanoTime();

        blackhole += loop(cell, depth, sampled, ITERATIONS);
        var elapsed = System.nanoTime() - start;

        System.out.println(label + " : " + format(elapsed, ITERATIONS) + " ns/op");
    }

    private static long loop(ObservabilityStrategyCell cell, int depth, boolean sampled, long iterations) {
        long sum = 0;

        for (long i = 0; i < iterations; i++) {
            var result = InvocationContext.runWithContext("bench", null, null, depth, sampled, () -> cell.around(ObservabilityStep0BenchTest::body).await());

            sum += System.identityHashCode(result);
        }

        return sum;
    }

    private static Promise<Object> body() {
        return Promise.success(PAYLOAD);
    }

    private static void benchCounters() throws InterruptedException {
        System.out.println("--- (e) counter contention (" + THREADS + " threads x " + PER_THREAD + " incr each) ---");

        var atomicSingle = new AtomicLong();
        var atomicSingleNs = timeThreads(1, () -> incrementAtomic(atomicSingle, PER_THREAD));
        var adderSingle = new LongAdder();
        var adderSingleNs = timeThreads(1, () -> incrementAdder(adderSingle, PER_THREAD));
        var atomic = new AtomicLong();
        var atomicNs = timeThreads(THREADS, () -> incrementAtomic(atomic, PER_THREAD));
        var adder = new LongAdder();
        var adderNs = timeThreads(THREADS, () -> incrementAdder(adder, PER_THREAD));

        System.out.println("(e) AtomicLong  1-thread : " + format(atomicSingleNs, PER_THREAD) + " ns/op (final=" + atomicSingle.get() + ")");
        System.out.println("(e) LongAdder   1-thread : " + format(adderSingleNs, PER_THREAD) + " ns/op (final=" + adderSingle.sum() + ")");
        System.out.println("(e) AtomicLong  " + THREADS + "-thread : " + format(atomicNs, THREADS * PER_THREAD) + " ns/op (final=" + atomic.get() + ")");
        System.out.println("(e) LongAdder   " + THREADS + "-thread : " + format(adderNs, THREADS * PER_THREAD) + " ns/op (final=" + adder.sum() + ")");
    }

    private static long timeThreads(int count, Runnable task) throws InterruptedException {
        var pool = new Thread[count];

        for (int i = 0; i < count; i++) {
            pool[i] = new Thread(task);
        }

        var start = System.nanoTime();

        for (var t : pool) {
            t.start();
        }

        for (var t : pool) {
            t.join();
        }

        return System.nanoTime() - start;
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void incrementAtomic(AtomicLong counter, long iterations) {
        for (long i = 0; i < iterations; i++) {
            counter.incrementAndGet();
        }
    }

    private static void incrementAdder(LongAdder counter, long iterations) {
        for (long i = 0; i < iterations; i++) {
            counter.increment();
        }
    }

    private static String format(long elapsedNs, long ops) {
        return String.format("%7.2f", (double) elapsedNs / ops);
    }

    private static ObservabilityConfigRegistry registry(ObservabilityBaseline baseline) {
        return ObservabilityConfigRegistry.observabilityConfigRegistry(clusterNodeStub(), kvStoreStub(), baseline);
    }

    private static ObservabilityStrategyCell registeredCell(ObservabilityConfigRegistry registry) {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        registry.register(cell);

        return cell;
    }

    private static Level setTraceLevel(Level level) {
        var ctx = (LoggerContext) LogManager.getContext(false);
        var config = ctx.getConfiguration();
        var loggerConfig = config.getLoggerConfig("org.pragmatica.aether.trace");
        var previous = loggerConfig.getLevel();

        loggerConfig.setLevel(level);
        ctx.updateLoggers();

        return previous;
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> kvStoreStub() {
        return Mockito.mock(KVStore.class);
    }

    @SuppressWarnings("unchecked")
    private static RabiaNode<KVCommand<AetherKey>> clusterNodeStub() {
        RabiaNode<KVCommand<AetherKey>> node = Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList()))
               .thenAnswer(_ -> Promise.success(List.of(Unit.unit())));

        return node;
    }
}
