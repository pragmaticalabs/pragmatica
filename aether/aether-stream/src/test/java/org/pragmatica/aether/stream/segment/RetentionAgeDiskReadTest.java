// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.io.FileError;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1639 B1 (v1602's P13): age learning against the REAL disk tier, which is where a transient read failure comes
/// from in production. An I/O error reading a block file arrives as `FileError.ReadFailed` -- not the
/// `StorageError.ReadError` a fake tier can inject -- and must be retried; a MISSING block file arrives as "absent"
/// and must stay remembered.
class RetentionAgeDiskReadTest {
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final String REF = "streams/orders/0/0-0";

    @TempDir
    Path dir;

    private StorageInstance storage;
    private SegmentIndex index;
    private RetentionEnforcer enforcer;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("disk-age",
                                                  List.of(LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20).unwrap()),
                                                  MetadataStore.inMemoryMetadataStore("disk-age"));
        index = new SegmentIndex();
        enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                       index,
                                                       WINDOW_MS,
                                                       RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                       SegmentReader.segmentReader(storage, index));
    }

    /// The block file is unreadable during pass 1 (an I/O error, here a permission flake) and readable again
    /// afterwards: its age is learned on a later pass. Red under "FileError.ReadFailed deterministic".
    @Test
    void aDiskReadFailure_isRetried_andTheAgeIsLearnedOnALaterPass() throws Exception {
        var id = seal();
        var file = blockFile(id);

        index.rebuildFromRefs(Map.of(REF, id));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        var failed = storage.get(id).await();

        assumeTrue(failed.isFailure(), "a mode-000 file is readable here (running as root?): the probe cannot fail");
        failed.onFailure(cause -> assertThat(cause).as("what the disk tier really reports").isInstanceOf(FileError.ReadFailed.class));

        enforcer.enforceNow().await();
        assertThat(maxTimestamp()).as("unknown after the failed read").isEqualTo(0L);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        enforcer.enforceNow().await();

        assertThat(maxTimestamp()).as("learned once the file is readable again").isPositive();
    }

    /// A block whose file does not exist is "absent", not an I/O error: remembered, so it is not read again even
    /// once a file appears under its id. Keeps the deterministic not-found path deterministic.
    @Test
    void anIntactBlock_isLearnedOnTheFirstPass() {
        index.rebuildFromRefs(Map.of(REF, seal()));
        enforcer.enforceNow().await();

        assertThat(maxTimestamp()).as("control: this enforcer learns ages from the disk tier").isPositive();
    }

    @Test
    void aMissingBlockFile_isRemembered_notRetried() throws Exception {
        var id = seal();
        var file = blockFile(id);
        var content = Files.readAllBytes(file);

        index.rebuildFromRefs(Map.of(REF, id));
        Files.delete(file);

        enforcer.enforceNow().await();
        Files.write(file, content);
        assertThat(storage.get(id).await().isSuccess()).as("the block reads again").isTrue();
        enforcer.enforceNow().await();

        assertThat(maxTimestamp()).as("remembered as not found, not read again").isEqualTo(0L);
    }

    private BlockId seal() {
        var eventTime = System.currentTimeMillis() - WINDOW_MS / 2;
        var bytes = ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(0).putLong(eventTime).putInt(1).put((byte) 1).array();

        storageSegmentSink(storage, new SegmentIndex()).seal(sealedSegment("orders", 0, 0, 0, 1, eventTime, eventTime, bytes))
                                                        .await()
                                                        .unwrap();

        return storage.resolveRef(REF).unwrap();
    }

    private Path blockFile(BlockId id) {
        var hex = id.hexString();
        var file = dir.resolve("blocks").resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);

        assertThat(Files.isRegularFile(file)).as("located the block file").isTrue();

        return file;
    }

    private long maxTimestamp() {
        return index.listSegments("orders", 0).getFirst().maxTimestamp();
    }
}
