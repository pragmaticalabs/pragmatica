// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataSnapshot;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageInstance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.RetentionEnforcer.retentionEnforcer;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1278: retention persists a reclaimed-through floor before it drops the segment refs the floor licenses, and a
/// rebuilt index anchors the sealed watermark at that floor -- so recovery can tell history retention reclaimed
/// (fine) from history whose refs were lost (#1014; recovery refuses a WAL compacted past it).
class ReclaimedThroughFloorTest {
    private static final String STREAM = "floored";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long EXPIRED = System.currentTimeMillis() - 2 * ONE_HOUR_MS;

    @TempDir
    Path snapshotDir;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("floors");
        storage = StorageInstance.storageInstance("floors", List.of(MemoryTier.memoryTier(ONE_GB)), metadataStore);
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
    }

    @Nested
    class Reclamation {
        @Test
        void reclaim_writesTheFloorRef_andRecordsIt() {
            sealExpired(0, 9);
            sealExpired(10, 19);

            assertThat(enforce(RefDurability.LIVE)).isEqualTo(2);
            assertThat(index.listSegments(STREAM, PARTITION)).isEmpty();
            assertThat(storage.resolveRef(SegmentIndex.floorRefName(STREAM, PARTITION, 19)).isPresent()).isTrue();
            assertThat(index.reclaimedThrough(STREAM, PARTITION)).isEqualTo(19L);
            assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("reclaiming never un-seals").isEqualTo(19L);
        }

        /// The ordering itself: at the moment the floor is made durable it is already written, and every segment
        /// ref it licenses still exists -- so a crash there restarts with both.
        @Test
        void reclaim_floorIsDurableBeforeAnySegmentRefIsDropped() {
            sealExpired(0, 9);
            sealExpired(10, 19);
            var seenAtPersist = new ArrayList<Boolean>();

            enforce(() -> {
                seenAtPersist.add(storage.resolveRef(SegmentIndex.floorRefName(STREAM, PARTITION, 19)).isPresent());
                seenAtPersist.add(storage.resolveRef(refName(0, 9)).isPresent());
                seenAtPersist.add(storage.resolveRef(refName(10, 19)).isPresent());

                return Result.unitResult();
            });

            assertThat(seenAtPersist).as("[floor written, segment 0-9 kept, segment 10-19 kept] at persist time")
                                     .containsExactly(true, true, true);
            assertThat(storage.resolveRef(refName(0, 9)).isPresent()).as("dropped after").isFalse();
        }

        @Test
        void reclaim_reclaimsNothing_whenTheFloorCannotBeMadeDurable() {
            sealExpired(0, 9);

            assertThat(enforce(() -> Causes.cause("snapshot disk full").<Unit> result())).isZero();
            assertThat(index.listSegments(STREAM, PARTITION)).hasSize(1);
            assertThat(storage.resolveRef(refName(0, 9)).isPresent()).isTrue();
            assertThat(index.reclaimedThrough(STREAM, PARTITION)).as("not recorded until durable").isEqualTo(-1L);
        }

        @Test
        void reclaim_dropsTheSupersededFloor() {
            sealExpired(0, 9);
            enforce(RefDurability.LIVE);
            sealExpired(10, 19);
            enforce(RefDurability.LIVE);

            assertThat(storage.resolveRef(SegmentIndex.floorRefName(STREAM, PARTITION, 9)).isPresent()).isFalse();
            assertThat(storage.resolveRef(SegmentIndex.floorRefName(STREAM, PARTITION, 19)).isPresent()).isTrue();
        }

        /// A segment above a hole is never reclaimed: its floor would anchor a rebuilt watermark past the hole.
        @Test
        void reclaim_neverPassesTheContiguousSealedWatermark() {
            sealExpired(0, 9);
            sealExpired(20, 29);

            assertThat(enforce(RefDurability.LIVE)).isEqualTo(1);
            assertThat(index.listSegments(STREAM, PARTITION)).extracting(SegmentIndex.SegmentRef::startOffset)
                                                             .containsExactly(20L);
            assertThat(index.reclaimedThrough(STREAM, PARTITION)).isEqualTo(9L);
        }
    }

    @Nested
    class Restart {
        /// Acceptance 1: retention reclaims every segment, then a restart from the snapshot on disk -- the
        /// rebuilt watermark is the floor, so a WAL compacted to it starts exactly at `watermark + 1`.
        @Test
        void restart_afterRetentionReclaimedEverySegment_rebuildsTheWatermarkFromTheFloor() {
            var snapshots = snapshotManager();
            sealExpired(0, 9);
            sealExpired(10, 19);

            enforce(RefDurability.snapshotted(snapshots));
            snapshots.forceSnapshot();
            var rebuilt = rebuiltFrom(latest(snapshots));

            assertThat(rebuilt.listSegments(STREAM, PARTITION)).isEmpty();
            assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).isEqualTo(19L);
            assertThat(rebuilt.reclaimedThrough(STREAM, PARTITION)).isEqualTo(19L);
        }

        /// Acceptance 3: a crash between the floor and the deletion it licenses. The snapshot the durability step
        /// wrote is the state such a crash leaves on disk: it holds the floor AND the refs, and rebuilds to the same
        /// watermark, so the next boot still accepts.
        @Test
        void restart_crashBetweenFloorAndDeletion_stillRebuildsTheSameWatermark() {
            var snapshots = snapshotManager();
            var atCrash = new ArrayList<MetadataSnapshot>();
            sealExpired(0, 9);
            sealExpired(10, 19);

            enforce(() -> snapshots.snapshotNow()
                                   .onSuccess(atCrash::add)
                                   .mapToUnit());

            assertThat(atCrash).hasSize(1);
            assertThat(atCrash.getFirst().refs()).containsKeys(SegmentIndex.floorRefName(STREAM, PARTITION, 19),
                                                               refName(0, 9),
                                                               refName(10, 19));
            assertThat(rebuiltFrom(atCrash.getFirst()).lastSealedOffset(STREAM, PARTITION)).isEqualTo(19L);
        }
    }

    @Nested
    class Rebuild {
        @Test
        void rebuild_anchorsAtTheFloor_andExtendsAcrossSurvivingRefs() {
            var rebuilt = rebuiltFrom(Map.of(SegmentIndex.floorRefName(STREAM, PARTITION, 99),
                                             blockId(1),
                                             "streams/" + STREAM + "/" + PARTITION + "/100-199",
                                             blockId(2)));

            assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).isEqualTo(199L);
        }

        /// #1014: a prefix missing below the lowest surviving ref, with no floor recording it as reclaimed, is lost
        /// refs, not reclaimed history -- the watermark stays below it instead of anchoring at the lowest ref.
        @Test
        void rebuild_doesNotTreatAnUnrecordedMissingPrefixAsSealed() {
            var rebuilt = rebuiltFrom(Map.of("streams/" + STREAM + "/" + PARTITION + "/100-199", blockId(2)));

            assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).isEqualTo(-1L);
        }

        @Test
        void rebuild_floorBelowAMissingRange_stopsAtTheFloor() {
            var rebuilt = rebuiltFrom(Map.of(SegmentIndex.floorRefName(STREAM, PARTITION, 49),
                                             blockId(1),
                                             "streams/" + STREAM + "/" + PARTITION + "/100-199",
                                             blockId(2)));

            assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).isEqualTo(49L);
        }

        /// A crash after the new floor was written and before the superseded one was dropped leaves two.
        @Test
        void rebuild_twoFloors_theHigherWins() {
            var rebuilt = rebuiltFrom(Map.of(SegmentIndex.floorRefName(STREAM, PARTITION, 9),
                                             blockId(1),
                                             SegmentIndex.floorRefName(STREAM, PARTITION, 19),
                                             blockId(2)));

            assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).isEqualTo(19L);
            assertThat(rebuilt.reclaimedThrough(STREAM, PARTITION)).isEqualTo(19L);
        }
    }

    private void sealExpired(long start, long end) {
        sink.seal(sealedSegment(STREAM, PARTITION, start, end, (int) (end - start + 1), EXPIRED, EXPIRED, new byte[]{(byte) start, (byte) end}))
            .await()
            .onFailure(cause -> fail(cause.message()));
    }

    private int enforce(RefDurability durability) {
        return retentionEnforcer(storage,
                                 index,
                                 ONE_HOUR_MS,
                                 RetentionEnforcer.SegmentRetentionFloor.NONE,
                                 durability,
                                 SegmentReader.segmentReader(storage, index),
                                 SegmentTierPressure.NONE,
                                 PressureRelief.NONE).enforceNow()
                                                     .await()
                                                     .onFailure(cause -> fail(cause.message()))
                                                     .or(-1);
    }

    private SnapshotManager snapshotManager() {
        return SnapshotManager.snapshotManager(metadataStore, SnapshotConfig.snapshotConfig(snapshotDir, "node-1"));
    }

    private static MetadataSnapshot latest(SnapshotManager snapshots) {
        return snapshots.restoreFromLatest()
                        .onFailure(cause -> fail(cause.message()))
                        .unwrap()
                        .unwrap();
    }

    private static SegmentIndex rebuiltFrom(MetadataSnapshot snapshot) {
        return rebuiltFrom(snapshot.refs());
    }

    private static SegmentIndex rebuiltFrom(Map<String, BlockId> refs) {
        var rebuilt = new SegmentIndex();

        rebuilt.rebuildFromRefs(refs);

        return rebuilt;
    }

    private static String refName(long start, long end) {
        return "streams/" + STREAM + "/" + PARTITION + "/" + start + "-" + end;
    }

    private static BlockId blockId(int seed) {
        return BlockId.blockId(new byte[]{(byte) seed}).unwrap();
    }
}
