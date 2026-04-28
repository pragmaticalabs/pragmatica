// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


record SnapshotMembershipView(Map<NodeId, CoreMember> coreMembers,
                              int desiredCoreSize,
                              Set<NodeId> nodesWithoutSlices) implements MembershipView {
    static MembershipView from(ClusterGenerationSnapshot snapshot) {
        return new SnapshotMembershipView(snapshot.coreMembers(),
                                          snapshot.desiredCoreSize(),
                                          snapshot.nodesWithoutSlices());
    }

    @Override public Set<NodeId> coreMemberIds() {
        return coreMembers.keySet();
    }

    @Override public Set<NodeId> onDutyMemberIds() {
        return coreMembers.entrySet().stream()
                                   .filter(entry -> entry.getValue().lifecycle() == NodeLifecycleState.ON_DUTY)
                                   .map(Map.Entry::getKey)
                                   .collect(Collectors.toUnmodifiableSet());
    }

    @Override public int healthyOnDutyCount() {
        return (int) coreMembers.values().stream()
                                       .filter(member -> member.lifecycle() == NodeLifecycleState.ON_DUTY)
                                       .filter(member -> member.healthHint() == HealthHint.HEALTHY)
                                       .count();
    }

    @Override public Set<NodeId> ctmProvisionedNodeIds() {
        return coreMembers.entrySet().stream()
                                   .filter(entry -> entry.getValue().provisioningSource() == ProvisioningSource.CTM)
                                   .map(Map.Entry::getKey)
                                   .collect(Collectors.toUnmodifiableSet());
    }
}
