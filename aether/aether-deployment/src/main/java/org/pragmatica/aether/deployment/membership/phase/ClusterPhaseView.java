// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberView;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


/// Derived `ClusterPhase` view (E.6, spec §7).
///
/// `ClusterPhase` is no longer an authoritative KV atom. The view computes it on demand
/// from per-peer `NodeLifecycleKey` snapshot using spec §7's formula:
///
/// ```text
/// quorum         = max(1, expectedClusterSize / 2 + 1)
/// onDutyPeers    = count(NodeLifecycleValue.state == ON_DUTY)
/// oldestOnDutyAt = min(updatedAt of those entries)
/// haveLeader     = leaderReader returns Some
///
/// (priorPhase.everReachedNormal == false, sub-quorum)        → COLD_BOOT
/// (priorPhase.everReachedNormal == false, quorum reached,
///   nowMs - oldestOnDutyAt >= stableWindow, haveLeader)      → NORMAL
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
    /// so each `compute()` call sees the live view (SWIM ∪ KV at that instant).
    @FunctionalInterface
    public interface MembershipViewReader {
        MembershipView view();
    }

    /// Legacy adapter — preserved so callers that haven't yet been migrated continue to
    /// compile. Each invocation builds a one-shot `MembershipView` whose KV reader walks
    /// the supplied snapshot map (no live SWIM input — SWIM-derived ON_DUTY peers will
    /// not appear, matching the pre-H.2b behaviour exactly). New callers should pass a
    /// real `MembershipViewReader` instead.
    @Deprecated
    @FunctionalInterface
    public interface LifecycleSnapshotReader {
        Map<NodeId, NodeLifecycleValue> snapshot();
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

    @Deprecated
    public static ClusterPhaseView clusterPhaseView(int expectedClusterSize,
                                                    TimeSpan stableWindow,
                                                    TimeSpan recoveryStableWindow,
                                                    LifecycleSnapshotReader lifecycleReader,
                                                    Supplier<Option<ClusterPhase>> priorPhaseReader,
                                                    BooleanSupplier haveLeaderReader) {
        return new ClusterPhaseView(expectedClusterSize,
                                    stableWindow,
                                    recoveryStableWindow,
                                    () -> legacyView(lifecycleReader),
                                    priorPhaseReader,
                                    haveLeaderReader);
    }

    /// Legacy-API adapter — synthesises a SWIM `HealthSnapshot` from KV `ON_DUTY` entries so
    /// pre-H.2b callers preserve their existing semantics (`ON_DUTY` in KV ⇒ counted as
    /// ON_DUTY regardless of live SWIM input). New callers that pass a real
    /// `MembershipViewReader` get the H model: SWIM is authoritative for "alive" and a
    /// stale KV `ON_DUTY` entry for a SWIM-faulty peer is filtered to `UNTRACKED`.
    private static MembershipView legacyView(LifecycleSnapshotReader lifecycleReader) {
        var snapshot = lifecycleReader.snapshot();
        var swim = new java.util.HashMap<NodeId, org.pragmatica.swim.SwimHealth>();
        snapshot.forEach((peer, value) -> {
                             if (value.state() == org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState.ON_DUTY) {
                             swim.put(peer, org.pragmatica.swim.SwimHealth.HEALTHY);
                         }
                         });
        var swimSnapshot = org.pragmatica.swim.HealthSnapshot.healthSnapshot(swim);

        return MembershipView.membershipView(() -> Option.some(swimSnapshot),
                                             consumer -> snapshot.forEach((peer, value) -> consumer.accept(org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey.nodeLifecycleKey(peer),
                                                                                                           value)));
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
    /// **RC1 membership-v2 step 1.** The `MembershipView` no longer carries a KV
    /// `NodeLifecycleValue` per peer (SWIM-derived membership has no consensus timestamp), so
    /// the legacy oldest-KV-`updatedAt` formula is dropped. Stability is now satisfied purely
    /// by the SWIM aliveness gate (`onDutyCount() >= quorum`, checked by the caller) — this is
    /// the graceful fallback the view already used once the FSM stopped emitting `ON_DUTY`
    /// writes, now made unconditional.
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
