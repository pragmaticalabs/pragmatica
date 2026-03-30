package org.pragmatica.aether.slice.kvstore;

import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.StorageBlockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StorageRefKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.AetherKey.StorageBlockKey.storageBlockKey;
import static org.pragmatica.aether.slice.kvstore.AetherKey.StorageRefKey.storageRefKey;
import static org.pragmatica.aether.slice.kvstore.AetherValue.StorageBlockValue.storageBlockValue;
import static org.pragmatica.aether.slice.kvstore.AetherValue.StorageRefValue.storageRefValue;

class StorageKeyValueTest {

    private static final String INSTANCE = "test-store";
    private static final String BLOCK_HEX = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final long NOW = System.currentTimeMillis();

    @Nested
    class StorageBlockKeyTests {

        @Test
        void storageBlockKey_asString_formatsCorrectly() {
            var key = storageBlockKey(INSTANCE, BLOCK_HEX);

            assertThat(key.asString()).isEqualTo("storage-block/" + INSTANCE + "/" + BLOCK_HEX);
        }

        @Test
        void storageBlockKey_parse_roundTrips() {
            var original = storageBlockKey(INSTANCE, BLOCK_HEX);

            StorageBlockKey.storageBlockKey(original.asString())
                           .onSuccess(parsed -> {
                               assertThat(parsed.instanceName()).isEqualTo(INSTANCE);
                               assertThat(parsed.blockIdHex()).isEqualTo(BLOCK_HEX);
                           })
                           .onFailureRun(Assertions::fail);
        }

        @Test
        void storageBlockKey_parse_invalidPrefix_returnsError() {
            StorageBlockKey.storageBlockKey("wrong-prefix/" + INSTANCE + "/" + BLOCK_HEX)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause -> assertThat(cause.message()).contains("Invalid storage-block key format"));
        }

        @Test
        void storageBlockKey_parse_missingSlash_returnsError() {
            StorageBlockKey.storageBlockKey("storage-block/noslashhere")
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause -> assertThat(cause.message()).contains("Invalid storage-block key format"));
        }
    }

    @Nested
    class StorageRefKeyTests {

        @Test
        void storageRefKey_asString_formatsCorrectly() {
            var key = storageRefKey(INSTANCE, "my-artifact");

            assertThat(key.asString()).isEqualTo("storage-ref/" + INSTANCE + "/my-artifact");
        }

        @Test
        void storageRefKey_parse_roundTrips() {
            var original = storageRefKey(INSTANCE, "my-artifact");

            StorageRefKey.storageRefKey(original.asString())
                         .onSuccess(parsed -> {
                             assertThat(parsed.instanceName()).isEqualTo(INSTANCE);
                             assertThat(parsed.referenceName()).isEqualTo("my-artifact");
                         })
                         .onFailureRun(Assertions::fail);
        }

        @Test
        void storageRefKey_parse_invalidPrefix_returnsError() {
            StorageRefKey.storageRefKey("wrong-prefix/" + INSTANCE + "/my-artifact")
                         .onSuccessRun(Assertions::fail)
                         .onFailure(cause -> assertThat(cause.message()).contains("Invalid storage-ref key format"));
        }
    }

    @Nested
    class StorageBlockValueTests {

        @Test
        void storageBlockValue_withTierAdded_addsNewTier() {
            var value = storageBlockValue(BLOCK_HEX, Set.of("MEMORY"), 1, NOW, NOW);

            var updated = value.withTierAdded("LOCAL_DISK");

            assertThat(updated.presentIn()).containsExactlyInAnyOrder("MEMORY", "LOCAL_DISK");
            assertThat(updated.refCount()).isEqualTo(1);
        }

        @Test
        void storageBlockValue_withRefCountIncremented_incrementsCount() {
            var value = storageBlockValue(BLOCK_HEX, Set.of("MEMORY"), 1, NOW, NOW);

            var updated = value.withRefCountIncremented();

            assertThat(updated.refCount()).isEqualTo(2);
        }

        @Test
        void storageBlockValue_withRefCountDecremented_decrementsCount() {
            var value = storageBlockValue(BLOCK_HEX, Set.of("MEMORY"), 3, NOW, NOW);

            var updated = value.withRefCountDecremented();

            assertThat(updated.refCount()).isEqualTo(2);
        }

        @Test
        void storageBlockValue_withRefCountDecremented_floorsAtZero() {
            var value = storageBlockValue(BLOCK_HEX, Set.of("MEMORY"), 0, NOW, NOW);

            var updated = value.withRefCountDecremented();

            assertThat(updated.refCount()).isZero();
        }
    }

    @Nested
    class StorageRefValueTests {

        @Test
        void storageRefValue_factory_setsTimestamp() {
            var before = System.currentTimeMillis();

            var value = storageRefValue(BLOCK_HEX);

            var after = System.currentTimeMillis();
            assertThat(value.blockIdHex()).isEqualTo(BLOCK_HEX);
            assertThat(value.updatedAt()).isBetween(before, after);
        }
    }
}
