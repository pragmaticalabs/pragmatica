// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.metrics.fsm.ClusterSyncContext;
import org.pragmatica.aether.metrics.fsm.ClusterSyncState;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// TW-O17 of the worker-replica-feed design (W-OBS): the non-KV items a worker needs (peer directory, endpoint directory, DHT replication)
/// are meant to ride the existing `ClusterSync` ping and pong as one `ClusterDirectory` payload, and a plain member must install the
/// leader's directory from a responder's pong (NT-O44).
///
/// Today (enabled half): the leader's ping reaches only the recipients the node's ping-target predicate admits (cores and governors,
/// `AetherNode`'s `setPingTargetEligibility`), so a plain member is sent nothing; a recipient that is not a core gets only the
/// non-core ping; and neither `ClusterSyncPing` nor `ClusterSyncPong` has a directory slot.
class ClusterDirectoryTripwireTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final NodeId CORE = NodeId.nodeId("core").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("governor").unwrap();
    private static final NodeId MEMBER = NodeId.nodeId("member").unwrap();
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();

    private static ClusterSyncContext leaderContext(RecordingNetwork network) {
        var ctxRef = new AtomicReference<ClusterSyncContext>();
        Function<Fsm<ClusterSyncState, ClusterFsmEvent>, ClusterSyncState> factory = fsm -> {
            var ctx = new ClusterSyncContext(fsm,
                                             SELF,
                                             network,
                                             new NoopClusterSyncCollector(),
                                             TimeSpan.timeSpan(1).hours(),
                                             () -> 1L,
                                             3,
                                             () -> Epoch.epoch(0L, 1L, 0L),
                                             PeerObservationStore.peerObservationStore(),
                                             PeriodicObservationConfig.defaultConfig(),
                                             () -> true,
                                             () -> Set.of(DRAINED));

            ctxRef.set(ctx);

            return ctx.dormant();
        };

        Fsm.fsm("cluster-directory-tripwire", factory);

        return ctxRef.get();
    }

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void leaderPing_isSentOnlyToCoresAndGovernors_andNeverToAPlainMember_today() {
        var network = new RecordingNetwork(Set.of(CORE, GOVERNOR, MEMBER));
        var ctx = leaderContext(network);

        ctx.setPingTargetEligibility(peer -> peer.equals(CORE) || peer.equals(GOVERNOR));
        ctx.setMetricsRecipient(CORE::equals);
        ctx.broadcastPing(Epoch.epoch(0L, 1L, 0L), 1L);

        assertThat(network.sent.keySet()).as("control: the leader does ping its recipients (the drain set rides the authority ping)").containsExactlyInAnyOrder(CORE, GOVERNOR);
        assertThat(network.sent.get(CORE)).allSatisfy(ping -> assertThat(ping.drainNodes()).containsExactly(DRAINED));
        assertThat(network.sent).as("TW-O17: a plain member is sent nothing, so there is no path for leader-authored content to a member").doesNotContainKey(MEMBER);
        assertThat(network.sent.get(GOVERNOR)).as("a recipient that is not a core gets only the non-core ping: no readiness view, no dispatched set")
                                              .allSatisfy(ping -> {
                                                  assertThat(ping.readinessView()).isEmpty();
                                                  assertThat(ping.dispatchedNodes()).isEmpty();
                                              });
    }

    @Test
    void clusterSyncMessages_haveNoDirectorySlot_today() {
        assertThat(componentNames(ClusterSyncPing.class)).as("TW-O17: ClusterSyncPing has no directory slot; its last component is carriesAuthority").doesNotContain("directory", "observedDirectoryGeneration")
                                                          .last().isEqualTo("carriesAuthority");
        assertThat(componentNames(ClusterSyncPong.class)).as("TW-O17: ClusterSyncPong has no directory slot beyond the observed-epoch triple; its last component is readyCandidate").doesNotContain("directory", "observedDirectoryGeneration")
                                                          .last().isEqualTo("readyCandidate");
    }

    @Disabled("W2/W4 (ClusterDirectory): enable when ClusterDirectory rides ClusterSyncPing/ClusterSyncPong; replace the body with NT-O44")
    @Test
    void aMember_installsTheLeadersDirectory_viaAResponderPong() {
        var present = componentNames(ClusterSyncPong.class).contains("directory");

        throw new AssertionError((present ? "ClusterSyncPong.directory landed: replace this body with the real check, which is: " : "ClusterSyncPong.directory is not there yet; the property to check once it is: ")
                                 + "NT-O44: a member that pings its core uplinks installs the leader's ClusterDirectory from a responder's pong, only from a core, the leader or its own governor, and only when strictly newer than the one it holds");
    }

    /// Records, per recipient, every `ClusterSyncPing` the context sends.
    private static final class RecordingNetwork implements ClusterNetwork {
        private final Map<NodeId, List<ClusterSyncPing>> sent = new ConcurrentHashMap<>();
        private final Set<NodeId> peers;

        RecordingNetwork(Set<NodeId> peers) {
            this.peers = peers;
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            if (message instanceof ClusterSyncPing ping) {
                sent.merge(nodeId, List.of(ping), (a, b) -> java.util.stream.Stream.concat(a.stream(), b.stream()).toList());
            }

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
