package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


/// Leader election value: the elected leader plus the election `viewSequence` that fences the
/// write (H4, cluster-topology-overhaul §Wave 8.2).
///
/// The KV applier ([KVStore]) treats `LeaderKey` writes as compare-and-put: a write applies ONLY
/// when its `viewSequence` is strictly greater than the stored one. A minority-flapped node's
/// stale election (computed against an old view) carries a sequence at or below the committed
/// one and is rejected deterministically on every replica. The fence is the generic [EpochBearing]
/// one: the `viewSequence` is the epoch, and every election MINTS it ([#mintsEpoch()]), so an equal
/// sequence is refused, and a `Remove` needs a strictly greater `LeaderValue` witness. Like every
/// `EpochBearing` value it is fenced under any key, and its refusals are not stale-epoch refusals
/// ([#reportsStaleRefusal()]): a losing concurrent election is normal, not a lagging epoch source.
///
/// `viewSequence` is baseline-anchored, not a per-attempt counter (see
/// `LeaderElectionContext#nextViewSequence`): every proposer derives it as
/// `observed-committed + 1`, so peers proposing off the same observed commit produce IDENTICAL
/// commands and still coalesce to the same `BatchId` — the consensus-dedup property this record
/// has always relied on is preserved.
@Codec
public record LeaderValue(NodeId leader, long viewSequence) implements EpochBearing<Long> {
    public static LeaderValue leaderValue(NodeId leader, long viewSequence) {
        return new LeaderValue(leader, viewSequence);
    }

    @Override
    public Long fenceEpoch() {
        return viewSequence;
    }

    /// Every election claims a new `viewSequence`, so an equal one is stale.
    @Override
    public boolean mintsEpoch() {
        return true;
    }

    /// A refused election is a lost race, which is normal, so it is neither counted nor logged.
    @Override
    public boolean reportsStaleRefusal() {
        return false;
    }

    /// A `Long` epoch is not enough to be a leader: only another `LeaderValue` is fenced against this one.
    @Override
    public Class<?> fenceKind() {
        return LeaderValue.class;
    }
}
