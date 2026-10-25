// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.ReplicaIncarnationSource;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.ReplicaFilter;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// The wiring of the replica feed on a core (W-OBS W2): who serves, the two inbound routes, the dedicated serve pool, the ticks, the sources of
/// the cluster directory and the sender gate's governor lookup.
class ReplicaFeedAssemblyTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final NodeId PEER = new NodeId("worker-1");
    private static final NodeId GOVERNOR = new NodeId("governor-1");

    private static final Serializer NO_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    };

    private static final Deserializer NOTHING = new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    };

    private record Sent(NodeId peer, ProtocolMessage message, String thread) {}

    private static KVStore<AetherKey, AetherValue> genesisStore() {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                        NO_BYTES,
                                                        NOTHING,
                                                        ReplicaFilter.replicaFilter(),
                                                        ReplicaIncarnationSource.replicaIncarnationSource(),
                                                        CommitRing.DEFAULT_BUDGET_BYTES);

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(ClusterIncarnationKey.SINGLETON,
                                                                                                    ClusterIncarnationValue.genesis("lineage", "id-A")))),
                               1);
        store.onPhaseCompleted(1);

        return store;
    }

    /// Mutation: drop the `!worker` term, the `active` term or the `!pendingCatchUp` term.
    @Test
    void servesReplicaFeed_isTrueOnlyForAnActiveCoreThatIsNotBehind() {
        assertThat(ReplicaFeedAssembly.servesReplicaFeed(false, true, false)).isTrue();
        assertThat(ReplicaFeedAssembly.servesReplicaFeed(true, true, false)).as("a worker never serves").isFalse();
        assertThat(ReplicaFeedAssembly.servesReplicaFeed(false, false, false)).as("a core out of consensus does not").isFalse();
        assertThat(ReplicaFeedAssembly.servesReplicaFeed(false, true, true)).as("nor a core still catching up").isFalse();
    }

    /// The two inbound routes reach the server, and the server answers from the dedicated serve pool, not from the thread that delivered the
    /// message. Mutations: drop either route; build the server over `Runnable::run` instead of the pool.
    @Test
    @SuppressWarnings("unchecked")
    void replicaFeed_routesBothRequests_andAnswersOnTheServePool() throws InterruptedException {
        var sent = new CopyOnWriteArrayList<Sent>();
        var answered = new CountDownLatch(2);
        var routes = new ArrayList<MessageRouter.Entry<?>>();
        var deferred = new ArrayList<Supplier<ScheduledFuture<?>>>();

        ReplicaFeedAssembly.replicaFeed(SELF,
                                        genesisStore(),
                                        (peer, message) -> record(sent, answered, peer, message),
                                        () -> true,
                                        _ -> true,
                                        _ -> true,
                                        routes,
                                        deferred::add);

        var types = routes.stream().flatMap(entry -> entry.entries()).map(tuple -> tuple.first().getSimpleName()).toList();

        assertThat(types).containsExactlyInAnyOrder("ReplicaSubscribe", "ReplicaSnapshotRequest");
        assertThat(deferred).as("a drain tick and a heartbeat tick").hasSize(2);

        var caller = Thread.currentThread().getName();

        deliver(routes, new ReplicaSubscribe(PEER, 1L, IncarnationTag.NONE, 0L, List.of(), SubscribeMode.LIVE_ONLY));
        deliver(routes, new ReplicaSnapshotRequest(PEER, 2L, IncarnationTag.NONE, 0L, "", 0L));

        assertThat(answered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sent).extracting(Sent::thread).allSatisfy(thread -> assertThat(thread).startsWith("replica-feed-serve-").isNotEqualTo(caller));
        assertThat(sent).extracting(entry -> entry.message().getClass().getSimpleName()).contains("ReplicaEntries", "ReplicaSnapshotChunk");
    }

    /// The gate closed: a request is refused, not served. Mutation: pass `() -> true` as the server's serving gate.
    @Test
    void replicaFeed_refusesWhileTheNodeDoesNotServe() throws InterruptedException {
        var sent = new CopyOnWriteArrayList<Sent>();
        var answered = new CountDownLatch(1);
        var routes = new ArrayList<MessageRouter.Entry<?>>();
        var serving = new AtomicBoolean(false);

        ReplicaFeedAssembly.replicaFeed(SELF,
                                        genesisStore(),
                                        (peer, message) -> record(sent, answered, peer, message),
                                        serving::get,
                                        _ -> true,
                                        _ -> true,
                                        routes,
                                        _ -> {});
        deliver(routes, new ReplicaSubscribe(PEER, 1L, IncarnationTag.NONE, 0L, List.of(), SubscribeMode.LIVE_ONLY));

        assertThat(answered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sent).singleElement().satisfies(entry -> assertThat(((ReplicaRefused) entry.message()).reason()).isEqualTo(RefusalReason.NOT_SERVING));
    }

    private static void record(List<Sent> sent, CountDownLatch answered, NodeId peer, ProtocolMessage message) {
        sent.add(new Sent(peer, message, Thread.currentThread().getName()));
        answered.countDown();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Message> void deliver(List<MessageRouter.Entry<?>> routes, T message) {
        routes.stream()
              .flatMap(entry -> entry.entries())
              .filter(tuple -> tuple.first().equals(message.getClass()))
              .forEach(tuple -> ((Consumer<T>) tuple.last()).accept(message));
    }

    /// The governor lookup of the pong path's sender gate (C-4). Mutations: drop the `dissolved` filter; compare the wrong id; ignore the
    /// community of this node.
    @Test
    void isOwnGovernor_isTrueOnlyForTheAnnouncedUndissolvedGovernorOfTheOwnCommunity() {
        var announced = GovernorAnnouncementValue.governorAnnouncementValue(GOVERNOR, 3);
        Map<String, GovernorAnnouncementValue> governors = Map.of("alpha", announced, "beta", announced.withDissolved());

        assertThat(isOwnGovernor("alpha", GOVERNOR, governors)).isTrue();
        assertThat(isOwnGovernor("alpha", PEER, governors)).as("not that community's governor").isFalse();
        assertThat(isOwnGovernor("beta", GOVERNOR, governors)).as("a dissolved announcement is no governor").isFalse();
        assertThat(isOwnGovernor("gamma", GOVERNOR, governors)).as("a community with no announcement").isFalse();
        assertThat(ReplicaFeedAssembly.isOwnGovernor(SELF, GOVERNOR, _ -> Option.none(), community -> Option.option(governors.get(community))))
            .as("a node in no community").isFalse();
    }

    private static boolean isOwnGovernor(String community, NodeId sender, Map<String, GovernorAnnouncementValue> governors) {
        return ReplicaFeedAssembly.isOwnGovernor(SELF, sender, _ -> Option.some(community), name -> Option.option(governors.get(name)));
    }

    /// The directory's DHT replication is the worker projection's, field for field. Mutation: swap two fields in `directoryReplication`.
    @Test
    void directoryDhtReplication_carriesTheProjectionsFields_forAnUnconfiguredLeader() {
        var projected = WorkerHierarchyAssembly.workerDhtReplication(Option.none(), Option.none()).unwrap();
        var directory = ReplicaFeedAssembly.directoryDhtReplication(genesisStore()).unwrap();

        assertThat(directory.replicationFactor()).isEqualTo(projected.replicationFactor());
        assertThat(directory.confirmationFactor()).isEqualTo(projected.confirmationFactor());
        assertThat(directory.cacheReplicationFactor()).isEqualTo(projected.cacheReplicationFactor());
        assertThat(directory.cacheConfirmationFactor()).isEqualTo(projected.cacheConfirmationFactor());
        assertThat(directory.configVersion()).isEqualTo(projected.configVersion());
        assertThat(directory.changeVersion()).isEqualTo(projected.changeVersion());
        assertThat(directory.floorWriteQuorum()).isEqualTo(projected.floorWriteQuorum());
        assertThat(directory.floorReadQuorum()).isEqualTo(projected.floorReadQuorum());
        assertThat(directory.changeSettled()).isEqualTo(projected.changeSettled());
    }

    /// An unsettled replication change carries its own floors, and the two floors are different numbers here, so a swap of the two fields shows.
    /// Mutation: swap `floorWriteQuorum` and `floorReadQuorum` in `directoryReplication`, or drop `changeVersion`.
    @Test
    void directoryDhtReplication_carriesAnUnsettledChangesFloors_inTheirOwnFields() {
        var change = new AetherValue.DhtReplicationChangeValue(7L, 5, 3, 4, 2, 3, AetherValue.DhtReplicationStage.APPLYING, 100L, false);
        var directory = ReplicaFeedAssembly.directoryDhtReplication(Option.none(), Option.some(change)).unwrap();

        assertThat(directory.changeVersion()).isEqualTo(7L);
        assertThat(directory.floorWriteQuorum()).isEqualTo(4);
        assertThat(directory.floorReadQuorum()).isEqualTo(2);
        assertThat(directory.changeSettled()).isFalse();
    }

    /// The directory lists the tracked members that have a dial address, cores and workers alike, sorted by id. Mutation: list only cores,
    /// or include a member without an address.
    @Test
    void directoryNodes_listsTheAddressedMembers() {
        var fsm = MembershipFsm.membershipFsm();
        var core = new NodeId("core-a");
        var worker = new NodeId("worker-a");

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(core, new NodeAddress("localhost", 7001), Map.of(NodeInfo.LABEL_ROLE, "core")));
        fsm.onMemberDescriptor(NodeInfo.nodeInfo(worker, new NodeAddress("localhost", 7002), Map.of(NodeInfo.LABEL_ROLE, "worker")));

        assertThat(ReplicaFeedAssembly.directoryNodes(fsm)).extracting(NodeInfo::id).containsExactly(core, worker);
    }
}
