// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.Message;


/// The non-core leave channel (#731), symmetric to [WorkerJoinDecision] (#728).
///
/// A worker's REMOVED edge never reaches `MembershipDecision` — [MembershipDeltaProjector]
/// keeps the core-delta invariant (workers never enter `announced`), so `processRemoved` used to
/// discard a departed worker's edge entirely after pruning its own `announcedWorkers` guard.
/// Nothing told the deployment FSM the worker was gone: its entry in the in-memory `workerNodes`
/// allocation-pool set, its `SliceNodeKey`/`NodeArtifactKey`/`NodeRoutesKey` KV footprint, all
/// lingered forever, and the reconciler never saw a shortfall to fill.
///
/// Deliberately does not carry `role` — a leave has nothing to differentiate by, unlike a join.
/// `Message.Local`, not `Message.Wired`: projected independently per-node from that node's own
/// FSM edges, same as [WorkerJoinDecision].
public record WorkerLeaveDecision(NodeId nodeId, HlcTimestamp stampedAt) implements Message.Local {
    public static WorkerLeaveDecision workerLeaveDecision(NodeId nodeId, HlcTimestamp stampedAt) {
        return new WorkerLeaveDecision(nodeId, stampedAt);
    }
}
