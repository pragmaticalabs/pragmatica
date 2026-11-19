package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;


/// #420 T-S2. A put of a block this node already owns credits the record and writes nothing
/// (`StorageInstance` `handlePut` -> `deduplicateBlock`), so a shared copy lost since the first put is not restored by
/// a second put. PR-2 adds `writeShared` for that; the control below pins the put path it leaves alone, so the
/// `writeShared` arms PR-2 adds here are measured against a put that provably writes nothing.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StorageInstanceWriteSharedTest {
    private static final byte[] CONTENT = "shared block".getBytes(StandardCharsets.UTF_8);

    private CountingTier shared;
    private MetadataStore store;
    private StorageInstance node;

    @BeforeEach
    void setUp() {
        shared = CountingTier.countingTier(MemoryTier.memoryTier(1 << 20, TierLevel.REMOTE));
        store = MetadataStore.inMemoryMetadataStore("t-s2");
        node = StorageInstance.storageInstance("t-s2",
                                               List.of(MemoryTier.memoryTier(1 << 20), shared),
                                               store);
    }

    /// CTRL (design r13 T-S2, at the pin and after PR-2): the second put finds the record owned, credits it and
    /// writes nothing to the shared tier, even though the shared tier lost the block in between.
    @Test
    void secondPutOnAnOwnedRecord_writesNothingToTheSharedTier() {
        var id = node.put(CONTENT).await().unwrap();

        assertThat(shared.puts()).as("precondition: the first put wrote the shared tier once").isEqualTo(1);
        shared.delete(id).await().unwrap();
        assertThat(shared.exists(id).await().unwrap()).as("precondition: the shared copy is gone").isFalse();
        var before = store.getLifecycle(id).unwrap().refCount();

        assertThat(node.put(CONTENT).await().unwrap()).isEqualTo(id);
        assertThat(shared.puts()).as("CTRL T-S2: a second put on an owned record writes nothing to the shared tier")
                  .isEqualTo(1);
        assertThat(shared.exists(id).await().unwrap()).as("CTRL T-S2: the second put did not restore the shared copy")
                  .isFalse();
        assertThat(store.getLifecycle(id).unwrap().refCount()).as("CTRL T-S2: the second put credited the record")
                  .isEqualTo(before + 1);
    }

    /// A shared tier double that counts the block writes it receives.
    private record CountingTier(MemoryTier inner, AtomicInteger writes) implements StorageTier {
        static CountingTier countingTier(MemoryTier inner) {
            return new CountingTier(inner, new AtomicInteger());
        }

        int puts() {
            return writes.get();
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return inner.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            writes.incrementAndGet();

            return inner.put(id, content);
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
