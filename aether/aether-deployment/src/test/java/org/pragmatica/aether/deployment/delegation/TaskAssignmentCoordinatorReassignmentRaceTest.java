// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.delegation;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue.AssignmentStatus;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Theme E #189: when reassignment decision and the consensus Put race against a
/// concurrent ACTIVE write, the per-group lock + in-lock re-read MUST detect the
/// flip and skip the Put. Asserted by simulating the race deterministically with
/// a `KVStore` subclass whose `get()` flips from FAILED to ACTIVE between the
/// initial `forEach` snapshot and the lock-protected re-read.
class TaskAssignmentCoordinatorReassignmentRaceTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();

    @Test
    void concurrentActiveWrite_doesNotTriggerReassignment() throws InterruptedException {
        var router = MessageRouter.mutable();
        var raceStore = new RaceTriggeringKvStore(router);
        // Seed: group APP_HTTP_REQUEST_ROUTING currently FAILED on PEER_A.
        // forEach() will surface this state to the FSM's assignmentMap so
        // identifyGroupsNeedingAssignment marks it for reassignment.
        var group = TaskGroup.values()[0];
        var seedKey = TaskAssignmentKey.taskAssignmentKey(group);
        var seedFailed = new TaskAssignmentValue(PEER_A, System.currentTimeMillis(), AssignmentStatus.FAILED, "");
        raceStore.directPut(seedKey, seedFailed);
        // Arm the race: when the FSM later issues `kvStore.get(seedKey)` from
        // inside the per-group lock, return ACTIVE — simulating a concurrent
        // writer that flipped the assignment between decision and Put.
        var seedActive = new TaskAssignmentValue(PEER_A, System.currentTimeMillis(), AssignmentStatus.ACTIVE, "");
        raceStore.armPostForeachOverride(seedKey, seedActive);

        var clusterNode = new RecordingClusterNode();
        var topology = new SimpleTopology(SELF, PEER_A);

        var coordinator = TaskAssignmentCoordinator.taskAssignmentCoordinator(SELF,
                                                                              clusterNode,
                                                                              raceStore,
                                                                              topology,
                                                                              timeSpan(60).seconds());
        coordinator.onLeaderChange(LeaderNotification.leaderChange(Option.some(SELF), true));

        // Active.onEntry triggers reconcile() synchronously on the dispatching thread.
        // The recording cluster node captures any consensus apply call.
        var deadline = System.currentTimeMillis() + 1000L;
        while (clusterNode.applyInvocations() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        var attempts = clusterNode.commandsForGroup(group);
        assertThat(attempts)
            .as("Per-group lock + in-lock re-read must detect the ACTIVE flip and skip the Put")
            .isEmpty();
    }

    /// Minimal KVStore that lets a test stamp values directly (bypassing consensus)
    /// AND override the per-key result of `get()` after `forEach` has completed —
    /// simulating a concurrent writer flipping the value between snapshot and Put.
    private static final class RaceTriggeringKvStore extends KVStore<AetherKey, AetherValue> {
        private volatile AetherKey overrideKey;
        private volatile AetherValue overrideValue;
        private volatile boolean foreachCompleted;

        RaceTriggeringKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void directPut(AetherKey key, AetherValue value) {
            super.process(new KVCommand.Put<>(key, value));
        }

        void armPostForeachOverride(AetherKey key, AetherValue value) {
            this.overrideKey = key;
            this.overrideValue = value;
        }

        @Override public Option<AetherValue> get(AetherKey key) {
            if (foreachCompleted && key.equals(overrideKey)) {return Option.some(overrideValue);}
            return super.get(key);
        }

        @Override public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            super.forEach(keyClass, valueClass, consumer);
            foreachCompleted = true;
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(io.netty.buffer.ByteBuf byteBuf) {return null;}
        };
    }

    /// Captures every `apply()` so the test can assert no reassignment Put landed.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Integer> applyCount = new AtomicReference<>(0);

        int applyInvocations() {return applyCount.get();}

        List<KVCommand<AetherKey>> commandsForGroup(TaskGroup group) {
            synchronized (applied) {
                return applied.stream()
                              .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put
                                             && put.key() instanceof TaskAssignmentKey k
                                             && k.taskGroup() == group)
                              .toList();
            }
        }

        @Override public NodeId self() {return SELF;}
        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);
            applyCount.updateAndGet(v -> v + 1);
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }

    /// Minimal TopologyManager exposing two healthy core members; the FSM's
    /// `collectHealthyCoreNodes` ignores everything except `topology()` + `isPassive`.
    private static final class SimpleTopology implements TopologyManager {
        private final NodeId self;
        private final List<NodeId> all;

        SimpleTopology(NodeId self, NodeId other) {
            this.self = self;
            this.all = List.of(self, other);
        }

        @Override public NodeInfo self() {
            return NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        }

        @Override public Option<NodeInfo> get(NodeId id) {return Option.none();}
        @Override public int clusterSize() {return all.size();}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.none();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return timeSpan(1).seconds();}
        @Override public TimeSpan helloTimeout() {return timeSpan(1).seconds();}
        @Override public Option<TlsConfig> tls() {return Option.empty();}
        @Override public Option<NodeState> getState(NodeId id) {return Option.none();}
        @Override public List<NodeId> topology() {return all;}
    }
}
