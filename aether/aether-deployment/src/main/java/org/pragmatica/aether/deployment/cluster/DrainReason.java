// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

/// Membership v2 / E2 — drain-action reason used by both [`ClusterTopologyManager#drainNode`]
/// (leader-pinned, operator/auto-remediation reasons) and the §8.2 unified [`DrainProcedure`]
/// (node-local, local-trigger reasons). Surfaces *why* a drain was started so audit/log/metrics
/// consumers can distinguish operator-initiated from auto-remediation and local-trigger flows. One
/// behavioural reading exists (#1050): [`#isSurplusTrim`] decides whether the CTM's drain-grace
/// backstop re-checks that the cluster can still spare the node before it reaps.
///
/// **Leader-pinned (drainNode) reasons**:
/// - [`#OPERATOR_COMMAND`] — explicit operator-initiated scale-down or decommission.
/// - [`#OVERPROVISION_SCALE_DOWN`] — configured size shrunk; surplus peers must drain.
/// - [`#OVERPROVISION_PARTITION_HEAL`] — observed member-set exceeds configured count
///   after a partition heal; the leader-pinned reconciler picked drain victims.
/// - [`#JOIN_GRACE_REAP`] — a CTM-provisioned replacement booted but never reached
///   SWIM-healthy within the M10 join-grace window, so the `MembershipFsm` join-grace
///   reaper drove it OBSERVED→DEAD. Membership is now correct, but the wedged joiner's
///   container/JVM is still running as a non-member zombie; the leader drains it through
///   the standard `drainNode` path so its `graceTerminate` backstop reaps the
///   container/instance (prevents Docker restart-loop / paid cloud orphans).
///
/// **Local-trigger (DrainProcedure) reasons** (E2 Phase 2b):
/// - [`#QUORUM_LOSS`] — local node observed `localQuorumCount < threshold` for
///   `quorumLossDrainThreshold`; spec §8.1.
/// - [`#COMMANDED`] — leader/CTM-commanded drain delivered via the cluster-sync ping's GLOBAL
///   `drainNodes` set (membership-architecture-v2-spec B5a). The receiving node self-checks
///   `drainNodes.contains(self)` and initiates its local `DrainProcedure` (CAS-guarded, idempotent).
/// - [`#CORE_ABSENCE`] — this node saw no term-accepted `ClusterSyncPing` for
///   `timeouts.cluster.core_absence`, so it has lost the core and dissolves locally (#590). The
///   community tier's analogue of `QUORUM_LOSS`, and local for the same reason the core tier's fence
///   is: announcing dissolve normally means writing `GovernorAnnouncementKey` through consensus — the
///   very thing an isolated community cannot do. Paired with the core's own longer
///   `timeouts.cluster.community_absence` window, which is what keeps the two from being live at once.
public enum DrainReason {
    OPERATOR_COMMAND,
    OVERPROVISION_SCALE_DOWN,
    OVERPROVISION_PARTITION_HEAL,
    JOIN_GRACE_REAP,
    QUORUM_LOSS,
    COMMANDED,
    CORE_ABSENCE;
    /// Whether this reason trims a SURPLUS — a node removed only because the cluster had more than it
    /// needed. Such a decision goes stale when the cluster later falls short, so the drain-grace
    /// backstop re-checks before reaping (#1050). `JOIN_GRACE_REAP` is deliberately NOT a surplus
    /// trim: its target is a never-joined zombie that is not a member, is typically reaped DURING the
    /// deficit it was provisioned to fill, and has no other reaper (a never-announced member emits no
    /// `NodeRemoved`), so gating it on the deficit would orphan it. `OPERATOR_COMMAND` is an explicit
    /// operator decision and is reaped as issued.
    public boolean isSurplusTrim() {
        return switch (this) {
            case OVERPROVISION_SCALE_DOWN, OVERPROVISION_PARTITION_HEAL -> true;
            case OPERATOR_COMMAND, JOIN_GRACE_REAP, QUORUM_LOSS, COMMANDED, CORE_ABSENCE -> false;
        };
    }
}
