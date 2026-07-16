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

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #265 increments 0-3: hydration observability snapshot (0), placement-role seam (1), placement-gated
/// ring materialization (2), and the budget reject/defer reframe (3). Verifies the snapshot reads real
/// state (materialized-ring count, per-partition floor × rings, per-node budget + over-budget flag +
/// deferred-partition count), that the placement-role supplier defaults to always-OWNER and is settable,
/// and — increment 2 — that a partition ring materializes IFF this node is its OWNER/REPLICA: a
/// non-replica holds the stream metadata-only (zero off-heap bytes), and a deferred partition
/// materializes on the reconcile hook / owner-append safety valve. Increment 3: a follower that cannot
/// admit a held floor no longer over-subscribes — it holds the stream metadata-only and reports the held
/// partitions as DEFERRED. Separate file to avoid churning `StreamBudgetAccountingTest`'s line baseline
/// (same harness convention). The default-supplier Snapshot tests materialize every ring
/// (`ringsMaterialized == partitionsDeclared`) because the bare manager reports OWNER for every partition.
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

    /// The same mgmt-default retention over 16 partitions — the acceptance-in-miniature stream for the
    /// placement-gating proofs.
    private static StreamConfig sixteenPartition(String name) {
        var retention = RetentionPolicy.retentionPolicy(10_000, 4L * 1024 * 1024, 60 * 60 * 1000L);
        return StreamConfig.streamConfig(name, 16, retention, "latest");
    }

    /// Fixed placement double: partitions 0-2 OWNER, 3-4 REPLICA, the rest NONE — 5 held, 11 metadata-only.
    private static Role ownerThreeReplicaTwo(String stream, int partition) {
        return partition < 3
               ? Role.OWNER
               : partition < 5
                 ? Role.REPLICA
                 : Role.NONE;
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
        void hydrationSnapshot_deferred_whenFollowerCannotAdmitFloor() {
            // 1 KiB budget: per spec §6 (increment 3) the follower NO LONGER over-subscribes — the held
            // floor doesn't fit, so the stream hydrates metadata-only with its partitions DEFERRED. The
            // pool never exceeds the cap; the snapshot's deferred count is the budget-defer sensor.
            var manager = streamPartitionManager(1024);
            try {
                manager.onStreamConfigPut(streamConfigPut(mgmtDefault("committed")));

                var snapshot = manager.hydrationSnapshot();

                assertThat(snapshot.overBudget()).isFalse();
                assertThat(snapshot.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(snapshot.totalAllocatedBytes()).isLessThanOrEqualTo(snapshot.maxTotalBytes());
                assertThat(snapshot.deferredPartitions()).isEqualTo(4L);
                var view = find(snapshot, "committed");
                assertThat(view.ringsMaterialized()).isEqualTo(0);
                assertThat(view.partitionsDeferred()).isEqualTo(4);
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
                // Supplier set AFTER create: even partitions REPLICA, odd NONE — proves the supplier is
                // settable and drives the LIVE roleCounts tally.
                manager.placementRoleSupplier((_, partition) -> partition % 2 == 0
                                                                ? Role.REPLICA
                                                                : Role.NONE);

                var view = find(manager.hydrationSnapshot(), "mixed");

                assertThat(view.roleCounts().getOrDefault(Role.REPLICA, 0L)).isEqualTo(2L);
                assertThat(view.roleCounts().getOrDefault(Role.NONE, 0L)).isEqualTo(2L);
                assertThat(view.roleCounts().getOrDefault(Role.OWNER, 0L)).isEqualTo(0L);
                // Materialization was decided at CREATE time (default all-OWNER), so all 4 rings are built;
                // setting a NONE-bearing supplier AFTER does NOT release them — a ring, once materialized,
                // STAYS until release (increment 5). roleCounts reflects the new supplier; ringsMaterialized
                // reflects the create-time decision. This is the documented gate/release asymmetry.
                assertThat(view.ringsMaterialized()).isEqualTo(view.partitionsDeclared());
            } finally {
                manager.close();
            }
        }
    }

    /// #265 increment 2 — placement-gated ring materialization (THE memory win). A partition's
    /// `OffHeapRingBuffer` is built IFF this node is its OWNER/REPLICA; a non-replica partition is
    /// metadata-only (no ring, zero off-heap bytes). The supplier is set BEFORE the config-put so the
    /// gate is exercised at hydrate time. Budget accounting follows the MATERIALIZED count, not the
    /// declared count.
    @Nested
    class PlacementGatedMaterialization {

        /// The acceptance-in-miniature (non-replica leg): a node that answers NONE for all 16 partitions
        /// of a stream processes the committed config Put → the stream metadata is present (a follower
        /// must not diverge from committed config) but NO ring is materialized and ZERO off-heap bytes are
        /// reserved. This is the O(streams × partitions × nodes) blow-up eliminated on non-replicas.
        @Test
        void nonReplicaForAllPartitions_holdsMetadataOnly_allocatesZeroBytes() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                manager.placementRoleSupplier((_, _) -> Role.NONE);

                manager.onStreamConfigPut(streamConfigPut(sixteenPartition("orders16")));

                assertThat(manager.streamInfo("orders16").isPresent()).isTrue();
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);

                var view = find(manager.hydrationSnapshot(), "orders16");
                assertThat(view.partitionsDeclared()).isEqualTo(16);
                assertThat(view.ringsMaterialized()).isEqualTo(0);
                assertThat(view.floorBytesAllocated()).isEqualTo(0L);
                assertThat(view.roleCounts().getOrDefault(Role.NONE, 0L)).isEqualTo(16L);
            } finally {
                manager.close();
            }
        }

        /// The acceptance-in-miniature (replica leg): a node that is OWNER of 3 partitions and REPLICA of
        /// 2 (NONE for the other 11) materializes EXACTLY 5 rings and reserves EXACTLY 5 × the per-partition
        /// floor — not 16 × floor. Budget follows the materialized count.
        @Test
        void ownerThreeReplicaTwo_materializesExactlyFiveFloors() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            try {
                manager.placementRoleSupplier(PlacementGatedMaterialization::role);

                manager.onStreamConfigPut(streamConfigPut(sixteenPartition("orders16")));

                var perPartition = perPartitionFloor(10_000, 4L * 1024 * 1024);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(5 * perPartition);

                var view = find(manager.hydrationSnapshot(), "orders16");
                assertThat(view.partitionsDeclared()).isEqualTo(16);
                assertThat(view.ringsMaterialized()).isEqualTo(5);
                assertThat(view.floorBytesAllocated()).isEqualTo(5 * perPartition);
                assertThat(view.roleCounts().getOrDefault(Role.OWNER, 0L)).isEqualTo(3L);
                assertThat(view.roleCounts().getOrDefault(Role.REPLICA, 0L)).isEqualTo(2L);
                assertThat(view.roleCounts().getOrDefault(Role.NONE, 0L)).isEqualTo(11L);
            } finally {
                manager.close();
            }
        }

        private static Role role(String stream, int partition) {
            return ownerThreeReplicaTwo(stream, partition);
        }

        /// Deferred-then-reconciled (spec §5.4). Placement is unknown at config-put time (`roleFor` NONE),
        /// so the stream hydrates metadata-only with zero bytes. When the role for partition 0 resolves to
        /// OWNER and the reconcile hook fires (simulated by `materializePartition`), the ring materializes
        /// and exactly one floor is reserved. The hook is IDEMPOTENT — firing again does not double-allocate.
        @Test
        void deferredThenReconciled_materializesOnHook_idempotently() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var partition0 = new AtomicReference<>(Role.NONE);
            try {
                manager.placementRoleSupplier((_, partition) -> partition == 0
                                                                ? partition0.get()
                                                                : Role.NONE);

                manager.onStreamConfigPut(streamConfigPut(sixteenPartition("deferred")));

                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);
                assertThat(find(manager.hydrationSnapshot(), "deferred").ringsMaterialized()).isEqualTo(0);

                // Role resolves; the materialize-on-reconcile hook fires.
                partition0.set(Role.OWNER);
                manager.materializePartition("deferred", 0).onFailure(_ -> fail("materialize-on-reconcile should succeed"));

                var perPartition = perPartitionFloor(10_000, 4L * 1024 * 1024);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(perPartition);
                assertThat(find(manager.hydrationSnapshot(), "deferred").ringsMaterialized()).isEqualTo(1);

                // Idempotent: the hook re-firing (or a steady-state reconcile) does not re-allocate.
                manager.materializePartition("deferred", 0).onFailure(_ -> fail("idempotent materialize"));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(perPartition);
                assertThat(find(manager.hydrationSnapshot(), "deferred").ringsMaterialized()).isEqualTo(1);
            } finally {
                manager.close();
            }
        }

        /// Owner-append safety valve (spec §5.4). A publish that lands on the owner before the reconcile
        /// hook has materialized the ring builds it lazily rather than failing; a publish to a genuine
        /// non-replica (NONE) partition is rejected with the named `PARTITION_NOT_LOCAL` so the caller
        /// forwards to a holder. Only the append path materializes — never the read path.
        @Test
        void ownerAppendSafetyValve_materializesLazily_nonOwnerRejectedNamed() {
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            var partition0 = new AtomicReference<>(Role.NONE);
            try {
                manager.placementRoleSupplier((_, partition) -> partition == 0
                                                                ? partition0.get()
                                                                : Role.NONE);

                manager.onStreamConfigPut(streamConfigPut(sixteenPartition("valve")));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);

                // Non-owner (NONE) append is rejected with the route-away named cause — no materialize.
                manager.publishLocal("valve", 1, new byte[8], 1L)
                       .onSuccess(_ -> fail("append to a non-replica partition must be rejected"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL));
                assertThat(manager.totalAllocatedBytes()).isEqualTo(0L);

                // Role resolves to OWNER; a publish landing before the reconcile hook materializes lazily.
                partition0.set(Role.OWNER);
                manager.publishLocal("valve", 0, new byte[8], 2L).onFailure(_ -> fail("owner append should materialize + succeed"));

                var perPartition = perPartitionFloor(10_000, 4L * 1024 * 1024);
                assertThat(manager.totalAllocatedBytes()).isEqualTo(perPartition);
                assertThat(find(manager.hydrationSnapshot(), "valve").ringsMaterialized()).isEqualTo(1);
            } finally {
                manager.close();
            }
        }
    }
}
