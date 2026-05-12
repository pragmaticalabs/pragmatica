// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.phase;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


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
                               LifecycleSnapshotReader lifecycleReader,
                               Supplier<Option<ClusterPhase>> priorPhaseReader,
                               BooleanSupplier haveLeaderReader) {
    @FunctionalInterface public interface LifecycleSnapshotReader {
        Map<NodeId, NodeLifecycleValue> snapshot();
    }

    public static ClusterPhaseView clusterPhaseView(int expectedClusterSize,
                                                    TimeSpan stableWindow,
                                                    TimeSpan recoveryStableWindow,
                                                    LifecycleSnapshotReader lifecycleReader,
                                                    Supplier<Option<ClusterPhase>> priorPhaseReader,
                                                    BooleanSupplier haveLeaderReader) {
        return new ClusterPhaseView(expectedClusterSize,
                                    stableWindow,
                                    recoveryStableWindow,
                                    lifecycleReader,
                                    priorPhaseReader,
                                    haveLeaderReader);
    }

    public ClusterPhase compute(long nowMs) {
        var snapshot = lifecycleReader.snapshot();
        var stats = OnDutyStats.from(snapshot);
        var quorum = quorumThreshold();
        var haveLeader = haveLeaderReader.getAsBoolean();
        var everReachedNormal = priorEverReachedNormal();
        return computePhase(stats, quorum, haveLeader, everReachedNormal, nowMs);
    }

    private boolean priorEverReachedNormal() {
        return priorPhaseReader.get().map(ClusterPhaseView::isPostColdBoot)
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
        if (stats.onDutyCount() < quorum || !haveLeader) {return ClusterPhase.COLD_BOOT;}
        return stableWindowSatisfied(stats, nowMs, stableWindow)
              ? ClusterPhase.NORMAL
              : ClusterPhase.COLD_BOOT;
    }

    private ClusterPhase recoveringBranch(OnDutyStats stats, int quorum, boolean haveLeader, long nowMs) {
        if (stats.onDutyCount() < quorum || !haveLeader) {return ClusterPhase.RECOVERING;}
        return stableWindowSatisfied(stats, nowMs, recoveryStableWindow)
              ? ClusterPhase.NORMAL
              : ClusterPhase.RECOVERING;
    }

    /// Spec §7.3: stability window is "the duration after which a satisfied promotion
    /// condition becomes effective". We derive it from the oldest ON_DUTY entry timestamp
    /// in KV — the cluster has been at quorum since at least that point, so if the oldest
    /// entry is old enough (>= window), the cluster has been quorum-stable for the window.
    /// Equivalent to spec §7.3's `nowMs - stableSinceMs >= windowMs` without needing a
    /// per-leader `stableSinceMs` field.
    private static boolean stableWindowSatisfied(OnDutyStats stats, long nowMs, TimeSpan window) {
        return stats.oldestOnDutyAt().map(oldest -> nowMs - oldest >= window.millis())
                                     .or(false);
    }

    private int quorumThreshold() {
        return Math.max(1, expectedClusterSize / 2 + 1);
    }

    private record OnDutyStats(int onDutyCount, Option<Long> oldestOnDutyAt) {
        static OnDutyStats from(Map<NodeId, NodeLifecycleValue> snapshot) {
            var count = 0;
            var oldest = Long.MAX_VALUE;
            for (var value : snapshot.values()) {
                if (value.state() != NodeLifecycleState.ON_DUTY) {continue;}
                count += 1;
                if (value.updatedAt() < oldest) {oldest = value.updatedAt();}
            }
            return new OnDutyStats(count,
                                   count == 0
                                  ? none()
                                  : some(oldest));
        }
    }
}
