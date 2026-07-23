// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot.communityMetricsSnapshot;
import static org.pragmatica.aether.worker.metrics.PerMethodMetrics.perMethodMetrics;
import static org.pragmatica.aether.worker.metrics.PerSliceMetrics.perSliceMetrics;


/// #492 regression: [CommunityMetricsSnapshot] rides the core QUIC METRICS lane (broadcast to every
/// node, routed to the ControlLoop), so its generated codec — and the nested per-slice / per-method
/// records' codecs — MUST be registered in the production node registry. They previously lived only in
/// the orphaned `WorkerCodecs` assembly, so every broadcast attempt from a core node threw
/// "No codec registered" in `QuicClusterNetwork.writeToStream` (44x per forge failover run). Codecs are
/// sourced exactly as production builds them: `NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())`.
class CommunityMetricsSnapshotCodecTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void communityMetricsSnapshot_roundTripsThroughNodeCodecs() {
        var artifact = Artifact.artifact("org.test:slice:1.0.0").unwrap();
        var methods = List.of(perMethodMetrics("handle", 3L, 12.5, 0.01, 400L));
        var slices = List.of(perSliceMetrics(artifact, 5L, 20.0, 0.02, 1000L, methods));
        var original = communityMetricsSnapshot("community-1", new NodeId("gov-1"), 4, slices, 1_753_000_000_000L);

        var decoded = (CommunityMetricsSnapshot) CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void communityMetricsSnapshot_emptySlices_roundTrips() {
        var original = communityMetricsSnapshot("community-2", new NodeId("gov-2"), 1, List.of(), 1_753_000_000_001L);

        var decoded = (CommunityMetricsSnapshot) CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isEqualTo(original);
    }
}
