// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #2574 (know 89e17399a9): the consumer and the owner-activation gate hold by their `RefusalBackoff` after a refused read,
/// and RESET it once a read is sent. Scripted readers: three refusals (holds ~250, ~500, ~1000 ms), one sent read, then a
/// refusal again -- whose hold must be the first one (~250 ms), not the next doubling (~2000 ms). Red if a caller stops
/// resetting on a sent read, or stops holding at all.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RefusalBackoffWiringTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER = new NodeId("peer");
    private static final String STREAM = "orders";
    private static final int CONSUMERS = 10_000;

    /// Answers in order: refuse, refuse, refuse, a SENT read, refuse, ... and records when each read arrived. The sent read
    /// either succeeds (`sentSucceeds`) or fails with a cause that is not a refusal: both mean the frame was sent.
    private static class Script {
        private final boolean sentSucceeds;
        final List<Long> readsAtNanos = new CopyOnWriteArrayList<>();

        Script(boolean sentSucceeds) {
            this.sentSucceeds = sentSucceeds;
        }

        <T> Promise<T> next(T answer) {
            readsAtNanos.add(System.nanoTime());

            if (readsAtNanos.size() != 4) {
                return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(PEER)).promise();
            }

            return sentSucceeds
                   ? Promise.success(answer)
                   : StreamError.General.PARTITION_NOT_LOCAL.promise();
        }

        long gapMs(int from, int to) {
            return (readsAtNanos.get(to) - readsAtNanos.get(from)) / 1_000_000L;
        }
    }

    @Test
    void consumer_holdsAfterRefusals_andResetsOnceAReadSucceeds() {
        consumerHoldsAndResets(new Script(true));
    }

    @Test
    void consumer_holdsAfterRefusals_andResetsOnceAReadFailsWithoutARefusal() {
        consumerHoldsAndResets(new Script(false));
    }

    private static void consumerHoldsAndResets(Script script) {
        var manager = StreamPartitionManager.streamPartitionManager();

        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
        var runtime = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                                  DeadLetterHandler.deadLetterHandler(),
                                                                  inMemoryCursorStore(),
                                                                  scriptedReader(script));

        try {
            runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise());
            var tick = new AtomicInteger();

            driveUntil(() -> script.readsAtNanos.size() >= 6,
                       () -> manager.publishLocal(STREAM, 0, ("wake-" + tick.incrementAndGet()).getBytes(), tick.get()));
        } finally {
            runtime.close();
            manager.close();
        }

        assertHeldThenReset(script);
    }

    /// #2875: a trigger that checked the hold just before the refused pass parked the loop used to win `running` as the pass
    /// released it and re-read inside the hold (a first hold measured 0 ms). The window is nanoseconds wide, so many
    /// consumers are refused at once while appends hammer all of them: no consumer may re-read sooner than the shortest first
    /// hold (250 ms less 20% jitter) after its refused read.
    @RepeatedTest(5)
    void consumer_neverReadsInsideTheFirstHold_whileAppendsHammerManyLoops() throws InterruptedException {
        var manager = StreamPartitionManager.streamPartitionManager();
        var scripts = IntStream.range(0, CONSUMERS).mapToObj(_ -> new AlwaysRefuses()).toList();
        var stop = new AtomicBoolean();

        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1_000_000, 64 * 1024 * 1024, 60_000), "earliest"));
        var runtimes = scripts.stream()
                              .map(script -> StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                                                         DeadLetterHandler.deadLetterHandler(),
                                                                                         inMemoryCursorStore(),
                                                                                         scriptedReader(script)))
                              .toList();
        var hammers = IntStream.range(0, 4).mapToObj(_ -> Thread.ofPlatform().start(() -> hammer(manager, stop))).toList();

        try {
            runtimes.forEach(runtime -> runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise()));
            driveUntil(() -> scripts.stream().allMatch(script -> script.readsAtNanos.size() >= 2), () -> {});
        } finally {
            stop.set(true);
            for (var thread : hammers) {
                thread.join();
            }
            runtimes.forEach(StreamConsumerRuntime::close);
            manager.close();
        }

        assertThat(scripts).as("every loop read again after its first hold").allMatch(script -> script.readsAtNanos.size() >= 2);
        assertThat(scripts.stream().mapToLong(script -> script.gapMs(0, 1)).min().orElseThrow())
            .as("shortest first hold across %d refused loops", CONSUMERS).isGreaterThanOrEqualTo(190L);
    }

    private static void hammer(StreamPartitionManager manager, AtomicBoolean stop) {
        var payload = new byte[]{1};

        for (long tick = 0; !stop.get(); tick++) {
            manager.publishLocal(STREAM, 0, payload, tick);
            Thread.onSpinWait();
        }
    }

    private static final class AlwaysRefuses extends Script {
        private AlwaysRefuses() {
            super(false);
        }

        @Override
        <T> Promise<T> next(T answer) {
            readsAtNanos.add(System.nanoTime());

            return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(PEER)).promise();
        }
    }

    @Test
    void ownerActivationGate_holdsAfterRefusedProbes_andResetsOnceAProbeAnswers() {
        gateHoldsAndResets(new Script(true));
    }

    @Test
    void ownerActivationGate_holdsAfterRefusedProbes_andResetsOnceAProbeFailsWithoutARefusal() {
        gateHoldsAndResets(new Script(false));
    }

    private static void gateHoldsAndResets(Script script) {
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);
        var gate = OwnerActivation.ownerActivation(SELF,
                                                   (_, _) -> Option.some(record),
                                                   (_, _) -> true,
                                                   Option.none(),
                                                   () -> List.of(SELF, PEER),
                                                   (_, _, _) -> script.next(5L),
                                                   (_, _) -> -1L,
                                                   // the answered probe names a peer ahead whose catch-up fails, so the gate stays un-activated and keeps probing
                                                   (_, _, _, _) -> StreamError.General.PARTITION_NOT_LOCAL.<Long>promise(),
                                                   () -> true,
                                                   (_, _, _, from, to) -> Promise.success(LongStream.rangeClosed(from, to)
                                                                                                    .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset,
                                                                                                                                                            new byte[]{1},
                                                                                                                                                            1L))
                                                                                                    .toList()),
                                                   _ -> Unit.unit(),
                                                   PromotionTestRanges.NEVER_ALARM);

        driveUntil(() -> script.readsAtNanos.size() >= 6, () -> gate.admit(STREAM, 0));

        assertHeldThenReset(script);
    }

    /// Reads 0-2 refused: the gaps after them are the doubling holds. Read 3 was sent: reset. Read 4 refused: the gap after
    /// it is the FIRST hold again.
    private static void assertHeldThenReset(Script script) {
        assertThat(script.gapMs(0, 1)).as("first hold ~250 ms").isBetween(190L, 600L);
        assertThat(script.gapMs(2, 3)).as("third consecutive hold ~1000 ms").isGreaterThanOrEqualTo(780L);
        assertThat(script.gapMs(4, 5)).as("after a sent read the hold is the first one again, not ~2000 ms").isLessThan(700L);
    }

    private static StreamConsumerRuntime.PartitionReader scriptedReader(Script script) {
        return new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                return script.next(List.of());
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch epoch) {
                return script.next(new StreamPartitionManager.EpochRead(List.of(), epoch));
            }
        };
    }

    private static void driveUntil(BooleanSupplier done, Runnable trigger) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            trigger.run();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static ConsumerCursorStore inMemoryCursorStore() {
        var stored = new ConcurrentHashMap<String, Long>();

        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                stored.put(group + stream + partition, offset);

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.option(stored.get(group + stream + partition)));
            }
        };
    }
}
