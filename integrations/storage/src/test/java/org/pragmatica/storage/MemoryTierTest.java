package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class MemoryTierTest {

    private static final byte[] CONTENT_A = "content-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "content-bravo".getBytes(StandardCharsets.UTF_8);

    private MemoryTier tier;

    @BeforeEach
    void setUp() {
        tier = MemoryTier.memoryTier(1024 * 1024);
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
        void put_existingBlock_replacesContent() {
            var id = blockIdOf(CONTENT_A);
            var replacement = "replaced-data".getBytes(StandardCharsets.UTF_8);

            tier.put(id, CONTENT_A).await();
            tier.put(id, replacement).await();

            tier.get(id).await()
                .onFailure(c -> fail("get failed: " + c.message()))
                .onSuccess(opt -> opt.onPresent(data -> assertThat(data).isEqualTo(replacement)));
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
    class DeleteTests {

        @Test
        void delete_removesBlock() {
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
                .onFailure(c -> fail("exists failed: " + c.message()))
                .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_returnsFalse_forMissingBlock() {
            var id = blockIdOf(CONTENT_A);

            tier.exists(id).await()
                .onFailure(c -> fail("exists failed: " + c.message()))
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
            var smallTier = MemoryTier.memoryTier(10);
            var id = blockIdOf(CONTENT_A);

            var result = smallTier.put(id, CONTENT_A).await();

            result.onSuccess(_ -> fail("Expected failure for exceeding capacity"));
            result.onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.TierFull.class));
        }
    }

    @Nested
    class MetadataTests {

        @Test
        void level_returnsMemory() {
            assertThat(tier.level()).isEqualTo(TierLevel.MEMORY);
        }

        @Test
        void maxBytes_returnsConfiguredValue() {
            assertThat(tier.maxBytes()).isEqualTo(1024 * 1024);
        }
    }
}
