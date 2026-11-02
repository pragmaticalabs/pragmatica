// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import org.pragmatica.cluster.metrics.ClusterDirectory;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

/// Shared fixtures of the cluster-directory tests: directories, a network that records what each node sends, and the ping/pong builders.
final class DirectoryFixtures {
    private DirectoryFixtures() {}

    static final NodeId SELF = NodeId.nodeId("self").unwrap();
    static final NodeId LEADER = NodeId.nodeId("leader").unwrap();
    static final NodeId CORE = NodeId.nodeId("core").unwrap();
    static final NodeId GOVERNOR = NodeId.nodeId("governor").unwrap();
    static final NodeId STRANGER = NodeId.nodeId("stranger").unwrap();

    static final DhtReplication DHT = new DhtReplication(3, 2, 3, 2, 1L, -1L, 0, 0, true);

    static ClusterDirectory directory(long epochIncarnation, long epochTerm, long epochCounter, long generation) {
        return new ClusterDirectory(generation, epochIncarnation, epochTerm, epochCounter, List.<NodeInfo> of(), DHT, Option.none());
    }

    static DirectoryVersion version(long epochIncarnation, long epochTerm, long epochCounter, long generation) {
        return DirectoryVersion.directoryVersion(epochIncarnation, epochTerm, epochCounter, generation);
    }

    /// A pong from `sender` that reports `held` and attaches `attached` (when present).
    static ClusterSyncPong pong(NodeId sender, DirectoryVersion held, Option<ClusterDirectory> attached) {
        return new ClusterSyncPong(sender,
                                   org.pragmatica.cluster.metrics.MetricObservation.metricObservation(1L, 1L, System.currentTimeMillis(), Map.of()),
                                   1L,
                                   1L,
                                   0L,
                                   0L,
                                   0L,
                                   "READY",
                                   List.of(),
                                   List.of(),
                                   List.of(),
                                   Option.none(),
                                   held,
                                   attached);
    }

    /// A ping from `sender`; `authority` makes it the authority-bearing one, `asker` is the pair the sender reports.
    static ClusterSyncPing ping(NodeId sender,
                                long rabiaTerm,
                                boolean authority,
                                DirectoryVersion asker,
                                Option<ClusterDirectory> attached) {
        return new ClusterSyncPing(sender,
                                   Map.of(),
                                   rabiaTerm,
                                   1L,
                                   rabiaTerm,
                                   0L,
                                   Set.of(),
                                   Set.of(),
                                   Map.of(),
                                   Set.of(),
                                   true,
                                   authority,
                                   asker,
                                   attached);
    }

    /// Records every message each node sends, in order.
    static final class RecordingNetwork implements ClusterNetwork {
        record Sent(NodeId to, ProtocolMessage message) {}

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        private final Set<NodeId> peers;
        private final BiConsumer<NodeId, ProtocolMessage> tap;

        RecordingNetwork(Set<NodeId> peers) {
            this(peers, (_, _) -> {});
        }

        RecordingNetwork(Set<NodeId> peers, BiConsumer<NodeId, ProtocolMessage> tap) {
            this.peers = peers;
            this.tap = tap;
        }

        <T extends ProtocolMessage> List<T> sentTo(NodeId peer, Class<T> type) {
            return sent.stream()
                       .filter(s -> s.to().equals(peer))
                       .map(Sent::message)
                       .filter(type::isInstance)
                       .map(type::cast)
                       .toList();
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            sent.add(new Sent(nodeId, message));
            tap.accept(nodeId, message);

            return Unit.unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return Unit.unit();
        }

        @Override
        public void connect(ConnectNode connectNode) {}

        @Override
        public void disconnect(DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handleSend(Send send) {}

        @Override
        public void handleBroadcast(Broadcast broadcast) {}

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        @Override
        public int connectedNodeCount() {
            return peers.size();
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return peers;
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }
    }
}
