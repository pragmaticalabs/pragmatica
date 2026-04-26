// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


/// `GenerationSnapshotSource` that routes reads based on this node's current leadership.
///
/// On the leader, the authoritative snapshot lives in `HealthReconciler`; the leader never
/// receives its own `ClusterSyncPing` so its `NodeSnapshotCache` stays at `INITIAL` even when
/// the reconciler is publishing coherent snapshots to followers. Components that need to read
/// the snapshot on ANY node (including the leader) — `ClusterTopologyManager.reconcile()`,
/// `ClusterDeploymentManager.Active.activeNodes()`, management routes — must consult this
/// adapter instead of `NodeSnapshotCache` directly.
///
/// Followers fall through to the cache, which holds the last received ping's snapshot.
///
/// Theme M / M3 — follower reads carry an epoch+age TTL: if the cached snapshot's `rabiaTerm`
/// is older than `observedRabiaTerm - 1` AND the local age exceeds 30 s, the read returns
/// [`Option#none`] so consumers fall back to safer defaults rather than acting on demonstrably
/// stale membership data after a leader change.
public interface LeaderAwareSnapshotSource extends GenerationSnapshotSource {
    long FOLLOWER_STALE_EPOCH_TTL_MS = 30_000L;

    static LeaderAwareSnapshotSource leaderAwareSnapshotSource(BooleanSupplier isLeader,
                                                               Supplier<Option<ClusterGenerationSnapshot>> leaderSnapshotSupplier,
                                                               NodeSnapshotCache followerCache) {
        return leaderAwareSnapshotSource(isLeader, leaderSnapshotSupplier, followerCache, System::currentTimeMillis);
    }

    /// Full-arity factory with injectable clock — for tests that need deterministic time.
    static LeaderAwareSnapshotSource leaderAwareSnapshotSource(BooleanSupplier isLeader,
                                                               Supplier<Option<ClusterGenerationSnapshot>> leaderSnapshotSupplier,
                                                               NodeSnapshotCache followerCache,
                                                               LongSupplier clock) {
        return new LeaderAwareSnapshotSourceRecord(isLeader,
                                                   leaderSnapshotSupplier,
                                                   followerCache,
                                                   clock,
                                                   new AtomicReference<>(SnapshotStamp.UNSEEN));
    }

    /// Tuple `(observedRabiaTerm-of-snapshot, localFirstObservedAtMs)` used to age-out follower
    /// snapshots that fall behind the latest observed leader term.
    record SnapshotStamp(long rabiaTerm, long firstObservedAtMs) {
        static final SnapshotStamp UNSEEN = new SnapshotStamp(-1L, 0L);
    }
}

record LeaderAwareSnapshotSourceRecord(BooleanSupplier isLeader,
                                       Supplier<Option<ClusterGenerationSnapshot>> leaderSnapshotSupplier,
                                       NodeSnapshotCache followerCache,
                                       LongSupplier clock,
                                       AtomicReference<LeaderAwareSnapshotSource.SnapshotStamp> followerStamp)
        implements LeaderAwareSnapshotSource {
    @Override public Option<MembershipView> currentMembershipView() {
        if (isLeader.getAsBoolean()) {return leaderSnapshotSupplier.get()
                                                                       .map(LeaderAwareSnapshotSourceRecord::toMembershipView);}
        return readFollowerSnapshot().map(LeaderAwareSnapshotSourceRecord::toMembershipView);
    }

    @Override public long observedRabiaTerm() {
        if (isLeader.getAsBoolean()) {return leaderSnapshotSupplier.get().map(s -> s.epoch().rabiaTerm())
                                                                       .or(0L);}
        return followerCache.observedRabiaTerm();
    }

    /// Theme M / M3 — gate follower-cached snapshot on `(rabiaTerm, firstObservedAtMs)` TTL. If
    /// the snapshot's term is more than one epoch behind the latest observed term AND the local
    /// age exceeds [`#FOLLOWER_STALE_EPOCH_TTL_MS`], drop the snapshot.
    private Option<ClusterGenerationSnapshot> readFollowerSnapshot() {
        var snapshotOpt = followerCache.current();
        if (snapshotOpt.isEmpty()) {return snapshotOpt;}
        var snapshot = snapshotOpt.unwrap();
        var snapshotTerm = snapshot.epoch().rabiaTerm();
        var stamp = followerStamp.updateAndGet(prev -> refreshStamp(prev, snapshotTerm));
        if (isStaleEpochAndExpired(stamp, snapshotTerm)) {return Option.none();}
        return snapshotOpt;
    }

    private LeaderAwareSnapshotSource.SnapshotStamp refreshStamp(LeaderAwareSnapshotSource.SnapshotStamp prev,
                                                                 long snapshotTerm) {
        if (prev.rabiaTerm() == snapshotTerm) {return prev;}
        return new LeaderAwareSnapshotSource.SnapshotStamp(snapshotTerm, clock.getAsLong());
    }

    private boolean isStaleEpochAndExpired(LeaderAwareSnapshotSource.SnapshotStamp stamp, long snapshotTerm) {
        var observedTerm = followerCache.observedRabiaTerm();
        var epochsBehind = observedTerm - snapshotTerm;
        if (epochsBehind <= 1L) {return false;}
        var ageMs = clock.getAsLong() - stamp.firstObservedAtMs();
        return ageMs > FOLLOWER_STALE_EPOCH_TTL_MS;
    }

    private static MembershipView toMembershipView(ClusterGenerationSnapshot snapshot) {
        return new SnapshotMembershipView(snapshot.coreMembers(),
                                          snapshot.desiredCoreSize(),
                                          snapshot.nodesWithoutSlices());
    }
}
