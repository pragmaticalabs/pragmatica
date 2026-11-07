package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// G2 (spec v2 §9): re-seeding a block whose record survived but whose shared copy did not (an in-memory DHT after a
/// whole-cluster restart) must land the block in the shared tier. A put of an already-recorded block deduplicates and
/// writes nothing, so a re-seed cannot go through it.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StorageInstanceEnsureHeldTest {
    private static final byte[] CONTENT = "re-seed me".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void reseedOfARecordedButAbsentBlock_landsInTheSharedTier() {
        var disk = LocalDiskTier.localDiskTier(dir, 1 << 20).flatMap(LocalDiskTier::open).unwrap();
        var shared = RemoteTier.remoteTier(new RemoteTierTest.InMemoryS3Client(), "g2", 1 << 20);
        var node = StorageInstance.storageInstance("g2", List.of(disk, shared));
        var id = node.put(CONTENT).await().unwrap();

        shared.delete(id).await().unwrap();

        assertThat(shared.exists(id).await().unwrap()).as("precondition: the shared copy is gone").isFalse();

        node.put(CONTENT).await().unwrap();

        assertThat(shared.exists(id).await().unwrap()).as("the re-seed reached the shared tier").isTrue();
    }
}
