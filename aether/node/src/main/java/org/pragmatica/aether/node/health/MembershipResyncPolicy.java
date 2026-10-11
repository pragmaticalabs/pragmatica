// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import java.util.Set;

import org.pragmatica.consensus.NodeId;


/// When a core that already knows the committed electorate should ask its peers for the members its SWIM
/// view still lacks (#1803).
///
/// The electorate can name voters this node has never been introduced to: its join ack was merged through a
/// scope that did not yet include them, and gossip about them is a one-shot. This policy paces the re-ask.
/// A request is allowed at most once per [#MIN_INTERVAL_NANOS], at most [#MAX_REQUESTS_PER_VIEW] times for one
/// set of missing voters, and starts over when that set changes — so a voter that is simply dead (still
/// in the electorate until the next reconfiguration) costs a bounded burst, not a permanent drip.
public final class MembershipResyncPolicy {
    static final long MIN_INTERVAL_NANOS = 2_000_000_000L;
    static final int MAX_REQUESTS_PER_VIEW = 20;

    private Set<NodeId> missing = Set.of();
    private int requests;
    private long lastRequestNanos;

    private MembershipResyncPolicy() {}

    public static MembershipResyncPolicy membershipResyncPolicy() {
        return new MembershipResyncPolicy();
    }

    /// `true` when a request for `missingVoters` should go out at `nowNanos`.
    public synchronized boolean permits(Set<NodeId> missingVoters, long nowNanos) {
        if (missingVoters.isEmpty()) {
            missing = Set.of();
            requests = 0;

            return false;
        }

        if (!missingVoters.equals(missing)) {
            missing = Set.copyOf(missingVoters);
            requests = 0;
        }

        if (requests >= MAX_REQUESTS_PER_VIEW || (requests > 0 && nowNanos - lastRequestNanos < MIN_INTERVAL_NANOS)) {
            return false;
        }

        requests++;
        lastRequestNanos = nowNanos;

        return true;
    }
}
