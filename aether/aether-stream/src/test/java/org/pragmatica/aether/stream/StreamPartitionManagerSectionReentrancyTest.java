// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamConsumerRuntime.streamConsumerRuntime;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1258 review (B1): the partition's ordered append section must never run foreign code. Append
/// listeners — the consumer runtime's push path, which delivers SYNCHRONOUSLY on the publisher's thread —
/// are invoked only after the section is released (and after the WAL frame write), in offset order. So
/// a consumer handler may publish again: to another partition (no lock-order cycle, no deadlock) or to
/// its own partition (the outer record is already fully logged, so the WAL stays contiguous).
///
/// Also pins the throughput half of #1231's design: the WAL fsync is awaited OUTSIDE the section.
class StreamPartitionManagerSectionReentrancyTest {
    private static final ThreadLocal<Boolean> IN_HANDLER = ThreadLocal.withInitial(() -> false);

    @TempDir
    Path walDir;

    /// Reviewer probe P-A: before the fix the outer publish failed with OffsetRegression, the WAL held
    /// only [1], and the restart refused the stream.
    @Test
    void consumerPublishingToItsOwnPartition_bothPublishesSucceed_andWalIsContiguous() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        var runtime = streamConsumerRuntime(manager);
        var inner = new AtomicReference<Result<Long>>();
        var fired = new AtomicBoolean();

        assertThat(manager.createStream(config("s", 1)).isSuccess()).isTrue();
        runtime.subscribe("s",
                          0,
                          ConsumerConfig.consumerConfig("g"),
                          (_, payload, _) -> republishOnce(manager, payload, fired, inner));

        var outer = manager.publishLocal("s", 0, "a".getBytes(UTF_8), 1L);

        awaitSet(inner);
        runtime.close();
        manager.close();

        assertThat(outer.isSuccess()).as("outer publish acked: %s", outer).isTrue();
        assertThat(inner.get()).as("the handler ran and its publish succeeded").isNotNull();
        assertThat(inner.get().isSuccess()).as("inner publish acked: %s", inner.get()).isTrue();
        assertThat(replayOffsets(walDir.resolve("s").resolve("0.wal"))).containsExactly(0L, 1L);

        var rebuilt = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        assertThat(rebuilt.createStream(config("s", 1)).isSuccess()).as("restart rebuilds the stream").isTrue();
        rebuilt.close();
    }

    /// Reviewer probe P-B: consumers p0 → p1 and p1 → p0 with two publisher threads. Before the fix both
    /// threads deadlocked, each holding its partition's monitor while delivering into the other's.
    @Test
    void consumersPublishingAcrossPartitions_inBothDirections_doNotDeadlock() throws InterruptedException {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var runtime = streamConsumerRuntime(manager);

        assertThat(manager.createStream(config("s", 2)).isSuccess()).isTrue();
        subscribeForwarder(runtime, manager, 0, 1);
        subscribeForwarder(runtime, manager, 1, 0);

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);

        publisher(manager, 0, start, done).start();
        publisher(manager, 1, start, done).start();
        start.countDown();

        var finished = done.await(60, TimeUnit.SECONDS);
        var deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();

        assertThat(deadlocked).as("deadlocked threads").isNull();
        assertThat(finished).as("both publishers finish").isTrue();
        runtime.close();
        manager.close();
    }

    /// #1231: only the frame write is inside the section. With the WAL's fsync parked on a latch, a
    /// publish waits for durability — and meanwhile the same partition's ring still accepts an append.
    @Test
    void blockedFsync_doesNotBlockTheSection() throws Exception {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        assertThat(manager.createStream(config("s", 1)).isSuccess()).isTrue();
        var gate = GatedWalFsync.inject(GatedWalFsync.walOf(manager, "s", 0));
        var parkedPublish = CompletableFuture.supplyAsync(() -> manager.publishLocal("s", 0, "a".getBytes(UTF_8), 1L));

        assertThat(gate.forceEntered.await(10, TimeUnit.SECONDS)).as("the publish reached its fsync").isTrue();
        var ring = manager.partitionBuffer("s", 0).unwrap();
        var concurrentAppend = CompletableFuture.supplyAsync(() -> ring.append("b".getBytes(UTF_8), 2L));

        assertThat(concurrentAppend.get(5, TimeUnit.SECONDS).isSuccess())
            .as("the section is free while a publish waits on its fsync")
            .isTrue();
        assertThat(parkedPublish.isDone()).as("the publish is still waiting for durability").isFalse();

        gate.forceProceed.countDown();
        assertThat(parkedPublish.get(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        manager.close();
    }

    // === helpers ===

    /// Listeners run on the ring's notifier thread (#1258 round 2), so the handler's inner publish
    /// completes asynchronously to the outer one.
    private static void awaitSet(AtomicReference<Result<Long>> inner) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (inner.get() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static StreamConfig config(String name, int partitions) {
        return StreamConfig.streamConfig(name,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100_000, 64L * 1024 * 1024, 3_600_000),
                                         "earliest");
    }

    private static Promise<org.pragmatica.lang.Unit> republishOnce(StreamPartitionManager manager,
                                                                  byte[] payload,
                                                                  AtomicBoolean fired,
                                                                  AtomicReference<Result<Long>> inner) {
        if ("a".equals(new String(payload, UTF_8)) && fired.compareAndSet(false, true)) {
            inner.set(manager.publishLocal("s", 0, "b".getBytes(UTF_8), 2L));
        }
        return Promise.unitPromise();
    }

    private static void subscribeForwarder(StreamConsumerRuntime runtime,
                                           StreamPartitionManager manager,
                                           int from,
                                           int to) {
        runtime.subscribe("s",
                          from,
                          ConsumerConfig.consumerConfig("g" + from),
                          (offset, payload, _) -> forward(manager, to, offset, payload));
    }

    private static Promise<org.pragmatica.lang.Unit> forward(StreamPartitionManager manager, int to, long offset, byte[] payload) {
        if (new String(payload, UTF_8).startsWith("x") && !IN_HANDLER.get()) {
            IN_HANDLER.set(true);
            try {
                manager.publishLocal("s", to, ("y" + offset).getBytes(UTF_8), 1L);
            } finally {
                IN_HANDLER.set(false);
            }
        }
        return Promise.unitPromise();
    }

    private static Thread publisher(StreamPartitionManager manager, int partition, CountDownLatch start, CountDownLatch done) {
        var thread = new Thread(() -> publishAll(manager, partition, start, done), "pub-" + partition);

        thread.setDaemon(true);
        return thread;
    }

    private static void publishAll(StreamPartitionManager manager, int partition, CountDownLatch start, CountDownLatch done) {
        try {
            start.await();
            for (int i = 0; i < 5_000; i++) {
                manager.publishLocal("s", partition, ("x" + i).getBytes(UTF_8), 1L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    private static List<Long> replayOffsets(Path file) {
        var wal = PartitionWal.open(file).unwrap();
        var records = new ArrayList<WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        wal.close();
        return records.stream().map(WalRecord::offset).toList();
    }
}
