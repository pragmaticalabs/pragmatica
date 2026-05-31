// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/// Presence-derived adapter from the NTT/SWIM-projected `ClusterGenerationSnapshot` to the
/// consensus-layer `MembershipView`. Membership-v2 finale: the synthetic per-node lifecycle
/// layer was removed, so presence in `coreMembers` IS membership and being on duty —
/// `onDutyMemberIds()` equals `coreMemberIds()` and `healthyOnDutyCount()` is the member count
/// (the NTT set is healthy by construction).
record SnapshotMembershipView(Map<NodeId, CoreMember> coreMembers,
                              int desiredCoreSize,
                              Set<NodeId> nodesWithoutSlices) implements MembershipView {
    static MembershipView from(ClusterGenerationSnapshot snapshot) {
        return new SnapshotMembershipView(snapshot.coreMembers(),
                                          snapshot.desiredCoreSize(),
                                          snapshot.nodesWithoutSlices());
    }

    @Override
    public Set<NodeId> coreMemberIds() {
        return coreMembers.keySet();
    }

    @Override
    public Set<NodeId> onDutyMemberIds() {
        return coreMemberIds();
    }

    @Override
    public int healthyOnDutyCount() {
        return coreMembers.size();
    }

    @Override
    public Set<NodeId> ctmProvisionedNodeIds() {
        return coreMembers.entrySet()
                          .stream()
                          .filter(entry -> entry.getValue()
                                                .provisioningSource() == ProvisioningSource.CTM)
                          .map(Map.Entry::getKey)
                          .collect(Collectors.toUnmodifiableSet());
    }
}
