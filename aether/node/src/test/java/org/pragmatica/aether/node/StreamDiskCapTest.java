// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.lang.Option;

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

    /// #1616 R1 (the verifier's P5): a restart must not shrink the derived cap. First boot on a 100 GiB disk:
    /// 40 GiB. After a restart holding 30 GiB of in-retention segments the filesystem reports 70 GiB usable --
    /// the tier's own bytes are no longer free -- and the cap must still be 40 GiB, so utilization is 75%, well
    /// under the 95% refusal threshold. Measured on usable space alone it came back as 28 GiB: utilization 107%,
    /// every seal failing and every owner publish refused. The 30 GiB is a sparse file: real bytes on the tier
    /// as far as the cap's reading goes, none on the test machine's disk.
    @Test
    void restartWithInRetentionSegments_keepsTheDerivedCap_andStaysUnderTheRefusalThreshold() throws Exception {
        var segments = Files.createDirectories(dir.resolve("segments"));
        var firstBoot = StorageFactory.streamDiskMaxBytes(StreamingConfig.DERIVE_SEGMENT_DISK_MAX_BYTES,
                                                          segments,
                                                          _ -> Option.some(100 * GIB));

        try (var tierFile = new RandomAccessFile(segments.resolve("sealed-blocks").toFile(), "rw")) {
            tierFile.setLength(30 * GIB);
        }

        var afterRestart = StorageFactory.streamDiskMaxBytes(StreamingConfig.DERIVE_SEGMENT_DISK_MAX_BYTES,
                                                             segments,
                                                             _ -> Option.some(70 * GIB));

        assertThat(firstBoot).isEqualTo(40 * GIB);
        assertThat(afterRestart).as("the cap survives the restart").isEqualTo(firstBoot);
        assertThat((double) (30 * GIB) / afterRestart).as("utilization after the restart")
                                                      .isLessThan(SegmentTierPressure.REFUSE_AT);
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
