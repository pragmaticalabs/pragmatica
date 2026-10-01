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
/// **What holds (#1527).** A node that gains leadership through a NEW election (one that commits a
/// fresh `LeaderKey` naming it) holds a term strictly above the term of every earlier committed
/// leadership, cluster-wide, however often each node has led. The mechanism: the term IS the committed
/// [LeaderValue#viewSequence()] of the election that named this node, and the KV applier accepts a
/// `LeaderKey` write only when its `viewSequence` is strictly greater than the committed one. The
/// earlier source, a per-process count of this node's own leader gains, gave none of that: a node that
/// had led fewer times than its predecessor minted a LOWER term, and the `EpochBearing` fence refused
/// its writes.
///
/// **Read point.** The leader-gain edge is emitted only after the node's own `LeaderKey` commit has
/// been applied locally (the election FSM enters `Led(self)` from the commit notification or the
/// KV-pull of that commit), so the committed record is present when [#onLeaderGained()] reads it.
/// If the record names ANOTHER node by then, this node was deposed before it acted: the held term is
/// kept, its writes carry a stale term, and the fence refuses them — which is the intended outcome.
///
/// **Limitation — re-adopted leadership after a process restart (by reading, not tested).** A
/// restarted node whose restored store still names ITSELF re-enters `Led(self)` by adopting that
/// committed record (`adoptLeaderUnconditionally`, from `QuorumWaiting` / `AwaitingKvSync`, which has
/// no self check), with no new proposal. Its term then EQUALS its previous tenure's, while the
/// generation counter restarts at 0, so `Epoch(N, small)` orders BELOW the `Epoch(N, large)` it minted
/// before the restart; a write whose epoch comes from the leader epoch (e.g. `GovernorAuthority`) can be
/// refused. Not a regression — the old counter restarted at 1. Closed by #1525 (in-memory consensus
/// store, plus a boot token refusing a same-id restart), which removes both the restored self-record
/// and the same-id rejoin.
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

    /// The term this node holds or is about to adopt: the committed `viewSequence` of the election that
    /// named it, or the held term when the committed record names another node. A pure read — it never
    /// advances [#current()]. This is `LeaderReconciler`'s re-election pre-latch input (#1559): that
    /// reconciler's `LeaderChange` route is registered BEFORE the route that calls [#onLeaderGained()], so
    /// at its `activate()` the held term is still the previous tenure's, while the committed record (the
    /// gain edge follows the local commit) already carries this election's sequence. With in-memory
    /// consensus (#1545) a fresh cluster's first election commits sequence 1 — no pre-latch — and every
    /// later election, a failover to a never-led node included, commits a higher one.
    public long committedTerm() {
        return committedLeader.get()
                              .filter(this::namesSelf)
                              .map(LeaderValue::viewSequence)
                              .map(sequence -> Math.max(sequence,
                                                        term.get()))
                              .or(term::get);
    }

    /// Adopts the committed `viewSequence` of the election that named this node and returns the term
    /// now held. Max-merge: a replayed or out-of-order edge never regresses the term.
    public long onLeaderGained() {
        return adoptCommitted();
    }

    /// Re-adopts on ANY committed `LeaderKey` write that names this node, not only on the gain edge (#1797).
    /// A same-leader re-commit (the leader's `viewSequence` advancing while it already leads) emits no new
    /// gain edge, so without this the held term would stay below the committed sequence the KV applier
    /// accepted. Same max-merge as [#onLeaderGained()]: never lowers, and a record naming another node
    /// leaves the held term alone.
    public long onLeaderKeyCommitted() {
        return adoptCommitted();
    }

    private long adoptCommitted() {
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
