package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// #1473: a sentinel location that cannot be written (here: its parent is a regular file) leaves the snapshot
/// standing and says so once, not on every tick. The guard is the only thing lost, until a later write records it.
class SnapshotVolumeStampFailureTest {
    @TempDir
    Path tempDir;

    private LogCapture capture;

    @BeforeEach
    void captureWarns() {
        capture = LogCapture.capture(DefaultSnapshotManager.class, Level.ALL, true);
    }

    @AfterEach
    void releaseCapture() {
        capture.close();
    }

    @Test
    void snapshotNow_unwritableSentinelLocation_snapshotStandsAndWarnsExactlyOnce() throws IOException {
        var blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "no directory can be created beneath a regular file");
        var volume = tempDir.resolve("volume");
        var manager = snapshotManager(inMemoryMetadataStore("stamp-failure"),
                                      snapshotConfig(volume, "node-1").withVolumeSentinel(Option.some(blocker.resolve("sentinels").resolve("v.id"))));

        var first = manager.snapshotNow();
        var second = manager.snapshotNow();

        assertThat(first.isSuccess()).as("the snapshot stands").isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(Files.exists(volume.resolve("LATEST"))).isTrue();
        var warns = capture.messages(Level.WARN);

        assertThat(warns.stream().filter(w -> w.contains("Volume identity could not be recorded")).toList())
            .as("one WARN, not one per snapshot")
            .hasSize(1);
        assertThat(warns.getFirst()).contains(volume.toString());
    }
}
