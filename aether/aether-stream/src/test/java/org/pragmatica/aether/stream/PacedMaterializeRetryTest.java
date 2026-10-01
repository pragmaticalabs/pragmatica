// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager.ReplicaCatchupSource.CatchupView;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1805 — a materialize refused as paced (`reshuffle_concurrency`) must be re-driven until the ring exists.
/// Deterministic and in-JVM: the reconcile tick is called directly, the placement role and the catch-up view
/// are stubs the test controls. The incident: one node handed three partitions at once, two slots, the third
/// refused as paced — and the node then logged "held but not materialized" for 12 minutes because the refusal
/// lived only in a transient queue that a role flap purged, with every producer of a new attempt edge-triggered.
class PacedMaterializeRetryTest {
    private static final long FLOOR = 64L + 24L * 100 + Math.min(256 * 1024L, 64 * 1024L);
    private static final int PARTITIONS = 8;

    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();
    private final java.util.Set<String> caughtUp = ConcurrentHashMap.newKeySet();

    private static StreamConfig cfg() {
        var retention = RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L);

        return StreamConfig.streamConfig("app",
                                         PARTITIONS,
                                         retention,
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private StreamPartitionManager manager(long budget) {
        var manager = streamPartitionManager(budget);

        manager.placementRoleSupplier((stream, partition) -> roles.getOrDefault(stream + "#" + partition, Role.NONE));
        manager.replicaCatchupSource((stream, partition) -> new CatchupView(Integer.MAX_VALUE,
                                                                            caughtUp.contains(stream + "#" + partition)));
        manager.onStreamConfigPut(new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey("app"),
                                                                     StreamConfigValue.streamConfigValue(cfg())),
                                                 Option.none()));

        return manager;
    }

    private void hold(Role role, int... partitions) {
        for (var partition : partitions) {
            roles.put("app#" + partition, role);
        }
    }

    private static boolean materialized(StreamPartitionManager manager, int partition) {
        return manager.partitionBuffer("app", partition).isPresent();
    }

    private static void refuseAsPaced(StreamPartitionManager manager, int partition) {
        manager.materializePartition("app", partition)
               .onSuccess(_ -> fail("app#" + partition + " must be paced"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
    }

    private static void materializeOrFail(StreamPartitionManager manager, int partition) {
        manager.materializePartition("app", partition).onFailure(_ -> fail("app#" + partition + " should take a slot"));
    }

    /// Replica partitions with a ring that have not finished backfill — the quantity `reshuffle_concurrency` bounds.
    private long inFlightReplicas(StreamPartitionManager manager) {
        return IntStream.range(0, PARTITIONS)
                        .filter(p -> roles.get("app#" + p) == Role.REPLICA)
                        .filter(p -> materialized(manager, p))
                        .filter(p -> !caughtUp.contains("app#" + p))
                        .count();
    }

    @Test
    void burst_nPlusMany_allMaterialize_andTheBoundIsNeverExceeded() {
        var manager = manager(64 * 1024 * 1024L);
        try {
            hold(Role.REPLICA, 0, 1, 2, 3, 4);
            materializeOrFail(manager, 0);
            materializeOrFail(manager, 1);
            refuseAsPaced(manager, 2);
            refuseAsPaced(manager, 3);
            refuseAsPaced(manager, 4);

            for (var tick = 0; tick < 12 && !allMaterialized(manager); tick++) {
                assertThat(inFlightReplicas(manager)).as("pacing bound before tick %d", tick).isLessThanOrEqualTo(2L);
                completeOldestBackfill(manager);
                manager.reconcileReshuffle();
                assertThat(inFlightReplicas(manager)).as("pacing bound after tick %d", tick).isLessThanOrEqualTo(2L);
            }

            assertThat(allMaterialized(manager)).as("every refused partition eventually materializes").isTrue();
        } finally {
            manager.close();
        }
    }

    private boolean allMaterialized(StreamPartitionManager manager) {
        return IntStream.range(0, 5).allMatch(p -> materialized(manager, p));
    }

    private void completeOldestBackfill(StreamPartitionManager manager) {
        IntStream.range(0, 5)
                 .filter(p -> materialized(manager, p))
                 .filter(p -> !caughtUp.contains("app#" + p))
                 .findFirst()
                 .ifPresent(p -> caughtUp.add("app#" + p));
    }

    @Test
    void roleFlapsThroughNoneWhileQueued_partitionStillMaterializesOnceHeldAgain() {
        var manager = manager(64 * 1024 * 1024L);
        try {
            hold(Role.REPLICA, 0, 1, 2);
            materializeOrFail(manager, 0);
            materializeOrFail(manager, 1);
            refuseAsPaced(manager, 2);

            hold(Role.NONE, 2);               // ownership re-mint: this node is briefly not a holder
            manager.reconcileReshuffle();     // the stale queue entry is purged here
            hold(Role.OWNER, 2);              // the re-mint lands on this node; no new registry edge fires

            for (var i = 0; i < 3; i++) {
                manager.reconcileReshuffle();
            }

            assertThat(materialized(manager, 2)).as("a refusal forgotten at a role flap is held-but-unmaterialized forever (#1805)")
                                                 .isTrue();
        } finally {
            manager.close();
        }
    }

    @Test
    void partitionLosingOwnershipWhileQueued_isDroppedFromTheQueue_andNeverMaterialized() {
        var manager = manager(64 * 1024 * 1024L);
        try {
            hold(Role.REPLICA, 0, 1, 2);
            materializeOrFail(manager, 0);
            materializeOrFail(manager, 1);
            refuseAsPaced(manager, 2);
            hold(Role.NONE, 2);               // moved to another node for good

            caughtUp.add("app#0");
            caughtUp.add("app#1");            // both slots free
            for (var i = 0; i < StreamPartitionManager.PACED_REFUSAL_NONE_GRACE_TICKS + 2; i++) {
                manager.reconcileReshuffle();
            }

            assertThat(manager.hydrationSnapshot().materializeQueueDepth()).isEqualTo(0L);
            assertThat(materialized(manager, 2)).isFalse();

            hold(Role.REPLICA, 2);            // held again long after the grace: the ledger entry is gone
            for (var i = 0; i < 3; i++) {
                manager.reconcileReshuffle();
            }

            assertThat(materialized(manager, 2)).as("past the NONE grace the refusal is forgotten, not re-driven").isFalse();
        } finally {
            manager.close();
        }
    }

    @Test
    void queuedPartitionBecomingOwner_drainsWithoutAFreePermit() {
        var manager = manager(64 * 1024 * 1024L);
        try {
            hold(Role.REPLICA, 0, 1, 2);
            materializeOrFail(manager, 0);
            materializeOrFail(manager, 1);
            refuseAsPaced(manager, 2);

            hold(Role.OWNER, 2);              // both slots are still held by backfilling replicas
            manager.reconcileReshuffle();

            assertThat(materialized(manager, 2)).as("an owner materialize is never paced, so it needs no permit").isTrue();
        } finally {
            manager.close();
        }
    }

    @Test
    void heldButUnmaterializedPastTheThreshold_warns() {
        // Budget fits 3 rings. Two replicas take both slots, the third is paced, then an OWNER partition eats
        // the last floor so the queued head is budget-blocked: it stays held-but-unmaterialized.
        var manager = manager(3 * FLOOR);
        try {
            hold(Role.REPLICA, 0, 1, 2);
            hold(Role.OWNER, 3);
            materializeOrFail(manager, 0);
            materializeOrFail(manager, 1);
            refuseAsPaced(manager, 2);
            materializeOrFail(manager, 3);

            for (var i = 0; i < StreamPartitionManager.HELD_UNMATERIALIZED_WARN_TICKS - 1; i++) {
                manager.reconcileReshuffle();
            }
            assertThat(manager.heldUnmaterializedWarnings()).as("no WARN before the threshold").isEqualTo(0L);
            assertThat(materialized(manager, 2)).isFalse();

            manager.reconcileReshuffle();

            assertThat(manager.heldUnmaterializedWarnings()).as("WARN at the threshold").isEqualTo(1L);
            assertThat(materialized(manager, 2)).isFalse();
        } finally {
            manager.close();
        }
    }
}
