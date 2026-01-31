package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.NodeId;

/**
 * Leader election value with view sequence for consistency.
 * The viewSequence is a monotonic counter to reject stale proposals.
 */
public record LeaderValue(NodeId leader,
                          long viewSequence,
                          long electedAt) {
    public static LeaderValue leaderValue(NodeId leader, long viewSequence) {
        return new LeaderValue(leader, viewSequence, System.currentTimeMillis());
    }
}
