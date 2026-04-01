package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class LocalDiskTierTest {

    private static final byte[] CONTENT_A = "disk-content-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "disk-content-bravo".getBytes(StandardCharsets.UTF_8);
    private static final long MAX_BYTES = 10 * 1024 * 1024;

    @TempDir
    Path tempDir;

    private LocalDiskTier tier;

    @BeforeEach
    void setUp() {
        tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), MAX_BYTES)
                            .fold(c -> { fail("Tier creation failed: " + c.message()); return null; },
                                  t -> t);
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
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await()
                .onFailure(c -> fail("put failed: " + c.message()));

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> {
                    assertThat(opt.isPresent()).isTrue();
                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                });
        }

        @Test
        void get_nonExistent_returnsEmpty() {
            var id = blockIdOf(CONTENT_A);

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class DirectoryStructureTests {

        @Test
        void put_createsShardedDirectoryStructure() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await();

            var hex = id.hexString();
            var expectedPath = tempDir.resolve("blocks")
                                      .resolve(hex.substring(0, 2))
                                      .resolve(hex.substring(2, 4))
                                      .resolve(hex);

            assertThat(Files.exists(expectedPath)).isTrue();
        }
    }

    @Nested
    class DeleteTests {

        @Test
        void delete_removesFile() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await();
            tier.delete(id).await();

            tier.get(id).await()
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    @Nested
    class ExistsTests {

        @Test
        void exists_returnsTrue_forStoredBlock() {
            var id = blockIdOf(CONTENT_A);

            tier.put(id, CONTENT_A).await();

            tier.exists(id).await()
                .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_returnsFalse_forMissingBlock() {
            var id = blockIdOf(CONTENT_A);

            tier.exists(id).await()
                .onSuccess(found -> assertThat(found).isFalse());
        }
    }

    @Nested
    class CapacityTests {

        @Test
        void usedBytes_tracksAccurately() {
            var id = blockIdOf(CONTENT_A);

            assertThat(tier.usedBytes()).isZero();

            tier.put(id, CONTENT_A).await();
            assertThat(tier.usedBytes()).isEqualTo(CONTENT_A.length);

            tier.delete(id).await();
            assertThat(tier.usedBytes()).isZero();
        }

        @Test
        void put_exceedsMaxBytes_returnsFailure() {
            var smallTier = LocalDiskTier.localDiskTier(tempDir.resolve("small"), 10)
                                        .fold(c -> { fail("Tier creation failed: " + c.message()); return null; },
                                              t -> t);
            var id = blockIdOf(CONTENT_A);

            var result = smallTier.put(id, CONTENT_A).await();

            result.onSuccess(_ -> fail("Expected failure for exceeding capacity"));
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.TierFull.class));
        }
    }

    @Nested
    class PersistenceTests {

        @Test
        void survives_restart() {
            var storagePath = tempDir.resolve("persistent");
            var id = blockIdOf(CONTENT_A);

            var firstTier = LocalDiskTier.localDiskTier(storagePath, MAX_BYTES)
                                        .fold(c -> { fail("Tier creation failed: " + c.message()); return null; },
                                              t -> t);

            firstTier.put(id, CONTENT_A).await();

            var secondTier = LocalDiskTier.localDiskTier(storagePath, MAX_BYTES)
                                         .fold(c -> { fail("Tier creation failed: " + c.message()); return null; },
                                               t -> t);

            secondTier.get(id).await()
                      .onFailure(c -> fail("get failed: " + c.message()))
                      .onSuccess(opt -> {
                          assertThat(opt.isPresent()).isTrue();
                          opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                      });

            assertThat(secondTier.usedBytes()).isEqualTo(CONTENT_A.length);
        }
    }

    @Nested
    class MetadataTests {

        @Test
        void level_returnsLocalDisk() {
            assertThat(tier.level()).isEqualTo(TierLevel.LOCAL_DISK);
        }

        @Test
        void maxBytes_returnsConfiguredValue() {
            assertThat(tier.maxBytes()).isEqualTo(MAX_BYTES);
        }
    }
}
