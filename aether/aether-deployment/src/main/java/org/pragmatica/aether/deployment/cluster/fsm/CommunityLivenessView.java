// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.consensus.NodeId;


/// The leader's OBSERVED view of whether a community member is still answering, and the core half of
/// the #590 mechanism.
///
/// ## What this replaces
/// The per-community FSM used to read liveness from `GovernorAnnouncementValue.memberCount` — a field
/// the community writes about ITSELF. Under a core/community partition the governor cannot write, so
/// that number does not expire, it FREEZES at its last healthy value. The core therefore kept the
/// community `ACTIVE` and kept placing work on nodes it could no longer reach, on the strength of a
/// status field that had stopped being true.
///
/// This view answers the same question from behaviour instead: the leader broadcasts `ClusterSyncPing`
/// cluster-wide and every live node answers, so a node whose pongs have stopped is absent as a matter
/// of observation, not of self-description.
///
/// ## Ordering
/// The absence window is `timeouts.cluster.community_absence`, which is required to be strictly longer
/// than the `core_absence` window on which a worker fences itself. That inequality is the whole
/// no-double-active guarantee: a community always stops serving before the core re-places its slices.
/// The threshold lives inside the implementation rather than in the caller so there is exactly one
/// place the two windows are compared.
@FunctionalInterface
public interface CommunityLivenessView {
    /// `true` when the leader has had no contact from `node` for longer than the community-absence
    /// window. `false` covers both "recently heard from" and "no evidence either way" — an absent
    /// verdict must be positively observed, never inferred from missing data.
    boolean isAbsent(NodeId node);

    /// The pre-wiring default: nothing is ever absent, so the FSM behaves exactly as it did before
    /// #590. Tests and any deployment that has not wired the collector keep the old semantics rather
    /// than silently gaining a demotion path with no signal behind it.
    static CommunityLivenessView unwired() {
        return _ -> false;
    }
}
