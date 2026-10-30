// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.GarbageCollectorConfig;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SharedDelete;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageGarbageCollector.SharedDeleteLimits;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;
import org.pragmatica.storage.TierScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// v-2504 (ruling 743c163d08): `StreamFootprint.adopt` may release the ADOPTED life's OWN refs at or below that life's own
/// durable reclaim floor, IF_IDENTIFIED only. The four conditions, plus "adopt releases no other life".
class AdoptBelowOwnFloorTest {
    private static final SharedDeleteLimits NO_GRACE = new SharedDeleteLimits(0L, 8, 10_000, 60_000L);

    @TempDir
    Path dir;

    private MetadataStore store;
    private SharedTier shared;
    private StorageInstance storage;
    private SnapshotManager snapshots;
    private StorageGarbageCollector collector;
    private SegmentIndex sealing;

    @BeforeEach
    void setUp() {
        store = MetadataStore.inMemoryMetadataStore("streams");
        var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1L << 30).unwrap();

        shared = new SharedTier();
        storage = StorageInstance.storageInstance("streams", List.of(MemoryTier.memoryTier(1 << 20), disk, shared), store);
        storage.sharedDeleteIdentification(SegmentProvenance::isIdentified);
        snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), "streams"));
        collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());
        collector.activate();
        sealing = new SegmentIndex();
    }

    /// Condition 1: no floor recorded at all, nothing is below it.
    @Test
    void anAbsentFloor_releasesNothing() {
        var blocks = sealLife("orders", 7L, 4);

        adoptAndCollect("orders", 7L);

        assertEveryRef("orders@7", 4, true);
        blocks.forEach(id -> assertThat(shared.holds(id)).isTrue());
    }

    /// Condition 1: a floor whose name does not parse is unreadable; it releases nothing.
    @Test
    void anUnreadableFloor_releasesNothing() {
        var blocks = sealLife("orders", 7L, 4);

        floor("stream-floors/orders@7/0/not-a-number");
        floor("stream-floors/orders@7/0/");
        floor("stream-floors/orders@7/zero/3");
        floor("stream-floors/orders@7/0/3/extra");
        adoptAndCollect("orders", 7L);

        assertEveryRef("orders@7", 4, true);
        blocks.forEach(id -> assertThat(shared.holds(id)).isTrue());
    }

    /// Condition 2: floors of the same partition recorded under OTHER incarnations (8, and the unmarked life 0) are never
    /// applied to the adopted life 7.
    @Test
    void aFloorUnderAnotherIncarnation_isNeverApplied() {
        var blocks = sealLife("orders", 7L, 4);

        floor("stream-floors/orders@8/0/3");
        floor("stream-floors/orders/0/3");
        adoptAndCollect("orders", 7L);

        assertEveryRef("orders@7", 4, true);
        blocks.forEach(id -> assertThat(shared.holds(id)).isTrue());
    }

    /// Conditions 3 and 4: an older floor releases a subset of what a newer one would, and nothing above the floor is touched,
    /// including a segment that straddles it.
    @Test
    void anOlderFloor_releasesASubset_andNothingAboveTheFloorIsTouched() {
        var blocks = sealLife("orders", 7L, 5);

        floor("stream-floors/orders@7/0/1");
        adoptAndCollect("orders", 7L);

        assertThat(resolves("streams/orders@7/0/0-0")).as("below the older floor: released").isFalse();
        assertThat(resolves("streams/orders@7/0/1-1")).as("at the older floor: released").isFalse();
        assertThat(shared.holds(blocks.get(0))).isFalse();
        assertThat(shared.holds(blocks.get(1))).isFalse();
        for (var i = 2; i < 5; i++) {
            assertThat(resolves("streams/orders@7/0/" + i + "-" + i)).as("above the floor: kept " + i).isTrue();
            assertThat(shared.holds(blocks.get(i))).as("above the floor: shared copy kept " + i).isTrue();
        }
    }

    /// Condition 4 at the boundary: a segment that starts at or below the floor but ends above it is kept.
    @Test
    void aSegmentStraddlingTheFloor_isKept() {
        var straddling = sealRange("orders", 7L, 0, 0, 1);
        var below = sealRange("orders", 7L, 0, 2, 2);

        floor("stream-floors/orders@7/0/0");
        adoptAndCollect("orders", 7L);

        assertThat(resolves("streams/orders@7/0/0-1")).as("0-1 ends above floor 0").isTrue();
        assertThat(shared.holds(straddling)).isTrue();
        assertThat(resolves("streams/orders@7/0/2-2")).isTrue();
        assertThat(shared.holds(below)).isTrue();
    }

    /// Condition 4, per partition: partition 1's floor is not partition 0's.
    @Test
    void aFloorOfAnotherPartition_isNotApplied() {
        var p0 = sealRange("orders", 7L, 0, 0, 0);
        var p1 = sealRange("orders", 7L, 1, 0, 0);

        floor("stream-floors/orders@7/1/5");
        adoptAndCollect("orders", 7L);

        assertThat(resolves("streams/orders@7/0/0-0")).as("partition 0 has no floor").isTrue();
        assertThat(shared.holds(p0)).isTrue();
        assertThat(resolves("streams/orders@7/1/0-0")).isFalse();
        assertThat(shared.holds(p1)).isFalse();
    }

    /// Adopt releases NO other life: life 5 and the unmarked life 0 keep every ref and shared copy, even refs below their own
    /// durable floors, and so does another stream.
    @Test
    void adopt_releasesNoOtherLife_evenBelowThatLifesOwnFloor() {
        var five = sealLife("orders", 5L, 3);
        var zero = sealLife("orders", 0L, 3);
        var other = sealLife("audit", 2L, 3);
        var seven = sealLife("orders", 7L, 3);

        floor("stream-floors/orders@5/0/2");
        floor("stream-floors/audit@2/0/2");
        floor("stream-floors/orders@7/0/0");
        adoptAndCollect("orders", 7L);

        assertEveryRef("orders@5", 3, true);
        assertEveryRef("orders", 3, true);
        assertEveryRef("audit@2", 3, true);
        (java.util.stream.Stream.of(five, zero, other)).flatMap(List::stream).forEach(id -> assertThat(shared.holds(id)).isTrue());
        assertThat(resolves("streams/orders@7/0/0-0")).as("the adopted life's own, at its own floor").isFalse();
        assertThat(shared.holds(seven.get(0))).isFalse();
        assertThat(shared.holds(seven.get(1))).isTrue();
    }

    /// know e298aa0c00: the BOOT drain (StreamAssembly) serves the unmarked life 0 before any adopt names the committed life, so
    /// when the committed life is a minted one, life 0's refs at or below life 0's own floor are released IF_IDENTIFIED at boot.
    @Test
    void bootDrain_releasesLifeZeroBelowItsOwnFloor_beforeAnyAdoptNamesTheCommittedLife() {
        var zero = sealLife("orders", 0L, 3);

        floor("stream-floors/orders/0/1");
        sealLife("orders", 7L, 1);
        adoptAndCollect("orders", 7L);

        assertThat(resolves("streams/orders/0/0-0")).isFalse();
        assertThat(resolves("streams/orders/0/1-1")).isFalse();
        assertThat(resolves("streams/orders/0/2-2")).as("above life 0's floor").isTrue();
        assertThat(shared.holds(zero.get(0))).isFalse();
        assertThat(shared.holds(zero.get(2))).isTrue();
    }

    /// know e298aa0c00: the drain queue is shared by every stream, so adopt of one stream releases what the boot rebuild queued for the
    /// others — each entry judged against its OWN (stream, incarnation) floor. `orders` floor 1 keeps its segments 2 and 3 although
    /// `audit`'s floor (3) is higher; `audit`'s segment 4 is above its own floor and stays.
    @Test
    void theSharedDrainQueue_judgesEachEntryAgainstItsOwnStreamsFloor() {
        var orders = sealLife("orders", 0L, 4);
        var audit = sealLife("audit", 0L, 5);

        sealLife("billing", 7L, 1);
        floor("stream-floors/orders/0/1");
        floor("stream-floors/audit/0/3");
        adoptOnly("billing", 7L);

        assertThat(resolves("streams/orders/0/0-0")).isFalse();
        assertThat(resolves("streams/orders/0/1-1")).isFalse();
        assertThat(resolves("streams/orders/0/2-2")).as("above orders' floor, below audit's").isTrue();
        assertThat(resolves("streams/orders/0/3-3")).as("above orders' floor, at audit's").isTrue();
        assertThat(resolves("streams/audit/0/3-3")).isFalse();
        assertThat(resolves("streams/audit/0/4-4")).as("above audit's floor").isTrue();
        assertThat(shared.holds(orders.get(1))).isFalse();
        assertThat(shared.holds(orders.get(2))).isTrue();
        assertThat(shared.holds(audit.get(3))).isFalse();
        assertThat(shared.holds(audit.get(4))).isTrue();
    }

    /// IF_IDENTIFIED only: a ref of the adopted life at its own floor whose block carries no identity header (written before
    /// the header existed) is released, but its shared copy stays.
    @Test
    void aLegacyBlockBelowTheOwnFloor_isReleased_butItsSharedCopyStays() {
        var legacy = storage.putRef("streams/orders@7/0/9-9", events(9, System.currentTimeMillis())).await().unwrap();
        var identified = sealRange("orders", 7L, 0, 10, 10);

        floor("stream-floors/orders@7/0/10");
        adoptAndCollect("orders", 7L);

        assertThat(resolves("streams/orders@7/0/9-9")).isFalse();
        assertThat(resolves("streams/orders@7/0/10-10")).isFalse();
        assertThat(shared.holds(identified)).as("identified: gone").isFalse();
        assertThat(shared.holds(legacy)).as("legacy: shared copy stays").isTrue();
    }

    /// MT-55b pin (the PR's cursor test covers only the unfenced commit): the fenced, epoch-carrying commit is the one the
    /// consumer runtime uses; its block must reach private tiers only.
    @Test
    void aFencedCursorCommit_isWrittenToPrivateTiersOnly() {
        CursorStore.cursorStore(storage)
                   .commit("billing", "orders", 0, 41L, org.pragmatica.aether.slice.generation.Epoch.epoch(1L, 1L, 1L))
                   .await()
                   .unwrap();

        var block = storage.resolveRef(CursorStore.buildRefName("billing", "orders", 0)).unwrap();

        assertThat(shared.holds(block)).as("no shared copy of a fenced cursor").isFalse();
    }

    /// AU1 pin: the three marks map to the three authorities; a segment learned LEGACY carries none.
    @Test
    void theIdentityMark_decidesTheAuthority() {
        var ref = SegmentIndex.SegmentRef.segmentRef(0, 0);

        assertThat(RetentionEnforcer.authorityOf(ref.withIdentity(SegmentIndex.IdentityMark.IDENTIFIED))).isEqualTo(SharedDelete.AUTHORISED);
        assertThat(RetentionEnforcer.authorityOf(ref.withIdentity(SegmentIndex.IdentityMark.UNKNOWN))).isEqualTo(SharedDelete.IF_IDENTIFIED);
        assertThat(RetentionEnforcer.authorityOf(ref.withIdentity(SegmentIndex.IdentityMark.LEGACY))).isEqualTo(SharedDelete.NONE);
    }

    private void adoptAndCollect(String stream, long incarnation) {
        var restarted = new SegmentIndex();

        restarted.rebuildFromRefs(store);
        // as StreamAssembly does at boot, before any adopt
        restarted.drainReclaimedBelowFloor().forEach(name -> storage.deleteRef(name, SharedDelete.IF_IDENTIFIED).await());
        StreamFootprint.streamFootprint(storage, store, restarted).adopt(stream, incarnation).await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);
    }

    /// Adopt with no boot drain before it: what the rebuild queued is released by the adopt itself.
    private void adoptOnly(String stream, long incarnation) {
        var restarted = new SegmentIndex();

        restarted.rebuildFromRefs(store);
        StreamFootprint.streamFootprint(storage, store, restarted).adopt(stream, incarnation).await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);
    }

    private List<BlockId> sealLife(String stream, long incarnation, int segments) {
        var blocks = new java.util.ArrayList<BlockId>();

        for (var i = 0; i < segments; i++) {
            blocks.add(sealRange(stream, incarnation, 0, i, i));
        }

        return blocks;
    }

    private BlockId sealRange(String stream, long incarnation, int partition, long start, long end) {
        var now = System.currentTimeMillis();

        sealing.adopt(stream, incarnation);
        storageSegmentSink(storage, sealing).seal(sealedSegment(stream, partition, start, end, (int) (end - start + 1), now, now, events(start + 100 * partition + 1000 * incarnation, now)))
                                            .await()
                                            .unwrap();

        return storage.resolveRef("streams/" + SegmentIndex.durableName(stream, incarnation) + "/" + partition + "/" + start + "-" + end)
                      .unwrap();
    }

    private void floor(String name) {
        storage.putRef(name, RetentionEnforcer.encodeFloor(0L), TierScope.PRIVATE).await().unwrap();
    }

    private boolean resolves(String ref) {
        return storage.resolveRef(ref).isPresent();
    }

    private void assertEveryRef(String durable, int segments, boolean present) {
        for (var i = 0; i < segments; i++) {
            assertThat(resolves("streams/" + durable + "/0/" + i + "-" + i)).as(durable + " " + i).isEqualTo(present);
        }
    }

    private static byte[] events(long offset, long eventTime) {
        return ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(offset).putLong(eventTime).putInt(1).put((byte) 7).array();
    }

    private static final class SharedTier implements StorageTier {
        private final MemoryTier backing = MemoryTier.memoryTier(1L << 30, TierLevel.REMOTE);

        boolean holds(BlockId id) {
            return backing.exists(id).await().unwrap();
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }

        @Override
        public boolean isShared() {
            return true;
        }
    }
}
