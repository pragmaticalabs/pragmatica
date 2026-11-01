// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.PartitionLifetime.Scope;
import org.pragmatica.aether.stream.StreamPartitionManager.CachedWatermark;
import org.pragmatica.aether.stream.StreamPartitionManager.CommittedMark;
import org.pragmatica.aether.stream.StreamPartitionManager.EpochTrust;
import org.pragmatica.aether.stream.StreamPartitionManager.InstalledSlice;
import org.pragmatica.aether.stream.StreamPartitionManager.ReplicatedWrite;
import org.pragmatica.aether.stream.StreamPartitionManager.TailCut;


/// #2201 A-05 (spec 5.1): the per-partition state of ONE stream on this node, rows 4-19 of the spec table, each keyed by
/// partition. [StreamEntries] holds one per stream name, created on the first write, and drops it when the stream's life
/// ends ([StreamPartitionManager#purgePartitionState]: every stream-removal path, and a committed life replacing this node's
/// losing proposal). It is keyed by stream, not owned by the [StreamPartitionManager.StreamEntry], because some of it is
/// written while no entry exists (a durable-flag raise on a failed open, the install list-locks, an owner's visible position
/// or an epoch verdict before the stream is hydrated), and a committed-config adoption that replaces the entry keeps it.
///
/// Moved, not rewritten (PR-2a): every row keeps its type of map, its own concurrency and today's removal sites; the
/// monitors that order some of them ([StreamPartitionManager]'s `quarantineLock` and `refusedCutOrder`, and each
/// `pendingInstalls` list) keep their jobs. No new lock. A ring install or release does NOT replace [RingScoped] wholesale:
/// its rows are dropped at the sites that drop them today, which are a ring release or install and the end of a cut.
final class PartitionState {
    private final RingScoped ringScoped = RingScoped.ringScoped();
    private final Standing standing = Standing.standing();
    private final RepairEpisode repairEpisode = RepairEpisode.repairEpisode();

    private PartitionState() {}

    static PartitionState partitionState() {
        return new PartitionState();
    }

    RingScoped ringScoped() {
        return ringScoped;
    }

    Standing standing() {
        return standing;
    }

    RepairEpisode repairEpisode() {
        return repairEpisode;
    }

    /// The names of the rows that hold an entry for `partition`, for tests that pin what a life carries over. Read-only.
    Set<String> heldRows(int partition) {
        return Stream.of(held("durableWatermarks",
                              ringScoped.durableWatermarks().containsKey(partition)),
                         held("openCuts",
                              ringScoped.openCuts().containsKey(partition)),
                         held("lastReplicatedWalWrite",
                              standing.lastReplicatedWalWrite().containsKey(partition)),
                         held("unverifiedReplicas",
                              standing.unverifiedReplicas().contains(partition)),
                         held("verifiedPrefix",
                              standing.verifiedPrefix().containsKey(partition)),
                         held("divergedAt",
                              standing.divergedAt().containsKey(partition)),
                         held("provenanceMismatchAt",
                              standing.provenanceMismatchAt().containsKey(partition)),
                         held("epochTrust",
                              standing.epochTrust().containsKey(partition)),
                         held("replicaCommitted",
                              standing.replicaCommitted().containsKey(partition)),
                         held("pendingInstalls",
                              standing.pendingInstalls().containsKey(partition)),
                         held("raisedLocally",
                              standing.raisedLocally().stream().anyMatch(raised -> raised.startsWith(partition + "#"))),
                         held("cutGenerations",
                              standing.cutGenerations().containsKey(partition)),
                         held("refusedCuts",
                              repairEpisode.refusedCuts().containsKey(partition)),
                         held("pendingCuts",
                              repairEpisode.pendingCuts().containsKey(partition)),
                         held("reportedUnsettled",
                              repairEpisode.reportedUnsettled().containsKey(partition)),
                         held("repairGenerationOf",
                              repairEpisode.repairGenerationOf().containsKey(partition)))
                     .filter(HeldRow::held)
                     .map(HeldRow::name)
                     .collect(Collectors.toCollection(TreeSet::new));
    }

    private record HeldRow(String name, boolean held) {}

    private static HeldRow held(String name, boolean held) {
        return new HeldRow(name, held);
    }

    /// Rows whose lifetime is the ring they describe.
    ///
    /// @param durableWatermarks the durable watermark last read per partition, a cache checked against the manager's one
    ///                          watermark generation; dropped on every ring install and release.
    /// @param openCuts          the cut currently open on each partition (#2087): begun inside the ring's ordered section, closed
    ///                          after the ring shrank or on ANY other exit. The token makes the close idempotent and keeps it from
    ///                          closing a LATER cut: a close that arrives after another cut began finds a different token and does
    ///                          nothing.
    record RingScoped(@PartitionLifetime(value = Scope.RING, removedBy = {"dropDurableWatermark", "purgePartitionState"}) ConcurrentHashMap<Integer, CachedWatermark> durableWatermarks,
                      @PartitionLifetime(value = Scope.RING, removedBy = {"endCut", "purgePartitionState"}) ConcurrentHashMap<Integer, Object> openCuts) {
        static RingScoped ringScoped() {
            return new RingScoped(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    /// Rows whose lifetime is the partition on this node: they survive a ring release and rebuild, and end with the stream.
    ///
    /// @param lastReplicatedWalWrite the latest replicated WAL write per partition (#1244): what `syncReplicated` commits, and
    ///                               the offset it then makes visible (#1235). Updated inside the partition's ordered append
    ///                               section, so it always holds the highest offset written. Dropped by a cut above it, and
    ///                               by a WAL release only while it still points at the released log (know `d9cd07cda1` S3).
    /// @param unverifiedReplicas     #1730 phase 2: replica partitions whose recovered tail has not been verified against the
    ///                               committed owner yet. Such a copy may hold a lineage the owner never had (an ex-owner's
    ///                               unacknowledged records), so nothing it recovered is visible to a read served here until
    ///                               `markVerified` covers it; its own appends do not drag it into view either. Outlives a
    ///                               ring release (know `548514c110` Q2).
    /// @param verifiedPrefix         #2087: how far a replica's unverified tail has been compared with the committed owner
    ///                               (`markVerified`); only meaningful while the partition is in `unverifiedReplicas`. A
    ///                               verified prefix is exposed up to the owner's report.
    /// @param divergedAt             #1505 F2: the lowest offset at which this replica was found to hold a DIVERGENT entry. Read
    ///                               and written only inside the partition's ordered append section
    ///                               ([OffHeapRingBuffer#appendOrderedAt]), recorded under the manager's `quarantineLock`. It
    ///                               survives a ring release and rebuild but not a process restart (persisting it would be a
    ///                               new persisted-state format, #1513). Cleared when a repair cut lifts it and when the
    ///                               stream's life ends.
    /// @param provenanceMismatchAt   the offsets at which a PROVENANCE comparison found this copy divergent (N13), while the
    ///                               partition stays quarantined for it. The durable flag is raised only if the divergence is
    ///                               not repaired (`flagUnrepaired`): a repair removes the entry.
    /// @param epochTrust             the committed owner epoch this copy last judged itself against, and whether it may serve
    ///                               and acknowledge at and above that epoch's start (`unverifiedFrom`).
    /// @param replicaCommitted       #2087: the highest owner-visible offset this REPLICA has been told of, with the owner epoch
    ///                               it was reported under (`commitAdvanced`). A replica exposes to a consumer read no more
    ///                               than the minimum of this and what it holds. Absent means nothing is known: nothing is
    ///                               exposed until the owner reports. A newer epoch replaces the entry, even with a lower
    ///                               value; an older one is ignored. A cut lowers it, never removes it.
    /// @param pendingInstalls        #1638 F1: the catch-up installs recorded but not yet settled (`installProvenance`). Each
    ///                               partition's list is also that partition's lock serialising recording an install against
    ///                               trimming one, so a trim never drops an entry a pending install has just recorded or
    ///                               skipped; partitions never wait on each other's sidecar fsyncs (#1638 N2). Lock order: a
    ///                               partition's list, then its log's write lock, never the reverse.
    /// @param raisedLocally          #1596: the reasons already raised, `partition#kind#evidence`, so a condition met on every
    ///                               append raises one transaction, not one per append. A raise that fails is forgotten and
    ///                               retried at the next occurrence.
    /// @param cutGenerations         how many times the log of each partition has been cut (#2087 B4-cut), odd while a cut is
    ///                               open; anything folded from the log compares it with the value it was built under. Its
    ///                               value must stay ordered across ring replacements, which is why it is not ring-scoped.
    record Standing(@PartitionLifetime(value = Scope.STANDING, removedBy = {"forgetCutState", "forgetReplicatedWrites", "purgePartitionState"}) ConcurrentHashMap<Integer, ReplicatedWrite> lastReplicatedWalWrite,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"exposeVerified", "forgetCutState", "purgePartitionState"}) Set<Integer> unverifiedReplicas,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"exposeVerified", "forgetCutState", "purgePartitionState"}) ConcurrentHashMap<Integer, Long> verifiedPrefix,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"forgetCutState", "purgePartitionState"}) ConcurrentHashMap<Integer, Long> divergedAt,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"forgetCutState", "purgePartitionState"}) ConcurrentHashMap<Integer, Long> provenanceMismatchAt,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"purgePartitionState"}) ConcurrentHashMap<Integer, EpochTrust> epochTrust,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"purgePartitionState"}) ConcurrentHashMap<Integer, CommittedMark> replicaCommitted,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"purgePartitionState"}) ConcurrentHashMap<Integer, List<InstalledSlice>> pendingInstalls,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"forgetRaise", "purgePartitionState"}) Set<String> raisedLocally,
                    @PartitionLifetime(value = Scope.STANDING, removedBy = {"purgePartitionState"}) ConcurrentHashMap<Integer, Long> cutGenerations) {
        static Standing standing() {
            return new Standing(new ConcurrentHashMap<>(),
                                ConcurrentHashMap.newKeySet(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                ConcurrentHashMap.newKeySet(),
                                new ConcurrentHashMap<>());
        }
    }

    /// Rows whose lifetime is one repair episode, closed also when the subject goes away.
    ///
    /// @param refusedCuts        the cause of the cut refusal last reported per partition (#2084), so a repair retried against
    ///                           the same obstacle reports nothing more. Changed only under the manager's `refusedCutOrder`;
    ///                           closed when the cut resumes, when the partition's replica is released and when the stream's
    ///                           life ends.
    /// @param pendingCuts        what the repair in progress has discarded so far; reported once when it settles.
    /// @param reportedUnsettled  the range already reported while its repair was unsettled; the settled report follows only if
    ///                           the range grew.
    /// @param repairGenerationOf the repair a pending cut belongs to, so a timer scheduled for an earlier repair, which
    ///                           settled, never reports a newer one.
    record RepairEpisode(@PartitionLifetime(value = Scope.REPAIR_EPISODE, removedBy = {"closeRefusedCut", "cutResumed", "purgePartitionState"}) ConcurrentHashMap<Integer, String> refusedCuts,
                         @PartitionLifetime(value = Scope.REPAIR_EPISODE, removedBy = {"settleRepairIn", "purgePartitionState"}) ConcurrentHashMap<Integer, TailCut> pendingCuts,
                         @PartitionLifetime(value = Scope.REPAIR_EPISODE, removedBy = {"settleRepairIn", "purgePartitionState"}) ConcurrentHashMap<Integer, TailCut> reportedUnsettled,
                         @PartitionLifetime(value = Scope.REPAIR_EPISODE, removedBy = {"settleRepairIn", "purgePartitionState"}) ConcurrentHashMap<Integer, Long> repairGenerationOf) {
        static RepairEpisode repairEpisode() {
            return new RepairEpisode(new ConcurrentHashMap<>(),
                                     new ConcurrentHashMap<>(),
                                     new ConcurrentHashMap<>(),
                                     new ConcurrentHashMap<>());
        }
    }
}
