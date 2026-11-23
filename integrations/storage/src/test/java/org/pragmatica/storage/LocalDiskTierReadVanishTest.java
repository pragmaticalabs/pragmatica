package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// [LocalDiskTier#get] when the block file is gone by the time it is read (#3594). A delete lands at any moment of a read; a
/// block that is not there is absent, never a read failure, as `stat` and `touch` already answer. The reclaim of a displaced
/// checkpoint block deletes it while a reader that resolved it a moment earlier is reading it; before the fix the reader saw
/// `Failed to read <path>` and the caller's `Result.getOrThrow` turned that into an error (flake #3594).
class LocalDiskTierReadVanishTest {
    private static final int ROUNDS = 2_000;

    @TempDir
    Path dir;

    /// The window between "the file is there" and "the read" cannot be paused from outside, so the block path holds a
    /// directory: it exists, a read of it fails, and the path is no regular file, which is the state a vanished file leaves
    /// when the read finds it gone. The read must answer absent.
    @Test
    void aReadWhoseFileIsNoRegularFile_isAbsent_notAReadFailure() throws IOException {
        var id = BlockId.blockId("block".getBytes()).unwrap();
        var tier = LocalDiskTier.localDiskTier(dir, 1 << 20).unwrap();
        var hex = id.hexString();

        Files.createDirectories(dir.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex));

        var read = tier.get(id).await();

        assertThat(read.isSuccess()).as("the read of a block that is not a file is not a failure: %s", read).isTrue();
        read.onSuccess(content -> assertThat(content.isPresent()).as("and answers absent").isFalse());
    }

    /// The race itself: one thread puts and deletes a block over and over while another reads it. Every read answers present
    /// or absent. Before the fix a read that found the file in the check and gone in the read failed.
    @Test
    void readsRacingDeletes_answerPresentOrAbsent_neverFail() {
        var id = BlockId.blockId("raced block".getBytes()).unwrap();
        var tier = LocalDiskTier.localDiskTier(dir, 1 << 20).unwrap();
        var content = "raced block".getBytes();
        var done = new AtomicBoolean();
        var failures = new AtomicInteger();
        var churn = CompletableFuture.runAsync(() -> churn(tier, id, content, done));

        IntStream.range(0, ROUNDS * 10).forEach(_ -> countFailure(tier, id, failures));
        done.set(true);
        churn.join();

        assertThat(failures.get()).as("reads of a block deleted under them that failed").isZero();
    }

    /// Control: the fix tells a vanished file from a broken read by the path afterwards. A regular file that is there and cannot
    /// be read is still a failure, and a block that is there is still read.
    @Test
    void aRegularFileThatCannotBeRead_isStillAReadFailure_andAReadableOneIsRead() throws IOException {
        var id = BlockId.blockId("held".getBytes()).unwrap();
        var tier = LocalDiskTier.localDiskTier(dir, 1 << 20).unwrap();
        var hex = id.hexString();
        var file = dir.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);

        tier.put(id, "held".getBytes()).await().unwrap();
        assertThat(tier.get(id).await().unwrap().or(() -> new byte[0])).isEqualTo("held".getBytes());

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(file), "the user running the test reads any file (root)");

            assertThat(tier.get(id).await().isFailure()).isTrue();
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static void churn(LocalDiskTier tier, BlockId id, byte[] content, AtomicBoolean done) {
        while (!done.get()) {
            tier.put(id, content).await();
            tier.delete(id).await();
        }
    }

    private static void countFailure(LocalDiskTier tier, BlockId id, AtomicInteger failures) {
        if (tier.get(id).await().isFailure()) {
            failures.incrementAndGet();
        }
    }
}
