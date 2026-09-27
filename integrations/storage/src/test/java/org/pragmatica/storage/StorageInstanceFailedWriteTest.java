package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1602 (review of #1567): what a write that fails part-way, a retry of it, and demotion must leave behind.
/// P1, P1b, P2 and P3 are the verifier's probes (v1602), kept verbatim in substance.
class StorageInstanceFailedWriteTest {
    private static final byte[] CONTENT = "checkpoint block".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Nested
    class FailedWriteLeavesNothing {
        /// B1: the disk took the block, the last tier refused it. The put fails and leaves NO record claiming
        /// the block exists -- so a second put, with the last tier still full, fails too instead of
        /// deduplicating onto a phantom.
        @Test
        void p1_afterACompensatedFailure_noRecordRemains_andANewPutDoesNotSucceedOnNothing() {
            var store = MetadataStore.inMemoryMetadataStore("p1");
            var disk = diskTier();
            var storage = StorageInstance.storageInstance("p1",
                                                          List.of(MemoryTier.memoryTier(1 << 20),
                                                                  disk,
                                                                  MemoryTier.memoryTier(1, TierLevel.REMOTE)),
                                                          store);
            var id = BlockId.blockId(CONTENT).unwrap();

            assertThat(storage.putRef("cursor", CONTENT).await().isFailure()).as("first put fails: last tier full").isTrue();
            assertThat(disk.exists(id).await().unwrap()).as("compensation removed the disk copy").isFalse();
            assertThat(store.getLifecycle(id).isPresent()).as("no record for a block stored nowhere").isFalse();
            assertThat(storage.put(CONTENT).await().isFailure()).as("the second put fails too: nothing is stored").isTrue();
        }

        @Test
        void p1b_aPutThatReportsSuccess_isReadable() {
            var storage = StorageInstance.storageInstance("p1b",
                                                          List.of(MemoryTier.memoryTier(1 << 20),
                                                                  diskTier(),
                                                                  MemoryTier.memoryTier(1, TierLevel.REMOTE)));

            storage.putRef("cursor", CONTENT).await();
            storage.putRef("cursor", CONTENT)
                   .await()
                   .onSuccess(id -> assertThat(storage.get(id).await().unwrap().isPresent())
                                        .as("putRef reported success for %s; the block must be readable", id)
                                        .isTrue());
        }

        /// B1, the retry a caller actually makes: disk ok, the last tier fails once and then recovers. The
        /// retry really writes the block: it is readable, and it is on the disk.
        @Test
        void retryAfterALastTierFailure_writesTheBlockForReal() {
            var disk = diskTier();
            var storage = StorageInstance.storageInstance("retry",
                                                          List.of(MemoryTier.memoryTier(1 << 20),
                                                                  disk,
                                                                  new FailingFirstPuts(MemoryTier.memoryTier(1 << 20, TierLevel.REMOTE), 1)));

            storage.putRef("cursor", CONTENT).await().onSuccess(_ -> fail("the last tier was injected to fail once"));

            var id = storage.putRef("cursor", CONTENT).await().onFailure(cause -> fail(cause.message())).unwrap();

            assertThat(storage.get(id).await().unwrap().unwrap()).isEqualTo(CONTENT);
            assertThat(disk.exists(id).await().unwrap()).as("the retry wrote the disk copy again").isTrue();
        }

        /// B2: compensation undoes only what THIS put created. A copy already on disk with no record (written
        /// after the last metadata snapshot, then a restart) survives a failed put of the same content.
        @Test
        void p2_compensation_doesNotDeleteACopyThatWasOnDiskBeforeThePut() {
            var disk = diskTier();
            var id = BlockId.blockId(CONTENT).unwrap();

            disk.put(id, CONTENT).await().unwrap();

            var storage = StorageInstance.storageInstance("p2",
                                                          List.of(MemoryTier.memoryTier(1 << 20),
                                                                  disk,
                                                                  MemoryTier.memoryTier(1, TierLevel.REMOTE)));

            assertThat(storage.put(CONTENT).await().isFailure()).isTrue();
            assertThat(disk.exists(id).await().unwrap()).as("the pre-existing disk copy survives a failed put").isTrue();
        }
    }

    @Nested
    class DemotionKeepsADurableCopy {
        /// B3 (v1602 P3): after a seal and a truncation the block stays on the durable tier. Above the high
        /// watermark the disk tier is NOT demoted to the in-memory DHT tier below it.
        @Test
        void p3_demotion_neverMovesASealedBlockOffTheOnlyDurableTier() {
            var store = MetadataStore.inMemoryMetadataStore("p3");
            var block = "segment [0-2] payload".getBytes(StandardCharsets.UTF_8);
            var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), block.length + 1).unwrap();
            var dht = MemoryTier.memoryTier(1 << 20, TierLevel.REMOTE);
            var tiers = List.<StorageTier> of(MemoryTier.memoryTier(1 << 20), disk, dht);
            var storage = StorageInstance.storageInstance("p3", tiers, store, WritePolicy.WRITE_THROUGH, some(dir.resolve("logs")));
            var wal = storage.openLog("orders/0").unwrap();

            for (long offset = 0; offset < 5; offset++) {
                wal.append(offset, ("e" + offset).getBytes(StandardCharsets.UTF_8), 1L).await().unwrap();
            }

            var id = storage.seal(wal, 0, 2, "streams/orders/0/0-2", block).await().unwrap();

            wal.truncate(2).unwrap();
            var remaining = new ArrayList<Long>();
            wal.replay(-1, record -> remaining.add(record.offset())).unwrap();
            assertThat(remaining).as("WAL truncated through 2").containsExactly(3L, 4L);

            var demotion = DemotionManager.demotionManager(tiers, store, DemotionConfig.demotionConfig());
            demotion.activate();
            demotion.demote();

            assertThat(disk.exists(id).await().unwrap()).as("the block keeps its durable copy").isTrue();
            assertThat(dht.exists(id).await().unwrap()).isTrue();
            assertThat(store.getLifecycle(id).unwrap().presentIn()).contains(TierLevel.LOCAL_DISK);
            wal.close();
        }

        /// When a durable tier sits below, demotion targets it -- skipping the non-durable tier between -- so
        /// capacity pressure still moves blocks without ever leaving them on no durable tier.
        @Test
        void demotion_fromADurableTier_goesToTheNextDurableTier() {
            var store = MetadataStore.inMemoryMetadataStore("two-disks");
            var small = LocalDiskTier.localDiskTier(dir.resolve("small"), CONTENT.length + 1).unwrap();
            var big = LocalDiskTier.localDiskTier(dir.resolve("big"), 1 << 20).unwrap();
            var tiers = List.<StorageTier> of(small, MemoryTier.memoryTier(1 << 20, TierLevel.REMOTE), big);
            var storage = StorageInstance.storageInstance("two-disks", tiers, store);
            var id = storage.put(CONTENT).await().unwrap();
            var demotion = DemotionManager.demotionManager(tiers, store, DemotionConfig.demotionConfig());

            demotion.activate();
            demotion.demote();

            assertThat(small.exists(id).await().unwrap()).as("demoted off the full disk").isFalse();
            assertThat(big.exists(id).await().unwrap()).as("onto the next DURABLE tier").isTrue();
        }
    }

    @Nested
    class Pins {
        /// N2: an encrypting wrapper over the local disk is durable, and one over memory is not -- the
        /// `streams` instance with `streams_encrypted` seals only because of this pass-through.
        @Test
        void encryptingTier_passesDurabilityThrough_andAnEncryptedDiskSeals() {
            var keyring = keyring();
            var encryptedDisk = EncryptingStorageTier.wrap(diskTier(), keyring);
            var encryptedMemory = EncryptingStorageTier.wrap(MemoryTier.memoryTier(1 << 20), keyring);
            var storage = StorageInstance.storageInstance("encrypted",
                                                          List.of(MemoryTier.memoryTier(1 << 20), encryptedDisk),
                                                          MetadataStore.inMemoryMetadataStore("encrypted"),
                                                          WritePolicy.WRITE_THROUGH,
                                                          some(dir.resolve("logs")));
            var wal = storage.openLog("orders/0").unwrap();

            wal.append(0, new byte[]{1}, 1L).await().unwrap();

            assertThat(encryptedDisk.isDurable()).isTrue();
            assertThat(encryptedMemory.isDurable()).isFalse();
            storage.seal(wal, 0, 0, "streams/orders/0/0-0", CONTENT).await().onFailure(cause -> fail(cause.message()));
            assertThat(wal.sealedThrough()).isZero();
            wal.close();
        }

        /// N2: a seal that deduplicates onto a stored block credits it, then rewrites the required tiers; when
        /// the rewrite fails the credit is given back -- no ref names the block, so the count must not grow.
        @Test
        void failedSealRewrite_givesTheCreditBack() {
            var calls = new AtomicInteger();
            Fn2<Result<Unit>, Path, byte[]> failSecond = (path, bytes) -> calls.incrementAndGet() == 1
                                                                          ? FileOps.writeBytesForced(path, bytes)
                                                                          : Causes.cause("disk failed on the rewrite").result();
            var store = MetadataStore.inMemoryMetadataStore("rewrite");
            var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20, timeSpan(30).seconds(), none(), some(failSecond), none())
                                    .unwrap();
            var storage = StorageInstance.storageInstance("rewrite",
                                                          List.of(MemoryTier.memoryTier(1 << 20), disk),
                                                          store,
                                                          WritePolicy.WRITE_THROUGH,
                                                          some(dir.resolve("logs")));
            var id = storage.put(CONTENT).await().unwrap();
            var wal = storage.openLog("orders/0").unwrap();

            wal.append(0, new byte[]{1}, 1L).await().unwrap();
            storage.seal(wal, 0, 0, "streams/orders/0/0-0", CONTENT).await().onSuccess(_ -> fail("the rewrite was injected to fail"));

            assertThat(store.getLifecycle(id).unwrap().refCount()).as("the seal's credit was given back").isEqualTo(1);
            assertThat(storage.resolveRef("streams/orders/0/0-0").isPresent()).isFalse();
            wal.close();
        }
    }

    private LocalDiskTier diskTier() {
        return LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20).unwrap();
    }

    private static EncryptionKeyring keyring() {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);

        return BlockEncryptor.aesGcm(key, "k1")
                             .flatMap(encryptor -> EncryptionKeyring.encryptionKeyring(Map.of("k1", encryptor), "k1"))
                             .unwrap();
    }

    /// A tier whose first `failures` puts fail; everything else delegates.
    private static final class FailingFirstPuts implements StorageTier {
        private final StorageTier delegate;
        private final AtomicInteger remaining;

        FailingFirstPuts(StorageTier delegate, int failures) {
            this.delegate = delegate;
            this.remaining = new AtomicInteger(failures);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return remaining.getAndDecrement() > 0
                   ? Causes.cause("injected last-tier failure").promise()
                   : delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isShared() {
            return true;
        }
    }
}
