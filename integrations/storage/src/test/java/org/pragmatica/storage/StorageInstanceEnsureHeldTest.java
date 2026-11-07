package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// G2 (spec v2 §9): re-seeding a block whose record survived but whose shared copy did not (an in-memory DHT after a
/// whole-cluster restart) must land the block in the shared tier, and the GC freshness refresh must move a held block's
/// write time. A put of an already-recorded block deduplicates and writes nothing, so neither may go through it:
/// [StorageInstance#ensureHeld] is the operation. The re-seed test went RED at rc5 `23c0bd8f6b` with the re-seed made
/// by `put` (commit `1b9bf25b15`).
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StorageInstanceEnsureHeldTest {
    private static final byte[] CONTENT = "re-seed me".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private RemoteTierTest.InMemoryS3Client s3;
    private LocalDiskTier disk;
    private RemoteTier shared;
    private MetadataStore store;
    private StorageInstance node;

    @BeforeEach
    void setUp() {
        s3 = new RemoteTierTest.InMemoryS3Client();
        disk = LocalDiskTier.localDiskTier(dir, 1 << 20).flatMap(LocalDiskTier::open).unwrap();
        shared = RemoteTier.remoteTier(s3, "g2", 1 << 20);
        store = MetadataStore.inMemoryMetadataStore("g2");
        node = StorageInstance.storageInstance("g2", List.of(disk, shared), store);
    }

    @Test
    void reseedOfARecordedButAbsentBlock_landsInTheSharedTier() {
        var id = node.put(CONTENT).await().unwrap();

        shared.delete(id).await().unwrap();

        assertThat(shared.exists(id).await().unwrap()).as("precondition: the shared copy is gone").isFalse();

        node.ensureHeld(id, TierLevel.REMOTE).await().unwrap();

        assertThat(shared.get(id).await().unwrap().unwrap()).as("the re-seed reached the shared tier").isEqualTo(CONTENT);
    }

    /// The re-seed changes no reference count and moves no epoch: the record already existed and tier presence is an
    /// observation.
    @Test
    void reseed_leavesTheRecordsCountAndTheEpochAlone_andRecordsThePresence() {
        var id = node.put(CONTENT).await().unwrap();

        shared.delete(id).await().unwrap();
        // The record forgets the shared copy too, so the presence asserted below can only come from the re-seed.
        store.observeLifecycle(id, lc -> lc.withTierRemoved(TierLevel.REMOTE));

        assertThat(store.getLifecycle(id).unwrap().presentIn()).as("precondition").doesNotContain(TierLevel.REMOTE);

        var before = store.getLifecycle(id).unwrap();
        var epoch = store.currentEpoch();

        node.ensureHeld(id, TierLevel.REMOTE).await().unwrap();

        var after = store.getLifecycle(id).unwrap();

        assertThat(after.refCount()).isEqualTo(before.refCount());
        assertThat(after.presentIn()).contains(TierLevel.REMOTE);
        assertThat(store.currentEpoch()).isEqualTo(epoch);
    }

    /// The GC refresh of a block the tier holds moves its write time and sends no bytes.
    @Test
    void refreshOfAHeldBlock_movesItsWriteTime_withoutRewritingIt() {
        var id = node.put(CONTENT).await().unwrap();
        var written = shared.stat(id).await().unwrap().unwrap().writtenAtMillis();
        var puts = s3.puts.get();

        s3.clock.addAndGet(60_000L);
        node.ensureHeld(id, TierLevel.REMOTE).await().unwrap();

        assertThat(shared.stat(id).await().unwrap().unwrap().writtenAtMillis()).isEqualTo(written + 60_000L);
        assertThat(s3.puts.get()).as("no bytes sent").isEqualTo(puts);
    }

    @Test
    void reseed_ofABlockNoTierHolds_failsWithBlockNotFound_andWritesNothing() {
        var id = BlockId.blockId("held nowhere".getBytes(StandardCharsets.UTF_8)).unwrap();

        assertThat(node.ensureHeld(id, TierLevel.REMOTE).await().<Cause>fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.BlockNotFound.class);
        assertThat(shared.exists(id).await().unwrap()).isFalse();
    }

    /// A copy that does not hash to the id is never copied on.
    @Test
    void reseed_fromACorruptCopy_failsWithIntegrityError_andWritesNothing() {
        var memory = MemoryTier.memoryTier(1 << 20);
        var corruptNode = StorageInstance.storageInstance("g2-corrupt", List.of(memory, shared));
        var id = BlockId.blockId(CONTENT).unwrap();

        memory.put(id, "not the content".getBytes(StandardCharsets.UTF_8)).await().unwrap();

        assertThat(corruptNode.ensureHeld(id, TierLevel.REMOTE).await().<Cause>fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.IntegrityError.class);
        assertThat(shared.exists(id).await().unwrap()).isFalse();
    }

    @Test
    void ensureHeld_onATierTheInstanceLacks_failsWithNoSuchTier() {
        var id = node.put(CONTENT).await().unwrap();

        assertThat(node.ensureHeld(id, TierLevel.MEMORY).await().<Cause>fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.NoSuchTier.class);
    }

    /// Re-seeding the private tier from the shared one works the same way: the disk copy is restored from the bucket.
    @Test
    void reseedOfTheDiskCopy_isWrittenFromTheSharedTier() {
        var id = node.put(CONTENT).await().unwrap();

        disk.delete(id).await().unwrap();
        node.ensureHeld(id, TierLevel.LOCAL_DISK).await().unwrap();

        assertThat(disk.get(id).await().unwrap().unwrap()).isEqualTo(CONTENT);
    }

    /// A put of the id still writing its first tier: ensureHeld waits for it. Without the wait the shared tier is not
    /// written yet and the first tier's copy does not exist yet, so the call would fail with BlockNotFound at once.
    @Test
    void ensureHeld_waitsForAWriteOfTheSameIdInFlight() {
        var gate = Promise.<Unit>promise();
        var gated = new GatedTier(MemoryTier.memoryTier(1 << 20, TierLevel.LOCAL_DISK), gate);
        var gatedNode = StorageInstance.storageInstance("g2-in-flight", List.of(gated, shared));
        var id = BlockId.blockId(CONTENT).unwrap();
        var put = gatedNode.put(CONTENT);
        var held = gatedNode.ensureHeld(id, TierLevel.REMOTE);

        LockSupport.parkNanos(200_000_000L);

        assertThat(held.isResolved()).as("ensureHeld waits while the put is in flight").isFalse();

        gate.succeed(Unit.unit());

        assertThat(put.await().unwrap()).isEqualTo(id);
        held.await().unwrap();
        assertThat(shared.get(id).await().unwrap().unwrap()).isEqualTo(CONTENT);
    }

    /// A durable tier whose writes wait for `gate`.
    private record GatedTier(MemoryTier inner, Promise<Unit> gate) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return inner.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return gate.flatMap(_ -> inner.put(id, content));
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return inner.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return inner.exists(id);
        }

        @Override
        public Promise<Boolean> touch(BlockId id) {
            return inner.touch(id);
        }

        @Override
        public boolean isDurable() {
            return true;
        }

        @Override
        public TierLevel level() {
            return inner.level();
        }

        @Override
        public long usedBytes() {
            return inner.usedBytes();
        }

        @Override
        public long maxBytes() {
            return inner.maxBytes();
        }
    }
}
