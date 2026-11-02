// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
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

/// #2574 pressure test: the FORWARD send rate does not rise when the lane is refusing. Each caller class whose retry the
/// caller sweep found driven by its trigger rather than by a timer -- the backfill gap trigger, the consumer poll, the owner
/// activation gate -- is run against the REAL forward client over a fixed window, in two arms that differ only in what the
/// transport tells the client:
///
/// - `SILENT_DROP`, the pre-F1 behaviour: the frame is dropped but reported `Sent`, so every request waits out its read
///   timeout (this is exactly what `StreamForwardTransport.send` returning `void` did);
/// - `REFUSING`, F1: the transport reports `BackpressureRefused` and the request fails at once.
///
/// The arms count the FORWARD frames the client hands the transport. Under fail-fast the count must not exceed the
/// timeout-paced count: each caller keeps the refusal's own wait (the request's read timeout) before sending again. The
/// windows are short and the read timeout scaled down (400 ms) to keep the test quick; the ratio is what is pinned. Counts
/// are compared with one frame of slack for the window edge (a send scheduled for the instant the window closes lands on
/// either side of it); without the pacing the refusing arm sends many times more (the counts are printed).
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ForwardRefusalPressureTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");
    private static final String STREAM = "orders";
    private static final TimeSpan READ_TIMEOUT = TimeSpan.timeSpan(400).millis();
    private static final long WINDOW_MS = 2_400L;
    private static final long TRIGGER_EVERY_MS = 20L;
    private static final int EDGE_SLACK = 1;

    private enum Arm {
        SILENT_DROP,
        REFUSING
    }

    /// Counts FORWARD request frames; delivers none.
    private static final class CountingTransport implements StreamForwardTransport {
        private final Arm arm;
        private final AtomicInteger frames = new AtomicInteger();

        private CountingTransport(Arm arm) {
            this.arm = arm;
        }

        @Override
        public void send(NodeId target, StreamForwardMessage message) {
            frames.incrementAndGet();
        }

        @Override
        public Promise<WriteOutcome> sendOutcome(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
            frames.incrementAndGet();

            return Promise.success(arm == Arm.REFUSING
                                   ? new WriteOutcome.BackpressureRefused(target)
                                   : new WriteOutcome.Sent(target));
        }
    }

    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @AfterEach
    void tearDown() {
        Option.option(runtime).onPresent(StreamConsumerRuntime::close);
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    private static StreamForwardClient client(CountingTransport transport) {
        return StreamForwardClient.streamForwardClient(SELF, transport, READ_TIMEOUT, READ_TIMEOUT);
    }

    // ---------------------------------------------------------------- backfill gap trigger

    /// One backfill call per received batch (`ReplicationReceiveHandler` -> `onGap`), here every 20 ms, against a refusing
    /// owner. Pre-F1 the 400 ms dead flight absorbed them; F1's refused flight holds its slot for the same wait.
    @Test
    void backfillGapTrigger_refusingLane_sendsNoMoreThanTheTimeoutPacedRate() {
        var silent = backfillFrames(Arm.SILENT_DROP);
        var refusing = backfillFrames(Arm.REFUSING);

        report("backfill gap trigger", silent, refusing);
        assertThat(refusing).as("refusing %d vs timeout-paced %d frames in %d ms", refusing, silent, WINDOW_MS)
                            .isLessThanOrEqualTo(silent + EDGE_SLACK);
    }

    private static int backfillFrames(Arm arm) {
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

            drive(() -> backfill.backfill(STREAM, 0));

            return transport.frames.get();
        } finally {
            partitions.close();
        }
    }

    // ---------------------------------------------------------------- consumer poll

    /// A consumer whose reads are forwarded to the owner (`StreamReadRouter.readFrom` -> `readRemoteValidated`), polled at its
    /// own interval (50 ms at most after a failure) and woken by appends every 20 ms. Pre-F1 each read waited 400 ms; F1
    /// parks the loop for the refusal's wait.
    @Test
    void consumerPoll_refusingLane_sendsNoMoreThanTheTimeoutPacedRate() {
        var silent = consumerFrames(Arm.SILENT_DROP);
        var refusing = consumerFrames(Arm.REFUSING);

        report("consumer poll", silent, refusing);
        assertThat(refusing).as("refusing %d vs timeout-paced %d frames in %d ms", refusing, silent, WINDOW_MS)
                            .isLessThanOrEqualTo(silent + EDGE_SLACK);
    }

    private int consumerFrames(Arm arm) {
        var transport = new CountingTransport(arm);
        var forwardClient = client(transport);

        manager = StreamPartitionManager.streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                              DeadLetterHandler.deadLetterHandler(),
                                                              inMemoryCursorStore(),
                                                              forwardedReader(forwardClient));
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise());
        var tick = new AtomicInteger();

        drive(() -> manager.publishLocal(STREAM, 0, ("wake-" + tick.incrementAndGet()).getBytes(), tick.get()));
        runtime.close();
        manager.close();
        runtime = null;
        manager = null;

        return transport.frames.get();
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
    /// production probe), demanded every 20 ms (`admit`, once per owner action). Pre-F1 each gate run waited out the probe
    /// timeout and concurrent demands were told it was in progress; F1 paces the gate by the refusal's wait.
    @Test
    void ownerActivationGate_refusingLane_sendsNoMoreThanTheTimeoutPacedRate() {
        var silent = activationFrames(Arm.SILENT_DROP);
        var refusing = activationFrames(Arm.REFUSING);

        report("owner activation gate", silent, refusing);
        assertThat(refusing).as("refusing %d vs timeout-paced %d frames in %d ms", refusing, silent, WINDOW_MS)
                            .isLessThanOrEqualTo(silent + 2 * EDGE_SLACK);
    }

    private static int activationFrames(Arm arm) {
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

        drive(() -> gate.admit(STREAM, 0));

        return transport.frames.get();
    }

    // ---------------------------------------------------------------- dead-letter publish

    /// The dead-letter publish retries at 100 ms doubling (to 10 s); after a refused publish it waits at least the refusal's
    /// own wait (the publish timeout the publish would have spent), and any other failure keeps the plain backoff.
    @Test
    void deadLetterRetry_afterARefusal_waitsAtLeastTheRefusalsWait() {
        var wait = TimeSpan.timeSpan(5).seconds();
        var refused = StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(OWNER), wait);

        for (var attempt = 1; attempt <= 3; attempt++) {
            assertThat(ConsumerRuntimeState.deadLetterRetryDelayMs(attempt, refused)).as("attempt %d after a refusal", attempt)
                                                                                     .isGreaterThanOrEqualTo(wait.millis());
            assertThat(ConsumerRuntimeState.deadLetterRetryDelayMs(attempt, StreamError.General.PARTITION_NOT_LOCAL))
                .as("attempt %d after any other failure keeps the plain backoff", attempt)
                .isLessThan(wait.millis());
        }
    }

    /// The same pacing end to end in the consumer: a dead-letter sink whose publish is refused, a SKIP consumer whose handler
    /// fails one event. With a 1.2 s refusal wait the sink sees at most three appends in 2.5 s (0, 1.2, 2.4); the plain
    /// backoff alone (100 ms doubling) would make five (0, 0.1, 0.3, 0.7, 1.5). Pins that the retry uses the wait.
    @Test
    void deadLetterRetry_refusedSink_appendsNoMoreOftenThanTheRefusalsWait() {
        var wait = TimeSpan.timeSpan(1200).millis();
        var appends = new AtomicInteger();
        var refused = StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(OWNER), wait);
        var sink = new DeadLetterHandler() {
            @Override
            public Promise<Unit> append(String streamName,
                                        int partition,
                                        long offset,
                                        String failingGroup,
                                        byte[] payload,
                                        String errorMessage,
                                        int attemptCount) {
                appends.incrementAndGet();

                return refused.promise();
            }

            @Override
            public Promise<List<DeadLetterHandler.DeadLetterEntry>> read(String streamName, int maxCount) {
                return Promise.success(List.of());
            }
        };

        manager = StreamPartitionManager.streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager, sink);
        runtime.subscribe(STREAM,
                          0,
                          ConsumerConfig.consumerConfig("group-dlq", 1, ConsumerConfig.ProcessingMode.ORDERED, ConsumerConfig.ErrorStrategy.SKIP),
                          (_, _, _) -> StreamError.General.BUFFER_EMPTY.promise());
        manager.publishLocal(STREAM, 0, "poison".getBytes(), 1L);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2_500L));

        System.out.printf("PRESSURE dead-letter retry window=2500ms refusalWait=1200ms appends=%d%n", appends.get());
        assertThat(appends.get()).as("dead-letter appends in 2.5 s under a 1.2 s refusal wait").isBetween(1, 3);
    }

    // ---------------------------------------------------------------- shared

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

    /// Runs `trigger` every [#TRIGGER_EVERY_MS] for [#WINDOW_MS]; the frames counted are those sent within the window.
    private static void drive(Runnable trigger) {
        var end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WINDOW_MS);

        while (System.nanoTime() < end) {
            trigger.run();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(TRIGGER_EVERY_MS));
        }
    }

    private static void report(String callerClass, int silent, int refusing) {
        System.out.printf("PRESSURE %s window=%dms readTimeout=%dms silentDrop=%d refusing=%d%n",
                          callerClass,
                          WINDOW_MS,
                          READ_TIMEOUT.millis(),
                          silent,
                          refusing);
    }
}
