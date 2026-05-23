// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies Phase 2 PR-B (cluster-convergence-reconciler) wiring of the
/// `NodeReadinessTracker` into `ClusterSyncCollector.buildPong()`. The candidate set on the
/// tracker must surface on the next outgoing pong's `readyCandidate` field; clearing the
/// tracker must surface `Option.none()`.
class ClusterSyncCollectorReadinessTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId LEADER = NodeId.nodeId("leader").unwrap();

    @Test
    void buildPong_trackerEmpty_readyCandidateIsNone() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
        var tracker = NodeReadinessTracker.nodeReadinessTracker();
        collector.setReadinessTracker(tracker);

        collector.onClusterSyncPing(new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Option.none(), Set.of()));

        assertThat(network.sentPongs).hasSize(1);
        assertThat(network.sentPongs.get(0).readyCandidate()).isEqualTo(Option.none());
    }

    @Test
    void buildPong_trackerMarked_readyCandidateIsSome() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
        var tracker = NodeReadinessTracker.nodeReadinessTracker();
        collector.setReadinessTracker(tracker);
        tracker.markReady(SELF);

        collector.onClusterSyncPing(new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Option.none(), Set.of()));

        assertThat(network.sentPongs).hasSize(1);
        assertThat(network.sentPongs.get(0).readyCandidate()).isEqualTo(Option.some(SELF));
    }

    @Test
    void buildPong_trackerClearedAfterMark_readyCandidateBackToNone() {
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
        var tracker = NodeReadinessTracker.nodeReadinessTracker();
        collector.setReadinessTracker(tracker);
        tracker.markReady(SELF);
        tracker.clear();

        collector.onClusterSyncPing(new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Option.none(), Set.of()));

        assertThat(network.sentPongs).hasSize(1);
        assertThat(network.sentPongs.get(0).readyCandidate()).isEqualTo(Option.none());
    }

    @Test
    void buildPong_noTrackerWired_defaultEmitsNone() {
        // Default collector has an internal `nodeReadinessTracker()` so even without
        // `setReadinessTracker(...)` the candidate field is present and empty (not null).
        var network = new RecordingNetwork();
        var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);

        collector.onClusterSyncPing(new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Option.none(), Set.of()));

        assertThat(network.sentPongs).hasSize(1);
        assertThat(network.sentPongs.get(0).readyCandidate()).isEqualTo(Option.none());
    }

    private static final class RecordingNetwork implements ClusterNetwork {
        final List<ClusterSyncPong> sentPongs = new ArrayList<>();

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof ClusterSyncPong pong) {
                sentPongs.add(pong);
            }
            return Unit.unit();
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return Unit.unit();}
        @Override public void connect(ConnectNode connectNode) {}
        @Override public void disconnect(DisconnectNode disconnectNode) {}
        @Override public void listNodes(ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(Send send) {}
        @Override public void handleBroadcast(Broadcast broadcast) {}
        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        @Override public int connectedNodeCount() {return 0;}
        @Override public Set<NodeId> connectedPeers() {return Set.of();}
        @Override public Option<Server> server() {return Option.none();}
    }
}
