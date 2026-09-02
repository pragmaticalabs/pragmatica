// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.Message;


/// The non-core join channel (#728): a worker has been promoted to FSM Member and needs a role
/// assignment, but must NOT perturb the core membership delta.
///
/// ## Why this exists as a separate type rather than a `MembershipDecision`
///
/// `MembershipDecision` is the cluster-canonical CORE topology stream. Its `NodeJoined` payload
/// carries a `topology()` snapshot that consumers treat as the authoritative core view —
/// `ClusterSyncScheduler` and `ControlLoop` assign it directly to their topology state, and
/// `DHTTopologyListener` adds the node to the hash ring. Routing a worker through that type would
/// therefore contaminate every consumer that reasons about core membership, which is exactly the
/// Wave-2 invariant [`MembershipDeltaProjector`] was built to protect.
///
/// So the worker join travels on its own channel with its own type. The core delta stays pure —
/// `announced` never sees a worker, and no `topology()` payload ever contains one — while the
/// one consumer that legitimately needs to know about worker arrivals (the cluster deployment
/// FSM, which assigns the role and mints the community) gets its trigger.
///
/// ## What #728 was
///
/// `MembershipDeltaProjector.processJoined` dropped non-core joins on the floor and returned. Its
/// `emitJoin` is the sole production emitter of `MembershipDecision.NodeJoined`, which is the only
/// event reaching `ClusterDeploymentState.assignNodeRole`, which is the only writer of community
/// keys and worker activation directives. So a node self-asserting `role=worker` — what every
/// CTM-provisioned worker does — could never be assigned a role, never minted a community, and
/// never activated: it reached FSM Member and stopped there, a non-participating member.
///
/// ## Ordering and gating are inherited, not reimplemented
///
/// Worker edges ride the projector's existing quorum-gated FIFO queue exactly as core edges do,
/// because this decision is emitted from the same drain path. That matters: `assignWorkerRole`
/// submits KV commands into consensus, so emitting while non-quorate would produce writes that
/// cannot commit. The drainer's dequeue gate is the single place quorum is consulted, and it
/// covers both channels by construction.
/// `Message.Local`, not `Message.Wired`: this rides the node-local bus only. The worker join is
/// projected independently on every node from that node's own FSM edges, exactly as
/// `MembershipDecision` is, so it never needs serialization and must not acquire a wire tag.
public record WorkerJoinDecision(NodeId nodeId, String role, HlcTimestamp stampedAt) implements Message.Local {
    public static WorkerJoinDecision workerJoinDecision(NodeId nodeId, String role, HlcTimestamp stampedAt) {
        return new WorkerJoinDecision(nodeId, role, stampedAt);
    }
}
