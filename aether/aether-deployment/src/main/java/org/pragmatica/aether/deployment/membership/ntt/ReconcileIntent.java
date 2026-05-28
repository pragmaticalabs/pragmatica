// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.consensus.NodeId;

import java.util.Set;


/// Structured intent emitted by [`LeaderReconciler`] per reconciliation decision
/// (spec §7.4). At E1, observation-only — the consumer just logs. Stage 6+ triggers
/// actual CTM provisioning / drain.
///
/// At E1 [`#peersToProvision`] and [`#peersToDrain`] are intentionally empty placeholders
/// — KSUID-named peer selection / drain selection is wired in Stage 6.
/// [`#inFlightProvisioningCount`] is informational; the per-peer details (timestamp
/// snapshot) are kept inside the reconciler's internal state.
///
/// @param observedAtNanos          `TimeSource`-derived monotonic nanos at the moment the
///                                 reconcile decision was made
/// @param trigger                  which of the four trigger paths produced this intent
/// @param clusterMembershipCount   SWIM-converged member-set count at the moment of decision
/// @param configuredCoreCount      configured core count (`coreCount`) at the moment of decision
/// @param peersToProvision         peers the leader would provision (empty placeholder at E1)
/// @param peersToDrain             peers the leader would drain (empty placeholder at E1)
/// @param inFlightProvisioningCount peers this leader has provisioned and is still tracking
///                                  for in-flight expiry
public record ReconcileIntent(long observedAtNanos,
                              ReconcileTrigger trigger,
                              int clusterMembershipCount,
                              int configuredCoreCount,
                              Set<NodeId> peersToProvision,
                              Set<NodeId> peersToDrain,
                              int inFlightProvisioningCount) {
    public static ReconcileIntent reconcileIntent(long observedAtNanos,
                                                  ReconcileTrigger trigger,
                                                  int clusterMembershipCount,
                                                  int configuredCoreCount,
                                                  Set<NodeId> peersToProvision,
                                                  Set<NodeId> peersToDrain,
                                                  int inFlightProvisioningCount) {
        return new ReconcileIntent(observedAtNanos,
                                   trigger,
                                   clusterMembershipCount,
                                   configuredCoreCount,
                                   Set.copyOf(peersToProvision),
                                   Set.copyOf(peersToDrain),
                                   inFlightProvisioningCount);
    }
}
