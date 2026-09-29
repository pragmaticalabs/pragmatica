// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1529: the epoch the QUIC transport reads when it reports a peer connecting or leaving. An inbound attach
/// reads it on the event loop, so it must never wait on the `KVStore` monitor, which a snapshot restore holds
/// for the whole install; it must still follow the committed incarnation.
class TransportEpochSupplierTest {
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {}
    }, new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            return null;
        }
    });
    private final LeaderTerm leaderTerm = LeaderTerm.leaderTerm(NodeId.nodeId("node-1")
                                                                      .unwrap(),
                                                                Option::none);
    private Supplier<Epoch> epoch;

    @BeforeEach
    void setUp() {
        var routes = new ArrayList<MessageRouter.Entry<?>>();

        epoch = AetherNode.transportEpochSupplier(kvStore, leaderTerm, new AtomicLong(4L), routes);
        routes.forEach(this::register);
    }

    @Test
    void transportEpoch_followsTheCommittedIncarnation_throughGenesisAndRestore() {
        assertThat(epoch.get()).as("before genesis").isEqualTo(Epoch.epoch(0L, 0L, 4L));

        ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-a", () -> "id-a")
                          .onPresent(command -> applyBatch(List.of(command)));
        assertThat(epoch.get().incarnation()).as("after genesis").isEqualTo(ClusterIncarnationValue.GENESIS);

        applyBatch(ClusterIncarnation.restoreCommands(ClusterIncarnationValue.clusterIncarnationValue("lineage-a", 5, "id-a-5"),
                                                      7,
                                                      "restore-id"));
        assertThat(epoch.get().incarnation()).as("after a restore (Remove + Put in one batch)").isEqualTo(8);
    }

    /// Another thread holds the `KVStore` monitor, as `restoreCommittedSnapshot` does for a whole install.
    /// The read must still complete at once: reading through `ClusterIncarnation.current` would park here.
    @Test
    void transportEpoch_neverWaitsOnTheKvStoreMonitor() throws Exception {
        ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-a", () -> "id-a")
                          .onPresent(command -> applyBatch(List.of(command)));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        Thread.ofPlatform()
              .start(() -> holdMonitor(held, release));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        try{
            var read = CompletableFuture.supplyAsync(epoch::get);

            assertThat(read.get(2, TimeUnit.SECONDS)
                           .incarnation()).isEqualTo(ClusterIncarnationValue.GENESIS);
        } finally{
            release.countDown();
        }
    }

    private void holdMonitor(CountDownLatch held, CountDownLatch release) {
        synchronized (kvStore) {
            held.countDown();
            awaitQuietly(release);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try{
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private void applyBatch(List<KVCommand<AetherKey>> commands) {
        kvStore.process(kvStore.createBatch(commands));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries()
             .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }
}
