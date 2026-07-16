// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec
public record CommunityMetricsSnapshot(String communityId,
                                       NodeId governorId,
                                       int memberCount,
                                       List<PerSliceMetrics> sliceMetrics,
                                       long timestampMs) implements Message.Wired {
    public CommunityMetricsSnapshot {
        sliceMetrics = sliceMetrics == null
                       ? List.of()
                       : List.copyOf(sliceMetrics);
    }

    @Override
    public StreamType streamType() {
        return StreamType.METRICS;
    }

    public static CommunityMetricsSnapshot communityMetricsSnapshot(String communityId,
                                                                    NodeId governorId,
                                                                    int memberCount,
                                                                    List<PerSliceMetrics> sliceMetrics,
                                                                    long timestampMs) {
        return new CommunityMetricsSnapshot(communityId, governorId, memberCount, sliceMetrics, timestampMs);
    }

    public static CommunityMetricsSnapshot communityMetricsSnapshot(String communityId,
                                                                    NodeId governorId,
                                                                    int memberCount,
                                                                    List<PerSliceMetrics> sliceMetrics) {
        return communityMetricsSnapshot(communityId, governorId, memberCount, sliceMetrics, System.currentTimeMillis());
    }
}
