// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The leader term this node mints every leader-authored `Epoch(rabiaTerm, counter)` from — the
/// dominant component of the DHT/stream ownership epochs, the consumer-assignment epoch and the
/// cluster-sync ping epoch.
///
/// **The guarantee (S28).** A node that gains leadership orders strictly after EVERY prior leader,
/// cluster-wide. The mechanism: the term IS the committed [LeaderValue#viewSequence()] of the
/// election that named this node. The KV applier accepts a `LeaderKey` write only when its
/// `viewSequence` is strictly greater than the committed one, so every committed leadership carries a
/// sequence strictly above every earlier committed leadership — on every replica, across restarts of
/// any individual process, and regardless of how often each node has led. A per-process count of this
/// node's own leader gains (the earlier source) gave none of that: a node that had led fewer times than
/// its predecessor minted a LOWER term, and the `EpochBearing` fence refused its writes.
///
/// **Read point.** The leader-gain edge is emitted only after the node's own `LeaderKey` commit has
/// been applied locally (the election FSM enters `Led(self)` from the commit notification or the
/// KV-pull of that commit), so the committed record is present when [#onLeaderGained()] reads it.
/// If the record names ANOTHER node by then, this node was deposed before it acted: the held term is
/// kept, its writes carry a stale term, and the fence refuses them — which is the intended outcome.
///
/// **Not covered.** The sequence is only as durable as the committed `LeaderKey`: a whole-cluster
/// cold start with an empty store restarts it at 1. A cluster-incarnation component dominating the
/// term is the planned answer; it composes here, as the prefix this class hands to the epoch mint
/// (`current()` is the single source every consumer reads).
public final class LeaderTerm {
    private final NodeId self;
    private final Supplier<Option<LeaderValue>> committedLeader;
    private final AtomicLong term = new AtomicLong(0L);

    private LeaderTerm(NodeId self, Supplier<Option<LeaderValue>> committedLeader) {
        this.self = self;
        this.committedLeader = committedLeader;
    }

    public static LeaderTerm leaderTerm(NodeId self, Supplier<Option<LeaderValue>> committedLeader) {
        return new LeaderTerm(self, committedLeader);
    }

    /// The term this node currently mints from.
    public long current() {
        return term.get();
    }

    /// Adopts the committed `viewSequence` of the election that named this node and returns the term
    /// now held. Max-merge: a replayed or out-of-order edge never regresses the term.
    public long onLeaderGained() {
        return committedLeader.get()
                              .filter(this::namesSelf)
                              .map(LeaderValue::viewSequence)
                              .map(this::advanceTo)
                              .or(term::get);
    }

    private boolean namesSelf(LeaderValue value) {
        return value.leader()
                    .equals(self);
    }

    private long advanceTo(long committedSequence) {
        return term.accumulateAndGet(committedSequence, Math::max);
    }
}
