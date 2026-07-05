// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager.HydrationSnapshot;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamHydration;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #265 increment 0 (hydration observability snapshot) + increment 1 (placement-role seam). Verifies
/// the snapshot reads real state (materialized-ring count, per-partition floor × rings, per-node
/// budget + over-budget flag) and that the placement-role supplier defaults to always-OWNER and is
/// settable. Separate file to avoid churning `StreamBudgetAccountingTest`'s line baseline (same
/// harness convention). Both increments are zero-behavior-change: `buildPartitions` still
/// materializes every ring, so `ringsMaterialized == partitionsDeclared` here.
class StreamHydrationSnapshotTest {

    private static final long SEGMENT = 256 * 1024L;
    private static final long HEADER = 64L;
    private static final long INDEX_ENTRY = 24L;

    /// The management-API default retention (StreamRoutes.MANAGEMENT_API_RETENTION): 10_000 count,
    /// 4 MiB bytes, 1h. 4 partitions per the stream default.
    private static StreamConfig mgmtDefault(String name) {
        var retention = RetentionPolicy.retentionPolicy(10_000, 4L * 1024 * 1024, 60 * 60 * 1000L);
        return StreamConfig.streamConfig(name, 4, retention, "latest");
    }

    private static long perPartitionFloor(long maxCount, long maxBytes) {
        return HEADER + INDEX_ENTRY * maxCount + Math.min(SEGMENT, maxBytes);
    }

    private static StreamHydration find(HydrationSnapshot snapshot, String name) {
        return snapshot.streams()
                       .stream()
                       .filter(s -> s.name().equals(name))
                       .findFirst()
                       .orElseThrow(() -> new AssertionError("stream not in snapshot: " + name));
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> streamConfigPut(StreamConfig config) {
        var key = StreamConfigKey.streamConfigKey(config.name());
        var value = StreamConfigValue.streamConfigValue(config);
        var put = new KVCommand.Put<>(key, value);
        return new ValuePut<>(put, Option.none());
    }

    @Nested
    class Snapshot {

        @Test
        void hydrationSnapshot_reflectsTwoHydratedStreams_countsAndBytes() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                manager.createStream(mgmtDefault("orders")).onFailure(_ -> fail("Expected success"));
                manager.createStream(mgmtDefault("payments")).onFailure(_ -> fail("Expected success"));

                var snapshot = manager.hydrationSnapshot();
                var streamFloor = perPartitionFloor(10_000, 4L * 1024 * 1024) * 4;

                // Per-node totals track the real budget counters.
                assertThat(snapshot.totalAllocatedBytes()).isEqualTo(streamFloor * 2);
                assertThat(snapshot.maxTotalBytes()).isEqualTo(manager.maxTotalBytes());
                assertThat(snapshot.overBudget()).isFalse();
                assertThat(snapshot.streams()).hasSize(2);

                // Per-stream: partitions × floor, rings == declared (no placement gating yet).
                for (var name : new String[] {"orders", "payments"}) {
                    var view = find(snapshot, name);
                    assertThat(view.partitionsDeclared()).isEqualTo(4);
                    assertThat(view.ringsMaterialized()).isEqualTo(4);
                    assertThat(view.floorBytesAllocated()).isEqualTo(streamFloor);
                    // Default supplier: every partition tallied OWNER.
                    assertThat(view.roleCounts().getOrDefault(Role.OWNER, 0L)).isEqualTo(4L);
                    assertThat(view.roleCounts().getOrDefault(Role.REPLICA, 0L)).isEqualTo(0L);
                    assertThat(view.roleCounts().getOrDefault(Role.NONE, 0L)).isEqualTo(0L);
                }
            } finally {
                manager.close();
            }
        }

        @Test
        void hydrationSnapshot_empty_reportsBudgetWithNoStreams() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                var snapshot = manager.hydrationSnapshot();

                assertThat(snapshot.streams()).isEmpty();
                assertThat(snapshot.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(snapshot.maxTotalBytes()).isEqualTo(64 * 1024 * 1024L);
                assertThat(snapshot.overBudget()).isFalse();
            } finally {
                manager.close();
            }
        }

        @Test
        void hydrationSnapshot_overBudget_whenFollowerOversubscribes() {
            // 1 KiB budget: the follower hydrate path over-subscribes the floor to avoid cluster
            // divergence (StreamBudgetAccountingTest.Hydration), so the pool exceeds maxTotalBytes.
            var manager = streamPartitionManager(1024);
            try {
                manager.onStreamConfigPut(streamConfigPut(mgmtDefault("committed")));

                var snapshot = manager.hydrationSnapshot();

                assertThat(snapshot.overBudget()).isTrue();
                assertThat(snapshot.totalAllocatedBytes()).isGreaterThan(snapshot.maxTotalBytes());
                assertThat(find(snapshot, "committed").ringsMaterialized()).isEqualTo(4);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class PlacementRoleSeam {

        @Test
        void defaultSupplier_reportsAllOwner() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                manager.createStream(mgmtDefault("orders")).onFailure(_ -> fail("Expected success"));

                var view = find(manager.hydrationSnapshot(), "orders");

                assertThat(view.roleCounts()).containsEntry(Role.OWNER, 4L);
                assertThat(view.roleCounts()).doesNotContainKeys(Role.REPLICA, Role.NONE);
            } finally {
                manager.close();
            }
        }

        @Test
        void customSupplier_isSettable_reflectedInRoleCounts() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                manager.createStream(mgmtDefault("mixed")).onFailure(_ -> fail("Expected success"));
                // Test-double supplier (the seam increment 2's gate will consume): even partitions
                // REPLICA, odd partitions NONE — proves the supplier is settable and consulted.
                manager.placementRoleSupplier((_, partition) -> partition % 2 == 0
                                                                ? Role.REPLICA
                                                                : Role.NONE);

                var view = find(manager.hydrationSnapshot(), "mixed");

                assertThat(view.roleCounts().getOrDefault(Role.REPLICA, 0L)).isEqualTo(2L);
                assertThat(view.roleCounts().getOrDefault(Role.NONE, 0L)).isEqualTo(2L);
                assertThat(view.roleCounts().getOrDefault(Role.OWNER, 0L)).isEqualTo(0L);
                // Materialization is unchanged by the supplier (zero behavior change): all rings built.
                assertThat(view.ringsMaterialized()).isEqualTo(view.partitionsDeclared());
            } finally {
                manager.close();
            }
        }
    }
}
