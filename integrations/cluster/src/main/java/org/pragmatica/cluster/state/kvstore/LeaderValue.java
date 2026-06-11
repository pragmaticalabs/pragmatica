package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

/// Leader election value: the elected leader plus the election `viewSequence` that fences the
/// write (H4, cluster-topology-overhaul §Wave 8.2).
///
/// The KV applier ([KVStore]) treats `LeaderKey` writes as compare-and-put: a write applies ONLY
/// when its `viewSequence` is strictly greater than the stored one. A minority-flapped node's
/// stale election (computed against an old view) carries a sequence at or below the committed
/// one and is rejected deterministically on every replica.
///
/// `viewSequence` is baseline-anchored, not a per-attempt counter (see
/// `LeaderElectionContext#nextViewSequence`): every proposer derives it as
/// `observed-committed + 1`, so peers proposing off the same observed commit produce IDENTICAL
/// commands and still coalesce to the same `BatchId` — the consensus-dedup property this record
/// has always relied on is preserved.
@Codec
public record LeaderValue(NodeId leader, long viewSequence) {
    public static LeaderValue leaderValue(NodeId leader, long viewSequence) {
        return new LeaderValue(leader, viewSequence);
    }
}
