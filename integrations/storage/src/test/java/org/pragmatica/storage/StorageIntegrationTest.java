package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;
import static org.pragmatica.storage.StorageReadinessGate.storageReadinessGate;

class StorageIntegrationTest {

    private static final byte[] CONTENT_A = "integration-alpha-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "integration-bravo-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_C = "integration-charlie-content".getBytes(StandardCharsets.UTF_8);
    private static final String NODE_ID = "integration-node-1";

    @Nested
    class EndToEndTests {

        @TempDir
        Path tempDir;

        private StorageInstance instance;

        @BeforeEach
        void setUp() {
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 10 * 1024 * 1024).unwrap();
            instance = StorageInstance.storageInstance("e2e-test", List.of(memoryTier, diskTier));
        }

        @Test
        void putThenGet_withMemoryAndDiskTiers_roundTrips() {
            var id = instance.put(CONTENT_A).await().unwrap();

            var retrieved = instance.get(id).await().unwrap();

            assertThat(retrieved.isPresent()).isTrue();
            retrieved.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
        }

        @Test
        void putDuplicate_sameContent_deduplicates() {
            var id1 = instance.put(CONTENT_A).await().unwrap();
            var id2 = instance.put(CONTENT_A).await().unwrap();

            assertThat(id1).isEqualTo(id2);
        }

        @Test
        void putMultipleBlocks_waterfallRead_readsFromFastestTier() {
            var idA = instance.put(CONTENT_A).await().unwrap();
            var idB = instance.put(CONTENT_B).await().unwrap();
            var idC = instance.put(CONTENT_C).await().unwrap();

            var dataA = instance.get(idA).await().unwrap();
            var dataB = instance.get(idB).await().unwrap();
            var dataC = instance.get(idC).await().unwrap();

            dataA.onPresent(d -> assertThat(d).isEqualTo(CONTENT_A));
            dataB.onPresent(d -> assertThat(d).isEqualTo(CONTENT_B));
            dataC.onPresent(d -> assertThat(d).isEqualTo(CONTENT_C));
        }

        @Test
        void largeContent_diskOnly_storesAndRetrieves() {
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("large-blocks"), 10 * 1024 * 1024).unwrap();
            var diskOnlyInstance = StorageInstance.storageInstance("disk-only", List.of(diskTier));

            var largeContent = new byte[4096];
            Arrays.fill(largeContent, (byte) 0x42);

            var id = diskOnlyInstance.put(largeContent).await().unwrap();

            assertThat(diskTier.usedBytes()).isGreaterThanOrEqualTo(largeContent.length);

            var retrieved = diskOnlyInstance.get(id).await().unwrap();

            assertThat(retrieved.isPresent()).isTrue();
            retrieved.onPresent(data -> assertThat(data).isEqualTo(largeContent));
        }
    }

    @Nested
    class SnapshotIntegrationTests {

        @TempDir
        Path tempDir;

        private InMemoryMetadataStore metadataStore;
        private StorageInstance instance;
        private Path snapshotDir;
        private Path blocksDir;

        @BeforeEach
        void setUp() {
            metadataStore = inMemoryMetadataStore("snap-integration");
            blocksDir = tempDir.resolve("blocks");
            snapshotDir = tempDir.resolve("snapshots");
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(blocksDir, 10 * 1024 * 1024).unwrap();
            instance = StorageInstance.storageInstance("snap-test", List.of(memoryTier, diskTier), metadataStore);
        }

        @Test
        void snapshotAndRestore_fullCycle_preservesAllData() {
            var idA = instance.put(CONTENT_A).await().unwrap();
            var idB = instance.put(CONTENT_B).await().unwrap();
            instance.createRef("ref-alpha", idA).await().unwrap();
            instance.createRef("ref-beta", idB).await().unwrap();

            var config = snapshotConfig(snapshotDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(metadataStore, config);
            manager.forceSnapshot();

            var freshStore = inMemoryMetadataStore("restored");
            var freshManager = snapshotManager(freshStore, config);
            var snapshot = freshManager.restoreFromLatest().unwrap();

            freshStore.restoreLifecycles(snapshot.lifecycles());
            freshStore.restoreRefs(snapshot.refs());

            assertThat(snapshot.isValid()).isTrue();
            assertThat(freshStore.containsBlock(idA)).isTrue();
            assertThat(freshStore.containsBlock(idB)).isTrue();
            assertThat(freshStore.resolveRef("ref-alpha").isPresent()).isTrue();
            assertThat(freshStore.resolveRef("ref-beta").isPresent()).isTrue();
        }

        @Test
        void snapshotWithReadinessGate_startupSequence_correctOrder() {
            var gate = storageReadinessGate();

            assertThat(gate.state()).isEqualTo(ReadinessState.LOADING_SNAPSHOT);
            assertThat(gate.isReadReady()).isFalse();
            assertThat(gate.isWriteReady()).isFalse();

            gate.snapshotLoaded();

            assertThat(gate.state()).isEqualTo(ReadinessState.SNAPSHOT_LOADED);
            assertThat(gate.isReadReady()).isTrue();
            assertThat(gate.isWriteReady()).isFalse();

            gate.consensusSynced();

            assertThat(gate.state()).isEqualTo(ReadinessState.READY);
            assertThat(gate.isReadReady()).isTrue();
            assertThat(gate.isWriteReady()).isTrue();
        }

        @Test
        void multipleSnapshots_pruning_keepsOnlyRetentionCount() throws IOException {
            var retentionCount = 2;
            var config = snapshotConfig(snapshotDir, 1, 600_000, retentionCount, NODE_ID);
            var manager = snapshotManager(metadataStore, config);

            for (int i = 0; i < 5; i++) {
                var content = ("prune-integration-" + i).getBytes(StandardCharsets.UTF_8);
                instance.put(content).await().unwrap();
                manager.forceSnapshot();
            }

            assertThat(snapshotFileCount(snapshotDir)).isLessThanOrEqualTo(retentionCount);
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

    @Nested
    class TierWaterfallTests {

        @TempDir
        Path tempDir;

        @Test
        void memoryTierFull_writesToDisk_readsFromDisk() {
            var smallMemory = MemoryTier.memoryTier(32);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("waterfall-blocks"), 10 * 1024 * 1024).unwrap();
            var waterfallInstance = StorageInstance.storageInstance("waterfall", List.of(smallMemory, diskTier));

            var id = waterfallInstance.put(CONTENT_A).await().unwrap();

            assertThat(diskTier.usedBytes()).isGreaterThan(0);

            var retrieved = waterfallInstance.get(id).await().unwrap();

            assertThat(retrieved.isPresent()).isTrue();
            retrieved.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
        }

        @Test
        void blockExistsInBothTiers_readsFromMemory() {
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("both-tiers"), 10 * 1024 * 1024).unwrap();
            var dualInstance = StorageInstance.storageInstance("dual", List.of(memoryTier, diskTier));

            var id = dualInstance.put(CONTENT_A).await().unwrap();

            assertThat(memoryTier.usedBytes()).isGreaterThan(0);
            assertThat(diskTier.usedBytes()).isGreaterThan(0);

            var retrieved = dualInstance.get(id).await().unwrap();

            assertThat(retrieved.isPresent()).isTrue();
            retrieved.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
        }
    }

    @Nested
    class RefManagementTests {

        @TempDir
        Path tempDir;

        private InMemoryMetadataStore metadataStore;
        private StorageInstance instance;

        @BeforeEach
        void setUp() {
            metadataStore = inMemoryMetadataStore("ref-integration");
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("ref-blocks"), 10 * 1024 * 1024).unwrap();
            instance = StorageInstance.storageInstance("ref-test", List.of(memoryTier, diskTier), metadataStore);
        }

        @Test
        void createAndResolveRef_acrossSnapshotRestore_persists() {
            var id = instance.put(CONTENT_A).await().unwrap();
            instance.createRef("persistent-ref", id).await().unwrap();

            var snapshotDir = tempDir.resolve("ref-snapshots");
            var config = snapshotConfig(snapshotDir, 100, 600_000, 5, NODE_ID);
            var manager = snapshotManager(metadataStore, config);
            manager.forceSnapshot();

            var freshStore = inMemoryMetadataStore("ref-restored");
            var freshManager = snapshotManager(freshStore, config);
            var snapshot = freshManager.restoreFromLatest().unwrap();

            freshStore.restoreLifecycles(snapshot.lifecycles());
            freshStore.restoreRefs(snapshot.refs());

            var resolvedId = freshStore.resolveRef("persistent-ref");

            assertThat(resolvedId.isPresent()).isTrue();
            resolvedId.onPresent(resolved -> assertThat(resolved).isEqualTo(id));
        }

        @Test
        void deleteRef_decrementsRefCount() {
            var id = instance.put(CONTENT_A).await().unwrap();
            instance.createRef("temp-ref", id).await().unwrap();

            var lifecycleBefore = metadataStore.getLifecycle(id).unwrap();
            var refCountBefore = lifecycleBefore.refCount();

            instance.deleteRef("temp-ref").await().unwrap();

            var lifecycleAfter = metadataStore.getLifecycle(id).unwrap();

            assertThat(lifecycleAfter.refCount()).isEqualTo(refCountBefore - 1);
        }

        @Test
        void multipleRefsToSameBlock_refCountTracksCorrectly() {
            var id = instance.put(CONTENT_A).await().unwrap();

            var initialRefCount = metadataStore.getLifecycle(id).unwrap().refCount();

            instance.createRef("ref-1", id).await().unwrap();
            instance.createRef("ref-2", id).await().unwrap();
            instance.createRef("ref-3", id).await().unwrap();

            var finalRefCount = metadataStore.getLifecycle(id).unwrap().refCount();

            assertThat(finalRefCount).isEqualTo(initialRefCount + 3);
        }
    }

    @Nested
    class ErrorHandlingTests {

        @TempDir
        Path tempDir;

        @Test
        void getNonExistentBlock_returnsNone() {
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("error-blocks"), 10 * 1024 * 1024).unwrap();
            var errorInstance = StorageInstance.storageInstance("error-test", List.of(memoryTier, diskTier));

            var randomId = BlockId.blockId("nonexistent-content".getBytes(StandardCharsets.UTF_8)).unwrap();

            var result = errorInstance.get(randomId).await().unwrap();

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void memoryTierFull_returnsError() {
            var tinyMemory = MemoryTier.memoryTier(8);

            var largeContent = new byte[64];
            Arrays.fill(largeContent, (byte) 0xAB);
            var id = BlockId.blockId(largeContent).unwrap();

            var result = tinyMemory.put(id, largeContent).await();

            result.onSuccess(_ -> fail("Expected TierFull error"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.TierFull.class));
        }
    }
}
