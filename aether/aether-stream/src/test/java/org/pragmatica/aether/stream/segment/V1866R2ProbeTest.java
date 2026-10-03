// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// v1866 round 2 probes, adapted to the incarnation model (#1278 review round 3). Each probe asserts what a recreated
/// stream is owed — it starts from nothing — and each was RED at 7f698c3cd. Production gives every cluster create of a
/// name a new incarnation ([StreamConfig#incarnation], minted by the manager when it commits through a cluster); these
/// managers run without a cluster, so each life sets its incarnation explicitly, as the cluster would have minted it.
/// The original probes re-created the stream with an IDENTICAL config, which no production path does, so the lives
/// differ here only in the field production differs in. The fifth probe (kill points after a durable tombstone) pinned
/// the tombstone protocol, which the incarnation key made redundant and which was removed; it is replaced by the probe
/// that an old life's surviving refs never anchor the new life.
class V1866R2ProbeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int RING_EVENTS = 4;
    private static final int FIRST_LIFE = 60;
    private static final int SECOND_LIFE = 10;
    private static final long OLD_LIFE = 1L;
    private static final long NEW_LIFE = 2L;

    @TempDir
    Path walDir;

    @TempDir
    Path storageDir;

    private final HookedStore metadata = new HookedStore(MetadataStore.inMemoryMetadataStore("streams"));

    /// KEY (down node): X never processed the config removal. The recreated stream's committed config names a new
    /// life, so X opens neither the old WAL nor the old refs.
    @Test
    void downNode_recreatedStream_inheritsTheOldLife() {
        var storage = storage();
        var firstIndex = new SegmentIndex();
        var first = manager(storage, firstIndex, footprint(storage, firstIndex));

        createStream(first, OLD_LIFE);
        publish(first, FIRST_LIFE, "old-");
        awaitSealed(firstIndex);
        first.close(); // X goes down; the destroy happens on the other nodes only

        var xIndex = rebuilt();
        var x = manager(storage, xIndex, footprint(storage, xIndex));

        createStream(x, NEW_LIFE);
        var info = x.partitionInfo(STREAM, PARTITION).onFailure(cause -> fail(cause.message())).unwrap();
        x.close();

        assertThat(info.headOffset()).as("X's new life starts from nothing").isEqualTo(-1L);
    }

    /// The destroy's reclamation did not happen (here: no footprint at all, standing for a crash or a failed drop):
    /// the old life's refs survive and its WAL is gone. The recreate is a new life and starts at offset 0 regardless.
    @Test
    void tombstoneNotDurable_walsAlreadyGone_recreateInheritsTheOldWatermark() {
        var storage = storage();
        var firstIndex = new SegmentIndex();
        var first = manager(storage, firstIndex, StreamFootprint.NONE);

        firstIndex.adopt(STREAM, OLD_LIFE);
        createStream(first, OLD_LIFE);
        publish(first, FIRST_LIFE, "old-");
        awaitSealed(firstIndex);
        first.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        first.close();

        assertThat(refsOf(STREAM)).as("fixture: the old life's segment refs survived the destroy").isNotEmpty();

        var secondIndex = rebuilt();
        var second = manager(storage, secondIndex, footprint(storage, secondIndex));

        createStream(second, NEW_LIFE);
        var offsets = publish(second, SECOND_LIFE, "new-");

        awaitSealedThrough(secondIndex, SECOND_LIFE - RING_EVENTS - 1);
        second.close();

        assertThat(offsets).as("the recreated stream starts at offset 0").startsWith(0L);
    }

    /// Attack 3: a destroy that lands while a retention pass is between its floor write and its reclaim. The pass was
    /// decided under the old life and records nothing in the new one, and the new life writes its own durable floor.
    @Test
    void destroyDuringRetentionPass_recreatedStreamReclaimsWithoutADurableFloor() {
        var storage = storage();
        var index = new SegmentIndex();
        var sink = storageSegmentSink(storage, index);
        var footprint = footprint(storage, index);

        footprint.adopt(STREAM, OLD_LIFE).await();
        seal(sink, 0, 9);
        seal(sink, 10, 19);
        enforce(storage, index, () -> footprint.forget(STREAM).await().mapToUnit());

        var inMemoryAfterDestroy = index.reclaimedThrough(STREAM, PARTITION);

        footprint.adopt(STREAM, NEW_LIFE).await();
        seal(sink, 0, 9);
        enforce(storage, index, RefDurability.LIVE);

        assertThat(index.listSegments(STREAM, PARTITION)).as("fixture: the new segment was reclaimed").isEmpty();
        assertThat(metadata.listAllRefs()).as("in-memory floor after destroy was %d; the new life's reclaim needs its own durable floor",
                                              inMemoryAfterDestroy)
                                          .containsKey(SegmentIndex.floorRefName(SegmentIndex.durableName(STREAM, NEW_LIFE),
                                                                                 PARTITION,
                                                                                 9));
    }

    /// Attack 3, durable variant: the destroy completes between the floor's block write and its ref repoint, so the
    /// old life's floor ref lands after the destroy. It is under the old life's name, and the recreate never reads it.
    @Test
    void destroyBetweenFloorBlockWriteAndRepoint_leavesADurableFloor_thatAnchorsTheRecreate() {
        var storage = storage();
        var index = new SegmentIndex();
        var sink = storageSegmentSink(storage, index);
        var footprint = footprint(storage, index);
        var fired = new java.util.concurrent.atomic.AtomicBoolean(false);

        footprint.adopt(STREAM, OLD_LIFE).await();
        seal(sink, 0, 9);
        seal(sink, 10, 19);
        metadata.beforePut(ref -> {
            if (ref.startsWith(SegmentIndex.floorRefPrefix(SegmentIndex.durableName(STREAM, OLD_LIFE), PARTITION))
                && fired.compareAndSet(false, true)) {
                footprint.forget(STREAM).await().onFailure(cause -> fail(cause.message()));
            }
        });
        enforce(storage, index, RefDurability.LIVE);
        metadata.beforePut(_ -> {});

        assertThat(fired.get()).as("fixture: the destroy ran inside the floor write").isTrue();
        assertThat(rebuiltIn(NEW_LIFE).lastSealedOffset(STREAM, PARTITION)).as("a recreate after restart must not anchor at the destroyed stream's floor")
                                                                            .isEqualTo(-1L);
    }

    /// Replaces the tombstone kill-point probe: whatever an old life leaves in storage — segment refs and floors, of any
    /// partition, half-dropped or untouched — the new life rebuilds to nothing, another stream is untouched, and
    /// adopting the new life reclaims the old life's refs.
    @Test
    void oldLifeRefs_neverAnchorTheNewLife_andAreReclaimedOnAdoption() {
        var block = BlockId.blockId(new byte[]{1}).unwrap();
        var storage = storage();
        var old = SegmentIndex.durableName(STREAM, OLD_LIFE);

        metadata.putRef("streams/" + old + "/0/0-99", block);
        metadata.putRef("streams/" + old + "/0/100-199", block);
        metadata.putRef("streams/" + old + "/1/0-49", block);
        metadata.putRef(SegmentIndex.floorRefName(old, 0, 99), block);
        metadata.putRef(SegmentIndex.floorRefName(old, 1, 49), block);
        metadata.putRef("streams/other/0/0-9", block);

        var index = rebuiltIn(NEW_LIFE);

        assertThat(index.lastSealedOffset(STREAM, 0)).isEqualTo(-1L);
        assertThat(index.lastSealedOffset(STREAM, 1)).isEqualTo(-1L);
        assertThat(index.reclaimedThrough(STREAM, 0)).isEqualTo(-1L);
        assertThat(index.lastSealedOffset("other", 0)).as("another stream is untouched").isEqualTo(9L);

        var fresh = new SegmentIndex();

        fresh.rebuildFromRefs(metadata);
        footprint(storage, fresh).adopt(STREAM, NEW_LIFE).await().onFailure(cause -> fail(cause.message()));
        assertThat(metadata.listAllRefs().keySet()).as("adopting the new life reclaimed the old one")
                                                   .containsExactly("streams/other/0/0-9");
    }

    private StorageInstance storage() {
        return StorageInstance.storageInstance("streams",
                                               List.of(MemoryTier.memoryTier(ONE_GB),
                                                       LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                               metadata);
    }

    private StreamFootprint footprint(StorageInstance storage, SegmentIndex index) {
        return StreamFootprint.streamFootprint(storage, metadata, index);
    }

    private StreamPartitionManager manager(StorageInstance storage, SegmentIndex index, StreamFootprint footprint) {
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(storageSegmentSink(storage, index)),
                                             Option.some(walDir),
                                             index::lastSealedOffset);

        manager.streamFootprint(footprint);

        return manager;
    }

    private SegmentIndex rebuilt() {
        var index = new SegmentIndex();

        index.rebuildFromRefs(metadata);

        return index;
    }

    /// What a restarted node holds once the committed config of `incarnation` hydrates: the rebuilt index, serving it.
    private SegmentIndex rebuiltIn(long incarnation) {
        var index = rebuilt();

        index.adopt(STREAM, incarnation);

        return index;
    }

    private Map<String, BlockId> refsOf(String stream) {
        var refs = new HashMap<>(metadata.listAllRefs());

        refs.keySet().removeIf(ref -> SegmentIndex.lifeOf(ref).filter(life -> life.streamName().equals(stream)).isEmpty());

        return refs;
    }

    private static void seal(StorageSegmentSink sink, long start, long end) {
        sink.seal(SealedSegment.sealedSegment(STREAM, PARTITION, start, end, (int) (end - start + 1), 1000L, 1000L, new byte[]{(byte) start}))
            .await()
            .onFailure(cause -> fail(cause.message()));
    }

    private static void enforce(StorageInstance storage, SegmentIndex index, RefDurability durability) {
        RetentionEnforcer.retentionEnforcer(storage,
                                            index,
                                            3_600_000L,
                                            RetentionEnforcer.SegmentRetentionFloor.NONE,
                                            durability,
                                            SegmentReader.segmentReader(storage, index),
                                            SegmentTierPressure.NONE,
                                            PressureRelief.NONE)
                         .enforceNow()
                         .await()
                         .onFailure(cause -> fail(cause.message()));
    }

    private static void createStream(StreamPartitionManager manager, long incarnation) {
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(RING_EVENTS, 1024 * 1024, 600_000),
                                                       "earliest")
                                         .withIncarnation(incarnation))
               .onFailure(cause -> fail(cause.message()));
    }

    private static List<Long> publish(StreamPartitionManager manager, int count, String prefix) {
        var offsets = new ArrayList<Long>();

        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, (prefix + i).getBytes(UTF_8), 1000L + i)
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(offsets::add);
        }

        return offsets;
    }

    /// Lets the sealer drain before the manager closes, so no seal is still retrying while the temp dirs are removed.
    private static void awaitSealedThrough(SegmentIndex index, long through) {
        var deadline = System.nanoTime() + 60_000_000_000L;

        while (index.lastSealedOffset(STREAM, PARTITION) < through && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitSealed(SegmentIndex index) {
        var deadline = System.nanoTime() + 60_000_000_000L;

        while (index.lastSealedOffset(STREAM, PARTITION) < FIRST_LIFE - RING_EVENTS - 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("fixture: sealed").isGreaterThanOrEqualTo(FIRST_LIFE - RING_EVENTS - 1);
    }

    /// Delegates everything; runs a hook BEFORE each removeRef.
    private static final class HookedStore implements MetadataStore {
        private final MetadataStore delegate;
        private volatile Consumer<String> beforeRemove = _ -> {};
        private volatile Consumer<String> beforePut = _ -> {};

        void beforePut(Consumer<String> hook) {
            this.beforePut = hook;
        }

        HookedStore(MetadataStore delegate) {
            this.delegate = delegate;
        }

        void beforeRemove(Consumer<String> hook) {
            this.beforeRemove = hook;
        }

        @Override
        public Option<BlockId> removeRef(String refName) {
            beforeRemove.accept(refName);

            return delegate.removeRef(refName);
        }

        @Override
        public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
            return delegate.getLifecycle(blockId);
        }

        @Override
        public void createLifecycle(BlockLifecycle lifecycle) {
            delegate.createLifecycle(lifecycle);
        }

        @Override
        public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.claimBlock(blockId, sentinel);
        }

        @Override
        public boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.releaseClaim(blockId, sentinel);
        }

        @Override
        public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
            return delegate.computeLifecycle(blockId, updater);
        }

        @Override
        public void removeLifecycle(BlockId blockId) {
            delegate.removeLifecycle(blockId);
        }

        @Override
        public void putRef(String refName, BlockId blockId) {
            beforePut.accept(refName);
            delegate.putRef(refName, blockId);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
        }

        @Override
        public Option<BlockId> replaceRef(String refName, BlockId blockId) {
            beforePut.accept(refName);
            return delegate.replaceRef(refName, blockId);
        }

        @Override
        public boolean containsBlock(BlockId blockId) {
            return delegate.containsBlock(blockId);
        }

        @Override
        public String instanceName() {
            return delegate.instanceName();
        }

        @Override
        public List<BlockLifecycle> listBlocksByTier(TierLevel tier) {
            return delegate.listBlocksByTier(tier);
        }

        @Override
        public List<BlockLifecycle> listAllLifecycles() {
            return delegate.listAllLifecycles();
        }

        @Override
        public Map<String, BlockId> listAllRefs() {
            return delegate.listAllRefs();
        }

        @Override
        public long currentEpoch() {
            return delegate.currentEpoch();
        }

        @Override
        public void restoreLifecycles(List<BlockLifecycle> entries) {
            delegate.restoreLifecycles(entries);
        }

        @Override
        public void restoreRefs(Map<String, BlockId> refs) {
            delegate.restoreRefs(refs);
        }

        @Override
        public void restoreEpoch(long epoch) {
            delegate.restoreEpoch(epoch);
        }
    }
}
