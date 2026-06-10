// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// Structured intent emitted by [`LeaderReconciler`] per reconciliation decision
/// (spec §7.4 — E2 Phase 1.5 simplification). The reconcile is fully state-derived,
/// so observers receive only the *count delta* (provisions / drains issued this pass);
/// the reconciler owns peer selection internally.
///
/// @param observedAtNanos          `TimeSource`-derived monotonic nanos at the moment the
///                                 reconcile decision was made
/// @param trigger                  which of the five trigger paths produced this intent
/// @param clusterMembershipCount   SWIM-converged member-set count at the moment of decision
/// @param configuredCoreCount      configured core count (`coreCount`) at the moment of decision
/// @param provisionCount           number of provision actions dispatched this pass
/// @param drainCount               number of drain actions dispatched this pass
/// @param inFlightProvisioningCount peers this leader has provisioned and is still tracking
///                                  for in-flight expiry
public record ReconcileIntent(long observedAtNanos,
                              ReconcileTrigger trigger,
                              int clusterMembershipCount,
                              int configuredCoreCount,
                              int provisionCount,
                              int drainCount,
                              int inFlightProvisioningCount) {
    public static ReconcileIntent reconcileIntent(long observedAtNanos,
                                                  ReconcileTrigger trigger,
                                                  int clusterMembershipCount,
                                                  int configuredCoreCount,
                                                  int provisionCount,
                                                  int drainCount,
                                                  int inFlightProvisioningCount) {
        return new ReconcileIntent(observedAtNanos,
                                   trigger,
                                   clusterMembershipCount,
                                   configuredCoreCount,
                                   provisionCount,
                                   drainCount,
                                   inFlightProvisioningCount);
    }
}
