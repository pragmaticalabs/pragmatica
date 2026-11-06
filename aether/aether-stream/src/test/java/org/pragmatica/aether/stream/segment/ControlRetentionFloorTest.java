// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.control.ControlKind;
import org.pragmatica.aether.stream.control.ControlLog;
import org.pragmatica.aether.stream.control.ControlOutcome;
import org.pragmatica.aether.stream.control.ControlPartitionLog;
import org.pragmatica.aether.stream.control.ControlRecord;
import org.pragmatica.aether.stream.control.ControlRetentionFloor;
import org.pragmatica.aether.stream.control.MirrorView;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.RetentionEnforcer.retentionEnforcer;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;


/// #2590 P1, behaviours 16 and 18: the control log's retention floor, read by the real [RetentionEnforcer]. The floor is
/// the oldest live key's latest record, taken from the committed index; `-1` while the index is invalid; and a tombstone
/// releases it.
class ControlRetentionFloorTest {
    private static final String STREAM = "ctl";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final ControlKind.Named KIND = new ControlKind.Named("ANY");

    private StreamPartitionManager manager;
    private ControlLog controlLog;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.ownershipRecords((_, _) -> Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(NodeId.nodeId("ctl-owner")
                                                                                                                         .unwrap(),
                                                                                                                   Epoch.ZERO,
                                                                                                                   1L,
                                                                                                                   HlcTimestamp.ZERO,
                                                                                                                   List.of(),
                                                                                                                   1L)));
        controlLog = ControlLog.controlLog(ControlPartitionLog.partitionManagerLog(manager, Option.none(), Option.none()),
                                           ControlKind.Admissions.admissions(Map.of(KIND.name(),
                                                                                    (_, _, _) -> Result.unitResult())),
                                           MirrorView.EMPTY);
        manager.partitionLogEvents(controlLog.events());
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       1,
                                                       1,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// Behaviour 16. Key `a`'s latest record sits at offset 5, inside the oldest sealed segment `[0, 9]`, which the age
    /// policy would delete: the floor (4) keeps it. A baseline over the same segments with no floor proves the segment was
    /// deletable. Mutation: the floor answers `MAX_VALUE`.
    @Test
    void floor_liveLatestInOldestSegment_segmentKept() {
        for (var i = 0; i < 5; i++) {
            write("f");
        }
        write("a");
        for (var i = 6; i < 20; i++) {
            write("f");
        }
        awaitFloor(4L);

        var baseline = sealedIndex();
        var underFloor = sealedIndex();

        enforce(baseline.index(), baseline.storage(), RetentionEnforcer.SegmentRetentionFloor.NONE);
        enforce(underFloor.index(), underFloor.storage(), ControlRetentionFloor.controlRetentionFloor(controlLog));

        assertThat(baseline.index()
                           .listSegments(STREAM, PARTITION)).as("control: with no floor the expired segment goes")
                                                            .extracting(SegmentIndex.SegmentRef::startOffset)
                                                            .containsExactly(10L);
        assertThat(underFloor.index()
                             .listSegments(STREAM, PARTITION)).as("a's latest record keeps its segment")
                                                              .extracting(SegmentIndex.SegmentRef::startOffset)
                                                              .containsExactly(0L, 10L);
    }

    /// Behaviour 16. A suffix truncation invalidates the index; until it is rebuilt the floor is `-1`, whatever the stale
    /// index says. Mutation: compute the floor from the stale index.
    @Test
    void floor_whileInvalid_isMinusOne() {
        write("a");
        write("b");
        write("a");
        awaitFloor(0L);

        manager.partitionBuffer(STREAM, PARTITION)
               .unwrap()
               .truncateSuffix(1L)
               .onFailure(cause -> fail(cause.message()));

        assertThat(controlLog.deletableThroughOffset(STREAM, PARTITION)).isEqualTo(-1L);
    }

    /// Behaviour 18. Key `a` at offset 0 holds the floor at `-1`; its tombstone removes it from the index and the floor
    /// moves to `b`'s record. Mutation: ignore tombstones.
    @Test
    void tombstone_removesKey_releasesFloor() {
        write("a");
        write("b");
        awaitFloor(-1L);
        awaitTrue("b is indexed", () -> controlLog.latest(STREAM, PARTITION, "b")
                                                  .map(Option::isPresent)
                                                  .or(false));

        assertThat(appendReady(ControlRecord.tombstone("a", 1L))).isInstanceOf(ControlOutcome.Admitted.class);

        awaitFloor(0L);
        assertThat(controlLog.latest(STREAM, PARTITION, "a")
                             .map(Option::isPresent)
                             .or(true)).as("the tombstoned key is gone")
                                       .isFalse();
    }

    private void write(String key) {
        assertThat(appendReady(ControlRecord.controlRecord(KIND, key, new byte[] {1}, 1L))).isInstanceOf(ControlOutcome.Admitted.class);
    }

    private ControlOutcome appendReady(ControlRecord record) {
        var deadline = System.nanoTime() + TimeSpan.timeSpan(10).seconds().nanos();

        while (System.nanoTime() < deadline) {
            var outcome = controlLog.append(STREAM, PARTITION, List.of(record))
                                    .await(TimeSpan.timeSpan(10).seconds())
                                    .map(List::getFirst)
                                    .or(ControlOutcome.IndexUnavailable.INDEX_UNAVAILABLE);

            if (outcome != ControlOutcome.IndexUnavailable.INDEX_UNAVAILABLE) {
                return outcome;
            }
            sleepBriefly();
        }
        return fail("the control log never became ready");
    }

    private void awaitFloor(long expected) {
        awaitTrue("the floor is " + expected,
                  () -> controlLog.latest(STREAM, PARTITION, "any")
                                  .isSuccess() && controlLog.deletableThroughOffset(STREAM, PARTITION) == expected);
    }

    private record Sealed(SegmentIndex index, StorageInstance storage) {}

    /// `[0, 9]` sealed long ago (expired by age) and `[10, 19]` sealed now.
    private static Sealed sealedIndex() {
        var storage = StorageInstance.storageInstance("ctl-floor", List.of(MemoryTier.memoryTier(ONE_GB)));
        var index = new SegmentIndex();
        var sink = storageSegmentSink(storage, index);
        var now = System.currentTimeMillis();
        var expired = now - 2 * ONE_HOUR_MS;

        sink.seal(sealedSegment(STREAM, PARTITION, 0, 9, 10, expired, expired, new byte[] {1, 2, 3}))
            .await();
        sink.seal(sealedSegment(STREAM, PARTITION, 10, 19, 10, now, now, new byte[] {4, 5, 6}))
            .await();

        return new Sealed(index, storage);
    }

    private static void enforce(SegmentIndex index, StorageInstance storage, RetentionEnforcer.SegmentRetentionFloor floor) {
        retentionEnforcer(storage, index, ONE_HOUR_MS, floor).enforceNow()
                                                            .await();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeSpan.timeSpan(10).seconds().nanos();

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until " + what);
            }
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }
}
