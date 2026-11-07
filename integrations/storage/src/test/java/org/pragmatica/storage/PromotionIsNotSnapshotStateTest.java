package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.PromotionConfig.promotionConfig;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// Spec v2 §9, C-08 under A2 read promotion: a copy promotion makes into the memory tier is tier presence, and tier
/// presence is not snapshot state. N promoted reads trigger zero snapshots; the control (writes past the threshold)
/// shows the trigger can fire.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PromotionIsNotSnapshotStateTest {
    private static final int THRESHOLD = 10;
    private static final int BLOCKS = 12;
    private static final int READS_PER_BLOCK = 8;

    @TempDir
    Path dir;

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void nPromotedReads_triggerZeroSnapshots_butWritesStillDo() throws IOException {
        var shared = new SharedTestTier();
        var writer = StorageInstance.storageInstance("writer",
                                                     List.of(MemoryTier.memoryTier(1 << 20), shared),
                                                     MetadataStore.inMemoryMetadataStore("writer"));
        var memory = MemoryTier.memoryTier(1 << 20);
        var readerStore = MetadataStore.inMemoryMetadataStore("reader");
        var reader = StorageInstance.storageInstance("reader", List.of(memory, shared), readerStore);
        var ids = IntStream.range(0, BLOCKS)
                           .mapToObj(i -> writer.put(bytes("promoted block " + i)).await().unwrap())
                           .toList();
        var manager = snapshotManager(readerStore, snapshotConfig(dir, THRESHOLD, 600_000, 50, "promotion-node"));

        manager.snapshotNow().unwrap();
        ids.forEach(id -> IntStream.range(0, READS_PER_BLOCK).forEach(_ -> reader.get(id).await().unwrap()));

        var before = readerStore.currentEpoch();
        var promotion = PromotionManager.promotionManager(List.of(memory, shared),
                                                          readerStore,
                                                          promotionConfig(READS_PER_BLOCK - 1, 300_000L, BLOCKS * 2));

        promotion.activate();

        assertThat(promotion.promote()).as("every read block was promoted").isEqualTo(BLOCKS);
        assertThat(ids).allSatisfy(id -> assertThat(memory.exists(id).await().unwrap()).as("copied into memory").isTrue());
        assertThat(ids).allSatisfy(id -> assertThat(readerStore.getLifecycle(id).unwrap().presentIn()).as("presence kept")
                                                                                                    .contains(TierLevel.MEMORY));
        assertThat(readerStore.currentEpoch() - before).as("promotion moved the epoch").isZero();

        manager.maybeSnapshot();

        assertThat(snapshotFiles()).as("the promoted reads took no snapshot").isEqualTo(1);

        IntStream.range(0, THRESHOLD).forEach(i -> reader.put(bytes("control " + i)).await().unwrap());
        manager.maybeSnapshot();

        assertThat(snapshotFiles()).as("control: writes past the threshold take one").isEqualTo(2);
    }

    private long snapshotFiles() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("snapshot-")).count();
        }
    }
}
