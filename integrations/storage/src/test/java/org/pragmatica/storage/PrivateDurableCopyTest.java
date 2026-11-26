package org.pragmatica.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// [StorageInstance#holdsPrivateDurableCopy] (#3480): only a tier that is neither shared nor a cache answers. A shared tier answers
/// [StorageInstance#exists] for the whole cluster, and a cache copy (a warm, or a read promoting from the shared tier) answers the
/// private-only check [StorageInstance#warm] makes; neither is a copy this node keeps across a restart.
class PrivateDurableCopyTest {
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final byte[] CONTENT = "a checkpoint block".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path diskRoot;

    private final SharedTestTier shared = new SharedTestTier();
    private final MemoryTier cache = MemoryTier.memoryTier(ONE_GB);
    private StorageInstance writer;
    private StorageInstance reader;

    @AfterEach
    void tearDown() {
        Stream.of(writer, reader).filter(instance -> instance != null).forEach(StorageInstance::shutdown);
    }

    @Test
    void aBlockThisNodePut_isHeldOnItsPrivateDurableTier() {
        writer = storageWithADisk("writer");
        var id = writer.put(CONTENT).await().unwrap();

        assertThat(writer.holdsPrivateDurableCopy(id).await().unwrap()).isTrue();
    }

    @Test
    void aBlockOnlyTheSharedTierHolds_isNotHeld_althoughItExists() {
        writer = storageWithADisk("writer");
        reader = storageWithADisk("reader");
        var id = writer.put(CONTENT).await().unwrap();

        assertThat(reader.exists(id).await().unwrap()).as("control: the shared tier answers exists for the cluster")
                  .isTrue();
        assertThat(reader.holdsPrivateDurableCopy(id).await().unwrap()).isFalse();
    }

    /// #3480 (t3480t, kills A1-dropNotShared): the production remote tier is shared AND durable, so the shared term of the private-durable
    /// test is what keeps a block only that tier holds from counting as held.
    @Test
    void aBlockOnlyASharedDurableTierHolds_isNotHeld_althoughItExists() {
        var remote = SharedTestTier.sharedDurableTestTier();
        writer = storageOver(remote, "writer");
        reader = storageOver(remote, "reader");
        var id = writer.put(CONTENT).await().unwrap();

        assertThat(reader.exists(id).await().unwrap()).as("control: the shared durable tier answers exists for the cluster").isTrue();
        assertThat(remote.exists(id).await().unwrap()).as("control: the shared durable tier holds it").isTrue();
        assertThat(reader.holdsPrivateDurableCopy(id).await().unwrap()).isFalse();
    }

    @Test
    void aBlockOnlyACacheTierHolds_isNotHeld() {
        writer = storageWithADisk("writer");
        reader = StorageInstance.storageInstance("reader",
                                                 List.of(cache,
                                                         LocalDiskTier.localDiskTier(diskRoot.resolve("reader"),
                                                                                     ONE_GB)
                                                                      .unwrap(),
                                                         shared),
                                                 MetadataStore.inMemoryMetadataStore("reader"));
        var id = writer.put(CONTENT).await().unwrap();

        assertThat(reader.warm(id).await().unwrap()).as("control: the warm copied the block into the cache").isTrue();
        assertThat(cache.exists(id).await().unwrap()).as("control: the cache tier holds it").isTrue();
        assertThat(reader.holdsPrivateDurableCopy(id).await().unwrap()).isFalse();
    }

    @Test
    void anAbsentBlock_isNotHeld() {
        writer = storageWithADisk("writer");
        var absent = BlockId.blockId("never put".getBytes(StandardCharsets.UTF_8)).unwrap();

        assertThat(writer.holdsPrivateDurableCopy(absent).await().unwrap()).isFalse();
    }

    @Test
    void theInterfaceDefault_answersNotHeld() {
        var bare = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                            new Class<?>[]{StorageInstance.class},
                                                            (proxy, method, args) -> InvocationHandler.invokeDefault(proxy,
                                                                                                                     method,
                                                                                                                     args));
        var id = BlockId.blockId(CONTENT).unwrap();

        assertThat(bare.holdsPrivateDurableCopy(id).await().unwrap()).isFalse();
    }

    private StorageInstance storageOver(StorageTier remote, String name) {
        return StorageInstance.storageInstance(name,
                                               List.of(MemoryTier.memoryTier(ONE_GB),
                                                       LocalDiskTier.localDiskTier(diskRoot.resolve(name),
                                                                                   ONE_GB)
                                                                    .unwrap(),
                                                       remote),
                                               MetadataStore.inMemoryMetadataStore(name));
    }

    private StorageInstance storageWithADisk(String name) {
        return StorageInstance.storageInstance(name,
                                               List.of(MemoryTier.memoryTier(ONE_GB),
                                                       LocalDiskTier.localDiskTier(diskRoot.resolve(name),
                                                                                   ONE_GB)
                                                                    .unwrap(),
                                                       shared),
                                               MetadataStore.inMemoryMetadataStore(name));
    }
}
