package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// #1473: an unmounted mount point presents an empty, listable snapshot directory, which the manager
/// used to read as a first boot. The discriminator is a sentinel OUTSIDE the volume holding the id of
/// the volume last written; a first boot has no sentinel, so the two states are no longer the same
/// observation. A degraded disk tier passes no sentinel at all and keeps booting on empty metadata.
class SnapshotVolumeSentinelTest {
    private static final String VOLUME_MARKER = ".volume-id";

    @TempDir
    Path tempDir;

    private Path volume;
    private Path sentinel;

    @BeforeEach
    void setUp() throws IOException {
        volume = Files.createDirectories(tempDir.resolve("volume").resolve("snapshots"));
        sentinel = tempDir.resolve("boot-fs").resolve("volume.id");
    }

    private SnapshotConfig armedConfig() {
        return snapshotConfig(volume, "node-1").withVolumeSentinel(Option.some(sentinel));
    }

    private SnapshotManager managerOver(SnapshotConfig config) {
        return snapshotManager(inMemoryMetadataStore("sentinel-test"), config);
    }

    private void writeOneSnapshot() {
        managerOver(armedConfig()).snapshotNow()
                                  .onFailure(cause -> fail("snapshot write must succeed: " + cause.message()));
    }

    private void unmount() throws IOException {
        try (Stream<Path> files = Files.list(volume)) {
            for (var file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void restoreFromLatest_refuses_whenSentinelExistsAndMountPointIsEmpty() throws IOException {
        writeOneSnapshot();
        unmount();

        managerOver(armedConfig()).restoreFromLatest()
                                  .onSuccess(_ -> fail("an empty directory under a sentinel is an unmounted volume, not a first boot"))
                                  .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.VolumeNotPreviouslyWritten.class));
    }

    @Test
    void restoreFromLatest_refuses_whenSentinelExistsAndSnapshotDirectoryIsAbsent() throws IOException {
        writeOneSnapshot();
        unmount();
        Files.delete(volume);

        managerOver(armedConfig()).restoreFromLatest()
                                  .onSuccess(_ -> fail("an absent directory under a sentinel is an unmounted volume"))
                                  .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.VolumeNotPreviouslyWritten.class));
    }

    @Test
    void restoreFromLatest_refusalNamesTheSnapshotPath() throws IOException {
        writeOneSnapshot();
        unmount();

        managerOver(armedConfig()).restoreFromLatest()
                                  .onFailure(cause -> assertThat(cause.message()).contains(volume.toString())
                                                                                 .contains(sentinel.toString()));
    }

    @Test
    void restoreFromLatest_refuses_whenAnotherVolumeIsMountedInPlace() throws IOException {
        writeOneSnapshot();
        unmount();
        Files.writeString(volume.resolve(VOLUME_MARKER), "some-other-volume");

        managerOver(armedConfig()).restoreFromLatest()
                                  .onSuccess(_ -> fail("a directory carrying another volume's id is not the volume written before"))
                                  .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.VolumeNotPreviouslyWritten.class));
    }

    @Test
    void restoreFromLatest_boots_onAGenuineFirstBoot() {
        managerOver(armedConfig()).restoreFromLatest()
                                  .onFailure(cause -> fail("no sentinel means nothing was ever written: " + cause.message()))
                                  .onSuccess(restored -> assertThat(restored.isPresent()).isFalse());
    }

    @Test
    void restoreFromLatest_boots_whenTheRightVolumeIsRemounted() {
        writeOneSnapshot();

        managerOver(armedConfig()).restoreFromLatest()
                                  .onFailure(cause -> fail("the volume written before must restore: " + cause.message()))
                                  .onSuccess(restored -> assertThat(restored.isPresent()).isTrue());
    }

    @Test
    void restoreFromLatest_boots_whenTheDiskTierIsDegradedAndPassesNoSentinel() throws IOException {
        writeOneSnapshot();
        unmount();

        managerOver(snapshotConfig(volume, "node-1")).restoreFromLatest()
                                                     .onFailure(cause -> fail("a degraded tier has no volume to lose: " + cause.message()))
                                                     .onSuccess(restored -> assertThat(restored.isPresent()).isFalse());
    }

    @Test
    void snapshotNow_stampsTheVolumeMarkerAndTheSentinelWithTheSameId() throws IOException {
        writeOneSnapshot();

        assertThat(Files.readString(volume.resolve(VOLUME_MARKER))).isNotBlank()
                                                                   .isEqualTo(Files.readString(sentinel));
    }

    @Test
    void snapshotNow_keepsTheVolumeId_acrossLaterSnapshots() throws IOException {
        writeOneSnapshot();

        var first = Files.readString(sentinel);

        writeOneSnapshot();

        assertThat(Files.readString(sentinel)).isEqualTo(first);
        assertThat(Files.readString(volume.resolve(VOLUME_MARKER))).isEqualTo(first);
    }

    @Test
    void snapshotNow_adoptsTheVolumeId_whenAVolumeWrittenBeforeTheSentinelExisted() throws IOException {
        Files.writeString(volume.resolve(VOLUME_MARKER), "volume-from-before-the-sentinel");

        writeOneSnapshot();

        assertThat(Files.readString(sentinel)).isEqualTo("volume-from-before-the-sentinel");
    }

    @Test
    void snapshotNow_neverRewritesAnExistingSentinel() throws IOException {
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "the-volume-the-node-wrote-before");
        Files.writeString(volume.resolve(VOLUME_MARKER), "a-different-volume");

        writeOneSnapshot();

        assertThat(Files.readString(sentinel)).as("a disagreement is for restore to report, not for a write to hide")
                                              .isEqualTo("the-volume-the-node-wrote-before");
    }

    @Test
    void snapshotNow_writesNoSentinel_whenTheConfigCarriesNone() {
        managerOver(snapshotConfig(volume, "node-1")).snapshotNow()
                                                     .onFailure(cause -> fail("snapshot write must succeed: " + cause.message()));

        assertThat(Files.exists(sentinel)).isFalse();
        assertThat(Files.exists(volume.resolve(VOLUME_MARKER))).isFalse();
    }
}
