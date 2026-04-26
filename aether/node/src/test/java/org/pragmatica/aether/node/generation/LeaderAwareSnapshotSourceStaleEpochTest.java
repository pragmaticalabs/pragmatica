// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Theme M / M3 — verifies the follower-side TTL guard on cached membership snapshots:
/// when the cached snapshot's `rabiaTerm` falls more than one epoch behind the latest
/// observed term AND the local cache age exceeds [`LeaderAwareSnapshotSource#FOLLOWER_STALE_EPOCH_TTL_MS`],
/// `currentMembershipView()` returns `Option.none()` so consumers fall back to safe defaults.
class LeaderAwareSnapshotSourceStaleEpochTest {

    @Test
    void followerView_returnsCachedSnapshot_whenEpochIsCurrent() {
        var clock = new AtomicLong(1_000_000L);
        var cache = stubCache(snapshotAt(5L), 5L);
        var source = LeaderAwareSnapshotSource.leaderAwareSnapshotSource(() -> false,
                                                                         () -> Option.none(),
                                                                         cache,
                                                                         clockOf(clock));
        var view = source.currentMembershipView();
        assertThat(view.isPresent()).as("current-epoch snapshot is returned").isTrue();
    }

    @Test
    void followerView_returnsCachedSnapshot_whenOnlyOneEpochBehind() {
        var clock = new AtomicLong(1_000_000L);
        var cache = stubCache(snapshotAt(4L), 5L);
        var source = LeaderAwareSnapshotSource.leaderAwareSnapshotSource(() -> false,
                                                                         () -> Option.none(),
                                                                         cache,
                                                                         clockOf(clock));
        var view = source.currentMembershipView();
        assertThat(view.isPresent()).as("one-epoch-behind snapshot is still acceptable").isTrue();
    }

    @Test
    void followerView_returnsCachedSnapshot_whenStaleButWithinTtl() {
        var clock = new AtomicLong(1_000_000L);
        var cache = stubCache(snapshotAt(3L), 10L);
        var source = LeaderAwareSnapshotSource.leaderAwareSnapshotSource(() -> false,
                                                                         () -> Option.none(),
                                                                         cache,
                                                                         clockOf(clock));
        // First read stamps the snapshot at clock=1_000_000.
        assertThat(source.currentMembershipView().isPresent()).isTrue();
        // Advance clock by 10 s — still well within the 30 s TTL.
        clock.set(1_010_000L);
        assertThat(source.currentMembershipView().isPresent())
                .as("stale snapshot is still served while within TTL").isTrue();
    }

    @Test
    void followerView_returnsNone_whenStaleEpochAndPastTtl() {
        var clock = new AtomicLong(1_000_000L);
        var cache = stubCache(snapshotAt(3L), 10L);
        var source = LeaderAwareSnapshotSource.leaderAwareSnapshotSource(() -> false,
                                                                         () -> Option.none(),
                                                                         cache,
                                                                         clockOf(clock));
        // First read stamps the snapshot.
        assertThat(source.currentMembershipView().isPresent()).isTrue();
        // Advance clock past the 30 s TTL.
        clock.set(1_000_000L + LeaderAwareSnapshotSource.FOLLOWER_STALE_EPOCH_TTL_MS + 1L);
        assertThat(source.currentMembershipView().isPresent())
                .as("stale-epoch snapshot is dropped after TTL elapses").isFalse();
    }

    @Test
    void followerView_resetsTtlStamp_onSnapshotEpochChange() {
        var clock = new AtomicLong(1_000_000L);
        var snapshotRef = new AtomicReference<>(snapshotAt(3L));
        var observedTerm = new AtomicLong(10L);
        var cache = stubCache(snapshotRef::get, observedTerm::get);
        var source = LeaderAwareSnapshotSource.leaderAwareSnapshotSource(() -> false,
                                                                         () -> Option.none(),
                                                                         cache,
                                                                         clockOf(clock));
        // Stamp the term=3 snapshot at t=1_000_000.
        assertThat(source.currentMembershipView().isPresent()).isTrue();
        // A fresher snapshot at term=9 arrives on the cache.
        snapshotRef.set(snapshotAt(9L));
        // Even after a full TTL passes, the new snapshot is current (within 1 of observedTerm=10)
        // and the stale-epoch test does not apply.
        clock.set(1_000_000L + LeaderAwareSnapshotSource.FOLLOWER_STALE_EPOCH_TTL_MS + 1L);
        assertThat(source.currentMembershipView().isPresent())
                .as("fresher snapshot resets the TTL stamp and is served").isTrue();
    }

    private static ClusterGenerationSnapshot snapshotAt(long rabiaTerm) {
        return ClusterGenerationSnapshot.empty(rabiaTerm);
    }

    private static LongSupplier clockOf(AtomicLong holder) {
        return holder::get;
    }

    private static NodeSnapshotCache stubCache(ClusterGenerationSnapshot fixedSnapshot, long fixedTerm) {
        return stubCache(() -> fixedSnapshot, () -> fixedTerm);
    }

    private static NodeSnapshotCache stubCache(java.util.function.Supplier<ClusterGenerationSnapshot> snapshotSupplier,
                                               LongSupplier observedTermSupplier) {
        return new NodeSnapshotCache() {
            @Override public Option<ClusterGenerationSnapshot> current() {
                return Option.option(snapshotSupplier.get());
            }

            @Override public long observedRabiaTerm() {
                return observedTermSupplier.getAsLong();
            }

            @Override public Epoch observedEpoch() {
                return Epoch.epoch(observedTermSupplier.getAsLong(), 0L);
            }

            @Override public void onClusterSyncPing(org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing ping) {}
        };
    }
}
