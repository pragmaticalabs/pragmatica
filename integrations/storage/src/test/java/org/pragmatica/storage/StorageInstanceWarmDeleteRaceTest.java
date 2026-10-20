package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A delete that runs while a prefetch of the same block is fetching it must win: the prefetch stores nothing, and
/// leaves no record, so the deleted block does not come back on the node that was warming it.
class StorageInstanceWarmDeleteRaceTest {
    /// A shared tier whose reads and deletes can be held open, or whose deletes can be made to fail.
    private static final class GateTier implements StorageTier {
        private final SharedTestTier inner = new SharedTestTier();
        final AtomicBoolean holdGets = new AtomicBoolean();
        final AtomicBoolean holdDeletes = new AtomicBoolean();
        final AtomicBoolean failDeletes = new AtomicBoolean();
        private final List<Runnable> heldGets = new CopyOnWriteArrayList<>();
        private final List<Runnable> heldDeletes = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            if (!holdGets.get()) {
                return inner.get(id);
            }

            // The bytes are read when the call is made: what a transfer that began before the delete carries.
            var snapshot = inner.get(id).await().unwrap();
            var held = Promise.<Option<byte[]>> promise();

            heldGets.add(() -> held.succeed(snapshot));

            return held;
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            if (failDeletes.get()) {
                return Causes.cause("shared tier refused the delete").promise();
            }

            if (!holdDeletes.get()) {
                return inner.delete(id);
            }

            var held = Promise.<Unit> promise();

            heldDeletes.add(() -> inner.delete(id).onResult(held::resolve));

            return held;
        }

        int heldGets() {
            return heldGets.size();
        }

        int heldDeletes() {
            return heldDeletes.size();
        }

        void releaseGets() {
            holdGets.set(false);
            run(heldGets);
        }

        void releaseDeletes() {
            holdDeletes.set(false);
            run(heldDeletes);
        }

        private static void run(List<Runnable> held) {
            var copy = List.copyOf(held);

            held.clear();
            copy.forEach(Runnable::run);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return inner.put(id, content);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return inner.exists(id);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
        }

        @Override
        public long usedBytes() {
            return inner.usedBytes();
        }

        @Override
        public long maxBytes() {
            return inner.maxBytes();
        }

        @Override
        public boolean isShared() {
            return true;
        }
    }

    private GateTier shared;
    private StorageInstance writer;
    private MemoryTier memory;
    private MetadataStore store;
    private DefaultStorageInstance reader;

    @BeforeEach
    void setUp() {
        shared = new GateTier();
        writer = StorageInstance.storageInstance("writer",
                                                 List.of(MemoryTier.memoryTier(10_000), shared),
                                                 MetadataStore.inMemoryMetadataStore("writer"));
        memory = MemoryTier.memoryTier(10_000, TierLevel.MEMORY);
        store = MetadataStore.inMemoryMetadataStore("reader");
        reader = (DefaultStorageInstance) StorageInstance.storageInstance("reader", List.of(memory, shared), store);
    }

    private BlockId writeElsewhere(String content) {
        return writer.put(content.getBytes(StandardCharsets.UTF_8)).await().unwrap();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + 15_000_000_000L;

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void delete_duringAWarm_doesNotResurrectTheBlock() throws Exception {
        var id = writeElsewhere("deleted-during-warm");

        shared.holdGets.set(true);

        var warm = reader.warm(id);

        awaitTrue(() -> shared.heldGets() > 0, "the warm's fetch to reach the shared tier");
        reader.delete(id).await().unwrap();
        shared.releaseGets();

        assertThat(warm.await().unwrap()).as("the warm did not store the block it was racing a delete for").isFalse();
        assertThat(memory.exists(id).await().unwrap()).as("no cached copy").isFalse();
        assertThat(store.getLifecycle(id).isPresent()).as("no record, so no marker for a block that no longer exists").isFalse();
        assertThat(reader.exists(id).await().unwrap()).as("the deleted block stays deleted").isFalse();
        assertThat(reader.get(id).await().unwrap().isPresent()).isFalse();
        assertThat(reader.inFlightWarms()).as("the cancelled warm's ticket is gone").isZero();
    }

    @Test
    void warm_startedWhileADeleteIsInFlight_doesNotStart() throws Exception {
        var id = writeElsewhere("warm-meets-a-delete-in-progress");

        shared.holdDeletes.set(true);

        var delete = reader.delete(id);

        awaitTrue(() -> shared.heldDeletes() > 0, "the delete to reach the shared tier");

        assertThat(shared.exists(id).await().unwrap()).as("control: the block is still in the shared tier").isTrue();
        assertThat(reader.warm(id).await().unwrap()).as("a block being deleted is not fetched").isFalse();
        assertThat(memory.exists(id).await().unwrap()).isFalse();

        shared.releaseDeletes();
        delete.await().unwrap();

        assertThat(store.getLifecycle(id).isPresent()).isFalse();
        assertThat(shared.exists(id).await().unwrap()).isFalse();
    }

    @Test
    void warm_afterTheDeleteCompleted_isNotRefusedByItsTombstone() {
        var id = writeElsewhere("deleted-then-written-again");

        reader.delete(id).await().unwrap();
        // A writer with no record of the block (the first one would deduplicate against its own) puts it back.
        StorageInstance.storageInstance("second-writer",
                                        List.of(MemoryTier.memoryTier(10_000), shared),
                                        MetadataStore.inMemoryMetadataStore("second-writer"))
                       .put("deleted-then-written-again".getBytes(StandardCharsets.UTF_8))
                       .await()
                       .unwrap();

        assertThat(reader.warm(id).await().unwrap()).as("the tombstone is gone once the delete is").isTrue();
    }

    @Test
    void warm_afterADeleteThatFailed_isNotRefusedByItsTombstone() {
        var id = writeElsewhere("delete-fails-block-stays");

        shared.failDeletes.set(true);

        assertThat(reader.delete(id).await().isFailure()).as("control: the delete failed").isTrue();
        shared.failDeletes.set(false);

        assertThat(reader.warm(id).await().unwrap()).as("a failed delete leaves no tombstone behind").isTrue();
    }

    @Test
    void warm_ofADifferentBlock_isNotCancelledByADelete() throws Exception {
        var warmed = writeElsewhere("warmed-block");
        var deleted = writeElsewhere("some-other-block");

        shared.holdGets.set(true);

        var warm = reader.warm(warmed);

        awaitTrue(() -> shared.heldGets() > 0, "the warm's fetch to reach the shared tier");
        reader.delete(deleted).await().unwrap();
        shared.releaseGets();

        assertThat(warm.await().unwrap()).as("only a delete of the SAME block cancels a warm").isTrue();
        assertThat(memory.exists(warmed).await().unwrap()).isTrue();
    }

    @Test
    void warm_leavesNoTicketBehind_whetherItCopiedOrNot() {
        var id = writeElsewhere("tickets-are-released");
        var unknown = BlockId.blockId("never-written".getBytes(StandardCharsets.UTF_8)).unwrap();

        assertThat(reader.warm(id).await().unwrap()).isTrue();
        assertThat(reader.warm(unknown).await().unwrap()).isFalse();
        assertThat(reader.inFlightWarms()).isZero();
    }
}
