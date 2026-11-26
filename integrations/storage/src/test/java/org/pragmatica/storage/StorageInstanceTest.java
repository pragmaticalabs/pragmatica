package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class StorageInstanceTest {

    private static final byte[] CONTENT_A = "storage-instance-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "storage-instance-bravo".getBytes(StandardCharsets.UTF_8);
    private static final long MEMORY_MAX = 1024 * 1024;
    private static final long DISK_MAX = 10 * 1024 * 1024;

    @TempDir
    Path tempDir;

    private MemoryTier memoryTier;
    private LocalDiskTier diskTier;
    private StorageInstance instance;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(MEMORY_MAX);
        diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), DISK_MAX)
                                .fold(c -> { fail("Disk tier creation failed: " + c.message()); return null; },
                                      t -> t);
        instance = StorageInstance.storageInstance("test-instance", List.of(memoryTier, diskTier));
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class PutGetTests {

        @Test
        void put_get_roundTrip() {
            var putResult = instance.put(CONTENT_A).await();

            putResult.onFailure(c -> fail("put failed: " + c.message()))
                     .onSuccess(id ->
                         instance.get(id).await()
                                 .onFailure(c -> fail("get failed: " + c.message()))
                                 .onSuccess(opt -> {
                                     assertThat(opt.isPresent()).isTrue();
                                     opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                                 })
                     );
        }

        @Test
        void put_deduplicates_sameContent() {
            var first = instance.put(CONTENT_A).await();
            var second = instance.put(CONTENT_A).await();

            first.onFailure(c -> fail("first put failed: " + c.message()))
                 .onSuccess(id1 ->
                     second.onFailure(c -> fail("second put failed: " + c.message()))
                           .onSuccess(id2 -> assertThat(id1).isEqualTo(id2))
                 );
        }

        @Test
        void put_differentContent_differentIds() {
            var first = instance.put(CONTENT_A).await();
            var second = instance.put(CONTENT_B).await();

            first.onSuccess(id1 ->
                second.onSuccess(id2 -> assertThat(id1).isNotEqualTo(id2))
            );
        }

        @Test
        void get_nonExistent_returnsEmpty() {
            var id = blockIdOf("nonexistent".getBytes(StandardCharsets.UTF_8));

            instance.get(id).await()
                    .onFailure(c -> fail("get failed: " + c.message()))
                    .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class TierWaterfallTests {

        @Test
        void get_readsFromMemoryFirst() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id -> {
                        assertThat(memoryTier.usedBytes()).isGreaterThan(0);

                        instance.get(id).await()
                                .onFailure(c -> fail("get failed: " + c.message()))
                                .onSuccess(opt -> {
                                    assertThat(opt.isPresent()).isTrue();
                                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                                });
                    });
        }

        @Test
        void get_fallsBackToDisk() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id -> {
                        memoryTier.delete(id).await();

                        assertThat(memoryTier.usedBytes()).isZero();

                        instance.get(id).await()
                                .onFailure(c -> fail("get from disk failed: " + c.message()))
                                .onSuccess(opt -> {
                                    assertThat(opt.isPresent()).isTrue();
                                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                                });
                    });
        }

        @Test
        void get_verifiesIntegrity() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id -> {
                        instance.get(id).await()
                                .onSuccess(opt -> opt.onPresent(data -> {
                                    var verifyResult = BlockId.blockId(data);
                                    verifyResult.onSuccess(computedId -> assertThat(computedId).isEqualTo(id));
                                }));
                    });
        }
    }

    @Nested
    class ExistsTests {

        @Test
        void exists_trueForStoredBlock() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id ->
                        instance.exists(id).await()
                                .onSuccess(found -> assertThat(found).isTrue())
                    );
        }

        @Test
        void exists_falseForMissingBlock() {
            var id = blockIdOf("missing".getBytes(StandardCharsets.UTF_8));

            instance.exists(id).await()
                    .onSuccess(found -> assertThat(found).isFalse());
        }
    }

    @Nested
    class RefTests {

        @Test
        void createRef_resolveRef_roundTrip() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id -> {
                        instance.createRef("my-ref", id).await()
                                .onFailure(c -> fail("createRef failed: " + c.message()));

                        var resolved = instance.resolveRef("my-ref");
                        assertThat(resolved.isPresent()).isTrue();
                        resolved.onPresent(resolvedId -> assertThat(resolvedId).isEqualTo(id));
                    });
        }

        @Test
        void resolveRef_missingRef_returnsEmpty() {
            var resolved = instance.resolveRef("nonexistent");

            assertThat(resolved.isEmpty()).isTrue();
        }

        @Test
        void deleteRef_removesReference() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id -> {
                        instance.createRef("temp-ref", id).await();
                        instance.deleteRef("temp-ref").await();

                        var resolved = instance.resolveRef("temp-ref");
                        assertThat(resolved.isEmpty()).isTrue();
                    });
        }
    }

    @Nested
    class TierInfoTests {

        @Test
        void tierInfo_returnsUtilization() {
            var info = instance.tierInfo();

            assertThat(info).hasSize(2);
            assertThat(info.get(0).level()).isEqualTo(TierLevel.MEMORY);
            assertThat(info.get(0).maxBytes()).isEqualTo(MEMORY_MAX);
            assertThat(info.get(1).level()).isEqualTo(TierLevel.LOCAL_DISK);
            assertThat(info.get(1).maxBytes()).isEqualTo(DISK_MAX);
        }

        @Test
        void tierInfo_reflectsUsedBytes_afterPut() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()));

            var info = instance.tierInfo();

            assertThat(info.get(0).usedBytes()).isGreaterThan(0);
            assertThat(info.get(1).usedBytes()).isGreaterThan(0);
        }
    }

    @Nested
    class InstanceMetadataTests {

        @Test
        void name_returnsConfiguredName() {
            assertThat(instance.name()).isEqualTo("test-instance");
        }
    }
}
