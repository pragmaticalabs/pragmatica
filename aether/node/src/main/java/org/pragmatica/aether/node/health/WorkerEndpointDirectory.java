// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.consensus.net.NodeInfo;

import static org.pragmatica.lang.Unit.unit;


/// Committed application dependency addresses, separate from the worker's membership scope.
/// Its size depends on deployed dependencies and is bounded by metadata transfer admission.
public record WorkerEndpointDirectory(AtomicReference<Map<NodeId, NodeInfo>> entries) {
    public static WorkerEndpointDirectory workerEndpointDirectory() {
        return new WorkerEndpointDirectory(new AtomicReference<>(Map.of()));
    }

    public Unit install(List<NodeInfo> directory) {
        entries.set(directory.stream()
                             .collect(Collectors.toUnmodifiableMap(NodeInfo::id,
                                                                   info -> info,
                                                                   (first, _) -> first)));

        return unit();
    }

    public List<NodeInfo> desiredConnections() {
        return List.copyOf(entries.get().values());
    }

    public List<NodeId> accessible(List<NodeId> candidates, MembershipFsm membership, Set<NodeId> connected) {
        var reachable = Set.copyOf(membership.reachableMembers(candidates));
        var directory = entries.get();
        var locallyObserved = membership.memberStates().keySet();

        return candidates.stream()
                         .filter(peer -> reachable.contains(peer) || (!locallyObserved.contains(peer)
                                                                      && directory.containsKey(peer)
                                                                      && connected.contains(peer)))
                         .toList();
    }
}
