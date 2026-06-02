// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;


/// Membership v2 / E2 — drain-action reason used by both [`ClusterTopologyManager#drainNode`]
/// (leader-pinned, operator/auto-remediation reasons) and the §8.2 unified [`DrainProcedure`]
/// (node-local, local-trigger reasons). Observability-only at this layer: surfaces *why* a
/// drain was started so audit/log/metrics consumers can distinguish operator-initiated from
/// auto-remediation and local-trigger flows.
///
/// **Leader-pinned (drainNode) reasons**:
/// - [`#OPERATOR_COMMAND`] — explicit operator-initiated scale-down or decommission.
/// - [`#OVERPROVISION_SCALE_DOWN`] — configured size shrunk; surplus peers must drain.
/// - [`#OVERPROVISION_PARTITION_HEAL`] — observed member-set exceeds configured count
///   after a partition heal; the leader-pinned reconciler picked drain victims.
///
/// **Local-trigger (DrainProcedure) reasons** (E2 Phase 2b):
/// - [`#QUORUM_LOSS`] — local node observed `localQuorumCount < threshold` for
///   `quorumLossDrainThreshold`; spec §8.1.
/// - [`#COMMANDED`] — leader/CTM-commanded drain delivered via the cluster-sync ping's GLOBAL
///   `drainNodes` set (membership-architecture-v2-spec B5a). The receiving node self-checks
///   `drainNodes.contains(self)` and initiates its local `DrainProcedure` (CAS-guarded, idempotent).
public enum DrainReason {
    OPERATOR_COMMAND,
    OVERPROVISION_SCALE_DOWN,
    OVERPROVISION_PARTITION_HEAL,
    QUORUM_LOSS,
    COMMANDED
}

