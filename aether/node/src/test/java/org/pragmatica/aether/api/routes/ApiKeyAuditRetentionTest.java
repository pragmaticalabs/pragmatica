// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #3528: the API-key audit keeps the newest 1000 entries PER KEY, removed by the leader's sweep
/// through a consensus batch. Three simulated nodes share one commit stream (every batch a node
/// submits is applied to all three stores), so "every node" is checked against real stores, not
/// against the sweeper's own view.
class ApiKeyAuditRetentionTest {
    // Literals on purpose: the ruling's numbers, not the production constants, so a changed constant goes red.
    private static final int BOUND = 1000;
    private static final int REMOVALS_PER_SWEEP = 1000;
    private static final int NODES = 3;
    private static final String KEY_A = "key-a";
    // Its entry ids ("key-a-b-<ts>") start with KEY_A's ("key-a-"): a prefix match on the id would mix them up.
    private static final String KEY_B = "key-a-b";

    private final List<KVStore<AetherKey, AetherValue>> stores = new ArrayList<>();
    private final List<ApiKeyRoutes> routes = new ArrayList<>();
    private final AtomicInteger leader = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        IntStream.range(0, NODES)
                 .forEach(this::startNode);
    }

    @AfterEach
    void tearDown() {
        routes.forEach(ApiKeyRoutes::stop);
    }

    @Test
    void sweep_moreThanBoundWritesAcrossLeaderChange_everyNodeHoldsNewestBoundAndOtherKeyUntouched() {
        writeAudits(KEY_B, 1, 5);
        writeAudits(KEY_A, 1, 600);
        sweepOnEveryNode();

        leader.set(1);
        writeAudits(KEY_A, 601, 1001);
        sweepOnEveryNode();

        stores.forEach(store -> assertThat(timestampsOf(store, KEY_A)).containsExactlyElementsOf(range(2, 1001)));
        stores.forEach(store -> assertThat(timestampsOf(store, KEY_B)).containsExactlyElementsOf(range(1, 5)));
    }

    @Test
    void sweep_atTheBound_removesNothing() {
        writeAudits(KEY_A, 1, BOUND);
        sweepOnEveryNode();

        stores.forEach(store -> assertThat(timestampsOf(store, KEY_A)).hasSize(BOUND));
    }

    @Test
    void sweep_onAFollower_removesNothing() {
        writeAudits(KEY_A, 1, BOUND + 1);
        routes.get(1)
              .sweepExpiredKeys();

        stores.forEach(store -> assertThat(timestampsOf(store, KEY_A)).hasSize(BOUND + 1));
    }

    @Test
    void sweep_restoredUnboundedBackup_drainsInCappedSweepsAndOtherKeyUntouched() {
        var excess = 2 * REMOVALS_PER_SWEEP + 500;

        writeAudits(KEY_B, 1, 5);
        writeAudits(KEY_A, 1, BOUND + excess);

        routes.getFirst()
              .sweepExpiredKeys();
        // the capped sweep drains OLDEST first: what remains is the newest contiguous run
        assertThat(timestampsOf(stores.getFirst(), KEY_A)).containsExactlyElementsOf(range(REMOVALS_PER_SWEEP + 1,
                                                                                          BOUND + excess));

        sweepOnEveryNode();
        sweepOnEveryNode();

        stores.forEach(store -> assertThat(timestampsOf(store, KEY_A)).containsExactlyElementsOf(range(excess + 1,
                                                                                                      BOUND + excess)));
        stores.forEach(store -> assertThat(timestampsOf(store, KEY_B)).containsExactlyElementsOf(range(1, 5)));
    }

    private void startNode(int index) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var node = mock(ManageableNode.class);

        stores.add(store);
        when(node.kvStore()).thenReturn(store);
        when(node.isLeader()).thenAnswer(_ -> leader.get() == index);
        when(node.<Object> apply(anyList())).thenAnswer(invocation -> commit(invocation.getArgument(0)));
        routes.add(ApiKeyRoutes.apiKeyRoutes(() -> node, Map::of));
    }

    private Promise<List<Object>> commit(List<KVCommand<AetherKey>> commands) {
        stores.forEach(store -> store.process(store.createBatch(commands)));

        return Promise.success(List.of());
    }

    private void sweepOnEveryNode() {
        routes.forEach(ApiKeyRoutes::sweepExpiredKeys);
    }

    private void writeAudits(String keyId, int fromTimestamp, int toTimestamp) {
        commit(IntStream.rangeClosed(fromTimestamp, toTimestamp)
                        .mapToObj(timestamp -> audit(keyId, timestamp))
                        .toList());
    }

    private static KVCommand<AetherKey> audit(String keyId, int timestamp) {
        AetherKey key = ApiKeyAuditKey.apiKeyAuditKey(keyId + "-" + timestamp);
        AetherValue value = ApiKeyAuditValue.apiKeyAuditValue(keyId, ApiKeyAuditValue.ACTION_ROTATED, timestamp, "test");

        return new Put<>(key, value);
    }

    private static List<Long> timestampsOf(KVStore<AetherKey, AetherValue> store, String keyId) {
        var timestamps = new ArrayList<Long>();

        store.forEach(ApiKeyAuditKey.class,
                      ApiKeyAuditValue.class,
                      (_, value) -> addIfOf(timestamps, keyId, value));
        timestamps.sort(null);

        return timestamps;
    }

    private static void addIfOf(List<Long> timestamps, String keyId, ApiKeyAuditValue value) {
        if (value.keyId().equals(keyId)) {
            timestamps.add(value.timestamp());
        }
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to)
                   .boxed()
                   .toList();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
