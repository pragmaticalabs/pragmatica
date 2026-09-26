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

    /// Advances the term on a local leadership gain and returns the term now held.
    public long onLeaderGained() {
        return term.incrementAndGet();
    }
}
