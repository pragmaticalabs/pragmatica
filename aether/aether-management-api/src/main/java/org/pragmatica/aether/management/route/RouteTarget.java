// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import org.pragmatica.aether.slice.delegation.TaskGroup;


public sealed interface RouteTarget {
    RouteTarget ANY = new AnyCoreNode();
    RouteTarget LOCAL = new LocalNode();
    RouteTarget LEADER = new LeaderNode();

    static RouteTarget taskGroup(TaskGroup group) {
        return new TaskGroupTarget(group);
    }

    /// Forward to the node identified by the path param at the given index.
    /// Used by per-node variant endpoints (`/api/nodes/<resource>/{id}`) where
    /// the request is dispatched to a specific peer named in the URL.
    static RouteTarget nodeIdParam(int paramIndex) {
        return new NodeIdParam(paramIndex);
    }

    /// Forward to the deterministic HRW owner of a stream partition (#1039).
    ///
    /// Distinct from [#nodeIdParam] because the destination is COMPUTED from the request rather than
    /// named in it: the owner is `hrw(engineKey, partition)`, so no path param carries it. That is why
    /// owner-scoped routes previously fell back to `taskGroup(STREAMING)` — which dispatches to an
    /// arbitrary STREAMING-capable node and discards the fact that the receiving node may itself be the
    /// owner. For the replica-set view that is not merely suboptimal but wrong: the `ReplicaRegistry` is
    /// authoritative ONLY on the owner (only the owner receives every replica's ack), so a non-owner
    /// answers `servedByOwner=false` with an empty ring that is indistinguishable from a genuinely
    /// empty partition. Measured on a live 5-node cluster: `servedByOwner=false` from 5 of 5 ports
    /// INCLUDING the owner's own, while `replicas-local` on that same port returned `true` with the real
    /// offsets at the same instant.
    ///
    /// `partitionParamIndex` names the partition param; the engine key is derived from the route's
    /// identity params by the SAME reduction the handler uses, never re-implemented — a second
    /// derivation of stream identity is precisely the defect tracked by #1040.
    static RouteTarget partitionOwner(int partitionParamIndex) {
        return new PartitionOwner(partitionParamIndex);
    }

    record TaskGroupTarget(TaskGroup group) implements RouteTarget {}

    record AnyCoreNode() implements RouteTarget {}

    record LocalNode() implements RouteTarget {}

    record LeaderNode() implements RouteTarget {}

    record NodeIdParam(int paramIndex) implements RouteTarget {}

    record PartitionOwner(int partitionParamIndex) implements RouteTarget {}
}
