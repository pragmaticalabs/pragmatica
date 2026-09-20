// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// Core-only source-envelope relay. Receivers store, never forward, this message; direct source
/// reports may be forwarded once to the leader, whose next sync cycle distributes its cache.
@Codec
public record SourceMetricsBatch(NodeId sender, List<CommunityMetricsSnapshot> snapshots) implements ProtocolMessage {
    public SourceMetricsBatch {
        snapshots = List.copyOf(snapshots);
    }

    public static SourceMetricsBatch sourceMetricsBatch(NodeId sender, List<CommunityMetricsSnapshot> snapshots) {
        return new SourceMetricsBatch(sender, snapshots);
    }

    @Override
    public StreamType streamType() {
        return StreamType.METRICS;
    }
}
