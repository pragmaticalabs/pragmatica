// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// On-demand request from core to a governor for a detailed metrics snapshot.
/// Used for diagnostics, dashboard deep-dive, and LLM/TTM integration.
///
/// @param sender      requesting core node ID
/// @param communityId target community
/// @param requestId   correlation ID for matching response
@Codec public record CommunityMetricsSnapshotRequest(NodeId sender, String communityId, long requestId) implements Message.Wired {
    public static CommunityMetricsSnapshotRequest communityMetricsSnapshotRequest(NodeId sender,
                                                                                  String communityId,
                                                                                  long requestId) {
        return new CommunityMetricsSnapshotRequest(sender, communityId, requestId);
    }
}
