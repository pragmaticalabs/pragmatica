// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;

import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;


/// Presence-derived counterpart to `SnapshotMembershipView`. Where the snapshot adapter
/// projects a `ClusterGenerationSnapshot`, this adapter derives membership directly from
/// NTT presence (`NodeTopologyTracker.currentMembers()`): in membership-v2 presence IS
/// membership and being on duty, so `onDutyMemberIds()` equals `coreMemberIds()` and
/// `healthyOnDutyCount()` is the member count (the NTT set is healthy by construction).
///
/// NTT does not carry `desiredCoreSize` or the CTM-provisioned set, so those are supplied
/// separately rather than read from the live presence set.
///
/// Step 1 of the membership/placement split
/// (`aether/docs/specs/membership-placement-split-spec.md` §8): this adapter is added
/// alongside `SnapshotMembershipView` but is NOT yet wired into any consumer.
record PresenceMembershipView(Set<NodeId> members,
                              int desiredCoreSize,
                              Set<NodeId> ctmProvisioned) implements MembershipView {
    PresenceMembershipView {
        members = Set.copyOf(members);
        ctmProvisioned = Set.copyOf(ctmProvisioned);
    }

    /// Captures a point-in-time view by reading each supplier exactly once, so a single
    /// consumer invocation observes a consistent membership set. At wiring time (a later
    /// step) `memberSupplier` will be `ntt::currentMembers`.
    static MembershipView from(Supplier<Set<NodeId>> memberSupplier,
                               IntSupplier desiredCoreSizeSupplier,
                               Supplier<Set<NodeId>> ctmProvisionedSupplier) {
        return new PresenceMembershipView(memberSupplier.get(),
                                          desiredCoreSizeSupplier.getAsInt(),
                                          ctmProvisionedSupplier.get());
    }

    @Override
    public Set<NodeId> coreMemberIds() {
        return members;
    }

    @Override
    public Set<NodeId> onDutyMemberIds() {
        return members;
    }

    @Override
    public int healthyOnDutyCount() {
        return members.size();
    }

    @Override
    public Set<NodeId> ctmProvisionedNodeIds() {
        return ctmProvisioned;
    }
}
