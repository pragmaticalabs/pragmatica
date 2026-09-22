// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1169: the rename that publishes a block over its previous copy must be ONE `rename(2)`
/// (`FileOps.moveAtomic`), never `Files.move(REPLACE_EXISTING)` alone -- the JDK's non-atomic move
/// unlinks the target first, so a reader (or a crash) in that window finds nothing at the block
/// path, contradicting the tier's own "previous copy never touched" contract and the #910 ruling
/// (`know: 812d4de2c` records the correction).
class LocalDiskTierAtomicReplaceTest {
    private static final byte[] OLD = "previous-copy-1169".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = "replacing-copy-1169".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    /// The reviewer's directory-partial probe, run at the real call site: the injected writer
    /// leaves a DIRECTORY at the partial path. `rename(dir, file)` is ENOTDIR under `ATOMIC_MOVE`
    /// and the target survives; the non-atomic path unlinks the target first and then succeeds,
    /// leaving a directory where the block was. So a surviving regular file with the previous
    /// bytes is only possible if the target was never unlinked.
    @Test
    void directoryPartial_renameRefused_previousCopySurvivesUntouched() {
        var dir = tempDir.resolve("blocks");
        var tier = LocalDiskTier.localDiskTier(dir,
                                               1024 * 1024,
                                               timeSpan(30).seconds(),
                                               none(),
                                               some(LocalDiskTierAtomicReplaceTest::directoryAsPartial))
                                .unwrap();
        var id = BlockId.blockId(OLD).unwrap();
        var path = blockPath(dir, id);

        FileOps.createDirectories(path.getParent()).unwrap();
        FileOps.writeBytes(path, OLD).unwrap();
        tier.put(id, NEW)
            .await()
            .onSuccess(_ -> fail("a partial that cannot be renamed over the block must fail the put"));
        assertThat(Files.isRegularFile(path)).as("the previous copy at %s was unlinked before the rename", path)
                  .isTrue();
        assertThat(FileOps.readBytes(path).unwrap()).containsExactly(OLD);
        assertThat(tier.get(id).await().unwrap().unwrap()).as("the tier still serves the previous copy")
                  .containsExactly(OLD);
        assertThat(FileOps.list(path.getParent()).unwrap()).as("the directory partial was discarded")
                  .containsExactly(path);
    }

    /// A reader racing the replace sees the previous copy or the new one, never absence. Under
    /// the non-atomic move the unlink-then-rename window is wide: the s25-1169 probe measured
    /// 203,018 absent reads out of 343,385 across 20,000 replaces on macOS APFS, and 0 of 183,040
    /// under `ATOMIC_MOVE`. The count is asserted, not the ratio: zero is the contract.
    ///
    /// ABSENCE is the only failure this can observe, so it is the only one counted. A TORN read is
    /// unreachable in both arms by construction -- the content is written to a sibling `.partial`
    /// and published by rename, so a reader opens either the old inode or the new one and never a
    /// half-written file. A `torn` counter here would be a vacuous zero that later reads as
    /// evidence; the `reads > 0` check below is the aliveness guard that does the real work,
    /// stopping a zero from meaning "the reader thread never ran".
    @Test
    void readerRacingReplace_seesOldOrNew_neverAbsence() throws InterruptedException {
        var dir = tempDir.resolve("race");
        var tier = LocalDiskTier.localDiskTier(dir, 1024 * 1024).unwrap();
        var id = BlockId.blockId(OLD).unwrap();
        var path = blockPath(dir, id);
        var stop = new AtomicBoolean();
        var reads = new AtomicLong();
        var absent = new AtomicLong();

        tier.put(id, OLD).await().unwrap();
        var reader = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                reads.incrementAndGet();
                FileOps.readBytes(path)
                       .onFailure(_ -> absent.incrementAndGet());
            }
        });

        try {
            for (int i = 0; i < 2_000; i++) {
                tier.put(id,
                         i % 2 == 0
                         ? NEW
                         : OLD).await().unwrap();
            }
        } finally {
            stop.set(true);
            reader.join();
        }

        assertThat(reads.get()).as("the reader must have observed the replace loop").isGreaterThan(0);
        assertThat(absent.get()).as("reads that found NO file at the block path (of %d reads)", reads.get()).isZero();
    }

    private static Result<Unit> directoryAsPartial(Path partial, byte[] ignored) {
        return FileOps.createDirectories(partial).map(_ -> unit());
    }

    private static Path blockPath(Path base, BlockId id) {
        var hex = id.hexString();

        return base.resolve(hex.substring(0, 2))
                   .resolve(hex.substring(2, 4))
                   .resolve(hex);
    }
}
