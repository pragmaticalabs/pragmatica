// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the ASSIGNED → ACTIVE transition flow and failure paths for SpokesmanPingLoop.
/// See `aether/docs/specs/cluster-generation-spec.md` §7.3.
class SpokesmanPingLoopLifecycleTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId GOV_A = NodeId.nodeId("gov-a").unwrap();

    private RecordingStatusWriter statusWriter;
    private SpokesmanPingLoop loop;

    @BeforeEach
    void setUp() {
        var network = new NoopNetwork();
        var rabiaTerm = new AtomicLong(7L);
        statusWriter = new RecordingStatusWriter();
        loop = SpokesmanPingLoop.spokesmanPingLoop(SELF,
                                                    network,
                                                    TimeSpan.timeSpan(1).seconds(),
                                                    rabiaTerm::get,
                                                    Map::of,
                                                    communityId -> Option.some(GOV_A),
                                                    statusWriter);
        loop.start();
    }

    @Test
    void assignedStatus_activatesAndWritesActive() {
        var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.epoch(7L, 0L), HlcTimestamp.ZERO, 1L);

        loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none()));

        assertThat(loop.isActive()).isTrue();
        assertThat(statusWriter.activeWrites).hasSize(1);
        assertThat(statusWriter.activeWrites.getFirst().self).isEqualTo(SELF);
        assertThat(statusWriter.activeWrites.getFirst().baseValue.communities()).containsExactly("pool-a");
    }

    @Test
    void assignedForOtherCoreNode_isIgnored() {
        var other = NodeId.nodeId("core-2").unwrap();
        var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.ZERO, HlcTimestamp.ZERO, 1L);

        loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(other), value), Option.none()));

        assertThat(loop.isActive()).isFalse();
        assertThat(statusWriter.activeWrites).isEmpty();
    }

    @Test
    void emptyCommunitiesOnAssigned_deactivatesWithoutWrite() {
        var value = SpokesmanValue.spokesmanValue(List.of(), Epoch.ZERO, HlcTimestamp.ZERO, 1L);

        loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none()));

        assertThat(loop.isActive()).isFalse();
        assertThat(statusWriter.activeWrites).isEmpty();
    }

    @Test
    void removeEvent_deactivates() {
        var assigned = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.ZERO, HlcTimestamp.ZERO, 1L);
        loop.onSpokesmanPut(new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), assigned), Option.none()));

        loop.onSpokesmanRemove(new ValueRemove<>(new KVCommand.Remove<>(SpokesmanKey.spokesmanKey(SELF)), Option.none()));

        assertThat(loop.isActive()).isFalse();
    }

    private static final class RecordingStatusWriter implements SpokesmanPingLoop.SpokesmanStatusWriter {
        final List<ActiveWrite> activeWrites = new ArrayList<>();
        final List<FailureWrite> failureWrites = new ArrayList<>();

        @Override public void writeActive(NodeId self, SpokesmanValue baseValue) {
            activeWrites.add(new ActiveWrite(self, baseValue));
        }

        @Override public void writeFailure(NodeId self, SpokesmanValue baseValue, String reason) {
            failureWrites.add(new FailureWrite(self, baseValue, reason));
        }
    }

    private record ActiveWrite(NodeId self, SpokesmanValue baseValue){}

    private record FailureWrite(NodeId self, SpokesmanValue baseValue, String reason){}

    private static final class NoopNetwork implements ClusterNetwork {
        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return Unit.unit();}
        @Override public void connect(ConnectNode connectNode) {}
        @Override public void disconnect(DisconnectNode disconnectNode) {}
        @Override public void listNodes(ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(Send send) {}
        @Override public void handleBroadcast(Broadcast broadcast) {}
        @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {return Unit.unit();}
        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        @Override public int connectedNodeCount() {return 0;}
        @Override public Set<NodeId> connectedPeers() {return Set.of();}
        @Override public Option<Server> server() {return Option.none();}
    }
}
