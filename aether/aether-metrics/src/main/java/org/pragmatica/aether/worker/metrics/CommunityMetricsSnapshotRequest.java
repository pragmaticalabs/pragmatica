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
@Codec public record CommunityMetricsSnapshotRequest( NodeId sender,
                                                      String communityId,
                                                      long requestId) implements Message.Wired {
    /// Factory following JBCT naming convention.
    public static CommunityMetricsSnapshotRequest communityMetricsSnapshotRequest(NodeId sender,
                                                                                  String communityId,
                                                                                  long requestId) {
        return new CommunityMetricsSnapshotRequest(sender, communityId, requestId);
    }
}
