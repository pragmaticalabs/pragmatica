// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #3140: many partitions are refused at random (the rest are sent, which resets the shared peer hold so the holds stay short
/// and expire often) while threads hammer `admit` on all of them. A hold expires under a hammering admit and the redrive
/// timer at once, which is the window of the stale refusal check: no partition may be probed again sooner than the shortest
/// first hold (250 ms less 20% jitter) after a REFUSED probe. The deterministic pin of the repeated check is
/// [OwnerActivationHoldYieldTest]; this is the wiring-level companion, as `RefusalBackoffWiringTest` is for the consumer.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OwnerActivationHoldStressTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER = new NodeId("peer");
    private static final String STREAM = "orders";
    private static final int PARTITIONS = 300;
    private static final long RUN_MS = 6_000L;
    private static final long MIN_FIRST_HOLD_MS = 190L;

    private record Probe(long atNanos, boolean refused) {}

    private final ConcurrentHashMap<Integer, List<Probe>> probes = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    @RepeatedTest(3)
    void noPartitionIsProbedInsideItsRefusalHold() throws InterruptedException {
        var gate = gate();
        var hammers = IntStream.range(0, 4).mapToObj(_ -> Thread.ofPlatform().start(() -> hammer(gate))).toList();

        Thread.sleep(RUN_MS);
        stopped.set(true);
        for (var thread : hammers) {
            thread.join();
        }

        assertThat(probes).as("every partition was probed").hasSize(PARTITIONS);
        assertThat(shortestHoldMs()).as("shortest wait between a refused probe and the partition's next probe")
                                    .isGreaterThanOrEqualTo(MIN_FIRST_HOLD_MS);
    }

    private long shortestHoldMs() {
        return probes.values()
                     .stream()
                     .mapToLong(OwnerActivationHoldStressTest::shortestHoldMs)
                     .min()
                     .orElseThrow();
    }

    private static long shortestHoldMs(List<Probe> partitionProbes) {
        return IntStream.range(0, partitionProbes.size() - 1)
                        .filter(index -> partitionProbes.get(index).refused())
                        .mapToLong(index -> (partitionProbes.get(index + 1).atNanos() - partitionProbes.get(index).atNanos()) / 1_000_000L)
                        .min()
                        .orElse(Long.MAX_VALUE);
    }

    private void hammer(OwnerActivation gate) {
        while (!stopped.get()) {
            for (var partition = 0; partition < PARTITIONS; partition++) {
                gate.admit(STREAM, partition);
            }
            Thread.onSpinWait();
        }
    }

    private OwnerActivation gate() {
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);

        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> Option.some(record),
                                               (_, _) -> !stopped.get(),
                                               Option.none(),
                                               () -> List.of(SELF, PEER),
                                               (_, _, partition) -> probe(partition),
                                               (_, _) -> -1L,
                                               (_, _, _, _) -> StreamError.General.PARTITION_NOT_LOCAL.<Long>promise(),
                                               () -> true,
                                               (_, _, _, _, _) -> Promise.success(List.<OffHeapRingBuffer.RawEvent>of()),
                                               _ -> Unit.unit(),
                                               PromotionTestRanges.NEVER_ALARM);
    }

    private Promise<Long> probe(int partition) {
        var refused = ThreadLocalRandom.current().nextBoolean();

        probes.computeIfAbsent(partition, _ -> new CopyOnWriteArrayList<>()).add(new Probe(System.nanoTime(), refused));

        return refused
               ? StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(PEER)).promise()
               : StreamError.General.PARTITION_NOT_LOCAL.promise();
    }
}
