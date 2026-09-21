// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.Cursor;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockLifecycle;
import org.pragmatica.storage.BlockMetadata;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
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

    /// #1271: a cursor written under a consumer assignment records the assignment's epoch, and a fenced
    /// fetch returns it only for THAT epoch — a node regaining a partition must not resume from its own
    /// earlier tenure's cursor, which can be ahead of what the successor committed (skipping events).
    @Nested
    class AssignmentEpoch {
        private static final Epoch FIRST_TENURE = Epoch.epoch(1L, 1L);
        private static final Epoch SECOND_TENURE = Epoch.epoch(1L, 3L);

        @Test
        void fencedFetch_returnsTheCursor_forTheEpochItWasWrittenUnder() {
            store.commit(GROUP, STREAM, PARTITION, 42L, FIRST_TENURE).await();

            assertThat(store.fetch(GROUP, STREAM, PARTITION, FIRST_TENURE).await())
                    .isEqualTo(Result.success(Option.some(42L)));
        }

        @Test
        void fencedFetch_ignoresTheCursor_fromAnEarlierTenure() {
            store.commit(GROUP, STREAM, PARTITION, 900L, FIRST_TENURE).await();

            assertThat(store.fetch(GROUP, STREAM, PARTITION, SECOND_TENURE).await())
                    .describedAs("resuming at 900 would skip whatever the other node's tenure had not yet committed")
                    .isEqualTo(Result.success(Option.none()));
        }

        @Test
        void fencedFetch_ignoresAnUnfencedCursor() {
            store.commit(GROUP, STREAM, PARTITION, 7L).await();

            assertThat(store.fetch(GROUP, STREAM, PARTITION, FIRST_TENURE).await())
                    .isEqualTo(Result.success(Option.none()));
        }

        /// The pull API reads any recorded cursor, fenced or not — its rewinds stay legitimate.
        @Test
        void unfencedFetch_readsAFencedCursor() {
            store.commit(GROUP, STREAM, PARTITION, 42L, FIRST_TENURE).await();

            assertThat(store.fetch(GROUP, STREAM, PARTITION).await())
                    .isEqualTo(Result.success(Option.some(42L)));
        }
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
        /// is issued for the cursor at all, and at the instant the replacing write-and-ref runs the
        /// previous ref is still resolvable.
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

            assertThat(observing.previousRefAtWrite)
                    .as("the second commit is the replacement; the old ref must still be in place when it runs")
                    .hasSize(2);
            assertThat(observing.previousRefAtWrite.get(1).isPresent())
                    .as("the ref was absent at replacement time — that is exactly the window #264 closes")
                    .isTrue();

            observed.fetch(GROUP, STREAM, PARTITION)
                    .await()
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                    .onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(100L)));
        }
    }

    /// Delegating [StorageInstance] that records the ref operations a commit performs, and the ref's state
    /// at the moment each write-and-ref is issued. Everything else passes straight through.
    ///
    /// #812: the observation point is [StorageInstance#putRef], not `createRef`. A commit reaches storage
    /// through `replaceRef`, which this double does not override -- so before #812 it inherited the
    /// interface default (`put` then `createRef`) and what these assertions observed was that FALLBACK,
    /// not the single upsert the production [DefaultStorageInstance] performs. `putRef` is now the
    /// primitive both paths go through, so the double and production observe the same call.
    private static final class RefObservingStorage implements StorageInstance {
        private final StorageInstance delegate;
        private final List<String> refOperations = new ArrayList<>();
        private final List<Option<BlockId>> previousRefAtWrite = new ArrayList<>();

        private RefObservingStorage(StorageInstance delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<BlockId> putRef(String refName, byte[] content) {
            refOperations.add("putRef:" + refName);
            previousRefAtWrite.add(delegate.resolveRef(refName));

            return delegate.putRef(refName, content);
        }

        @Override
        public Promise<Unit> createRef(String refName, BlockId id) {
            refOperations.add("createRef:" + refName);
            previousRefAtWrite.add(delegate.resolveRef(refName));

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

    /// #1333 (rev1369 MEDIUM-2) — the 40-byte block `offset | rabiaTerm | localCounter | rewindGeneration |
    /// rewindSequence` is what lets a same-node restart resume under the rewind epoch the consumer committed
    /// with. Every other test in this class goes through the offset-only or assignment-only `commit`/`fetch`,
    /// so before these pins a decode that DROPPED the rewind epoch left the whole repo green (rev1369's
    /// mutation M7'). Layout after the #1335 merge: 8 = unfenced (pull API), 24 = fenced without a rewind
    /// epoch (#1271, written by rc4 builds between #1335 and #1333), 40 = fenced with one (every fenced
    /// commit from #1333 on); anything else absent.
    @Nested
    class RewindEpochLayout {
        private static final Epoch TENURE = Epoch.epoch(1L, 1L);
        private static final Epoch OTHER_TENURE = Epoch.epoch(1L, 3L);
        private static final RewindEpoch EPOCH = RewindEpoch.rewindEpoch(7L, 3L);

        @Test
        void encodeRewoundCursor_decodeCursor_roundTrip_carriesBothEpochs() {
            var encoded = CursorStore.encodeRewoundCursor(Long.MAX_VALUE, TENURE, EPOCH);

            assertThat(encoded).hasSize(CursorStore.REWOUND_CURSOR_BYTES);
            assertThat(CursorStore.decodeCursor(encoded)).isEqualTo(Option.some(new CursorStore.StoredCursor(Long.MAX_VALUE,
                                                                                                             Option.some(TENURE),
                                                                                                             EPOCH)));
            assertThat(CursorStore.decodeOffset(encoded)).as("the offset-only reader still sees the offset in the first eight bytes")
                      .isEqualTo(Long.MAX_VALUE);
        }

        /// The epoch must live in the BLOCK on disk, not in the store object: the storage is closed and
        /// reopened over the same directory the way `StorageFactory` restores a node (fresh metadata store
        /// with the refs restored, fresh disk tier, fresh [CursorStore]) and the cursor resumes under the
        /// rewind epoch that was committed — for the tenure it was committed under, and for no other.
        @Test
        void commitUnderAnEpoch_thenReopenTheStorage_resumesUnderThePersistedEpoch(@TempDir Path dir) {
            var firstMetadata = MetadataStore.inMemoryMetadataStore("first");
            var first = cursorStore(diskStorage("first", dir, firstMetadata));

            first.commit(GROUP, STREAM, PARTITION, 42L, TENURE, EPOCH).await();
            var reopenedMetadata = MetadataStore.inMemoryMetadataStore("reopened");

            reopenedMetadata.restoreRefs(firstMetadata.listAllRefs());
            var reopened = cursorStore(diskStorage("reopened", dir, reopenedMetadata));

            assertThat(reopened.fetchCursor(GROUP, STREAM, PARTITION, TENURE).await()).as("the reopened store must resume under the rewind epoch the first one committed")
                      .isEqualTo(Result.success(Option.some(Cursor.cursor(42L, EPOCH))));
            assertThat(reopened.fetch(GROUP, STREAM, PARTITION, TENURE).await()).isEqualTo(Result.success(Option.some(42L)));
            assertThat(reopened.fetchCursor(GROUP, STREAM, PARTITION, OTHER_TENURE).await()).as("#1271's tenure rule holds for the rewound layout too")
                      .isEqualTo(Result.success(Option.none()));
        }

        /// A fenced block written WITHOUT a rewind epoch (the 24-byte #1271 layout, on rc4 before this
        /// change) reads as that tenure's cursor at [RewindEpoch#NONE]: not refused, since the group it
        /// belongs to has never been rewound and `NONE` is exactly its epoch. The next fenced commit
        /// rewrites the ref in the 40-byte layout.
        @Test
        void fencedBlockWithoutARewindEpoch_readsAsUnrewound_andTheNextCommitRewritesItInTheNewLayout() {
            var refName = CursorStore.buildRefName(GROUP, STREAM, PARTITION);

            storage.putRef(refName, CursorStore.encodeFencedOffset(99L, TENURE)).await();
            assertThat(store.fetchCursor(GROUP, STREAM, PARTITION, TENURE).await()).isEqualTo(Result.success(Option.some(Cursor.unrewound(99L))));
            store.commit(GROUP, STREAM, PARTITION, 7L, TENURE, RewindEpoch.NONE).await();
            var rewritten = storage.resolveRef(refName)
                                   .flatMap(id -> storage.get(id)
                                                         .await()
                                                         .option()
                                                         .flatMap(block -> block))
                                   .or(new byte[0]);

            assertThat(rewritten).as("every fenced commit now writes the 40-byte block")
                      .isEqualTo(CursorStore.encodeRewoundCursor(7L, TENURE, RewindEpoch.NONE));
        }

        /// The 8-byte unfenced block (pull API) is a legitimate cursor for the unfenced `fetch` (#1271's
        /// rule, kept) and invisible to a fenced one — it belongs to no tenure, so it reads as absent there.
        /// MEANING CHANGE, stated: before the #1335 merge this class pinned the opposite — an 8-byte block
        /// was REFUSED (read as absent, resume from earliest once; rev1369 MEDIUM-2, mutation M11). #1271's
        /// pull API writes 8-byte blocks legitimately, so refusing them would make every unfenced consumer
        /// resume from earliest after each restart — a behaviour regression, not a one-time redelivery.
        /// Ruling know 504ee397f (Reading A).
        @Test
        void unfencedEightByteBlock_isReadByTheUnfencedFetch_andInvisibleToAFencedOne() {
            var refName = CursorStore.buildRefName(GROUP, STREAM, PARTITION);
            var legacyBlock = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(99L).array();

            storage.putRef(refName, legacyBlock).await();
            assertThat(store.fetch(GROUP, STREAM, PARTITION).await()).isEqualTo(Result.success(Option.some(99L)));
            assertThat(store.fetchCursor(GROUP, STREAM, PARTITION, TENURE).await()).as("an unfenced block belongs to no tenure")
                      .isEqualTo(Result.success(Option.none()));
        }

        /// A block of any other length is unreadable and reads as absent, never as a garbled cursor.
        @Test
        void blockOfAnUnknownLength_readsAsAbsent() {
            var refName = CursorStore.buildRefName(GROUP, STREAM, PARTITION);

            storage.putRef(refName, new byte[4 * Long.BYTES]).await();
            assertThat(store.fetch(GROUP, STREAM, PARTITION).await()).isEqualTo(Result.success(Option.none()));
            assertThat(store.fetchCursor(GROUP, STREAM, PARTITION, TENURE).await()).isEqualTo(Result.success(Option.none()));
        }

        private static StorageInstance diskStorage(String name, Path dir, MetadataStore metadataStore) {
            var tier = LocalDiskTier.localDiskTier(dir, ONE_GB).unwrap();

            return StorageInstance.storageInstance(name, List.of(tier), metadataStore);
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
