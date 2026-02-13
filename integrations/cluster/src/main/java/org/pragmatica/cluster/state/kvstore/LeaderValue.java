package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.NodeId;

/// Leader election value containing only the elected leader.
/// Consensus protocol handles ordering - no need for viewSequence or timestamp.
/// Keeping only leader ensures identical commands from all nodes get the same BatchId.
public record LeaderValue(NodeId leader) {
    public static LeaderValue leaderValue(NodeId leader) {
        return new LeaderValue(leader);
    }
}
