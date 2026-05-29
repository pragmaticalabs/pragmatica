// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.swim.membership.MembershipPhase;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


/// Thin adapter over the unified `MembershipTracker`'s phase signal.
///
/// The tracker derives [`MembershipPhase`] from its own stable member set and quorum
/// threshold. This view maps that phase onto the KV-facing [`ClusterPhase`] and applies
/// one extra refinement the tracker cannot know about: leader awareness.
///
/// `MembershipPhase.NORMAL` means "a quorate stable set is held", but the tracker has no
/// concept of leadership. `ClusterPhaseView` requires a leader for `NORMAL`: if the tracker
/// reports `NORMAL` while no leader is present, the cluster is treated as `RECOVERING`.
/// `COLD_BOOT` and `RECOVERING` pass through unchanged.
public record ClusterPhaseView(Supplier<MembershipPhase> trackerPhaseSupplier,
                               BooleanSupplier haveLeaderReader) {
    public static ClusterPhaseView clusterPhaseView(Supplier<MembershipPhase> trackerPhaseSupplier,
                                                    BooleanSupplier haveLeaderReader) {
        return new ClusterPhaseView(trackerPhaseSupplier, haveLeaderReader);
    }

    public ClusterPhase compute() {
        return refineWithLeader(map(trackerPhaseSupplier.get()));
    }

    private static ClusterPhase map(MembershipPhase phase) {
        return switch (phase) {
            case COLD_BOOT -> ClusterPhase.COLD_BOOT;
            case NORMAL -> ClusterPhase.NORMAL;
            case RECOVERING -> ClusterPhase.RECOVERING;
        };
    }

    private ClusterPhase refineWithLeader(ClusterPhase mapped) {
        return mapped == ClusterPhase.NORMAL && !haveLeaderReader.getAsBoolean()
               ? ClusterPhase.RECOVERING
               : mapped;
    }
}
