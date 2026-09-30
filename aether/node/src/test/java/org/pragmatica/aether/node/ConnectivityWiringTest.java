// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1529: the connectivity reporter and its epoch adapter, which the QUIC transport calls on the event loop
/// when a peer connects (inbound attach) or leaves. Neither may wait on the `KVStore` monitor — a snapshot
/// restore holds it for the whole install — and the incarnation they report must still be the committed one,
/// whichever way the node learned it: its own commit (genesis, restore, declare-genesis) or a replay after a
/// snapshot install.
class ConnectivityWiringTest {
    private static final NodeId PEER = NodeId.nodeId("peer-2")
                                             .unwrap();
    private static final LeaderValue LEADER = LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                            .unwrap(),
                                                                      1L);

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final AtomicReference<Map<Object, Object>> snapshotContent = new AtomicReference<>(Map.of());
    private final KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {}
    }, new Deserializer() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf byteBuf) {
            return (T) snapshotContent.get();
        }
    });
    private final PeerObservationStore buffer = PeerObservationStore.peerObservationStore();
    private AetherNode.ConnectivityWiring wiring;

    @BeforeEach
    void setUp() {
        var sources = AetherNode.epochSources(kvStore, LeaderTerm.leaderTerm(LEADER.leader(), Option::none));

        sources.generationCounter()
               .set(4L);
        sources.incarnation()
               .routeEntries()
               .forEach(this::register);
        wiring = AetherNode.connectivityWiring(buffer, sources.incarnation(), sources.leaderEpoch(), _ -> {}, _ -> {});
    }

    /// Another thread holds the `KVStore` monitor, as `restoreCommittedSnapshot` does for a whole install.
    /// Both reports and the adapter must still finish at once, and report the committed incarnation.
    @Test
    void connectivityReports_neverWaitOnTheKvStoreMonitor_andCarryTheCommittedIncarnation() throws Exception {
        genesis();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        Thread.ofPlatform()
              .start(() -> holdMonitor(held, release));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        try{
            var reports = CompletableFuture.runAsync(this::reportBothAndReadTheAdapter);

            reports.get(2, TimeUnit.SECONDS);
        } finally{
            release.countDown();
        }
        assertThat(buffer.drainConnectivity()).extracting(PeerConnectivityObservation::observedEpochIncarnation)
                                              .containsExactly(ClusterIncarnation.current(kvStore),
                                                               ClusterIncarnation.current(kvStore))
                                              .containsOnly(ClusterIncarnationValue.GENESIS);
    }

    /// Every commit that changes the incarnation reaches the reports: genesis, a restore (Remove + Put in one
    /// batch), and `declare-genesis` (two leader transactions).
    @Test
    void reportedIncarnation_followsGenesisRestoreAndDeclareGenesis() {
        assertThat(reportedIncarnation()).as("before genesis").isEqualTo(ClusterIncarnation.NONE);

        genesis();
        assertThat(reportedIncarnation()).as("genesis").isEqualTo(ClusterIncarnation.current(kvStore)).isEqualTo(1);

        applyBatch(ClusterIncarnation.restoreCommands(ClusterIncarnationValue.clusterIncarnationValue("lineage-a", 5, "id-a-5"),
                                                      7,
                                                      "restore-id"));
        assertThat(reportedIncarnation()).as("restore").isEqualTo(ClusterIncarnation.current(kvStore)).isEqualTo(8);

        var current = ClusterIncarnation.committed(kvStore)
                                        .unwrap();
        var next = ClusterIncarnation.superseding(current, 3, current.incarnation(), "declared-id");

        applyBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER)));
        applyBatch(ClusterIncarnation.supersedeCommands(LEADER, "declare-genesis:test", current, next));
        assertThat(reportedIncarnation()).as("declare-genesis").isEqualTo(ClusterIncarnation.current(kvStore)).isEqualTo(9);
    }

    /// A node that learns the incarnation from a snapshot install (a lagging or restarted node catching up)
    /// sees it when the engine replays the install's notifications, right after activation.
    @Test
    void reportedIncarnation_followsASnapshotInstall_onReplay() {
        genesis();
        snapshotContent.set(Map.of(ClusterIncarnationKey.clusterIncarnationKey(),
                                   ClusterIncarnationValue.clusterIncarnationValue("lineage-b", 7, "id-b-7")));

        assertThat(kvStore.restoreSnapshot(new byte[1])
                          .isSuccess()).isTrue();
        kvStore.replayNotifications();

        assertThat(reportedIncarnation()).isEqualTo(ClusterIncarnation.current(kvStore)).isEqualTo(7);
    }

    private void reportBothAndReadTheAdapter() {
        wiring.reporter()
              .onPeerConnected(PEER, 7L, 3L);
        wiring.reporter()
              .onPeerDisconnected(PEER, 7L, 4L, false);
        assertThat(wiring.epoch()
                         .term()).isZero();
        assertThat(wiring.epoch()
                         .counter()).isEqualTo(4L);
    }

    private long reportedIncarnation() {
        wiring.reporter()
              .onPeerConnected(PEER, 7L, 3L);

        return buffer.drainConnectivity()
                     .getLast()
                     .observedEpochIncarnation();
    }

    private void genesis() {
        ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-a", () -> "id-a")
                          .onPresent(command -> applyBatch(List.of(command)));
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyBatch(List<? extends KVCommand> commands) {
        kvStore.process(kvStore.createBatch((List) commands));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries()
             .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }
}
