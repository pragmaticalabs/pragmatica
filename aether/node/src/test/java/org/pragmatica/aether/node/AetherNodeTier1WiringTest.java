// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies Tier 1 snapshot publication wiring: the node codec round-trips a
/// `ClusterGenerationSnapshot`, so the leader encoder + follower decoder used
/// by `ClusterSyncScheduler` / `NodeSnapshotCache` are genuinely invertible rather
/// than silently dropped as in Commit 3's no-op hooks (spec §7.2).
class AetherNodeTier1WiringTest {
    @Test
    void nodeCodec_roundTripsClusterGenerationSnapshot() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var original = ClusterGenerationSnapshot.empty(42L);

        var encoded = codec.encode(original);
        ClusterGenerationSnapshot decoded = codec.decode(encoded);

        assertThat(encoded).isNotEmpty();
        assertThat(decoded.rabiaTerm()).isEqualTo(42L);
        assertThat(decoded.epoch()).isEqualTo(Epoch.epoch(42L, 0L));
        assertThat(decoded.coreMembers()).isEmpty();
    }

    @Test
    void nodeCodec_registersGenerationCodecsNonTrivially() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

        // Encoding any ClusterGenerationSnapshot requires the whole graph of
        // generation codecs (Epoch, ClusterMode, ClusterQuiescence, GenerationReason).
        // Missing any one throws at encode time.
        var snapshot = ClusterGenerationSnapshot.empty(1L);
        var encoded = codec.encode(snapshot);

        assertThat(encoded).isNotNull();
        assertThat(encoded.length).isGreaterThan(0);
    }

}
