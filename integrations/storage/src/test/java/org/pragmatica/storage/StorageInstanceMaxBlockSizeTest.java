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
              .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StorageError.BlockTooLarge.class,
                                                                           tooLarge -> {
                                                                               assertThat(tooLarge.size()).as("offered size").isEqualTo(size);
                                                                               assertThat(tooLarge.maxBytes()).as("limit").isEqualTo(MAX);
                                                                           }));
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
    void defaultMaximum_isSixteenMiB() {
        assertThat(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isEqualTo(16 * 1024 * 1024);
        assertThat(StorageInstance.storageInstance("plain", List.of(MemoryTier.memoryTier(64L * 1024 * 1024)))
                                  .maxBlockBytes()).isEqualTo(16_777_216);
    }

    @Test
    void defaultInstance_acceptsExactlySixteenMiB_andRefusesOneByteMore() {
        var plain = StorageInstance.storageInstance("plain", List.of(MemoryTier.memoryTier(64L * 1024 * 1024)));
        var atLimit = new byte[16 * 1024 * 1024];
        var over = new byte[16 * 1024 * 1024 + 1];

        plain.put(atLimit).await().onFailure(cause -> fail("a block of exactly 16 MiB must be accepted: " + cause.message()));
        plain.put(over)
             .await()
             .onSuccess(_ -> fail("a block over 16 MiB must be refused"))
             .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StorageError.BlockTooLarge.class,
                                                                          tooLarge -> assertThat(tooLarge.maxBytes()).isEqualTo(16_777_216L)));
    }

    @Test
    void anInstanceThatDoesNotSayOtherwise_reportsTheDefaultMaximum() {
        var bare = (StorageInstance) java.lang.reflect.Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                                              new Class<?>[]{StorageInstance.class},
                                                                              (proxy, method, args) -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args));

        assertThat(bare.maxBlockBytes()).isEqualTo(16 * 1024 * 1024);
    }

    @Test
    void configuredMaximum_isWhatTheInstanceReports() {
        assertThat(storage.maxBlockBytes()).isEqualTo(MAX);
    }
}
