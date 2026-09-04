// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.BlockMetadata;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.CursorStore.cursorStore;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;

class CursorStoreTest {

    private static final String STREAM = "test-stream";
    private static final String GROUP = "my-group";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private CursorStore store;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        store = cursorStore(storage);
    }

    @Nested
    class CommitAndFetch {

        @Test
        void commit_fetch_returnsCommittedOffset() {
            store.commit(GROUP, STREAM, PARTITION, 42L).await();

            var result = store.fetch(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> {
                      assertThat(opt.isPresent()).isTrue();
                      opt.onPresent(offset -> assertThat(offset).isEqualTo(42L));
                  });
        }

        @Test
        void commit_overwritesPreviousOffset() {
            store.commit(GROUP, STREAM, PARTITION, 10L).await();
            store.commit(GROUP, STREAM, PARTITION, 100L).await();

            var result = store.fetch(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> {
                      assertThat(opt.isPresent()).isTrue();
                      opt.onPresent(offset -> assertThat(offset).isEqualTo(100L));
                  });
        }

        @Test
        void fetch_returnsNone_whenNotCommitted() {
            var result = store.fetch(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void commit_isolatesByConsumerGroup() {
            store.commit("group-a", STREAM, PARTITION, 10L).await();
            store.commit("group-b", STREAM, PARTITION, 20L).await();

            var resultA = store.fetch("group-a", STREAM, PARTITION).await();
            var resultB = store.fetch("group-b", STREAM, PARTITION).await();

            resultA.onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(10L)));
            resultB.onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(20L)));
        }

        @Test
        void commit_isolatesByPartition() {
            store.commit(GROUP, STREAM, 0, 10L).await();
            store.commit(GROUP, STREAM, 1, 20L).await();

            var result0 = store.fetch(GROUP, STREAM, 0).await();
            var result1 = store.fetch(GROUP, STREAM, 1).await();

            result0.onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(10L)));
            result1.onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(20L)));
        }

        @Test
        void commit_isolatesByStream() {
            store.commit(GROUP, "stream-a", PARTITION, 10L).await();
            store.commit(GROUP, "stream-b", PARTITION, 20L).await();

            var resultA = store.fetch(GROUP, "stream-a", PARTITION).await();
            var resultB = store.fetch(GROUP, "stream-b", PARTITION).await();

            resultA.onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(10L)));
            resultB.onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(20L)));
        }
    }

    @Nested
    class RefReplacementLeavesNoAbsentWindow {

        /// #264 — a committed cursor must never pass through a state where its ref does not exist.
        ///
        /// The crash window itself is not observable from a test, so what is pinned is the mechanism that
        /// removes it: the replacement must be a single upsert. The previous implementation removed the
        /// ref and then recreated it, and a crash in between left the ref ABSENT — which [CursorStore#fetch]
        /// reports as `Option.empty()`, indistinguishable from "this group never committed", so the group
        /// resumed from the earliest RETAINED offset and redelivered the whole window.
        ///
        /// Two independent assertions, because either alone can be satisfied by accident: no `deleteRef`
        /// is issued for the cursor at all, and at the instant the replacing `createRef` runs the previous
        /// ref is still resolvable.
        @Test
        void commit_overExistingCursor_neverRemovesTheRef_andTheOldValueStandsUntilReplaced() {
            var observing = new RefObservingStorage(storage);
            var observed = cursorStore(observing);

            observed.commit(GROUP, STREAM, PARTITION, 10L).await();
            observed.commit(GROUP, STREAM, PARTITION, 100L).await();

            assertThat(observing.refOperations)
                    .as("replacing a cursor must not remove its ref — the gap between remove and recreate"
                        + " is the #264 window, and an absent ref resumes from the earliest retained offset")
                    .noneMatch(operation -> operation.startsWith("deleteRef"));

            assertThat(observing.previousRefAtCreate)
                    .as("the second commit is the replacement; the old ref must still be in place when it runs")
                    .hasSize(2);
            assertThat(observing.previousRefAtCreate.get(1).isPresent())
                    .as("the ref was absent at replacement time — that is exactly the window #264 closes")
                    .isTrue();

            observed.fetch(GROUP, STREAM, PARTITION)
                    .await()
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                    .onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(100L)));
        }
    }

    /// Delegating [StorageInstance] that records the ref operations a commit performs, and the ref's state
    /// at the moment each `createRef` is issued. Everything else passes straight through.
    private static final class RefObservingStorage implements StorageInstance {
        private final StorageInstance delegate;
        private final List<String> refOperations = new ArrayList<>();
        private final List<Option<BlockId>> previousRefAtCreate = new ArrayList<>();

        private RefObservingStorage(StorageInstance delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Unit> createRef(String refName, BlockId id) {
            refOperations.add("createRef:" + refName);
            previousRefAtCreate.add(delegate.resolveRef(refName));

            return delegate.createRef(refName, id);
        }

        @Override
        public Promise<Unit> deleteRef(String refName) {
            refOperations.add("deleteRef:" + refName);

            return delegate.deleteRef(refName);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
        }

        @Override
        public Promise<BlockId> put(byte[] content) {
            return delegate.put(content);
        }

        @Override
        public Promise<BlockId> put(byte[] content, BlockMetadata metadata) {
            return delegate.put(content, metadata);
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
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public List<TierInfo> tierInfo() {
            return delegate.tierInfo();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }

    @Nested
    class RefcountReclamation {

        private MetadataStore metadataStore;
        private CursorStore store;

        @BeforeEach
        void setUp() {
            metadataStore = MetadataStore.inMemoryMetadataStore("refcount-test");
            var storage = StorageInstance.storageInstance("refcount-test",
                                                           List.of(MemoryTier.memoryTier(ONE_GB)),
                                                           metadataStore);
            store = cursorStore(storage);
        }

        /// #737 — a cursor block that no live cursor points at any more must reach refCount 0 so
        /// [org.pragmatica.storage.StorageGarbageCollector] can reclaim it. A repeat commit of the SAME
        /// offset is included deliberately: it must not leak an extra reference either.
        @Test
        void commit_repeatedly_leavesExactlyOneLiveBlock() {
            store.commit(GROUP, STREAM, PARTITION, 10L).await();
            store.commit(GROUP, STREAM, PARTITION, 20L).await();
            store.commit(GROUP, STREAM, PARTITION, 20L).await();
            store.commit(GROUP, STREAM, PARTITION, 30L).await();

            assertThat(refCountOf(10L)).as("superseded block must be collectable").isEqualTo(0);
            assertThat(refCountOf(20L))
                    .as("superseded block must be collectable -- the repeat commit must not leak an extra reference")
                    .isEqualTo(0);
            assertThat(refCountOf(30L)).as("the current cursor value must hold exactly one live reference").isEqualTo(1);
        }

        /// #737 — cursor blocks are content-addressed, so two cursors sitting at the same offset share
        /// one block. Releasing one cursor must not collect a block the other still needs.
        @Test
        void commit_sharedContent_onlyReleasedWhenLastCursorMovesAway() {
            var groupX = "group-x";
            var groupY = "group-y";

            store.commit(groupX, STREAM, PARTITION, 42L).await();
            store.commit(groupY, STREAM, PARTITION, 42L).await();

            assertThat(refCountOf(42L)).as("two cursors independently reference the shared block").isEqualTo(2);

            store.commit(groupX, STREAM, PARTITION, 99L).await();

            assertThat(refCountOf(42L))
                    .as("group-y still points at the shared block -- it must not be collectable yet")
                    .isEqualTo(1);

            store.commit(groupY, STREAM, PARTITION, 99L).await();

            assertThat(refCountOf(42L))
                    .as("no cursor references it any more -- now it must be collectable")
                    .isEqualTo(0);
        }

        private int refCountOf(long offset) {
            var blockId = BlockId.blockId(CursorStore.encodeOffset(offset))
                                  .fold(_ -> {
                                      org.junit.jupiter.api.Assertions.fail("BlockId computation failed");
                                      return null;
                                  }, id -> id);

            return metadataStore.getLifecycle(blockId)
                                 .map(BlockLifecycle::refCount)
                                 .or(-1);
        }
    }

    @Nested
    class GarbageCollectionIntegration {

        private MetadataStore metadataStore;
        private CursorStore store;
        private StorageGarbageCollector gc;

        @BeforeEach
        void setUp() {
            metadataStore = MetadataStore.inMemoryMetadataStore("gc-integration-test");
            var storage = StorageInstance.storageInstance("gc-integration-test",
                                                           List.of(MemoryTier.memoryTier(ONE_GB)),
                                                           metadataStore);
            store = cursorStore(storage);
            // 0 requests the shortest possible grace period; GarbageCollectorConfig floors it at 1ms
            // (its canonical constructor clamps gracePeriodMs to Math.max(gracePeriodMs, 1)), so the
            // sleep below -- not a zero grace -- is what actually lets collection proceed.
            gc = storageGarbageCollector(storage, metadataStore, garbageCollectorConfig(0, 500));
            gc.activate();
        }

        /// #737 -- the joining test: [CursorStore#commit]'s refcount-aware replace must produce blocks
        /// the PRODUCTION [org.pragmatica.storage.StorageGarbageCollector] actually reclaims, not just
        /// blocks that individually reach refCount 0 (that much is already pinned by
        /// [RefcountReclamation], against the metadata store directly). Three commits to the same
        /// cursor leave two superseded, now-orphaned blocks and one live one; after the grace period,
        /// `collectGarbage` must remove exactly the two superseded blocks, leave the live one in place,
        /// and leave the cursor's own read path (`fetch`) working.
        ///
        /// Red before: reverting `CursorStore.commit` to `deleteRef`-then-`createRef` -- equivalently,
        /// `put`+`createRef`, which never decrements what it supersedes -- leaves both superseded
        /// blocks permanently referenced, so `collectGarbage` collects 0, not 2. Confirmed via
        /// mutation probe.
        @Test
        void commit_thenCollectGarbage_reclaimsExactlySupersededBlocks() throws InterruptedException {
            store.commit(GROUP, STREAM, PARTITION, 10L).await();
            store.commit(GROUP, STREAM, PARTITION, 20L).await();
            store.commit(GROUP, STREAM, PARTITION, 30L).await();

            var supersededId10 = blockIdOf(10L);
            var supersededId20 = blockIdOf(20L);
            var liveId30 = blockIdOf(30L);

            Thread.sleep(20);

            var collected = gc.collectGarbage();

            assertThat(collected).as("both superseded cursor blocks must be reclaimed").isEqualTo(2);
            assertThat(metadataStore.containsBlock(supersededId10)).as("superseded block 10 must be gone").isFalse();
            assertThat(metadataStore.containsBlock(supersededId20)).as("superseded block 20 must be gone").isFalse();
            assertThat(metadataStore.containsBlock(liveId30)).as("the live block must survive collection").isTrue();

            store.fetch(GROUP, STREAM, PARTITION)
                 .await()
                 .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                 .onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(30L)));
        }

        private BlockId blockIdOf(long offset) {
            return BlockId.blockId(CursorStore.encodeOffset(offset))
                          .fold(_ -> {
                              org.junit.jupiter.api.Assertions.fail("BlockId computation failed");
                              return null;
                          }, id -> id);
        }
    }

    @Nested
    class Encoding {

        @Test
        void encodeOffset_decodeOffset_roundTrip() {
            var encoded = CursorStore.encodeOffset(Long.MAX_VALUE);
            var decoded = CursorStore.decodeOffset(encoded);

            assertThat(decoded).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void encodeOffset_decodeOffset_zero() {
            var encoded = CursorStore.encodeOffset(0L);
            var decoded = CursorStore.decodeOffset(encoded);

            assertThat(decoded).isEqualTo(0L);
        }

        @Test
        void buildRefName_formatsCorrectly() {
            var refName = CursorStore.buildRefName("my-group", "orders", 3);

            assertThat(refName).isEqualTo("cursors/my-group/orders/3");
        }
    }
}
