// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1505, property 3: the replica's catch-up apply and its live receive share ONE offset authority per
/// partition — the offset-addressed `appendRecovered` overload, decided inside the partition's ordered append
/// section. Two writers offering the SAME owner events at their owner offsets, racing each other (a live batch
/// and a catch-up response for the same range), must leave each event exactly once at its own offset, in the
/// ring AND in the WAL: whichever writer reaches an offset second verifies it rather than appending it again.
/// Under the pre-#1505 tail append the loser of every race re-appended its copy one offset higher.
///
/// The race is intermittent by nature, so each case is a `@RepeatedTest`.
class StreamPartitionManagerAlignedAppendTest {
    private static final String STREAM = "aligned";
    private static final int PARTITION = 0;
    private static final int EVENTS = 2_000;

    @TempDir
    Path walDir;

    @RepeatedTest(20)
    void appendRecoveredAtOffset_liveAndCatchupRaceTheSameEvents_eachEventHeldOnceAtItsOwnOffset() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        var refusals = raceTwoWriters(manager);

        manager.syncReplicated(STREAM, PARTITION).await().onFailure(cause -> fail(cause.message()));

        assertThat(refusals).as("every offer either appends or verifies — no refusal between agreeing writers").isEmpty();
        assertThat(markers(manager.readAppended(STREAM, PARTITION, 0, EVENTS * 2).unwrap()))
            .as("each event exactly once, at its own offset")
            .containsExactlyElementsOf(expected());
        manager.close();

        assertThat(replayAll(walDir.resolve(STREAM).resolve(PARTITION + ".wal")).stream()
                                                                                .map(record -> record.offset() + "=" + new String(record.payload(), UTF_8))
                                                                                .toList())
            .as("one WAL frame per offset — a verified offset writes no frame")
            .containsExactlyElementsOf(expected());
    }

    private static List<String> raceTwoWriters(StreamPartitionManager manager) {
        var refusals = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        IntStream.range(0, 2).forEach(_ -> pool.execute(() -> offerAll(manager, start, refusals)));
        start.countDown();
        pool.shutdown();
        awaitTermination(pool);

        return List.copyOf(refusals);
    }

    private static void offerAll(StreamPartitionManager manager, CountDownLatch start, ConcurrentLinkedQueue<String> refusals) {
        awaitLatch(start);

        for (var i = 0; i < EVENTS; i++) {
            Result<Long> result = manager.appendRecovered(STREAM, PARTITION, i, marker(i), 1000L + i);

            result.onFailure(cause -> refusals.add(cause.message()));
        }
    }

    private static List<String> expected() {
        return IntStream.range(0, EVENTS).mapToObj(i -> i + "=marker-" + i).toList();
    }

    private static List<String> markers(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(event -> event.offset() + "=" + new String(event.data(), UTF_8)).toList();
    }

    private static byte[] marker(int i) {
        return ("marker-" + i).getBytes(UTF_8);
    }

    private static List<WalRecord> replayAll(Path file) {
        var wal = PartitionWal.open(file).unwrap();
        var records = new ArrayList<WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        wal.close();
        return records;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private static void awaitTermination(ExecutorService pool) {
        try {
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("writers finished").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }
}
