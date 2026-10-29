// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
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
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #2258 (streams spec 3.2 items 5-6) end to end on one node: a segment this build seals carries its identity, retention
/// releases an expired one with shared-delete authority, and the shared-tier collection deletes its block from every tier,
/// while no block still inside a declared retention, or still referenced, leaves any tier.
class SegmentSharedDeleteTest {
    private static final long HOUR = 3_600_000L;
    private static final long UNBOUNDED = Long.MAX_VALUE;
    private static final SharedDeleteLimits NO_GRACE = new SharedDeleteLimits(0L, 8, 10_000, 60_000L);
    private static final RetentionPolicy HOURLY = RetentionPolicy.retentionPolicy(UNBOUNDED, UNBOUNDED, HOUR)
                                                                 .withDeclared(RetentionDimensions.AGE);

    @TempDir
    Path dir;

    private MetadataStore store;
    private LocalDiskTier disk;
    private SharedTier shared;
    private StorageInstance storage;
    private SnapshotManager snapshots;
    private StorageGarbageCollector collector;
    private SegmentIndex index;

    @BeforeEach
    void setUp() {
        store = MetadataStore.inMemoryMetadataStore("streams");
        disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1L << 30).unwrap();
        shared = new SharedTier();
        storage = StorageInstance.storageInstance("streams", List.of(MemoryTier.memoryTier(1 << 20), disk, shared), store);
        storage.sharedDeleteIdentification(SegmentProvenance::isIdentified);
        snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(dir.resolve("snapshots"), "streams"));
        collector = StorageGarbageCollector.storageGarbageCollector(storage, store, GarbageCollectorConfig.garbageCollectorConfig());
        collector.activate();
        index = new SegmentIndex();
    }

    /// NT-119: two partitions with identical events seal two blocks; reclaiming one partition's segment deletes its block
    /// from every tier after the collection run, while the other partition still reads its own.
    @Test
    void identicalEventsInTwoPartitions_sealTwoBlocks_andReclaimingOneDeletesOnlyIt() {
        var old = System.currentTimeMillis() - 2 * HOUR;
        var bytes = events(0, old);

        seal("orders", 0, 0, old, bytes);
        seal("orders", 1, 0, old, bytes);

        var p0 = block("streams/orders/0/0-0");
        var p1 = block("streams/orders/1/0-0");

        assertThat(p0).as("the identity header gives each partition its own block").isNotEqualTo(p1);

        enforcer(stream -> stream.equals("orders") ? Option.some(HOURLY) : Option.none(), partitionZeroOnly()).enforceNow().await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(shared.holds(p0)).as("partition 0's block left the shared tier").isFalse();
        assertThat(disk.exists(p0).await().unwrap()).as("and the private one").isFalse();
        assertThat(shared.holds(p1)).as("partition 1's block is untouched").isTrue();
        assertThat(SegmentReader.segmentReader(storage, index).readEvents("orders", 1, 0, 1).await().isSuccess()).isTrue();
    }

    /// The hazard the deletes must never cause: a segment inside its declared retention, or a block still referenced, is
    /// never deleted from any tier, by any pass or run.
    @Test
    void aSegmentInsideItsRetention_neverLeavesAnyTier() {
        var now = System.currentTimeMillis();

        seal("orders", 0, 0, now - 2 * HOUR, events(0, now - 2 * HOUR));
        seal("orders", 0, 1, now, events(1, now));

        var young = block("streams/orders/0/1-1");

        enforcer(_ -> Option.some(HOURLY), RetentionEnforcer.SegmentRetentionFloor.NONE).enforceNow().await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);
        tick();

        assertThat(shared.holds(young)).isTrue();
        assertThat(disk.exists(young).await().unwrap()).isTrue();
        assertThat(storage.resolveRef("streams/orders/0/1-1").isPresent()).isTrue();
    }

    /// The cold-restart hazard (CTO ruling d19f755873): a node that comes back on an EMPTY committed set (a whole-cluster
    /// cold restart without `[backup]`) has no policy for any stream; retention withholds them all and the collection
    /// deletes nothing, however old the segments.
    @Test
    void afterAColdRestartWithAnEmptyCommittedSet_nothingIsDeleted() {
        var old = System.currentTimeMillis() - 100 * HOUR;

        seal("orders", 0, 0, old, events(0, old));

        var id = block("streams/orders/0/0-0");
        var restarted = new SegmentIndex();

        restarted.rebuildFromRefs(store);

        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           restarted,
                                                           _ -> Option.none(),
                                                           HOUR,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           Option.some(SegmentReader.segmentReader(storage, restarted)));

        for (var pass = 0; pass < 3; pass++) {
            enforcer.enforceNow().await();
            collector.collectSharedDeletes(snapshots, NO_GRACE);
        }

        assertThat(shared.holds(id)).isTrue();
        assertThat(storage.resolveRef("streams/orders/0/0-0").isPresent()).isTrue();
    }

    /// Reads of a released range answer `CursorExpired` (the index no longer names it), never a missing block.
    @Test
    void aReadOfAReleasedRange_answersCursorExpired() {
        var now = System.currentTimeMillis();

        seal("orders", 0, 0, now - 2 * HOUR, events(0, now - 2 * HOUR));
        seal("orders", 0, 1, now, events(1, now));
        enforcer(_ -> Option.some(HOURLY), RetentionEnforcer.SegmentRetentionFloor.NONE).enforceNow().await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        var read = TieredStreamReader.tieredStreamReader(index, storage).read("orders", 0, 0, 1).await();

        var expired = read.<Boolean> fold(cause -> cause instanceof StreamError.CursorExpired, _ -> false);

        assertThat(expired).as("read: %s", read).isTrue();
    }

    /// NT-128: a reclaimed-through floor is a fact of this node; its block never reaches the shared tier.
    @Test
    void aFloor_isWrittenToPrivateTiersOnly() {
        var old = System.currentTimeMillis() - 2 * HOUR;

        seal("orders", 0, 0, old, events(0, old));
        seal("orders", 0, 1, System.currentTimeMillis(), events(1, System.currentTimeMillis()));
        enforcer(_ -> Option.some(HOURLY), RetentionEnforcer.SegmentRetentionFloor.NONE).enforceNow().await();

        var floor = storage.resolveRef(index.floorRefOf("orders", 0, 0)).unwrap();

        assertThat(shared.holds(floor)).isFalse();
        assertThat(disk.exists(floor).await().unwrap()).isTrue();
    }

    /// NT-127: a cursor commit writes private tiers only, and a rewind that recommits an earlier offset reads back.
    @Test
    void aCursorCommit_isWrittenToPrivateTiersOnly_andARewindReadsBack() {
        var cursors = CursorStore.cursorStore(storage);

        cursors.commit("billing", "orders", 0, 41L).await();
        cursors.commit("billing", "orders", 0, 42L).await();
        cursors.commit("billing", "orders", 0, 41L).await();

        var block = storage.resolveRef("cursors/billing/orders/0").unwrap();

        assertThat(shared.holds(block)).isFalse();
        assertThat(cursors.fetch("billing", "orders", 0).await().unwrap()).isEqualTo(Option.some(41L));
    }

    /// NT-122: at rebuild a `streams/` ref at or below the durable reclaim floor (left by a crash between the floor write
    /// and the release) is not installed; released without knowing, its new-format block leaves the shared tier after the
    /// collection run, while a legacy block's shared copy stays.
    @Test
    void aRefBelowTheDurableFloorAtRebuild_isReleased_andOnlyAnIdentifiedBlockLeavesTheSharedTier() {
        var now = System.currentTimeMillis();

        seal("orders", 0, 0, now, events(0, now));
        storage.putRef("streams/orders/0/1-1", events(1, now)).await();
        seal("orders", 0, 2, now, events(2, now));
        storage.putRef("stream-floors/orders/0/1", RetentionEnforcer.encodeFloor(1), org.pragmatica.storage.TierScope.PRIVATE).await();

        var identified = block("streams/orders/0/0-0");
        var legacy = block("streams/orders/0/1-1");
        var restarted = new SegmentIndex();

        restarted.rebuildFromRefs(store);

        var released = restarted.drainReclaimedBelowFloor();

        assertThat(released).containsExactlyInAnyOrder("streams/orders/0/0-0", "streams/orders/0/1-1");
        assertThat(restarted.listSegments("orders", 0)).extracting(SegmentIndex.SegmentRef::startOffset).containsExactly(2L);

        released.forEach(name -> storage.deleteRef(name, SharedDelete.IF_IDENTIFIED).await());
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(shared.holds(identified)).as("identified: gone from the shared tier").isFalse();
        assertThat(shared.holds(legacy)).as("legacy: its shared copy stays").isTrue();
    }

    /// NT-129: a legacy segment (no identity header) is released without shared-delete authority however retention meets
    /// it, and its shared copy is never deleted.
    @Test
    void aLegacySegment_neverLeavesTheSharedTier() {
        var old = System.currentTimeMillis() - 2 * HOUR;

        storage.putRef("streams/orders/0/0-0", events(0, old)).await();
        index.addSegment("orders", 0, 0, 0, old);
        seal("orders", 0, 1, System.currentTimeMillis(), events(1, System.currentTimeMillis()));

        var legacy = block("streams/orders/0/0-0");

        enforcer(_ -> Option.some(HOURLY), RetentionEnforcer.SegmentRetentionFloor.NONE).enforceNow().await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(storage.resolveRef("streams/orders/0/0-0").isPresent()).as("released").isFalse();
        assertThat(shared.holds(legacy)).isTrue();
    }

    /// NT-130 (as ruled, d19f755873): an explicit destroy releases the stream's segments without knowing, and their blocks
    /// leave the shared tier; adopting another life releases no other life.
    @Test
    void aDestroy_deletesTheStreamsBlocksEverywhere_andAnAdoptReleasesNoOtherLife() {
        var now = System.currentTimeMillis();
        var footprint = StreamFootprint.streamFootprint(storage, store, index);

        seal("orders", 0, 0, now, events(0, now));
        seal("audit", 0, 0, now, events(5, now));

        var orders = block("streams/orders/0/0-0");
        var audit = block("streams/audit/0/0-0");

        footprint.forget("orders").await();
        footprint.adopt("audit", 7L).await();
        collector.collectSharedDeletes(snapshots, NO_GRACE);

        assertThat(shared.holds(orders)).as("the destroyed stream's block is gone").isFalse();
        assertThat(shared.holds(audit)).as("the other life of audit is kept").isTrue();
        assertThat(storage.resolveRef("streams/audit/0/0-0").isPresent()).isTrue();
    }

    /// A release that fails puts the segment back and ends the partition's pass, so no interior gap opens.
    @Test
    void aFailedRelease_putsTheSegmentBack_andStopsThePartitionsPass() {
        var old = System.currentTimeMillis() - 2 * HOUR;

        seal("orders", 0, 0, old, events(0, old));
        seal("orders", 0, 1, old, events(1, old));
        seal("orders", 0, 2, System.currentTimeMillis(), events(2, System.currentTimeMillis()));

        var failing = new FailingRelease(storage);

        failing.failOn.set(true);
        RetentionEnforcer.retentionEnforcer(failing,
                                            index,
                                            _ -> Option.some(HOURLY),
                                            HOUR,
                                            RetentionEnforcer.SegmentRetentionFloor.NONE,
                                            Option.none())
                         .enforceNow()
                         .await();

        assertThat(index.listSegments("orders", 0)).extracting(SegmentIndex.SegmentRef::startOffset).containsExactly(0L, 1L, 2L);
        assertThat(storage.resolveRef("streams/orders/0/0-0").isPresent()).isTrue();
    }

    /// NT-131 / NT-126 (the delete codes): three consecutive failing runs raise `stream-segment-delete-failing` once, and the
    /// first run without a failure closes it.
    @Test
    void failingRuns_raiseDeleteFailingOnce_andACleanRunClosesIt() throws InterruptedException {
        var warnings = new CopyOnWriteArrayList<OperatorWarning>();
        var collection = SharedSegmentCollection.sharedSegmentCollection(collector, snapshots, NO_GRACE);
        var old = System.currentTimeMillis() - 2 * HOUR;

        collection.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add), "node-1");
        seal("orders", 0, 0, old, events(0, old));
        seal("orders", 0, 1, System.currentTimeMillis(), events(1, System.currentTimeMillis()));
        enforcer(_ -> Option.some(HOURLY), RetentionEnforcer.SegmentRetentionFloor.NONE).enforceNow().await();
        shared.failDeletes.set(true);

        for (var run = 0; run < 4; run++) {
            collection.runOnce();
        }

        shared.failDeletes.set(false);
        collection.runOnce();
        Thread.sleep(300);

        assertThat(warnings).extracting(OperatorWarning::code)
                            .containsExactly(OperatorWarningCode.STREAM_SEGMENT_DELETE_FAILING,
                                             OperatorWarningCode.STREAM_SEGMENT_DELETE_RECOVERED);
        assertThat(warnings.getFirst().subject()).isEqualTo("node node-1 storage streams");
    }

    private RetentionEnforcer enforcer(StreamRetentionSource source, RetentionEnforcer.SegmentRetentionFloor floor) {
        return RetentionEnforcer.retentionEnforcer(storage, index, source, HOUR, floor, Option.none());
    }

    /// Only partition 0 may be reclaimed: partition 1's floor holds everything.
    private static RetentionEnforcer.SegmentRetentionFloor partitionZeroOnly() {
        return (_, partition) -> partition == 0
                                 ? Long.MAX_VALUE
                                 : -1L;
    }

    private void seal(String stream, int partition, long offset, long eventTime, byte[] bytes) {
        storageSegmentSink(storage, index).seal(sealedSegment(stream, partition, offset, offset, 1, eventTime, eventTime, bytes))
                                          .await()
                                          .unwrap();
    }

    private BlockId block(String refName) {
        return storage.resolveRef(refName).unwrap();
    }

    private void tick() {
        var noGrace = StorageGarbageCollector.storageGarbageCollector(storage, store, new GarbageCollectorConfig(0L, 500));

        noGrace.activate();
        noGrace.collectGarbage();
    }

    private static byte[] events(long offset, long eventTime) {
        return ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(offset).putLong(eventTime).putInt(1).put((byte) 7).array();
    }

    /// The DHT stand-in: shared, and able to fail its deletes.
    private static final class SharedTier implements StorageTier {
        private final MemoryTier backing = MemoryTier.memoryTier(1L << 30, TierLevel.REMOTE);
        final AtomicBoolean failDeletes = new AtomicBoolean();

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
            return failDeletes.get()
                   ? Causes.cause("DHT write quorum unreachable").promise()
                   : backing.delete(id);
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

    /// The real instance, whose release with authority can be made to fail.
    private static final class FailingRelease implements StorageInstance {
        private final StorageInstance delegate;
        final AtomicBoolean failOn = new AtomicBoolean();

        FailingRelease(StorageInstance delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Unit> deleteRef(String name, SharedDelete authority) {
            return failOn.get()
                   ? Causes.cause("metadata store refused the release").promise()
                   : delegate.deleteRef(name, authority);
        }

        @Override
        public Promise<Unit> deleteRef(String name) {
            return delegate.deleteRef(name);
        }

        @Override
        public Promise<BlockId> putRef(String name, byte[] content, org.pragmatica.storage.TierScope scope) {
            return delegate.putRef(name, content, scope);
        }

        @Override
        public Promise<BlockId> putRef(String name, byte[] content) {
            return delegate.putRef(name, content);
        }

        @Override
        public Option<BlockId> resolveRef(String name) {
            return delegate.resolveRef(name);
        }

        @Override
        public Promise<BlockId> put(byte[] content) {
            return delegate.put(content);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public Promise<Unit> createRef(String name, BlockId id) {
            return delegate.createRef(name, id);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public List<StorageInstance.TierInfo> tierInfo() {
            return delegate.tierInfo();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }
}
