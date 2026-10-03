// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.DurableSealedOffsetSource;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.GarbageCollectorConfig;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataSnapshot;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// v1866 (adversarial verification of #1278): the composed chain no existing test runs end to end -- seal through the
/// production sink, snapshot, compact the WAL off the snapshot on disk, run the production-shaped retention with
/// [RetentionEnforcer.FloorDurability#snapshotted], and "crash" at every window by taking the snapshot that is on disk at
/// that instant. Each crash state is rebuilt the way boot rebuilds it and recovered from a copy of the WAL. Every
/// window must recover the survivors at their stored offsets with no lost head; the negative control (the pre-#1278
/// state: refs dropped, no floor) must refuse with [StreamError.WalHeadLost], so the instrument can see a refusal.
class RetentionFloorCrashWindowTest {
    private static final String STREAM = "crashwin";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 4;
    private static final int EVENTS = 200;
    private static final int PAYLOAD = 70 * 1024;
    private static final long SEALED_AT_LEAST = EVENTS - RING_EVENTS - 1;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long ONE_HOUR_MS = 3_600_000L;

    @TempDir
    Path walDir;

    @TempDir
    Path storageDir;

    @TempDir
    Path snapshotDir;

    @TempDir
    Path recoveryRoot;

    @Test
    void retention_crashAtEveryWindow_recoversTheSurvivorsAtStoredOffsets_andTheFloorlessStateRefuses() {
        var midDrop = new AtomicBoolean(false);
        var store = new SnapshotOnFirstSegmentDrop(MetadataStore.inMemoryMetadataStore("streams"), midDrop);
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(ONE_GB),
                                                              LocalDiskTier.localDiskTier(storageDir.resolve("blocks"), ONE_GB)
                                                                           .unwrap()),
                                                      store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(snapshotDir, "node-1"));
        var index = new SegmentIndex();
        var sink = storageSegmentSink(storage, index);
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(sink),
                                             Option.some(walDir),
                                             index::lastSealedOffset,
                                             DurableSealedOffsetSource.fromLatestSnapshot(snapshots));

        createStream(manager).onFailure(cause -> fail(cause.message()));
        IntStream.range(0, EVENTS).forEach(i -> manager.publishLocal(STREAM, PARTITION, payload(i), 1000L + i)
                                                       .onFailure(cause -> fail(cause.message())));
        awaitSealed(index);
        snapshots.forceSnapshot();
        manager.truncateWalsToSealed();
        manager.close();

        var sealedThrough = index.lastSealedOffset(STREAM, PARTITION);
        assertThat(sealedThrough).as("fixture: sealed through the ring's overflow").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var onDisk = new LinkedHashMap<String, MetadataSnapshot>();
        store.onMidDrop(() -> {
            snapshots.forceSnapshot();
            onDisk.put("W3 periodic snapshot after the first segment ref was dropped", latest(snapshots));
        });

        var snapshotted = RetentionEnforcer.FloorDurability.snapshotted(snapshots);
        var removed = RetentionEnforcer.retentionEnforcer(storage,
                                                          index,
                                                          ONE_HOUR_MS,
                                                          RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                          () -> {
                                                              onDisk.put("W1 floor written, not yet durable", latest(snapshots));
                                                              var persisted = snapshotted.persist();
                                                              onDisk.put("W2 floor durable, no ref dropped", latest(snapshots));
                                                              midDrop.set(true);
                                                              return persisted;
                                                          },
                                                          SegmentReader.segmentReader(storage, index),
                                                          SegmentTierPressure.NONE,
                                                          PressureRelief.NONE)
                                       .enforceNow()
                                       .await()
                                       .onFailure(cause -> fail(cause.message()))
                                       .unwrap();

        assertThat(removed).as("fixture: retention reclaimed segments").isPositive();
        assertThat(index.listSegments(STREAM, PARTITION)).as("fixture: every segment reclaimed").isEmpty();
        onDisk.put("W4 every ref dropped, no snapshot since", latest(snapshots));
        snapshots.forceSnapshot();
        onDisk.put("W5 every ref dropped, next periodic snapshot", latest(snapshots));

        assertThat(onDisk).as("fixture: all five windows captured").hasSize(5);
        assertThat(onDisk.get("W3 periodic snapshot after the first segment ref was dropped").refs()
                         .keySet()
                         .stream()
                         .filter(ref -> ref.startsWith("streams/"))
                         .count()).as("fixture: W3 really is mid-drop")
                                  .isPositive()
                                  .isLessThan(onDisk.get("W2 floor durable, no ref dropped").refs()
                                                    .keySet()
                                                    .stream()
                                                    .filter(ref -> ref.startsWith("streams/"))
                                                    .count());

        onDisk.forEach((window, snapshot) -> assertRecovers(window, snapshot.refs(), sealedThrough));

        var floorless = new HashMap<>(onDisk.get("W5 every ref dropped, next periodic snapshot").refs());
        floorless.keySet().removeIf(ref -> ref.startsWith("stream-floors/"));
        assertRefuses(floorless);
    }

    /// Pins the production factory itself (the one `AetherNode` calls): at the moment retention drops its first
    /// segment ref, the snapshot ON DISK already holds the floor. A factory that wired a non-durable floor step
    /// would leave the pre-retention snapshot on disk there, and this goes red.
    @Test
    void productionFactory_floorIsOnDiskBeforeTheFirstSegmentRefIsDropped() {
        var armed = new AtomicBoolean(true);
        var store = new SnapshotOnFirstSegmentDrop(MetadataStore.inMemoryMetadataStore("streams"), armed);
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(ONE_GB),
                                                              LocalDiskTier.localDiskTier(storageDir.resolve("blocks"), ONE_GB)
                                                                           .unwrap()),
                                                      store);
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(snapshotDir, "node-1"));
        var collector = StorageGarbageCollector.storageGarbageCollector(storage,
                                                                        store,
                                                                        GarbageCollectorConfig.garbageCollectorConfig());
        var index = new SegmentIndex();
        var sink = storageSegmentSink(storage, index);
        var floorOnDiskAtFirstDrop = new ArrayList<Boolean>();

        IntStream.of(0, 10).forEach(start -> sink.seal(SealedSegment.sealedSegment(STREAM, PARTITION, start, start + 9, 10, 1000L, 1000L,
                                                                                   new byte[]{(byte) start}))
                                                 .await()
                                                 .onFailure(cause -> fail(cause.message())));
        snapshots.forceSnapshot();
        store.onMidDrop(() -> floorOnDiskAtFirstDrop.add(latest(snapshots).refs()
                                                                            .containsKey(SegmentIndex.floorRefName(STREAM, PARTITION, 19))));

        var removed = RetentionEnforcer.retentionEnforcer(storage,
                                                          index,
                                                          ONE_HOUR_MS,
                                                          RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                          snapshots,
                                                          collector,
                                                          SegmentReader.segmentReader(storage, index),
                                                          SegmentTierPressure.NONE)
                                       .enforceNow()
                                       .await()
                                       .onFailure(cause -> fail(cause.message()))
                                       .unwrap();

        assertThat(removed).as("fixture: both segments reclaimed").isEqualTo(2);
        assertThat(floorOnDiskAtFirstDrop).as("the floor is in the on-disk snapshot when the first ref is dropped")
                                          .containsExactly(true);
    }

    private void assertRecovers(String window, Map<String, BlockId> refs, long sealedThrough) {
        var rebuilt = new SegmentIndex();
        rebuilt.rebuildFromRefs(refs);
        assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).as(window + ": rebuilt watermark").isEqualTo(sealedThrough);

        var lostBefore = StreamPartitionManager.walRecoveryHeadsLost();
        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(copyOfWal(window)), rebuilt::lastSealedOffset);

        try {
            createStream(recovered).onFailure(cause -> fail(window + ": recovery refused: " + cause.message()));
            var info = recovered.partitionInfo(STREAM, PARTITION).onFailure(cause -> fail(cause.message())).unwrap();
            assertThat(info.headOffset()).as(window + ": head").isEqualTo(EVENTS - 1L);
            recovered.readLocal(STREAM, PARTITION, sealedThrough + 1, EVENTS)
                     .onFailure(cause -> fail(window + ": " + cause.message()))
                     .onSuccess(events -> {
                         assertThat(events).as(window + ": survivors").hasSize((int) (EVENTS - 1 - sealedThrough));
                         events.forEach(event -> assertThat(event.offset()).as(window + ": stored offset")
                                                                           .isEqualTo(payloadIndex(event.data())));
                     });
            assertThat(StreamPartitionManager.walRecoveryHeadsLost() - lostBefore).as(window + ": no head lost").isZero();
        } finally {
            recovered.close();
        }
    }

    private void assertRefuses(Map<String, BlockId> refs) {
        var rebuilt = new SegmentIndex();
        rebuilt.rebuildFromRefs(refs);
        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(copyOfWal("floorless")), rebuilt::lastSealedOffset);

        try {
            var create = createStream(recovered);
            assertThat(create.isFailure()).as("control: refs dropped with no floor must refuse").isTrue();
            create.onFailure(cause -> assertThat(cause.stream().toList()).singleElement()
                                                                         .isInstanceOf(StreamError.WalHeadLost.class));
        } finally {
            recovered.close();
        }
    }

    private Path copyOfWal(String window) {
        var target = recoveryRoot.resolve("w" + Math.abs(window.hashCode()));

        try (var paths = Files.walk(walDir)) {
            paths.forEach(source -> copy(source, target.resolve(walDir.relativize(source).toString())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return target;
    }

    private static void copy(Path source, Path target) {
        try {
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
            } else {
                Files.copy(source, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MetadataSnapshot latest(SnapshotManager snapshots) {
        return snapshots.restoreFromLatest()
                        .onFailure(cause -> fail(cause.message()))
                        .unwrap()
                        .unwrap();
    }

    private static void awaitSealed(SegmentIndex index) {
        var deadline = System.currentTimeMillis() + 60_000;

        while (index.lastSealedOffset(STREAM, PARTITION) < SEALED_AT_LEAST && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(10_000_000);
        }
    }

    private static Result<?> createStream(StreamPartitionManager manager) {
        return manager.createStream(StreamConfig.streamConfig(STREAM,
                                                              1,
                                                              RetentionPolicy.retentionPolicy(RING_EVENTS, 1024 * 1024, 600_000),
                                                              "earliest"));
    }

    private static byte[] payload(int i) {
        var bytes = new byte[PAYLOAD];

        Arrays.fill(bytes, (byte) 'x');
        bytes[0] = (byte) (i >> 8);
        bytes[1] = (byte) i;

        return bytes;
    }

    private static int payloadIndex(byte[] data) {
        return ((data[0] & 0xff) << 8) | (data[1] & 0xff);
    }

    /// Delegates everything; once armed, runs the hook right after the FIRST `streams/` ref is removed -- a periodic
    /// snapshot landing in the middle of retention's ref drops.
    private static final class SnapshotOnFirstSegmentDrop implements MetadataStore {
        private final MetadataStore delegate;
        private final AtomicBoolean armed;
        private final AtomicBoolean fired = new AtomicBoolean(false);
        private volatile Runnable hook = () -> {};

        SnapshotOnFirstSegmentDrop(MetadataStore delegate, AtomicBoolean armed) {
            this.delegate = delegate;
            this.armed = armed;
        }

        void onMidDrop(Runnable hook) {
            this.hook = hook;
        }

        @Override
        public Option<BlockId> removeRef(String refName) {
            var removed = delegate.removeRef(refName);

            if (armed.get() && refName.startsWith("streams/") && fired.compareAndSet(false, true)) {
                hook.run();
            }

            return removed;
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
            delegate.putRef(refName, blockId);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
        }

        @Override
        public Option<BlockId> replaceRef(String refName, BlockId blockId) {
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
