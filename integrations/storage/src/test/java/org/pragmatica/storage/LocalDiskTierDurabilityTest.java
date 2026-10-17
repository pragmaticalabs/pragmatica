package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1567: the PRODUCTION local-disk write path -- the two-argument factory, no injected seam -- forces the
/// block before its put resolves. Read from the JDK's own `jdk.FileForce` events, so neither a non-durable
/// writer (`FileOps.writeBytes`) nor a skipped directory force can satisfy it.
class LocalDiskTierDurabilityTest {
    @TempDir
    Path tempDir;

    /// The partial file is forced with its metadata, and AFTER it the directory that the rename then
    /// installs the block into -- so the block and the entry naming it are both on the device.
    @Test
    void put_forcesThePartialFileThenTheBlockDirectory_beforeResolving() {
        var tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 1 << 20).unwrap();
        var content = "durable block".getBytes(StandardCharsets.UTF_8);
        var id = BlockId.blockId(content).unwrap();
        var hex = id.hexString();
        var blockDir = tempDir.resolve("blocks").resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).toAbsolutePath();

        var forced = FileForceRecording.forcedFilesDuring(() -> tier.put(id, content)
                                                                    .await()
                                                                    .onFailure(cause -> fail(cause.message())));
        var partialForce = forced.stream()
                                 .filter(force -> force.path().getFileName().toString().endsWith(".partial"))
                                 .findFirst();

        assertThat(partialForce).as("the partial file was forced").isPresent();
        assertThat(partialForce.get().metaData()).as("with its metadata: a new file's size lives in the inode").isTrue();
        assertThat(forced.stream().map(FileForceRecording.ForcedFile::path).toList())
            .as("the block directory is forced after the partial file")
            .containsSubsequence(partialForce.get().path(), blockDir);
    }

    /// The shard directories a put creates are made durable in their parents: without that, a durable block
    /// file can vanish with a directory whose own entry never reached the device.
    @Test
    void put_forcesTheParentsOfTheShardDirectoriesItCreates() {
        var base = tempDir.resolve("blocks");
        var tier = LocalDiskTier.localDiskTier(base, 1 << 20).unwrap();
        var content = "first block in its shard".getBytes(StandardCharsets.UTF_8);
        var id = BlockId.blockId(content).unwrap();
        var shard = base.resolve(id.hexString().substring(0, 2)).toAbsolutePath();

        var forced = FileForceRecording.forcedFilesDuring(() -> tier.put(id, content)
                                                                    .await()
                                                                    .onFailure(cause -> fail(cause.message())));

        assertThat(forced.stream().map(FileForceRecording.ForcedFile::path).toList())
            .contains(base.toAbsolutePath(), shard);
    }
}
