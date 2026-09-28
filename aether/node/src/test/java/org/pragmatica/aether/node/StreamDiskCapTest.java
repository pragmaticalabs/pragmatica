// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StreamingConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

/// #1604: the streams disk-tier cap follows the node's disk instead of a hard-coded 4 GiB.
class StreamDiskCapTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path dir;

    @Test
    void derivedCap_isFortyPercentOfUsable_clampedToTheFloorAndTheHeadroom() {
        assertThat(StorageFactory.derivedStreamDiskCap(100 * GIB)).isEqualTo(40 * GIB);
        assertThat(StorageFactory.derivedStreamDiskCap(1000 * GIB)).isEqualTo(400 * GIB);
        assertThat(StorageFactory.derivedStreamDiskCap(2 * GIB)).as("never below the 1 GiB floor").isEqualTo(GIB);
        assertThat(StorageFactory.derivedStreamDiskCap(3 * GIB + GIB / 2))
            .as("40% is 1.4 GiB, below usable - 2 GiB headroom (1.5 GiB)")
            .isEqualTo((long) (3.5 * GIB * 0.4));
    }

    @Test
    void configuredCap_isTakenAsIs() {
        assertThat(StorageFactory.streamDiskMaxBytes(7 * GIB, dir.resolve("segments"))).isEqualTo(7 * GIB);
    }

    /// Unset derives from the filesystem that will hold `segments/` -- before the directory exists.
    @Test
    void unsetCap_isDerivedFromTheFilesystem_ofTheNearestExistingAncestor() throws Exception {
        var usable = Files.getFileStore(dir).getUsableSpace();
        var derived = StorageFactory.streamDiskMaxBytes(StreamingConfig.DERIVE_SEGMENT_DISK_MAX_BYTES,
                                                        dir.resolve("not").resolve("yet").resolve("segments"));

        // Usable space moves while other processes write, so the derived cap is compared within 1%.
        assertThat(derived).isCloseTo(StorageFactory.derivedStreamDiskCap(usable), withinPercentage(1));
    }
}
