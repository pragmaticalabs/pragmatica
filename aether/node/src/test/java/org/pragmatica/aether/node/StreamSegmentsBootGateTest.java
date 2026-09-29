// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.io.FileOps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1567 F12/N2: a node whose `streams` block tier cannot be created must not boot silently onto memory + DHT,
/// where nothing can be sealed and every WAL grows. The degrade survives only behind the explicit
/// non-durable opt-in, as for the WAL directory (#634 item 2).
class StreamSegmentsBootGateTest {
    @TempDir
    Path dir;

    @Test
    void creatableSegmentsDir_boots_regardlessOfOptIn() {
        var segments = dir.resolve("segments");
        var probe = FileOps.createDirectories(segments).mapToUnit();

        assertThat(AetherNode.decideSegmentsAvailability(segments, probe, false).isSuccess()).isTrue();
        assertThat(AetherNode.decideSegmentsAvailability(segments, probe, true).isSuccess()).isTrue();
    }

    /// The probe is the real one against a path that cannot be created (its parent is a file).
    @Test
    void uncreatableSegmentsDir_withoutOptIn_refusesBoot_typed_namingTheEscapeHatch() throws IOException {
        var blocker = Files.writeString(dir.resolve("not-a-directory"), "blocks the segments dir");
        var segments = blocker.resolve("segments");
        var outcome = AetherNode.decideSegmentsAvailability(segments,
                                                            FileOps.createDirectories(segments).mapToUnit(),
                                                            false);

        outcome.onSuccess(_ -> fail("an uncreatable segments dir must refuse boot"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StorageFactory.StreamDiskTierUnavailable.class))
               .onFailure(cause -> assertThat(cause.message()).contains(segments.toString())
                                                              .contains("aether.allowNonDurableStreams"));
    }

    @Test
    void uncreatableSegmentsDir_withOptIn_degradesAsBefore() throws IOException {
        var blocker = Files.writeString(dir.resolve("not-a-directory"), "blocks the segments dir");
        var segments = blocker.resolve("segments");

        assertThat(AetherNode.decideSegmentsAvailability(segments,
                                                         FileOps.createDirectories(segments).mapToUnit(),
                                                         true)
                             .isSuccess()).isTrue();
    }
}
