// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.node.LeaderTerm;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.metrics.PeerHealthObservation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1635 B1 (v1640): the SWIM health detector reads the node's leader epoch INLINE on every hint, and a hint
/// arrives on a network thread — the SWIM UDP loop (`channelRead0` -> the FSM -> `reportHint`) or the QUIC loop
/// (a reconnect's HEALTHY hint). The epoch comes from [AetherNode#epochSources], so it must never wait on the
/// `KVStore` monitor, which a snapshot restore holds for the whole install, and it must still carry the
/// committed incarnation.
class SwimHintEpochMonitorTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");

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

    @Test
    void swimHint_neverWaitsOnTheKvStoreMonitor_andCarriesTheCommittedIncarnation() throws Exception {
        var sources = AetherNode.epochSources(kvStore, LeaderTerm.leaderTerm(SELF, Option::none));

        sources.incarnation()
               .routeEntries()
               .forEach(this::register);
        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                                              ClusterIncarnationValue.genesis("lineage-a",
                                                                                                                              "id-a")))));
        var store = PeerObservationStore.peerObservationStore();
        var detector = runningDetector(sources.leaderEpoch(), store);
        var member = SwimMember.swimMember(PEER, MemberState.SUSPECT, 0, new InetSocketAddress("127.0.0.2", 9002));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        Thread.ofPlatform()
              .start(() -> holdMonitor(held, release));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        try{
            CompletableFuture.runAsync(() -> detector.onMemberSuspect(member))
                             .get(2, TimeUnit.SECONDS);
            CompletableFuture.supplyAsync(() -> sources.generationEpoch()
                                                       .get())
                             .get(2, TimeUnit.SECONDS);
        } finally{
            release.countDown();
        }
        assertThat(store.drainHealth()).extracting(PeerHealthObservation::observedEpochIncarnation)
                                       .as("the hint carries the committed incarnation")
                                       .containsExactly(ClusterIncarnation.current(kvStore));
        assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(ClusterIncarnationValue.GENESIS);
    }

    private CoreSwimHealthDetector runningDetector(Supplier<Epoch> epoch,
                                                   PeerObservationStore store) {
        var nodes = List.of(NodeInfo.nodeInfo(SELF,
                                              NodeAddress.nodeAddress("127.0.0.1", 9001)
                                                         .unwrap()),
                            NodeInfo.nodeInfo(PEER,
                                              NodeAddress.nodeAddress("127.0.0.2", 9001)
                                                         .unwrap()));
        var detector = CoreSwimHealthDetector.coreSwimHealthDetector(MessageRouter.mutable(),
                                                                     new TopologyConfig(SELF,
                                                                                        3,
                                                                                        timeSpan(1).seconds(),
                                                                                        timeSpan(10).seconds(),
                                                                                        nodes),
                                                                     Mockito.mock(Serializer.class),
                                                                     Mockito.mock(Deserializer.class),
                                                                     epoch,
                                                                     () -> true,
                                                                     store);
        var ctx = detector.contextForTest();
        var swim = SwimProtocol.swimProtocol(SwimConfig.DEFAULT,
                                             new StubTransport(),
                                             noListener(),
                                             SELF,
                                             new InetSocketAddress("127.0.0.1", 9101))
                               .unwrap();

        ctx.dispatch(new SwimHealthEvents.StartRequested());
        ctx.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(), GossipEncryptor.none()));

        return detector;
    }

    private void holdMonitor(CountDownLatch held, CountDownLatch release) {
        synchronized (kvStore) {
            held.countDown();
            try{
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread()
                      .interrupt();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries()
             .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }

    private static SwimMembershipListener noListener() {
        return new SwimMembershipListener() {
            @Override
            public void onMemberJoined(SwimMember member) {}

            @Override
            public void onMemberSuspect(SwimMember member) {}

            @Override
            public void onMemberFaulty(SwimMember member, boolean fromGossip) {}

            @Override
            public void onMemberLeft(NodeId nodeId) {}
        };
    }

    private static final class StubTransport implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }
    }
}
