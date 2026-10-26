// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.DirectoryFixtures.RecordingNetwork;
import org.pragmatica.aether.metrics.fsm.ClusterSyncContext;
import org.pragmatica.aether.metrics.fsm.ClusterSyncState;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.metrics.DirectoryFixtures.CORE;
import static org.pragmatica.aether.metrics.DirectoryFixtures.DHT;
import static org.pragmatica.aether.metrics.DirectoryFixtures.GOVERNOR;
import static org.pragmatica.aether.metrics.DirectoryFixtures.LEADER;
import static org.pragmatica.aether.metrics.DirectoryFixtures.SELF;
import static org.pragmatica.aether.metrics.DirectoryFixtures.directory;
import static org.pragmatica.aether.metrics.DirectoryFixtures.pong;
import static org.pragmatica.aether.metrics.DirectoryFixtures.version;

/// The leader's side of the cluster directory and the pair every node reports (W-OBS W2): attached only to the authority-bearing ping, per peer
/// until that peer's pong reports the current pair (C-5), and reported by every node on every ping (F-1, NT-O47).
class ClusterSyncLeaderDirectoryTest {
    private static final Epoch EPOCH = Epoch.epoch(1L, 5L, 0L);
    private static final NodeId STRANGER_PRODUCER = NodeId.nodeId("producer-source").unwrap();

    private record Node(ClusterSyncContext context, ClusterSyncCollector collector, RecordingNetwork network) {}

    private static Node node(NodeId self, boolean leader, RecordingNetwork network) {
        var collector = ClusterSyncCollector.clusterSyncCollector(self, network);
        var ctxRef = new AtomicReference<ClusterSyncContext>();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> factory = fsm -> {
            var ctx = new ClusterSyncContext(fsm,
                                             self,
                                             network,
                                             collector,
                                             TimeSpan.timeSpan(1).hours(),
                                             () -> 5L,
                                             3,
                                             () -> EPOCH,
                                             PeerObservationStore.peerObservationStore(),
                                             PeriodicObservationConfig.defaultConfig(),
                                             () -> leader,
                                             () -> Set.of());

            ctxRef.set(ctx);

            return ctx.dormant();
        };

        Fsm.fsm("leader-directory-test-" + self.id(), factory);

        var context = ctxRef.get();

        collector.setMetricsProducerEligibility(_ -> true);
        context.setPingTargetEligibility(_ -> true);
        context.setMetricsRecipient(CORE::equals);

        return new Node(context, collector, network);
    }

    private static List<NodeInfo> nodes(String... ids) {
        return java.util.Arrays.stream(ids)
                               .map(id -> NodeInfo.nodeInfo(NodeId.nodeId(id).unwrap(), new org.pragmatica.net.tcp.NodeAddress("localhost", 7000)))
                               .toList();
    }

    private static boolean carriesDirectory(RecordingNetwork network, NodeId peer, int index) {
        return network.sentTo(peer, ClusterSyncPing.class).get(index).directory().isPresent();
    }

    /// C-5. The directory rides the leader's authority ping to a core and to a governor, is repeated until the peer's pong reports the current
    /// pair, and a changed content is a new generation that is sent again. Mutations: attach regardless of the acknowledged pair (drop the
    /// `isStrictlyAfter(acknowledgedDirectory...)` filter); never update the acknowledged map (the pong listener).
    @Test
    void theLeader_attachesTheDirectoryUntilThePeerAcknowledgesTheCurrentPair() {
        var network = new RecordingNetwork(Set.of(CORE, GOVERNOR));
        var leader = node(LEADER, true, network);
        var content = new AtomicReference<>(nodes("a"));

        leader.context().setDirectorySources(content::get, () -> Result.success(DHT));
        leader.context().broadcastPing(EPOCH, 5L);
        leader.context().broadcastPing(EPOCH, 5L);

        assertThat(network.sentTo(CORE, ClusterSyncPing.class)).as("the core's authority ping carries it, again while unacknowledged").allSatisfy(ping -> {
            assertThat(ping.carriesAuthority()).isTrue();
            assertThat(ping.directory().isPresent()).isTrue();
        });
        assertThat(network.sentTo(GOVERNOR, ClusterSyncPing.class)).as("the governor's ping is authority-bearing too").allSatisfy(ping -> {
            assertThat(ping.carriesAuthority()).isTrue();
            assertThat(ping.directory().isPresent()).isTrue();
        });
        assertThat(leader.collector().heldDirectoryVersion()).as("the leader holds and reports what it produced").isEqualTo(version(1, 5, 0, 1));

        leader.collector().onClusterSyncPong(pong(CORE, version(1, 5, 0, 1), Option.none()));
        leader.context().broadcastPing(EPOCH, 5L);

        assertThat(carriesDirectory(network, CORE, 2)).as("the core reported the current pair: not sent again").isFalse();
        assertThat(carriesDirectory(network, GOVERNOR, 2)).as("the governor has not: still sent").isTrue();

        content.set(nodes("a", "b"));
        leader.context().broadcastPing(EPOCH, 5L);

        assertThat(network.sentTo(CORE, ClusterSyncPing.class).get(3).directory().unwrap().generation()).as("changed content, next generation, sent again").isEqualTo(2);
        assertThat(leader.collector().heldDirectoryVersion()).isEqualTo(version(1, 5, 0, 2));
    }

    /// C-5. A send of more than 128 producers is several pings; only the first is authority-bearing, so only it carries the directory.
    /// Mutation: drop the `carriesAuthority()` filter in `withDirectoryFor` (every batch carries the 25 KiB directory, and a non-authority
    /// ping carries an unfenced one).
    @Test
    void ofAMultiBatchSend_onlyTheAuthorityBearingPingCarriesTheDirectory() {
        var network = new RecordingNetwork(Set.of(CORE));
        var leader = node(LEADER, true, network);
        var observations = new java.util.HashMap<NodeId, org.pragmatica.cluster.metrics.MetricObservation>();

        for (var index = 0; index < ClusterSyncContext.MAX_METRIC_PRODUCERS_PER_PING + 2; index++) {
            observations.put(NodeId.nodeId("producer-" + index).unwrap(),
                             org.pragmatica.cluster.metrics.MetricObservation.metricObservation(1L, 1L, System.currentTimeMillis(), java.util.Map.of()));
        }

        leader.context().setPeerMetricsForwarding(() -> true);
        leader.context().setDirectorySources(() -> nodes("a"), () -> Result.success(DHT));
        leader.collector().onClusterSyncPing(new ClusterSyncPing(STRANGER_PRODUCER,
                                                                 observations,
                                                                 5L,
                                                                 1L,
                                                                 5L,
                                                                 0L,
                                                                 Set.of(),
                                                                 Set.of(),
                                                                 java.util.Map.of(),
                                                                 Set.of(),
                                                                 false,
                                                                 false));
        leader.context().broadcastPing(EPOCH, 5L);

        var pings = network.sentTo(CORE, ClusterSyncPing.class);

        assertThat(pings).as("two batches").hasSize(2);
        assertThat(pings.get(0).carriesAuthority()).isTrue();
        assertThat(pings.get(0).directory().isPresent()).isTrue();
        assertThat(pings.get(1).carriesAuthority()).isFalse();
        assertThat(pings.get(1).directory().isPresent()).as("a non-authority ping never carries a directory").isFalse();
    }

    /// C-5. A node that is not the leader never attaches a directory, even with a producer wired and a directory held: there is no second,
    /// unfenced path. It still reports the pair it holds (F-1). Mutation: drop the `isLeader()` check in `leaderDirectory`.
    @Test
    void aNonLeader_neverAttaches_butReportsThePairItHolds() {
        var network = new RecordingNetwork(Set.of(CORE));
        var member = node(SELF, false, network);
        var held = directory(1, 5, 0, 4);

        member.collector().setPingAuthority(CORE::equals, LEADER::equals);
        member.context().setDirectorySources(() -> nodes("a"), () -> Result.success(DHT));
        member.collector().onClusterSyncPong(pong(CORE, held.version(), Option.some(held)));
        member.context().broadcastPing(EPOCH, 5L);

        assertThat(network.sentTo(CORE, ClusterSyncPing.class)).singleElement().satisfies(ping -> {
            assertThat(ping.carriesAuthority()).isFalse();
            assertThat(ping.directory().isPresent()).isFalse();
            assertThat(ping.observedDirectoryGeneration()).as("F-1: the pair it holds").isEqualTo(held.version());
        });
    }

    /// C-5. A node that is not the leader holds nothing it did not receive: it does not produce a directory of its own, even with the content
    /// sources wired. Mutation: drop the `isLeader()` check in `leaderDirectory` (the non-leader produces and installs its own).
    @Test
    void aNonLeader_doesNotProduceADirectoryOfItsOwn() {
        var network = new RecordingNetwork(Set.of(CORE));
        var member = node(SELF, false, network);

        member.context().setDirectorySources(() -> nodes("a"), () -> Result.success(DHT));
        member.context().broadcastPing(EPOCH, 5L);

        assertThat(member.collector().heldDirectory().isPresent()).isFalse();
        assertThat(member.collector().heldDirectoryVersion()).isEqualTo(DirectoryVersion.NONE);
    }

    /// NT-O47 (F-1). Once a member reports the pair it holds, no responder attaches a directory to any further pong. The member, a real
    /// context over a real collector, pings a responder that holds the directory; the responder's pong installs it; the member's next pings
    /// report the pair and the responder's next pongs carry none. Mutation: report `DirectoryVersion.NONE` in `buildMetricsPing` (the responder
    /// attaches every time), which is the spec's "report the pair only when LIVE".
    @Test
    void afterAMemberReportsThePair_noResponderAttachesAgain() {
        var memberCollector = new AtomicReference<ClusterSyncCollector>();
        var responderCollector = new AtomicReference<ClusterSyncCollector>();
        var responderNetwork = new RecordingNetwork(Set.of(SELF), (_, message) -> deliverPong(memberCollector.get(), message));
        var memberNetwork = new RecordingNetwork(Set.of(CORE), (_, message) -> deliverPing(responderCollector.get(), message));
        var member = node(SELF, false, memberNetwork);
        var responder = ClusterSyncCollector.clusterSyncCollector(CORE, responderNetwork);

        memberCollector.set(member.collector());
        responderCollector.set(responder);
        responder.installProducedDirectory(directory(1, 5, 0, 7));
        member.collector().setPingAuthority(CORE::equals, LEADER::equals);
        member.context().broadcastPing(EPOCH, 5L);

        assertThat(member.collector().heldDirectoryVersion()).as("installed from the responder's first pong").isEqualTo(version(1, 5, 0, 7));

        member.context().broadcastPing(EPOCH, 5L);
        member.context().broadcastPing(EPOCH, 5L);

        var pongs = responderNetwork.sentTo(SELF, ClusterSyncPong.class);

        assertThat(pongs).hasSize(3);
        assertThat(pongs).extracting(pong -> pong.directory().isPresent()).as("attached once, then never again").containsExactly(true, false, false);
        assertThat(pongs).extracting(ClusterSyncPong::observedDirectoryGeneration).containsOnly(version(1, 5, 0, 7));
    }

    private static void deliverPing(ClusterSyncCollector responder, org.pragmatica.consensus.ProtocolMessage message) {
        if (message instanceof ClusterSyncPing ping) {
            responder.onClusterSyncPing(ping);
        }
    }

    private static void deliverPong(ClusterSyncCollector member, org.pragmatica.consensus.ProtocolMessage message) {
        if (message instanceof ClusterSyncPong pong) {
            member.onClusterSyncPong(pong);
        }
    }
}
