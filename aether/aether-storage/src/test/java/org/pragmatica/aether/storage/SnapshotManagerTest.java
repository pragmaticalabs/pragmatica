package org.pragmatica.aether.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.aether.storage.MetadataSnapshot.metadataSnapshot;
import static org.pragmatica.aether.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.aether.storage.SnapshotManager.snapshotManager;

class SnapshotManagerTest {

    private static final byte[] CONTENT_A = "snapshot-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "snapshot-beta".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_C = "snapshot-gamma".getBytes(StandardCharsets.UTF_8);
    private static final String NODE_ID = "node-test-1";

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class MetadataSnapshotTests {

        @Test
        void metadataSnapshot_factory_computesHash() {
            var idA = blockIdOf(CONTENT_A);
            var lifecycle = BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY);
            var refs = Map.of("ref-a", idA);

            var snapshot = metadataSnapshot(1L, NODE_ID, List.of(lifecycle), refs);

            assertThat(snapshot.contentHash()).isNotNull();
            assertThat(snapshot.contentHash()).hasSize(32);
            assertThat(snapshot.epoch()).isEqualTo(1L);
            assertThat(snapshot.nodeId()).isEqualTo(NODE_ID);
        }

        @Test
        void metadataSnapshot_isValid_returnsTrueForUnmodified() {
            var idA = blockIdOf(CONTENT_A);
            var lifecycle = BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY);

            var snapshot = metadataSnapshot(1L, NODE_ID, List.of(lifecycle), Map.of("ref-a", idA));

            assertThat(snapshot.isValid()).isTrue();
        }

        @Test
        void metadataSnapshot_defensiveCopy_lifecyclesIsImmutable() {
            var idA = blockIdOf(CONTENT_A);
            var lifecycle = BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY);
            var mutableList = new ArrayList<>(List.of(lifecycle));

            var snapshot = metadataSnapshot(1L, NODE_ID, mutableList, Map.of());

            mutableList.clear();
            assertThat(snapshot.lifecycles()).hasSize(1);
        }

        @Test
        void metadataSnapshot_contentHash_returnsDefensiveCopy() {
            var idA = blockIdOf(CONTENT_A);
            var snapshot = metadataSnapshot(1L, NODE_ID, List.of(), Map.of("ref-a", idA));

            var firstHash = snapshot.contentHash();
            var secondHash = snapshot.contentHash();

            assertThat(firstHash).isEqualTo(secondHash);
            assertThat(firstHash).isNotSameAs(secondHash);
        }
    }

    @Nested
    class InMemoryMetadataStoreEpochTests {

        private InMemoryMetadataStore store;

        @BeforeEach
        void setUp() {
            store = inMemoryMetadataStore("epoch-test");
        }

        @Test
        void currentEpoch_initiallyZero() {
            assertThat(store.currentEpoch()).isZero();
        }

        @Test
        void currentEpoch_incrementsOnCreate() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            assertThat(store.currentEpoch()).isEqualTo(1);
        }

        @Test
        void currentEpoch_incrementsOnClaim() {
            var id = blockIdOf(CONTENT_A);
            store.claimBlock(id, BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            assertThat(store.currentEpoch()).isEqualTo(1);
        }

        @Test
        void currentEpoch_incrementsOnCompute() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            var epochAfterCreate = store.currentEpoch();

            store.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);

            assertThat(store.currentEpoch()).isEqualTo(epochAfterCreate + 1);
        }

        @Test
        void listAllLifecycles_returnsAllEntries() {
            var idA = blockIdOf(CONTENT_A);
            var idB = blockIdOf(CONTENT_B);
            store.createLifecycle(BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY));
            store.createLifecycle(BlockLifecycle.blockLifecycle(idB, TierLevel.LOCAL_DISK));

            var all = store.listAllLifecycles();

            assertThat(all).hasSize(2);
            assertThat(all.stream().map(BlockLifecycle::blockId))
                .containsExactlyInAnyOrder(idA, idB);
        }

        @Test
        void restoreLifecycles_replacesExistingEntries() {
            var idA = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY));

            var idB = blockIdOf(CONTENT_B);
            var replacement = BlockLifecycle.blockLifecycle(idB, TierLevel.LOCAL_DISK);
            store.restoreLifecycles(List.of(replacement));

            assertThat(store.containsBlock(idA)).isFalse();
            assertThat(store.containsBlock(idB)).isTrue();
            assertThat(store.listAllLifecycles()).hasSize(1);
        }

        @Test
        void listAllRefs_returnsAllMappings() {
            var idA = blockIdOf(CONTENT_A);
            var idB = blockIdOf(CONTENT_B);
            store.putRef("ref-alpha", idA);
            store.putRef("ref-beta", idB);

            var allRefs = store.listAllRefs();

            assertThat(allRefs).hasSize(2);
            assertThat(allRefs).containsEntry("ref-alpha", idA);
            assertThat(allRefs).containsEntry("ref-beta", idB);
        }

        @Test
        void restoreRefs_replacesExistingMappings() {
            var idA = blockIdOf(CONTENT_A);
            store.putRef("old-ref", idA);

            var idB = blockIdOf(CONTENT_B);
            store.restoreRefs(Map.of("new-ref", idB));

            assertThat(store.resolveRef("old-ref").isEmpty()).isTrue();
            assertThat(store.resolveRef("new-ref").isPresent()).isTrue();
        }
    }

    @Nested
    class SnapshotManagerTests {

        @TempDir
        Path tempDir;

        private InMemoryMetadataStore store;

        @BeforeEach
        void setUp() {
            store = inMemoryMetadataStore("snap-test");
        }

        @Test
        void maybeSnapshot_belowThreshold_noSnapshot() throws IOException {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            manager.maybeSnapshot();

            assertThat(snapshotFileCount(tempDir)).isZero();
        }

        @Test
        void forceSnapshot_writesFile() throws IOException {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            store.putRef("artifact-1", id);

            manager.forceSnapshot();

            assertThat(snapshotFileCount(tempDir)).isEqualTo(1);
        }

        @Test
        void forceSnapshot_createsLatestSymlink() {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            manager.forceSnapshot();

            var latestFile = tempDir.resolve("LATEST");
            assertThat(Files.exists(latestFile)).isTrue();
        }

        @Test
        void restoreFromLatest_afterSnapshot_returnsSnapshot() {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            var idA = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY));
            store.putRef("my-ref", idA);

            manager.forceSnapshot();

            var restored = manager.restoreFromLatest();

            assertThat(restored.isPresent()).isTrue();
            restored.onPresent(snap -> {
                assertThat(snap.lifecycles()).hasSize(1);
                assertThat(snap.refs()).containsKey("my-ref");
                assertThat(snap.isValid()).isTrue();
            });
        }

        @Test
        void restoreFromLatest_noSnapshot_returnsNone() {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            var restored = manager.restoreFromLatest();

            assertThat(restored.isEmpty()).isTrue();
        }

        @Test
        void forceSnapshot_prunesOldSnapshots() throws IOException {
            var retentionCount = 2;
            var config = snapshotConfig(tempDir, 1, 600_000, retentionCount, NODE_ID);
            var manager = snapshotManager(store, config);

            for (int i = 0; i < 5; i++) {
                var content = ("prune-content-" + i).getBytes(StandardCharsets.UTF_8);
                var id = blockIdOf(content);
                store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
                manager.forceSnapshot();
            }

            assertThat(snapshotFileCount(tempDir)).isLessThanOrEqualTo(retentionCount);
        }

        @Test
        void maybeSnapshot_epochThresholdExceeded_takesSnapshot() throws IOException {
            var config = snapshotConfig(tempDir, 2, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            var idA = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY));
            var idB = blockIdOf(CONTENT_B);
            store.createLifecycle(BlockLifecycle.blockLifecycle(idB, TierLevel.MEMORY));

            manager.maybeSnapshot();

            assertThat(snapshotFileCount(tempDir)).isEqualTo(1);
        }

        @Test
        void roundTrip_snapshotThenRestore_preservesData() {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            var idA = blockIdOf(CONTENT_A);
            var idB = blockIdOf(CONTENT_B);
            var idC = blockIdOf(CONTENT_C);
            store.createLifecycle(BlockLifecycle.blockLifecycle(idA, TierLevel.MEMORY));
            store.createLifecycle(BlockLifecycle.blockLifecycle(idB, TierLevel.LOCAL_DISK));
            store.createLifecycle(BlockLifecycle.blockLifecycle(idC, TierLevel.MEMORY));
            store.putRef("alpha", idA);
            store.putRef("beta", idB);

            manager.forceSnapshot();

            var freshStore = inMemoryMetadataStore("restored");
            var freshManager = snapshotManager(freshStore, config);

            var restored = freshManager.restoreFromLatest();

            assertThat(restored.isPresent()).isTrue();
            restored.onPresent(snap -> {
                assertThat(snap.lifecycles()).hasSize(3);
                assertThat(snap.refs()).hasSize(2);
                assertThat(snap.refs()).containsKey("alpha");
                assertThat(snap.refs()).containsKey("beta");
                assertThat(snap.isValid()).isTrue();

                freshStore.restoreLifecycles(snap.lifecycles());
                freshStore.restoreRefs(snap.refs());

                assertThat(freshStore.containsBlock(idA)).isTrue();
                assertThat(freshStore.containsBlock(idB)).isTrue();
                assertThat(freshStore.containsBlock(idC)).isTrue();
                assertThat(freshStore.resolveRef("alpha").isPresent()).isTrue();
                assertThat(freshStore.resolveRef("beta").isPresent()).isTrue();
            });
        }

        private long snapshotFileCount(Path dir) throws IOException {
            if (!Files.exists(dir)) {
                return 0;
            }

            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().startsWith("snapshot-"))
                            .filter(p -> p.getFileName().toString().endsWith(".dat"))
                            .count();
            }
        }
    }
}
