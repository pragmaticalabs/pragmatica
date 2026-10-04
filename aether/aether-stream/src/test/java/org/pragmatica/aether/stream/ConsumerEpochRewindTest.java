// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1873 (KIP-320), the consumer half: a RUNNING consumer whose owner restarted without a WAL (or failed over to a replica
/// that held less) is told, by a typed divergence, where the owner's new epoch began, and re-reads from there instead of
/// reading on from a cursor that points past the replaced records. The owner is scripted: it serves old-lineage records
/// under epoch E1, then answers the divergence the owner-side check produces, then serves the new lineage under E2.
class ConsumerEpochRewindTest {
    private static final String STREAM = "orders";
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final List<String> reads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    /// The v1862 r2 probe: the consumer read 0 and 1 under E1 (cursor 2), the owner restarted and began E2 at offset 1.
    /// The consumer must receive the new record at 1, not skip it.
    @Test
    void runningConsumer_toldItsLineageWasReplaced_rereadsFromWhereTheNewEpochBegan() throws InterruptedException {
        var calls = new AtomicInteger();
        var owner = new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                throw new AssertionError("a consumer reads through readFrom");
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch consumerEpoch) {
                reads.add("from=" + from + ",epoch=" + consumerEpoch.localCounter());

                return switch (calls.incrementAndGet()) {
                    case 1 -> Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "old-0"), event(1, "old-1")), E1));
                    case 2 -> new StreamError.EpochDiverged(E2, 1L).promise();
                    case 3 -> Promise.success(new StreamPartitionManager.EpochRead(List.of(event(1, "new-1"), event(2, "new-2")), E2));
                    default -> Promise.success(new StreamPartitionManager.EpochRead(List.of(), E2));
                };
            }
        };

        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), offsetStore(), owner);
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (offset, payload, ts) -> {
            delivered.add(offset + ":" + new String(payload, UTF_8));

            return Promise.unitPromise();
        });
        keepWaking(() -> delivered.size() >= 4);

        assertThat(delivered).as("the new records at the re-assigned offsets are delivered, not skipped (reads %s)", reads)
                             .containsExactly("0:old-0", "1:old-1", "1:new-1", "2:new-2");
        assertThat(reads).as("the divergence carried E1 back, the re-read carries E2").contains("from=2,epoch=1", "from=1,epoch=2");
    }

    /// Control: the owner never replaces the lineage: nothing is re-read.
    @Test
    void control_noDivergence_deliversEachRecordOnce() throws InterruptedException {
        var calls = new AtomicInteger();
        var owner = new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                throw new AssertionError("a consumer reads through readFrom");
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch consumerEpoch) {
                return switch (calls.incrementAndGet()) {
                    case 1 -> Promise.success(new StreamPartitionManager.EpochRead(List.of(event(0, "a"), event(1, "b")), E1));
                    default -> Promise.success(new StreamPartitionManager.EpochRead(List.of(), E1));
                };
            }
        };

        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager, DeadLetterHandler.deadLetterHandler(), offsetStore(), owner);
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (offset, payload, ts) -> {
            delivered.add(offset + ":" + new String(payload, UTF_8));

            return Promise.unitPromise();
        });
        keepWaking(() -> delivered.size() >= 2);
        Thread.sleep(300);

        assertThat(delivered).containsExactly("0:a", "1:b");
    }

    /// A reader that reports no epoch (a local or legacy reader) never diverges and leaves the consumer's epoch alone.
    @Test
    void aPlainReader_reportsNoEpoch_andNeverDiverges() throws InterruptedException {
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager);
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (offset, payload, ts) -> {
            delivered.add(offset + ":" + new String(payload, UTF_8));

            return Promise.unitPromise();
        });
        manager.publishLocal(STREAM, 0, "x".getBytes(UTF_8), 1L);
        manager.publishLocal(STREAM, 0, "y".getBytes(UTF_8), 2L);
        keepWaking(() -> delivered.size() >= 2);

        assertThat(delivered).startsWith("0:x", "1:y");
    }

    /// The consumer's ring is local, so appends wake it; a scripted owner needs a wake-up per stage.
    private void keepWaking(java.util.function.BooleanSupplier done) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;
        var tick = 0L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.publishLocal(STREAM, 0, ("wake-" + tick++).getBytes(UTF_8), tick);
            Thread.sleep(50);
        }
    }

    private static OffHeapRingBuffer.RawEvent event(long offset, String text) {
        return new OffHeapRingBuffer.RawEvent(offset, text.getBytes(UTF_8), offset);
    }

    private static org.pragmatica.aether.stream.segment.ConsumerCursorStore offsetStore() {
        var stored = new java.util.concurrent.ConcurrentHashMap<String, Long>();

        return new org.pragmatica.aether.stream.segment.ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                stored.put(group + stream + partition, offset);

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<org.pragmatica.lang.Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(org.pragmatica.lang.Option.option(stored.get(group + stream + partition)));
            }
        };
    }
}
