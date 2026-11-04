// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.replication.ForwardCatchupTransport;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #2574 pressure test (CTO, know 89e17399a9): the FORWARD send rate under a refusing lane, F1 vs pre-F1. Each caller class
/// whose next read is driven by its trigger rather than a timer -- the backfill gap trigger, the consumer poll, the owner
/// activation gate -- runs against the REAL forward client, at the production 2 s read timeout, in two arms that differ only
/// in what the transport tells the client:
///
/// - `SILENT_DROP`, pre-F1: the frame is dropped but reported `Sent`, so every read waits out its 2 s timeout;
/// - `REFUSING`, F1: the transport reports `BackpressureRefused`, the read fails at once, and the caller holds by its
///   `RefusalBackoff` (250 ms doubling to 4 s, +-20%).
///
/// All six scenarios run together for 60 s, each triggered every 20 ms, counting the FORWARD frames the client hands the
/// transport. Reported: the first-10 s burst and the sustained 60 s window, each against pre-F1. Asserted, as ruled: the
/// sustained window does not rise (one frame of slack per probed peer for the window edge), and the burst stays within the
/// ruled "slightly above" (sends at about 0, 0.25, 0.75, 1.75, 3.75, 7.75 s against one per 2 s: at most 1.5x + 1).
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ForwardRefusalPressureTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");
    private static final String STREAM = "orders";
    private static final TimeSpan READ_TIMEOUT = TimeSpan.timeSpan(2).seconds();
    private static final long BURST_MS = 10_000L;
    private static final long SUSTAINED_MS = 60_000L;
    private static final long TRIGGER_EVERY_MS = 20L;

    private enum Arm {
        SILENT_DROP,
        REFUSING
    }

    /// Records when each FORWARD frame was handed to it; delivers none.
    private static final class CountingTransport implements StreamForwardTransport {
        private final Arm arm;
        private final List<Long> sentAtNanos = new CopyOnWriteArrayList<>();

        private CountingTransport(Arm arm) {
            this.arm = arm;
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, StreamForwardMessage message) {
            sentAtNanos.add(System.nanoTime());

            return Promise.success(arm == Arm.REFUSING
                                   ? new WriteOutcome.BackpressureRefused(target)
                                   : new WriteOutcome.Sent(target));
        }
    }

    private record Counts(int burst, int sustained) {}

    private static StreamForwardClient client(CountingTransport transport) {
        return StreamForwardClient.streamForwardClient(SELF, transport, READ_TIMEOUT, READ_TIMEOUT);
    }

    @Test
    void refusingLane_sustainedSendRateDoesNotRise_andTheFirst10sBurstStaysSlight_perCallerClass() throws Exception {
        var classes = List.<Function<Arm, Counts>>of(ForwardRefusalPressureTest::backfillGapTrigger,
                                                     ForwardRefusalPressureTest::consumerPoll,
                                                     ForwardRefusalPressureTest::ownerActivationGate);
        var names = List.of("backfill gap trigger", "consumer poll", "owner activation gate (2 peers)");
        var slack = List.of(1, 1, 2);
        var futures = new ArrayList<Future<Counts>>();

        try (var executor = Executors.newFixedThreadPool(classes.size() * 2)) {
            for (var scenario : classes) {
                futures.add(executor.submit(() -> scenario.apply(Arm.SILENT_DROP)));
                futures.add(executor.submit(() -> scenario.apply(Arm.REFUSING)));
            }

            for (var index = 0; index < classes.size(); index++) {
                var silent = futures.get(2 * index).get();
                var refusing = futures.get(2 * index + 1).get();

                System.out.printf("PRESSURE %s readTimeout=2s first10s: silentDrop=%d refusing=%d | sustained60s: silentDrop=%d refusing=%d%n",
                                  names.get(index),
                                  silent.burst(),
                                  refusing.burst(),
                                  silent.sustained(),
                                  refusing.sustained());
                assertThat(refusing.sustained()).as("%s: sustained 60 s, refusing vs pre-F1", names.get(index))
                                                .isLessThanOrEqualTo(silent.sustained() + slack.get(index));
                assertThat((double) refusing.burst()).as("%s: first 10 s, refusing vs pre-F1", names.get(index))
                                                     .isLessThanOrEqualTo(silent.burst() * 1.5 + slack.get(index));
                assertThat(refusing.burst()).as("%s: the refusing arm was exercised", names.get(index)).isPositive();
            }
        }
    }

    // ---------------------------------------------------------------- backfill gap trigger

    /// One backfill call per received batch (`ReplicationReceiveHandler` -> `onGap`), here every 20 ms, against the owner.
    private static Counts backfillGapTrigger(Arm arm) {
        var transport = new CountingTransport(arm);
        var registry = ReplicaRegistry.replicaRegistry();
        var partitions = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        try {
            partitions.createStream(StreamConfig.streamConfig(STREAM));
            registry.registerReplica(STREAM, 0, OWNER);
            registry.updateWatermark(STREAM, 0, OWNER, 4L);
            registry.registerReplica(STREAM, 0, SELF);
            var backfill = PartitionBackfill.partitionBackfill(registry,
                                                               partitions.alignedRecovery(),
                                                               ForwardCatchupTransport.forwardCatchupTransport(client(transport), 64),
                                                               SELF);

            return drive(transport, () -> backfill.backfill(STREAM, 0));
        } finally {
            partitions.close();
        }
    }

    // ---------------------------------------------------------------- consumer poll

    /// A consumer whose reads are forwarded to the owner (`StreamReadRouter.readFrom` -> `readRemoteValidated`), polled at its
    /// own interval and woken by appends every 20 ms.
    private static Counts consumerPoll(Arm arm) {
        var transport = new CountingTransport(arm);
        var forwardClient = client(transport);
        var manager = StreamPartitionManager.streamPartitionManager();

        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
        var runtime = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                                  DeadLetterHandler.deadLetterHandler(),
                                                                  inMemoryCursorStore(),
                                                                  forwardedReader(forwardClient));

        try {
            runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise());
            var tick = new AtomicInteger();

            return drive(transport, () -> manager.publishLocal(STREAM, 0, ("wake-" + tick.incrementAndGet()).getBytes(), tick.get()));
        } finally {
            runtime.close();
            manager.close();
        }
    }

    /// The consumer's forwarded read, as `StreamReadRouter.forwardValidated` makes it.
    private static StreamConsumerRuntime.PartitionReader forwardedReader(StreamForwardClient forwardClient) {
        return new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                return forwardClient.readRemote(OWNER, stream, partition, from, max)
                                    .map(_ -> List.of());
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch epoch) {
                return forwardClient.readRemoteValidated(OWNER, stream, partition, from, max, epoch)
                                    .map(result -> new StreamPartitionManager.EpochRead(List.of(), result.ownerEpoch()));
            }
        };
    }

    // ---------------------------------------------------------------- owner activation gate

    /// An owner whose promotion gate probes two peers over the catch-up read class (`OwnerPeerReads.appendedWatermark`, the
    /// production probe), demanded every 20 ms (`admit`, once per owner action).
    private static Counts ownerActivationGate(Arm arm) {
        var transport = new CountingTransport(arm);
        var forwardClient = client(transport);
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);
        var gate = OwnerActivation.ownerActivation(SELF,
                                                   (_, _) -> Option.some(record),
                                                   (_, _) -> true,
                                                   Option.none(),
                                                   () -> List.of(SELF, PEER_A, PEER_B),
                                                   (target, stream, partition) -> OwnerPeerReads.appendedWatermark(forwardClient::readRemoteCatchup,
                                                                                                                   target,
                                                                                                                   stream,
                                                                                                                   partition,
                                                                                                                   64),
                                                   (_, _) -> -1L,
                                                   (_, _, _, tail) -> Promise.success(tail),
                                                   () -> true,
                                                   (_, _, _, from, to) -> Promise.success(LongStream.rangeClosed(from, to)
                                                                                                    .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset,
                                                                                                                                                            new byte[]{1},
                                                                                                                                                            1L))
                                                                                                    .toList()),
                                                   _ -> Unit.unit(),
                                                   PromotionTestRanges.NEVER_ALARM);

        return drive(transport, () -> gate.admit(STREAM, 0));
    }

    // ---------------------------------------------------------------- shared

    /// Runs `trigger` every [#TRIGGER_EVERY_MS] for [#SUSTAINED_MS]; counts the frames of the first [#BURST_MS] and of the
    /// whole window.
    private static Counts drive(CountingTransport transport, Runnable trigger) {
        var start = System.nanoTime();
        var end = start + TimeUnit.MILLISECONDS.toNanos(SUSTAINED_MS);

        while (System.nanoTime() < end) {
            trigger.run();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(TRIGGER_EVERY_MS));
        }

        var burstEnd = start + TimeUnit.MILLISECONDS.toNanos(BURST_MS);
        var frames = List.copyOf(transport.sentAtNanos);

        return new Counts((int) frames.stream().filter(at -> at < burstEnd).count(),
                          (int) frames.stream().filter(at -> at < end).count());
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
