// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 #1811 (spec 14.3 item 1): the materialization drain skips a queued head whose floor no longer fits the off-heap
/// budget instead of stopping its class behind it; the skipped head keeps its place and is offered first on the next pass. The
/// reconcile tick is called directly; the placement role and the catch-up view are stubs the test controls.
///
/// The scene: two reshuffle slots, both held by replica backfills of `app` (partitions 0 and 1). `big#0` (a large floor) and
/// then `app#2` and `app#4` (small floors) are refused as paced and queued, each while it fitted. An owner ring of `filler` then
/// takes budget, so the queued head `big#0` no longer fits while `app#2` still does. Budget: `3 * small + big + 1000` bytes.
class MaterializationSchedulerTest {
    private static final int PARTITIONS = 8;
    private static final long SMALL = OffHeapRingBuffer.floorBytes(100, 64 * 1024L);
    private static final long BIG = OffHeapRingBuffer.floorBytes(1000, 256 * 1024L);
    private static final long BUDGET = 3 * SMALL + BIG + 1000L;

    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();
    private final Set<String> caughtUp = ConcurrentHashMap.newKeySet();
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(BUDGET);
        manager.placementRoleSupplier((stream, partition) -> roles.getOrDefault(stream + "#" + partition, Role.NONE));
        manager.replicaCatchupSource((stream, partition) -> new CatchupView(Integer.MAX_VALUE, caughtUp.contains(stream + "#" + partition)));
        put(cfg("app", PARTITIONS, 100, 64 * 1024L));
        put(cfg("big", 1, 1000, 256 * 1024L));

        hold(Role.REPLICA, "app#0", "app#1", "big#0", "app#2", "app#4");
        materializeOrFail("app", 0);
        materializeOrFail("app", 1);
        refuseAsPaced("big", 0);
        refuseAsPaced("app", 2);
        refuseAsPaced("app", 4);

        hold(Role.OWNER, "filler#0");
        put(cfg("filler", 1, 1000, 256 * 1024L));
        assertThat(materialized("filler", 0)).as("fixture: the owner ring took the budget the queued head needs").isTrue();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// #2201 NT-197. RED at `15ac4ef4da`: the head `big#0` cannot get budget, so the drain stops the app class there and
    /// `app#2`, which fits, waits behind it.
    @Test
    void headNoLongerFits_nextFittingPartitionMaterializes() {
        caughtUp.add("app#0");

        manager.reconcileReshuffle();

        assertThat(materialized("app", 2)).as("the fitting partition behind the head is taken").isTrue();
        assertThat(materialized("big", 0)).as("the budget is never bypassed").isFalse();
    }

    /// #2201 NT-198. The skipped head keeps its position: once budget frees, the next pass offers it FIRST, ahead of `app#4`,
    /// which also fits and queued behind it, and only one slot is free. RED at `15ac4ef4da` at the first pass (nothing behind
    /// the head is taken); the control is the second pass, where the head is admitted as soon as it fits.
    @Test
    void skippedHead_isReofferedFirst_onTheNextPass() {
        caughtUp.add("app#0");
        manager.reconcileReshuffle();
        assertThat(materialized("app", 2)).as("first pass: the head is skipped, app#2 is taken").isTrue();
        assertThat(materialized("big", 0)).isFalse();

        manager.destroyStream("filler").onFailure(cause -> fail(cause.message()));
        caughtUp.add("app#1");
        manager.reconcileReshuffle();

        assertThat(materialized("big", 0)).as("second pass: the skipped head is offered first and now fits").isTrue();
        assertThat(materialized("app", 4)).as("one slot was free, and the head took it").isFalse();
    }

    private void put(StreamConfig config) {
        manager.onStreamConfigPut(new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                                     StreamConfigValue.streamConfigValue(config)),
                                                 Option.none()));
    }

    private void hold(Role role, String... refs) {
        for (var ref : refs) {
            roles.put(ref, role);
        }
    }

    private boolean materialized(String stream, int partition) {
        return manager.partitionBuffer(stream, partition).isPresent();
    }

    private void materializeOrFail(String stream, int partition) {
        manager.materializePartition(stream, partition).onFailure(cause -> fail(stream + "#" + partition + " should take a slot: " + cause.message()));
    }

    private void refuseAsPaced(String stream, int partition) {
        manager.materializePartition(stream, partition)
               .onSuccess(_ -> fail(stream + "#" + partition + " must be paced"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReshufflePaced.class));
    }

    private static StreamConfig cfg(String name, int partitions, int maxCount, long maxBytes) {
        return StreamConfig.streamConfig(name,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(maxCount, maxBytes, 3_600_000L),
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
