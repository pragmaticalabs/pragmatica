// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;

class StorageSegmentSinkTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
    }

    @Nested
    class Seal {

        @Test
        void seal_storesInStorage_andCreatesRef() {
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, 1000L, 2000L, new byte[]{1, 2, 3});

            sink.seal(segment).await();

            var refName = StorageSegmentSink.refName(segment);
            var blockId = storage.resolveRef(refName);
            assertThat(blockId.isEmpty()).isFalse();

            blockId.onPresent(id ->
                storage.get(id).await()
                       .onSuccess(opt -> opt.onPresent(data -> assertThat(data).isEqualTo(new byte[]{1, 2, 3})))
            );
        }

        @Test
        void seal_updatesIndex() {
            var segment = sealedSegment(STREAM, PARTITION, 10, 19, 10, 1000L, 2000L, new byte[]{4, 5});

            sink.seal(segment).await();

            var ref = index.findSegment(STREAM, PARTITION, 15);
            assertThat(ref.isEmpty()).isFalse();
            ref.onPresent(r -> {
                assertThat(r.startOffset()).isEqualTo(10);
                assertThat(r.endOffset()).isEqualTo(19);
            });
        }
    }

    @Nested
    class RefName {

        @Test
        void refName_formatsCorrectly() {
            var segment = sealedSegment("my-stream", 3, 100, 199, 100, 5000L, 6000L, new byte[0]);

            var name = StorageSegmentSink.refName(segment);

            assertThat(name).isEqualTo("streams/my-stream/3/100-199");
        }

        @Test
        void refName_partitionZero() {
            var segment = sealedSegment("s", 0, 0, 0, 1, 1L, 1L, new byte[0]);

            var name = StorageSegmentSink.refName(segment);

            assertThat(name).isEqualTo("streams/s/0/0-0");
        }
    }

    /// #812 -- [StorageSegmentSink#seal] paired [StorageInstance#put] with
    /// [StorageInstance#createRef]. Each credits the block, so every sealed segment sat at refCount 2
    /// for its one ref: dropping the ref left it at 1, never orphaned, and the production
    /// [StorageGarbageCollector] could never reclaim a single segment block.
    ///
    /// What is pinned is the CONSEQUENCE -- a real `collectGarbage` cycle removing the block from the
    /// metadata store and the tier -- not the arithmetic that enables it. The only hand-fed input is
    /// elapsed time; every refCount is produced by the code under test.
    @Nested
    class Reclamation {

        private MetadataStore metadataStore;
        private StorageInstance storage;
        private StorageSegmentSink sink;
        private StorageGarbageCollector gc;

        @BeforeEach
        void setUp() {
            metadataStore = MetadataStore.inMemoryMetadataStore("seal-reclamation");
            storage = StorageInstance.storageInstance("seal-reclamation",
                                                      List.of(MemoryTier.memoryTier(ONE_GB)),
                                                      metadataStore);
            sink = storageSegmentSink(storage, new SegmentIndex());
            // 0 requests the shortest possible grace period; GarbageCollectorConfig floors it at 1ms,
            // so the sleep below -- not a zero grace -- is what lets collection proceed.
            gc = storageGarbageCollector(storage, metadataStore, garbageCollectorConfig(0, 500));
            gc.activate();
        }

        private int refCountOf(BlockId id) {
            return metadataStore.getLifecycle(id)
                                .unwrap()
                                .refCount();
        }

        private boolean isOrphaned(BlockId id) {
            return metadataStore.getLifecycle(id)
                                .unwrap()
                                .isOrphaned();
        }

        /// Red before the fix: the sealed block sits at refCount 2, `deleteRef` brings it to 1,
        /// `isOrphaned` stays false and `collectGarbage` returns 0.
        @Test
        void seal_thenDropTheRef_reachesRefCountZero_andIsCollected() throws InterruptedException {
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, 1000L, 2000L, new byte[]{1, 2, 3});

            sink.seal(segment)
                .await()
                .onFailure(c -> fail("seal failed: " + c.message()));

            var refName = StorageSegmentSink.refName(segment);
            var blockId = storage.resolveRef(refName)
                                 .unwrap();

            assertThat(refCountOf(blockId))
                    .as("one segment ref must credit exactly one reference -- put+createRef credited two (#812)")
                    .isEqualTo(1);
            assertThat(isOrphaned(blockId)).isFalse();

            storage.deleteRef(refName)
                   .await()
                   .onFailure(c -> fail("deleteRef failed: " + c.message()));

            assertThat(refCountOf(blockId)).as("dropping the only ref must reach zero").isZero();
            assertThat(isOrphaned(blockId)).isTrue();

            Thread.sleep(20);

            assertThat(gc.collectGarbage())
                    .as("the orphaned segment block must actually be collected, not merely counted down")
                    .isEqualTo(1);
            assertThat(metadataStore.containsBlock(blockId)).as("lifecycle metadata must be gone").isFalse();
            storage.get(blockId)
                   .await()
                   .onFailure(c -> fail("get failed: " + c.message()))
                   .onSuccess(opt -> assertThat(opt.isEmpty()).as("the block must be gone from the tier").isTrue());
        }

        /// Re-sealing the same segment is a real path -- a node that crashed between sealing and
        /// acknowledging seals it again on recovery -- and it must not leak a reference. The write is a
        /// deduplicating credit and the ref replacement releases the block it displaced (itself), so
        /// the count stays at one and the segment stays collectable.
        @Test
        void seal_sameSegmentTwice_keepsExactlyOneReference_andStillCollects() throws InterruptedException {
            var segment = sealedSegment(STREAM, PARTITION, 0, 9, 10, 1000L, 2000L, new byte[]{7, 8, 9});

            sink.seal(segment)
                .await()
                .onFailure(c -> fail("first seal failed: " + c.message()));
            sink.seal(segment)
                .await()
                .onFailure(c -> fail("re-seal failed: " + c.message()));

            var refName = StorageSegmentSink.refName(segment);
            var blockId = storage.resolveRef(refName)
                                 .unwrap();

            assertThat(refCountOf(blockId)).as("a re-seal of one segment is still one reference").isEqualTo(1);

            storage.deleteRef(refName)
                   .await()
                   .onFailure(c -> fail("deleteRef failed: " + c.message()));

            Thread.sleep(20);

            assertThat(gc.collectGarbage()).as("a re-sealed segment must still be collectable").isEqualTo(1);
            assertThat(metadataStore.containsBlock(blockId)).isFalse();
        }
    }
}
