package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
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

            assertThat(retrieved.unwrap()).isEqualTo(CONTENT_A);
        }

        @Test
        void putDuplicate_sameContent_deduplicates() {
            var id1 = instance.put(CONTENT_A).await().unwrap();
            var id2 = instance.put(CONTENT_A).await().unwrap();

            assertThat(id1).isEqualTo(id2);
        }

        @Test
        void putMultipleBlocks_waterfallRead_readsFromFastestTier() {
            instance.put(CONTENT_A).await().unwrap();
            instance.put(CONTENT_B).await().unwrap();
            instance.put(CONTENT_C).await().unwrap();

            var idA = BlockId.blockId(CONTENT_A).unwrap();
            var idB = BlockId.blockId(CONTENT_B).unwrap();
            var idC = BlockId.blockId(CONTENT_C).unwrap();

            assertThat(instance.get(idA).await().unwrap().unwrap()).isEqualTo(CONTENT_A);
            assertThat(instance.get(idB).await().unwrap().unwrap()).isEqualTo(CONTENT_B);
            assertThat(instance.get(idC).await().unwrap().unwrap()).isEqualTo(CONTENT_C);
        }

        @Test
        void largeContent_diskOnly_storesAndRetrieves() {
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("large-blocks"), 10 * 1024 * 1024).unwrap();
            var diskOnlyInstance = StorageInstance.storageInstance("disk-only", List.of(diskTier));

            var largeContent = new byte[4096];
            Arrays.fill(largeContent, (byte) 0x42);

            var id = diskOnlyInstance.put(largeContent).await().unwrap();

            assertThat(diskTier.usedBytes()).isGreaterThanOrEqualTo(largeContent.length);
            assertThat(diskOnlyInstance.get(id).await().unwrap().unwrap()).isEqualTo(largeContent);
        }
    }

    @Nested
    class SnapshotIntegrationTests {

        @TempDir
        Path tempDir;

        private InMemoryMetadataStore metadataStore;
        private StorageInstance instance;
        private Path snapshotDir;

        @BeforeEach
        void setUp() {
            metadataStore = inMemoryMetadataStore("snap-integration");
            snapshotDir = tempDir.resolve("snapshots");
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 10 * 1024 * 1024).unwrap();
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
            assertThat(freshStore.resolveRef("ref-alpha").unwrap()).isEqualTo(idA);
            assertThat(freshStore.resolveRef("ref-beta").unwrap()).isEqualTo(idB);
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
        void multipleSnapshots_pruning_keepsOnlyRetentionCount() {
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

        @SuppressWarnings("JBCT-EXC-01")
        private long snapshotFileCount(Path dir) {
            return Result.lift(() -> {
                if (!Files.exists(dir)) {
                    return 0L;
                }
                try (var files = Files.list(dir)) {
                    return files.filter(p -> p.getFileName().toString().startsWith("snapshot-"))
                                .filter(p -> p.getFileName().toString().endsWith(".dat"))
                                .count();
                }
            }).or(0L);
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
            assertThat(waterfallInstance.get(id).await().unwrap().unwrap()).isEqualTo(CONTENT_A);
        }

        @Test
        void blockExistsInBothTiers_readsFromMemory() {
            var memoryTier = MemoryTier.memoryTier(1024 * 1024);
            var diskTier = LocalDiskTier.localDiskTier(tempDir.resolve("both-tiers"), 10 * 1024 * 1024).unwrap();
            var dualInstance = StorageInstance.storageInstance("dual", List.of(memoryTier, diskTier));

            var id = dualInstance.put(CONTENT_A).await().unwrap();

            assertThat(memoryTier.usedBytes()).isGreaterThan(0);
            assertThat(diskTier.usedBytes()).isGreaterThan(0);
            assertThat(dualInstance.get(id).await().unwrap().unwrap()).isEqualTo(CONTENT_A);
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

            assertThat(freshStore.resolveRef("persistent-ref").unwrap()).isEqualTo(id);
        }

        @Test
        void deleteRef_decrementsRefCount() {
            var id = instance.put(CONTENT_A).await().unwrap();
            instance.createRef("temp-ref", id).await().unwrap();

            var refCountBefore = metadataStore.getLifecycle(id).unwrap().refCount();

            instance.deleteRef("temp-ref").await().unwrap();

            var refCountAfter = metadataStore.getLifecycle(id).unwrap().refCount();

            assertThat(refCountAfter).isEqualTo(refCountBefore - 1);
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

            assertThat(result.isFailure()).isTrue();
        }
    }
}
