// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Unit.unit;


/// Worker-local observation scope. Verified metadata supplies addresses, never liveness.
/// Transport authorization and application RPC routing remain independent of this scope.
public record WorkerPeerScope(NodeId self,
                              Supplier<Set<NodeId>> coreIds,
                              MembershipFsm membership,
                              CoreSwimHealthDetector swim,
                              AtomicReference<Set<NodeId>> directoryIds,
                              AtomicReference<Option<Set<NodeId>>> projectedCoreIds) {
    public static WorkerPeerScope workerPeerScope(NodeId self,
                                                  Supplier<Set<NodeId>> coreIds,
                                                  MembershipFsm membership,
                                                  CoreSwimHealthDetector swim) {
        var scope = new WorkerPeerScope(self,
                                        coreIds,
                                        membership,
                                        swim,
                                        new AtomicReference<>(Set.of()),
                                        new AtomicReference<>(Option.none()));

        scope.setTrackingScope();

        return scope;
    }

    public boolean contains(NodeId peer) {
        return self.equals(peer) || routingCoreIds().contains(peer) || directoryIds.get()
                                                                                   .contains(peer);
    }

    /// These identities route requests only; they do not authorize voting or quorum evidence.
    public Set<NodeId> routingCoreIds() {
        return projectedCoreIds.get()
                               .or(coreIds);
    }

    public synchronized Unit installDirectory(List<NodeInfo> directory) {
        projectedCoreIds.set(Option.some(directory.stream()
                                                  .filter(info -> "core".equalsIgnoreCase(info.labels()
                                                                                              .getOrDefault(NodeInfo.LABEL_ROLE,
                                                                                                            "")))
                                                  .map(NodeInfo::id)
                                                  .collect(Collectors.toUnmodifiableSet())));
        directoryIds.set(directory.stream().map(NodeInfo::id).collect(Collectors.toUnmodifiableSet()));
        setTrackingScope();
        directory.forEach(membership::onMemberDescriptor);
        swim.observePeerDirectory(directory);

        return unit();
    }

    private void setTrackingScope() {
        membership.setTrackingEligibility(this::contains);
        swim.setMembershipEligibility(this::contains);
    }
}
