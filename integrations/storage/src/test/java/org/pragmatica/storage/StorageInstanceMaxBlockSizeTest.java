package org.pragmatica.storage;

import java.util.List;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// A0: an instance refuses a block over its maximum size before it claims or writes anything. All three write
/// entry points (`put`, `putRef`, `swapRef`) go through one check, and a refused write leaves no trace.
class StorageInstanceMaxBlockSizeTest {
    private static final int MAX = 1024;

    private MetadataStore metadataStore;
    private StorageInstance storage;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("max-block");
        storage = StorageInstance.storageInstance("max-block",
                                                  List.of(MemoryTier.memoryTier(64L * 1024 * 1024)),
                                                  metadataStore,
                                                  WritePolicy.WRITE_THROUGH,
                                                  Option.none(),
                                                  AppendLog.TornTailSink.logOnly(),
                                                  _ -> {},
                                                  MAX);
    }

    private static byte[] bytes(int size) {
        var data = new byte[size];

        for (var i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }

        return data;
    }

    private static void assertTooLarge(org.pragmatica.lang.Result<?> result, int size) {
        result.onSuccess(_ -> fail("a block of " + size + " bytes must be refused"))
              .onFailure(cause -> assertThat(cause).isEqualTo(StorageError.BlockTooLarge.blockTooLarge(size, MAX)));
    }

    @Test
    void blockOfExactlyTheMaximum_isAccepted() {
        var id = storage.put(bytes(MAX)).await().unwrap();

        assertThat(storage.get(id).await().unwrap().isPresent()).isTrue();
    }

    @Test
    void blockOverTheMaximum_isRefused_andNothingIsClaimed() {
        var content = bytes(MAX + 1);

        assertTooLarge(storage.put(content).await(), MAX + 1);
        assertThat(metadataStore.containsBlock(BlockId.blockId(content).unwrap())).as("no claim was made").isFalse();
        assertThat(storage.exists(BlockId.blockId(content).unwrap()).await().unwrap()).isFalse();
    }

    @Test
    void putRefOverTheMaximum_isRefused_andNoRefIsCreated() {
        assertTooLarge(storage.putRef("big", bytes(MAX + 1)).await(), MAX + 1);
        assertThat(storage.resolveRef("big").isEmpty()).isTrue();
    }

    @Test
    void swapRefOverTheMaximum_isRefused_andLeavesTheCurrentValue() {
        var kept = storage.putRef("name", bytes(10)).await().unwrap();

        assertTooLarge(storage.swapRef("name", bytes(MAX + 1)).await(), MAX + 1);
        assertThat(storage.resolveRef("name").unwrap()).isEqualTo(kept);
    }

    @Test
    void defaultInstance_refusesOverTheDefaultMaximum_andAcceptsAtIt() {
        var plain = StorageInstance.storageInstance("plain", List.of(MemoryTier.memoryTier(64L * 1024 * 1024)));
        var over = new byte[StorageInstance.DEFAULT_MAX_BLOCK_BYTES + 1];

        plain.put(over)
             .await()
             .onSuccess(_ -> fail("a block over the default maximum must be refused"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.BlockTooLarge.class));
    }
}
