package org.pragmatica.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The A2b tier contract on [LocalDiskTier]: write time is the block file's modification time.
class LocalDiskTierScanStatTouchTest extends TierScanStatTouchContract {
    @TempDir
    Path dir;

    @Override
    protected StorageTier newTier() {
        return LocalDiskTier.localDiskTier(dir, 1 << 20).flatMap(LocalDiskTier::open).unwrap();
    }

    @Override
    protected void age(BlockId id) {
        setModified(blockPath(id), System.currentTimeMillis() - 60_000L);
    }

    private Path blockPath(BlockId id) {
        var hex = id.hexString();

        return dir.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);
    }

    private static void setModified(Path path, long millis) {
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(millis));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void stat_isTheFilesSizeAndModificationTime() throws IOException {
        var id = store(bytes("on disk"));

        setModified(blockPath(id), 1_234_000L);

        var stat = tier.stat(id).await().unwrap().unwrap();

        assertThat(stat.size()).isEqualTo(Files.size(blockPath(id)));
        assertThat(stat.writtenAtMillis()).isEqualTo(1_234_000L);
    }

    /// A partial a crashed write left, a stray file in a shard directory, a block-named file in the wrong directory and the
    /// mount claim are not blocks.
    @Test
    void scan_skipsPartialsStraysMisplacedFilesAndTheClaim() throws IOException {
        var id = store(bytes("the real block"));
        var hex = id.hexString();
        var shardDirectory = blockPath(id).getParent();
        var other = BlockId.blockId(bytes("misplaced")).unwrap().hexString();

        assertThat(other.substring(0, 4)).as("precondition: belongs to another directory").isNotEqualTo(hex.substring(0, 4));

        Files.write(shardDirectory.resolve(hex + ".7.partial"), bytes("partial"));
        Files.write(shardDirectory.resolve("stray.txt"), bytes("stray"));
        Files.write(shardDirectory.resolve(other), bytes("misplaced"));
        Files.write(dir.resolve(MountClaim.CLAIM_FILE), bytes("claim"));

        assertThat(scanAll(KeyShard.all(), 10)).containsExactly(id);
    }

    @Test
    void touch_keepsTheFileItsContentAndItsSize() throws IOException {
        var content = bytes("unchanged by a touch");
        var id = store(content);

        setModified(blockPath(id), 1_000L);
        tier.touch(id).await().unwrap();

        assertThat(Files.readAllBytes(blockPath(id))).isEqualTo(content);
        assertThat(Files.getLastModifiedTime(blockPath(id)).toMillis()).isGreaterThan(1_000L);
        assertThat(tier.usedBytes()).isEqualTo(content.length);
        assertThat(tier.stat(id).await().unwrap().map(BlockStat::size)).isEqualTo(Option.some((long) content.length));
    }
}
