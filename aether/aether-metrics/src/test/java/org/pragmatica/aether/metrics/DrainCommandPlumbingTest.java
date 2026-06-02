// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.fsm.ClusterSyncContext;
import org.pragmatica.aether.metrics.fsm.ClusterSyncState;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.statemachine.Fsm;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Membership v2 (B5a, broadcast model) — leader→core DRAIN carried as the GLOBAL `drainNodes`
/// set on a single uniform broadcast ping. Three concerns: the leader-local `DrainCommandRegistry`,
/// the dispatch-side stamping in `ClusterSyncContext.broadcastPing`, and the receive-side handler
/// invocation in `ClusterSyncCollector.onClusterSyncPing` (self-check `drainNodes.contains(self)`).
class DrainCommandPlumbingTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();
    private static final NodeId LEADER = NodeId.nodeId("leader").unwrap();

    @Nested
    class Registry {
        @Test
        void requestDrain_thenIsDrainRequested_isTrue() {
            var registry = DrainCommandRegistry.drainCommandRegistry();
            registry.requestDrain(PEER_A);

            assertThat(registry.isDrainRequested(PEER_A)).isTrue();
            assertThat(registry.isDrainRequested(PEER_B)).isFalse();
            assertThat(registry.drainTargets()).containsExactly(PEER_A);
        }

        @Test
        void clearDrain_removesTarget() {
            var registry = DrainCommandRegistry.drainCommandRegistry();
            registry.requestDrain(PEER_A);
            registry.clearDrain(PEER_A);

            assertThat(registry.isDrainRequested(PEER_A)).isFalse();
            assertThat(registry.drainTargets()).isEmpty();
        }

        @Test
        void requestDrain_isIdempotent() {
            var registry = DrainCommandRegistry.drainCommandRegistry();
            registry.requestDrain(PEER_A);
            registry.requestDrain(PEER_A);

            assertThat(registry.drainTargets()).containsExactly(PEER_A);
        }
    }

    @Nested
    class Dispatch {
        @Test
        void broadcastPing_supplierHasTargets_pingCarriesDrainNodes() {
            var network = new RecordingNetwork();
            var ctx = buildContext(network, () -> Set.of(PEER_A));

            ctx.broadcastPing(Epoch.epoch(1L, 0L), 1L);

            assertThat(network.broadcastPings).hasSize(1);
            assertThat(network.broadcastPings.get(0).drainNodes()).containsExactly(PEER_A);
            assertThat(network.sentPings).as("single broadcast — no per-peer unicast").isEmpty();
        }

        @Test
        void broadcastPing_supplierEmpty_pingCarriesEmptyDrainNodes() {
            var network = new RecordingNetwork();
            var ctx = buildContext(network, Set::of);

            ctx.broadcastPing(Epoch.epoch(1L, 0L), 1L);

            assertThat(network.broadcastPings).hasSize(1);
            assertThat(network.broadcastPings.get(0).drainNodes()).isEmpty();
        }

        @Test
        void broadcastPing_defaultConstruction_pingCarriesEmptyDrainNodes() {
            var network = new RecordingNetwork();
            var ctx = buildContextDefault(network);

            ctx.broadcastPing(Epoch.epoch(1L, 0L), 1L);

            assertThat(network.broadcastPings).hasSize(1);
            assertThat(network.broadcastPings.get(0).drainNodes()).isEmpty();
        }
    }

    @Nested
    class Receive {
        @Test
        void onClusterSyncPing_drainNodesContainsSelf_invokesHandler() {
            var network = new RecordingNetwork();
            var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
            var calls = new AtomicInteger();
            collector.setDrainCommandHandler(calls::incrementAndGet);

            collector.onClusterSyncPing(drainPing());

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        void onClusterSyncPing_drainNodesAbsentSelf_doesNotInvokeHandler() {
            var network = new RecordingNetwork();
            var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
            var calls = new AtomicInteger();
            collector.setDrainCommandHandler(calls::incrementAndGet);

            collector.onClusterSyncPing(otherDrainPing());

            assertThat(calls.get()).isZero();
        }

        @Test
        void onClusterSyncPing_emptyDrainNodes_doesNotInvokeHandler() {
            var network = new RecordingNetwork();
            var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
            var calls = new AtomicInteger();
            collector.setDrainCommandHandler(calls::incrementAndGet);

            collector.onClusterSyncPing(nonePing());

            assertThat(calls.get()).isZero();
        }

        @Test
        void onClusterSyncPing_repeatedDrain_invokesHandlerEachTime() {
            var network = new RecordingNetwork();
            var collector = ClusterSyncCollector.clusterSyncCollector(SELF, network);
            var calls = new AtomicInteger();
            collector.setDrainCommandHandler(calls::incrementAndGet);

            collector.onClusterSyncPing(drainPing());
            collector.onClusterSyncPing(drainPing());

            assertThat(calls.get()).isEqualTo(2);
        }
    }

    private static ClusterSyncPing drainPing() {
        return new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Set.of(), Set.of(SELF));
    }

    private static ClusterSyncPing otherDrainPing() {
        return new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Set.of(), Set.of(PEER_A));
    }

    private static ClusterSyncPing nonePing() {
        return new ClusterSyncPing(LEADER, Map.of(), 0L, 0L, 0L, Set.of(), Set.of());
    }

    private static ClusterSyncContext buildContext(ClusterNetwork network, Supplier<Set<NodeId>> drainTargets) {
        var ctxRef = new AtomicReference<ClusterSyncContext>();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> factory =
            fsm -> {
                var ctx = new ClusterSyncContext(fsm,
                                                 SELF,
                                                 network,
                                                 new NoopClusterSyncCollector(),
                                                 TimeSpan.timeSpan(1).hours(),
                                                 () -> 1L,
                                                 HealthSignalSink.noop(),
                                                 3,
                                                 () -> Epoch.epoch(1L, 0L),
                                                 PeerObservationStore.peerObservationStore(),
                                                 PeriodicObservationConfig.defaultConfig(),
                                                 () -> true,
                                                 drainTargets);
                ctxRef.set(ctx);
                return ctx.dormant();
            };
        Fsm.fsm("drain-command-test", factory);
        return ctxRef.get();
    }

    private static ClusterSyncContext buildContextDefault(ClusterNetwork network) {
        var ctxRef = new AtomicReference<ClusterSyncContext>();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> factory =
            fsm -> {
                var ctx = new ClusterSyncContext(fsm,
                                                 SELF,
                                                 network,
                                                 new NoopClusterSyncCollector(),
                                                 TimeSpan.timeSpan(1).hours(),
                                                 () -> 1L,
                                                 HealthSignalSink.noop(),
                                                 3,
                                                 () -> Epoch.epoch(1L, 0L),
                                                 PeerObservationStore.peerObservationStore());
                ctxRef.set(ctx);
                return ctx.dormant();
            };
        Fsm.fsm("drain-command-default-test", factory);
        return ctxRef.get();
    }

    /// `ClusterNetwork` stub that records every outbound `ClusterSyncPing` — broadcast and unicast
    /// separately so the broadcast model can be asserted (one broadcast, zero per-peer sends).
    private static final class RecordingNetwork implements ClusterNetwork {
        private final CopyOnWriteArrayList<ClusterSyncPing> sentPings = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<ClusterSyncPing> broadcastPings = new CopyOnWriteArrayList<>();

        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof ClusterSyncPing ping) {sentPings.add(ping);}
            return Unit.unit();
        }

        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {
            if (message instanceof ClusterSyncPing ping) {broadcastPings.add(ping);}
            return Unit.unit();
        }
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
