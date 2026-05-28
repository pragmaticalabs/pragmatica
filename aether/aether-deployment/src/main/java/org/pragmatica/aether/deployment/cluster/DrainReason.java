// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;


/// Membership v2 / E2 — drain-action reason used by [`ClusterTopologyManager#drainNode`].
/// Observability-only at this layer: surfaces *why* the leader requested a node drain so
/// audit/log/metrics consumers can distinguish operator-initiated from auto-remediation
/// flows.
///
/// - [`#OPERATOR_COMMAND`] — explicit operator-initiated scale-down or decommission.
/// - [`#OVERPROVISION_SCALE_DOWN`] — configured size shrunk; surplus peers must drain.
/// - [`#OVERPROVISION_PARTITION_HEAL`] — observed member-set exceeds configured count
///   after a partition heal; the leader-pinned reconciler picked drain victims.
public enum DrainReason {
    OPERATOR_COMMAND,
    OVERPROVISION_SCALE_DOWN,
    OVERPROVISION_PARTITION_HEAL
}
