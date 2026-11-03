// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams overhaul PR-2c, A-01 (#2201): the ordered section withdraws an offset whose continuation failed, and refuses a run it could
/// not withdraw. NT-27 (batch rollback), NT-28 (replica receive rollback), NT-33 (T-017 batch variant), NT-107 (a run that does not fit).
@Timeout(60)
class OrderedSectionRollbackTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);

    /// NT-27: a failed continuation of an ordered batch leaves the head where it was, and the next append takes the withdrawn offset.
    @Nested
    class BatchRollback {
        @Disabled("NT-27, RED until PR-2c commit 3 (rollback): a failed batch continuation must leave the head unchanged (#2201)")
        @Test
        void appendBatchOrdered_leavesTheHeadUnchanged_whenTheContinuationFails() {
            try (var ring = offHeapRingBuffer(8, 4096)) {
                ring.append(bytes("a"), 1L).onFailure(cause -> fail(cause.message()));
                var before = ring.headOffset();

                var refused = ring.<Long> appendBatchOrdered(List.of(bytes("x"), bytes("y")),
                                                             new long[]{2L, 3L},
                                                             _ -> Causes.cause("wal refused").result());

                assertThat(refused.isFailure()).as("control: the continuation's failure is returned").isTrue();
                assertThat(ring.headOffset()).isEqualTo(before);
                assertThat(texts(ring.readAppended(before + 1, 5).unwrap())).isEmpty();
                assertThat(ring.<Long> appendOrdered(bytes("next"), 4L, Result::success).unwrap()).as("the withdrawn offset is reused")
                                                                                             .isEqualTo(before + 1);
            }
        }
    }

    /// NT-107: an ordered run the ring could not withdraw (it would evict its own members) is refused before any offset exists.
    @Nested
    class OversizeRun {
        @Disabled("NT-107, RED until PR-2c commit 3: a run larger than the ring's capacity must be refused RUN_DOES_NOT_FIT before any offset exists (#2201)")
        @Test
        void appendBatchOrdered_refusesARunLargerThanTheCapacity_beforeAnyOffset() {
            try (var ring = offHeapRingBuffer(4, 4096)) {
                var payloads = IntStream.range(0, 5).mapToObj(i -> bytes("e" + i)).toList();

                var refused = ring.<Long> appendBatchOrdered(payloads, new long[]{1L, 2L, 3L, 4L, 5L}, Result::success);

                assertThat(refused.isFailure()).isTrue();
                refused.onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.RUN_DOES_NOT_FIT));
                assertThat(ring.headOffset()).as("no offset was assigned").isEqualTo(-1L);
            }
        }

        @Disabled("NT-107, RED until PR-2c commit 3: a run whose bytes exceed the allocation must be refused RUN_DOES_NOT_FIT before any offset exists (#2201)")
        @Test
        void appendBatchOrdered_refusesARunLargerThanTheAllocation_beforeAnyOffset() {
            try (var ring = offHeapRingBuffer(8, 4096)) {
                ring.append(bytes("a"), 1L).onFailure(cause -> fail(cause.message()));
                var payloads = List.of(new byte[2000], new byte[2000], new byte[2000]);

                var refused = ring.<Long> appendBatchOrdered(payloads, new long[]{2L, 3L, 4L}, Result::success);

                refused.onSuccess(offset -> fail("a run larger than the allocation was appended through " + offset))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.RUN_DOES_NOT_FIT));
                assertThat(ring.headOffset()).as("no offset was assigned").isEqualTo(0L);
                assertThat(texts(ring.read(0, 5).unwrap())).as("the earlier event was not evicted").containsExactly("a");
            }
        }

        @TempDir
        Path walDir;

        @Disabled("NT-107, RED until PR-2c commit 3: the owner batch refuses an oversize run with nothing in the ring or the WAL, and the single-publish fallback lands contiguously (#2201)")
        @Test
        void publishBatch_oversizeRun_isRefusedUntouched_andTheSinglePublishFallbackStaysContiguous() throws Exception {
            var config = StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(4, 1_000_000L, 3_600_000L), "earliest");
            var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

            manager.createStream(config).onFailure(cause -> fail(cause.message()));
            var payloads = IntStream.range(0, 6).mapToObj(i -> bytes("evt-" + i)).toList();

            manager.publishLocalBatchAtFloor(STREAM, PARTITION, payloads, 1L, 0)
                   .onSuccess(offset -> fail("a run of 6 into a ring of 4 was appended through " + offset))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.RUN_DOES_NOT_FIT));
            assertThat(manager.partitionBuffer(STREAM, PARTITION).unwrap().headOffset()).as("no offset was assigned").isEqualTo(-1L);
            assertThat(walOffsets(manager)).as("nothing reached the WAL").isEmpty();

            for (var i = 0; i < payloads.size(); i++) {
                var expected = (long) i;

                manager.publishLocal(STREAM, PARTITION, payloads.get(i), 1L)
                       .onFailure(cause -> fail(cause.message()))
                       .onSuccess(offset -> assertThat(offset).isEqualTo(expected));
            }
            assertThat(walOffsets(manager)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
            manager.close();
        }
    }

    /// NT-33: T-017 through the rollback, batch variant. A frozen ring that cannot fit a run's event writes no WAL record and leaves the
    /// offsets intact; the next run lands at the next contiguous offset. Enabled regression guard, GREEN before and after PR-2c.
    @Nested
    class FrozenRingBatch {
        @TempDir
        Path walDir;

        @Test
        void publishBatch_onAFrozenRingThatCannotFitAnEvent_writesNoWalRecord_andLeavesOffsetsIntact() throws Exception {
            var config = StreamConfig.streamConfig("feed-2c", 1, RetentionPolicy.retentionPolicy(), "earliest");
            var manager = streamPartitionManager(OffHeapRingBuffer.floorBytes(config.retention().maxCount(), config.retention().maxBytes()),
                                                 Option.some(walDir));

            manager.createStream(config).onFailure(cause -> fail(cause.message()));
            manager.publishLocalBatchAtFloor("feed-2c", PARTITION, List.of(bytes("evt-0"), bytes("evt-1")), 1L, 0)
                   .onFailure(cause -> fail(cause.message()));

            manager.publishLocalBatchAtFloor("feed-2c", PARTITION, List.of(bytes("evt-2"), new byte[300_000]), 2L, 0)
                   .onSuccess(offset -> fail("a run holding an event the frozen ring cannot fit was appended through " + offset))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.RUN_DOES_NOT_FIT));

            assertThat(walOffsetsOf(manager, "feed-2c")).as("the refused run wrote no WAL record").containsExactly(0L, 1L);
            assertThat(manager.partitionBuffer("feed-2c", PARTITION).unwrap().headOffset()).as("offsets intact").isEqualTo(1L);
            manager.publishLocalBatchAtFloor("feed-2c", PARTITION, List.of(bytes("evt-2"), bytes("evt-3")), 3L, 0)
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(last -> assertThat(last).as("the next run lands contiguously").isEqualTo(3L));
            assertThat(walOffsetsOf(manager, "feed-2c")).containsExactly(0L, 1L, 2L, 3L);
            manager.close();
        }
    }

    /// NT-28: a replica receive whose WAL write is refused leaves the ring head where it was, so the sender's retry lands at the same
    /// offset and the WAL stays contiguous. The refusal is induced by a stray WAL record at the offset (OffsetRegression, which does not
    /// fail-stop the log); the stray record is then cut, as a repair would.
    @Nested
    class ReplicaReceiveRollback {
        @TempDir
        Path walDir;

        @Disabled("NT-28, RED until PR-2c commit 3: a refused replica WAL write must restore the ring head (#2201)")
        @Test
        void appendRecovered_refusedWalWrite_restoresTheHead_andTheRetryLandsAtTheSameOffset() throws Exception {
            var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

            manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            manager.appendRecovered(STREAM, PARTITION, 0L, bytes("r0"), 1L, E1).onFailure(cause -> fail(cause.message()));
            var wal = GatedWalFsync.walOf(manager, STREAM, PARTITION);

            wal.write(1L, bytes("stray"), 2L).onFailure(cause -> fail(cause.message()));

            var refused = manager.appendRecovered(STREAM, PARTITION, 1L, bytes("r1"), 3L, E1);

            assertThat(refused.isFailure()).as("the refused WAL write fails the receive").isTrue();
            assertThat(manager.partitionBuffer(STREAM, PARTITION).unwrap().headOffset()).as("the ring head is restored").isEqualTo(0L);

            wal.truncateSuffix(0L).onFailure(cause -> fail(cause.message()));
            manager.appendRecovered(STREAM, PARTITION, 1L, bytes("r1"), 3L, E1)
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(offset -> assertThat(offset).isEqualTo(1L));
            assertThat(walOffsets(manager)).as("the WAL is contiguous").containsExactly(0L, 1L);
            manager.close();
        }
    }

    private static List<Long> walOffsets(StreamPartitionManager manager) throws Exception {
        return walOffsetsOf(manager, STREAM);
    }

    private static List<Long> walOffsetsOf(StreamPartitionManager manager, String stream) throws Exception {
        var records = new ArrayList<AppendLog.WalRecord>();

        GatedWalFsync.walOf(manager, stream, PARTITION).replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        return records.stream().map(AppendLog.WalRecord::offset).toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    private static List<String> texts(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), UTF_8)).toList();
    }
}
