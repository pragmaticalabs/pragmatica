// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;

import java.util.List;


/// Inputs to the leader's `HealthReconciler` — exactly the events listed in spec §8.1.
/// Every atom-mutating decision derives from one of these signals.
///
/// Runtime-only value (no `@Codec`). Wire-level encoding for ping/pong integration is
/// introduced in Commit 3; for now the reconciler consumes these via direct in-process calls.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.1.
public sealed interface HealthSignal {
    record PingTimeout(NodeId nodeId, int missedIntervals, Epoch observedAt) implements HealthSignal{}

    record SwimHint(NodeId nodeId, HealthHint state, Epoch observedAt) implements HealthSignal{}

    record QuicDisconnect(NodeId nodeId, Epoch observedAt) implements HealthSignal{}

    /// Drain-eviction completion signal — emitted by `ClusterDeploymentManager` once
    /// every live slice on the draining node has been re-homed. Consumed by the
    /// leader's `HealthReconciler`, which authoritatively transitions the node's
    /// `NodeLifecycleKey` to `DECOMMISSIONED` per spec §8 single-writer rule.
    record DrainCompleted(NodeId nodeId, Epoch observedAt) implements HealthSignal{}

    record GovernorAnnounced(String communityId, NodeId governor, long communityTerm) implements HealthSignal{}

    record CommunityDissolved(String communityId) implements HealthSignal{}

    record SpokesmanAssignmentFailed(NodeId coreNodeId, List<String> affectedCommunities, String reason) implements HealthSignal {
        public SpokesmanAssignmentFailed {
            affectedCommunities = affectedCommunities == null
                                 ? List.of()
                                 : List.copyOf(affectedCommunities);
            if (reason == null) {reason = "";}
        }
    }

    record OperatorAction(OperatorIntent intent) implements HealthSignal{}
}
