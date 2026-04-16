// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

import java.util.List;


/// Rich metrics snapshot from a governor, sent on-demand to core.
/// Contains full sliding window history and per-slice breakdown.
/// ~1-5KB depending on window size and slice count.
///
/// @param communityId  community identifier
/// @param governorId   governor node ID
/// @param requestId    correlation ID matching the request
/// @param memberCount  current community member count
/// @param sliceMetrics per-slice metrics breakdown
/// @param slidingWindow sliding window history
/// @param timestampMs  when the snapshot was created
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
