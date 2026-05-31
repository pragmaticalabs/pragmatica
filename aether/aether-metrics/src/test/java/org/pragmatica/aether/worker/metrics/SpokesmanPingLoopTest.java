// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


class SpokesmanPingLoopTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId GOV_A = NodeId.nodeId("gov-a").unwrap();
    private static final NodeId GOV_B = NodeId.nodeId("gov-b").unwrap();

    private RecordingNetwork network;
    private AtomicLong rabiaTerm;
    private SpokesmanPingLoop loop;

    @BeforeEach
    void setUp() {
        network = new RecordingNetwork();
        rabiaTerm = new AtomicLong(7L);
        loop = SpokesmanPingLoop.spokesmanPingLoop(SELF,
                                                    network,
                                                    TimeSpan.timeSpan(1).seconds(),
                                                    rabiaTerm::get,
                                                    Map::of,
                                                    communityId -> switch (communityId){
                                                        case "pool-a" -> Option.some(GOV_A);
                                                        case "pool-b" -> Option.some(GOV_B);
                                                        default -> Option.none();
                                                    });
        loop.start();
    }

    @Nested
    class ActivationLifecycle {
        @Test
        void onSpokesmanPut_activeStatusWithCommunities_activatesLoop() {
            var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.epoch(7L, 0L), HlcTimestamp.ZERO, 1L)
                                       .withStatus(SpokesmanStatus.ACTIVE);

            loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none()));

            assertThat(loop.isActive()).isTrue();
        }

        @Test
        void onSpokesmanPut_assignedStatusActivatesAndRecordsTransition() {
            var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.ZERO, HlcTimestamp.ZERO, 1L);

            loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none()));

            assertThat(loop.isActive()).isTrue();
        }

        @Test
        void onSpokesmanPut_activeButEmptyCommunities_doesNotActivate() {
            var value = SpokesmanValue.spokesmanValue(List.of(), Epoch.ZERO, HlcTimestamp.ZERO, 1L)
                                       .withStatus(SpokesmanStatus.ACTIVE);

            loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none()));

            assertThat(loop.isActive()).isFalse();
        }

        @Test
        void onSpokesmanPut_forOtherCoreNode_isIgnored() {
            var other = NodeId.nodeId("core-2").unwrap();
            var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.ZERO, HlcTimestamp.ZERO, 1L)
                                       .withStatus(SpokesmanStatus.ACTIVE);

            loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(other), value), Option.none()));

            assertThat(loop.isActive()).isFalse();
        }

        @Test
        void onSpokesmanPut_transitionFromActiveToFailed_deactivates() {
            activateWith(List.of("pool-a"));

            var failed = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.ZERO, HlcTimestamp.ZERO, 1L)
                                        .withFailure("boom");
            loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), failed), Option.none()));

            assertThat(loop.isActive()).isFalse();
        }

        @Test
        void onSpokesmanRemove_whenActive_deactivates() {
            activateWith(List.of("pool-a"));

            loop.onSpokesmanRemove(new ValueRemove<>(new KVCommand.Remove<>(SpokesmanKey.spokesmanKey(SELF)), Option.none()));

            assertThat(loop.isActive()).isFalse();
        }
    }

    @Nested
    class PongAggregation {
        @Test
        void onClusterSyncPong_fromKnownGovernor_producesCommunityReport() {
            activateWith(List.of("pool-a"));

            var pong = new ClusterSyncPong(GOV_A, Map.of(), 7L, 7L, 3L, "READY", List.of(), List.of(), List.of());
            loop.onClusterSyncPong(pong);

            var reports = loop.currentReports();
            assertThat(reports).hasSize(1);
            assertThat(reports.getFirst().communityId()).isEqualTo("pool-a");
            assertThat(reports.getFirst().governorNodeId()).isEqualTo(GOV_A);
            assertThat(reports.getFirst().communityEpochTerm()).isEqualTo(7L);
            assertThat(reports.getFirst().communityEpochCounter()).isEqualTo(3L);
        }

        @Test
        void onClusterSyncPong_fromUnknownSender_isIgnored() {
            activateWith(List.of("pool-a"));
            var stranger = NodeId.nodeId("stranger").unwrap();

            loop.onClusterSyncPong(new ClusterSyncPong(stranger, Map.of(), 7L, 7L, 3L, "READY", List.of(), List.of(), List.of()));

            assertThat(loop.currentReports()).isEmpty();
        }

        @Test
        void onClusterSyncPong_twoGovernors_producesTwoReports() {
            activateWith(List.of("pool-a", "pool-b"));

            loop.onClusterSyncPong(new ClusterSyncPong(GOV_A, Map.of(), 7L, 7L, 3L, "READY", List.of(), List.of(), List.of()));
            loop.onClusterSyncPong(new ClusterSyncPong(GOV_B, Map.of(), 7L, 7L, 5L, "READY", List.of(), List.of(), List.of()));

            assertThat(loop.currentReports())
                  .extracting("communityId")
                  .containsExactlyInAnyOrder("pool-a", "pool-b");
        }
    }

    private void activateWith(List<String> communities) {
        var value = SpokesmanValue.spokesmanValue(communities, Epoch.epoch(7L, 0L), HlcTimestamp.ZERO, 1L)
                                    .withStatus(SpokesmanStatus.ACTIVE);
        loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none()));
    }

    private static final class RecordingNetwork implements ClusterNetwork {
        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {
            return Unit.unit();
        }

        @Override public void connect(ConnectNode connectNode) {}
        @Override public void disconnect(DisconnectNode disconnectNode) {}
        @Override public void listNodes(ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(Send send) {}
        @Override public void handleBroadcast(Broadcast broadcast) {}

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            return Unit.unit();
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        @Override public int connectedNodeCount() {return 0;}
        @Override public Set<NodeId> connectedPeers() {return Set.of();}
        @Override public Option<Server> server() {return Option.none();}
    }
}
