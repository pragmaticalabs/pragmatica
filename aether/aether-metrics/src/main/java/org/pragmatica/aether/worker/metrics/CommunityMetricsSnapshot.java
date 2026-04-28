// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

import java.util.List;


@Codec public record CommunityMetricsSnapshot(String communityId,
                                              NodeId governorId,
                                              long requestId,
                                              int memberCount,
                                              List<PerSliceMetrics> sliceMetrics,
                                              List<WindowSample> slidingWindow,
                                              long timestampMs) implements Message.Wired {
    public static CommunityMetricsSnapshot communityMetricsSnapshot(String communityId,
                                                                    NodeId governorId,
                                                                    long requestId,
                                                                    int memberCount,
                                                                    List<PerSliceMetrics> sliceMetrics,
                                                                    List<WindowSample> slidingWindow,
                                                                    long timestampMs) {
        return new CommunityMetricsSnapshot(communityId,
                                            governorId,
                                            requestId,
                                            memberCount,
                                            List.copyOf(sliceMetrics),
                                            List.copyOf(slidingWindow),
                                            timestampMs);
    }

    public static CommunityMetricsSnapshot communityMetricsSnapshot(String communityId,
                                                                    NodeId governorId,
                                                                    long requestId,
                                                                    int memberCount,
                                                                    List<PerSliceMetrics> sliceMetrics,
                                                                    List<WindowSample> slidingWindow) {
        return communityMetricsSnapshot(communityId,
                                        governorId,
                                        requestId,
                                        memberCount,
                                        sliceMetrics,
                                        slidingWindow,
                                        System.currentTimeMillis());
    }
}
