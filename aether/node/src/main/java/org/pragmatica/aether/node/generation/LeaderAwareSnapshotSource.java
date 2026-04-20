// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;

import java.util.function.BooleanSupplier;
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
public interface LeaderAwareSnapshotSource extends GenerationSnapshotSource {
    static LeaderAwareSnapshotSource leaderAwareSnapshotSource(BooleanSupplier isLeader,
                                                               Supplier<Option<ClusterGenerationSnapshot>> leaderSnapshotSupplier,
                                                               NodeSnapshotCache followerCache) {
        return new LeaderAwareSnapshotSourceRecord(isLeader, leaderSnapshotSupplier, followerCache);
    }
}

record LeaderAwareSnapshotSourceRecord(BooleanSupplier isLeader,
                                       Supplier<Option<ClusterGenerationSnapshot>> leaderSnapshotSupplier,
                                       NodeSnapshotCache followerCache) implements LeaderAwareSnapshotSource {
    @Override public Option<MembershipView> currentMembershipView() {
        if (isLeader.getAsBoolean()) {return leaderSnapshotSupplier.get().map(LeaderAwareSnapshotSourceRecord::toMembershipView);}
        return followerCache.currentMembershipView();
    }

    @Override public long observedRabiaTerm() {
        if (isLeader.getAsBoolean()) {return leaderSnapshotSupplier.get().map(s -> s.epoch().rabiaTerm())
                                                                                                .or(0L);}
        return followerCache.observedRabiaTerm();
    }

    private static MembershipView toMembershipView(ClusterGenerationSnapshot snapshot) {
        return new SnapshotMembershipView(snapshot.coreMembers(), snapshot.desiredCoreSize());
    }
}
