package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;

class InMemoryMetadataStoreTest {

    private static final byte[] CONTENT_A = "metadata-store-alpha".getBytes(StandardCharsets.UTF_8);

    private InMemoryMetadataStore store;

    @BeforeEach
    void setUp() {
        store = inMemoryMetadataStore("test-instance");
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class LifecycleTests {

        @Test
        void getLifecycle_unknownBlock_returnsNone() {
            var id = blockIdOf(CONTENT_A);

            var result = store.getLifecycle(id);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void createLifecycle_newBlock_canBeRetrieved() {
            var id = blockIdOf(CONTENT_A);
            var lifecycle = BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY);

            store.createLifecycle(lifecycle);

            var retrieved = store.getLifecycle(id);
            assertThat(retrieved.isPresent()).isTrue();
            retrieved.onPresent(lc -> {
                assertThat(lc.blockId()).isEqualTo(id);
                assertThat(lc.presentIn()).contains(TierLevel.MEMORY);
                assertThat(lc.refCount()).isEqualTo(1);
            });
        }

        @Test
        void computeLifecycle_existingBlock_appliesUpdater() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            var updated = store.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);

            assertThat(updated.isPresent()).isTrue();
            updated.onPresent(lc -> assertThat(lc.refCount()).isEqualTo(2));
        }

        @Test
        void computeLifecycle_unknownBlock_returnsNone() {
            var id = blockIdOf(CONTENT_A);

            var result = store.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void removeLifecycle_existingBlock_removesEntry() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            store.removeLifecycle(id);

            assertThat(store.getLifecycle(id).isEmpty()).isTrue();
        }
    }

    @Nested
    class ClaimTests {

        @Test
        void claimBlock_firstClaim_returnsTrue() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            var claimed = store.claimBlock(id, sentinel);

            assertThat(claimed).isTrue();
        }

        @Test
        void claimBlock_duplicateClaim_returnsFalse() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            store.claimBlock(id, sentinel);
            var secondClaim = store.claimBlock(id, sentinel);

            assertThat(secondClaim).isFalse();
        }
    }

    @Nested
    class ContainsTests {

        @Test
        void containsBlock_afterCreate_returnsTrue() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            assertThat(store.containsBlock(id)).isTrue();
        }

        @Test
        void containsBlock_afterRemove_returnsFalse() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            store.removeLifecycle(id);

            assertThat(store.containsBlock(id)).isFalse();
        }
    }

    @Nested
    class RefTests {

        @Test
        void putRef_andResolve_returnsBlockId() {
            var id = blockIdOf(CONTENT_A);

            store.putRef("my-artifact", id);

            var resolved = store.resolveRef("my-artifact");
            assertThat(resolved.isPresent()).isTrue();
            resolved.onPresent(resolvedId -> assertThat(resolvedId).isEqualTo(id));
        }

        @Test
        void resolveRef_unknownRef_returnsNone() {
            var resolved = store.resolveRef("nonexistent");

            assertThat(resolved.isEmpty()).isTrue();
        }

        @Test
        void removeRef_existingRef_returnsBlockId() {
            var id = blockIdOf(CONTENT_A);
            store.putRef("temp-ref", id);

            var removed = store.removeRef("temp-ref");

            assertThat(removed.isPresent()).isTrue();
            removed.onPresent(removedId -> assertThat(removedId).isEqualTo(id));
        }

        @Test
        void removeRef_unknownRef_returnsNone() {
            var removed = store.removeRef("nonexistent");

            assertThat(removed.isEmpty()).isTrue();
        }
    }

    @Nested
    class InstanceNameTests {

        @Test
        void instanceName_returnsConfiguredName() {
            assertThat(store.instanceName()).isEqualTo("test-instance");
        }
    }
}
