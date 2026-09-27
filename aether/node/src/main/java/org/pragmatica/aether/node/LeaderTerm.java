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
    private final AtomicLong localGains = new AtomicLong(0L);

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

    /// How many times THIS process has gained leadership — NOT a term, and never an epoch component.
    /// It exists only for `LeaderReconciler`'s re-election pre-latch (`> 1`), which read the old
    /// per-process counter as "leader term", and it keeps that reader's existing behaviour, defects
    /// included — it does NOT make the pre-latch correct. The reconciler's `LeaderChange` route is
    /// registered before the route that calls [#onLeaderGained()], so `activate()` reads the count
    /// BEFORE this gain is counted: the pre-latch fires only on a process's third gain, never for a
    /// first-tenure successor after failover. Moving the pre-latch to the committed term is a
    /// follow-up once #1525 makes the consensus store in-memory.
    public long localGainCount() {
        return localGains.get();
    }

    /// Adopts the committed `viewSequence` of the election that named this node and returns the term
    /// now held. Max-merge: a replayed or out-of-order edge never regresses the term.
    public long onLeaderGained() {
        localGains.incrementAndGet();

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
