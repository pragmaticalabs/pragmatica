// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberView;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


/// Derived `ClusterPhase` view (E.6, spec §7).
///
/// `ClusterPhase` is no longer an authoritative KV atom. The view computes it on demand
/// from the SWIM-derived `MembershipView` snapshot using spec §7's formula:
///
/// ```text
/// quorum         = max(1, expectedClusterSize / 2 + 1)
/// onDutyPeers    = count(MemberStatus.ON_DUTY)
/// haveLeader     = leaderReader returns Some
///
/// (priorPhase.everReachedNormal == false, sub-quorum)        → COLD_BOOT
/// (priorPhase.everReachedNormal == false, quorum reached,
///   stable window satisfied, haveLeader)                     → NORMAL
/// (priorPhase.everReachedNormal == false, quorum reached,
///   still inside stableWindow)                               → COLD_BOOT
/// (priorPhase.everReachedNormal == true, sub-quorum)         → RECOVERING
/// (priorPhase.everReachedNormal == true, quorum,
///   inside recoveryStableWindow)                             → RECOVERING
/// (priorPhase.everReachedNormal == true, quorum,
///   past recoveryStableWindow, haveLeader)                   → NORMAL
/// (priorPhase.everReachedNormal == true, no leader)          → RECOVERING
/// ```
///
/// The "ever-reached-NORMAL" bit is derived from the optional `priorPhaseReader`. Per
/// spec §7.2, `ClusterPhaseKey` becomes an optional cache populated by legacy
/// leader writes via `MembershipFsm` or by a future leader
/// FSM batch write (when the FSM owns the writes). The view consults the cache to
/// preserve the `COLD_BOOT` vs `RECOVERING` distinction across leader takeovers but
/// does NOT write to it — derivation is authoritative, the KV is a hint.
///
/// If no prior phase is available (KV cache empty, e.g., fresh cluster boot), the view
/// treats the cluster as "never reached NORMAL". This is conservative — it cannot mark
/// a cluster as `RECOVERING` until at least one `NORMAL` was observed, which matches
/// the spec §7.1 invariant `everReachedQuorum.get()`.
public record ClusterPhaseView(int expectedClusterSize,
                               TimeSpan stableWindow,
                               TimeSpan recoveryStableWindow,
                               MembershipViewReader membershipReader,
                               Supplier<Option<ClusterPhase>> priorPhaseReader,
                               BooleanSupplier haveLeaderReader) {
    /// H.2b (spec §H): read membership through `MembershipView` instead of a raw KV
    /// snapshot. The reader is a `Supplier<MembershipView>` rather than a snapshot lambda
    /// so each `compute()` call sees the live view (SWIM-derived membership at that instant).
    @FunctionalInterface
    public interface MembershipViewReader {
        MembershipView view();
    }

    public static ClusterPhaseView clusterPhaseView(int expectedClusterSize,
                                                    TimeSpan stableWindow,
                                                    TimeSpan recoveryStableWindow,
                                                    MembershipViewReader membershipReader,
                                                    Supplier<Option<ClusterPhase>> priorPhaseReader,
                                                    BooleanSupplier haveLeaderReader) {
        return new ClusterPhaseView(expectedClusterSize,
                                    stableWindow,
                                    recoveryStableWindow,
                                    membershipReader,
                                    priorPhaseReader,
                                    haveLeaderReader);
    }

    public ClusterPhase compute(long nowMs) {
        var view = membershipReader.view().snapshot();
        var stats = OnDutyStats.from(view);
        var quorum = quorumThreshold();
        var haveLeader = haveLeaderReader.getAsBoolean();
        var everReachedNormal = priorEverReachedNormal();

        return computePhase(stats, quorum, haveLeader, everReachedNormal, nowMs);
    }

    private boolean priorEverReachedNormal() {
        return priorPhaseReader.get()
                               .map(ClusterPhaseView::isPostColdBoot)
                               .or(false);
    }

    private static boolean isPostColdBoot(ClusterPhase phase) {
        return phase == ClusterPhase.NORMAL || phase == ClusterPhase.RECOVERING;
    }

    private ClusterPhase computePhase(OnDutyStats stats,
                                      int quorum,
                                      boolean haveLeader,
                                      boolean everReachedNormal,
                                      long nowMs) {
        if (!everReachedNormal) {
            return coldBootBranch(stats, quorum, haveLeader, nowMs);
        }
        return recoveringBranch(stats, quorum, haveLeader, nowMs);
    }

    private ClusterPhase coldBootBranch(OnDutyStats stats, int quorum, boolean haveLeader, long nowMs) {
        if (stats.onDutyCount() <quorum || !haveLeader) {return ClusterPhase.COLD_BOOT;}
        return stableWindowSatisfied(stats)
               ? ClusterPhase.NORMAL
               : ClusterPhase.COLD_BOOT;
    }

    private ClusterPhase recoveringBranch(OnDutyStats stats, int quorum, boolean haveLeader, long nowMs) {
        if (stats.onDutyCount() <quorum || !haveLeader) {return ClusterPhase.RECOVERING;}
        return stableWindowSatisfied(stats)
               ? ClusterPhase.NORMAL
               : ClusterPhase.RECOVERING;
    }

    /// Spec §7.3: stability window is "the duration after which a satisfied promotion
    /// condition becomes effective".
    ///
    /// **RC1 membership-v2 finale.** The `MembershipView` is SWIM-derived and carries no
    /// per-peer consensus timestamp, so the legacy oldest-KV-`updatedAt` formula is dropped.
    /// Stability is satisfied purely by the SWIM aliveness gate (`onDutyCount() >= quorum`,
    /// checked by the caller).
    private static boolean stableWindowSatisfied(OnDutyStats stats) {
        return stats.onDutyCount() > 0;
    }

    private int quorumThreshold() {
        return Math.max(1, expectedClusterSize / 2 + 1);
    }

    private record OnDutyStats(int onDutyCount) {
        static OnDutyStats from(Map<NodeId, MemberView> view) {
            var count = 0;

            for (var member : view.values()) {
                if (member.status() == MemberStatus.ON_DUTY) {count += 1;}
            }

            return new OnDutyStats(count);
        }
    }
}
