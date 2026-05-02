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
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;

import java.time.Instant;
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
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Auto-reassign safeguards for TaskAssignmentCoordinator.
///
/// Bug B coverage:
/// - Topology departure must arm the failed-node cooldown so a restarted node
///   doesn't immediately steal back the assignment.
/// - Tie-breaker on equal load must NOT prefer lexicographically-lowest NodeId,
///   otherwise a freshly-restarted node (load 0) wins every tie.
/// - Operator-driven `reassign(...)` must clear any stale cooldown for the target.
class TaskAssignmentCoordinatorAutoReassignTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("aaa-peer").unwrap();
    private static final NodeId PEER_B = nodeId("bbb-peer").unwrap();
    private static final NodeId PEER_C = nodeId("ccc-peer").unwrap();

    @Test
    void isOrphanedOrFailed_topologyDeparture_armsFailedNodeCooldown() throws InterruptedException {
        var router = MessageRouter.mutable();
        var kvStore = new RecordingKvStore(router);
        // Seed: METRICS-equivalent group is ACTIVE on PEER_A — but PEER_A is gone
        // from topology (only SELF and PEER_B are healthy).
        var group = TaskGroup.values()[0];
        var seed = new TaskAssignmentValue(PEER_A, System.currentTimeMillis(), AssignmentStatus.ACTIVE, "");
        kvStore.directPut(TaskAssignmentKey.taskAssignmentKey(group), seed);

        var clusterNode = new RecordingClusterNode(SELF);
        var topology = new SimpleTopology(SELF, List.of(SELF, PEER_B));
        var coordinator = TaskAssignmentCoordinator.taskAssignmentCoordinator(SELF,
                                                                              clusterNode,
                                                                              kvStore,
                                                                              topology,
                                                                              timeSpan(60).seconds());
        coordinator.onLeaderChange(LeaderNotification.leaderChange(Option.some(SELF), true));

        waitForApply(clusterNode, 1500L);

        // After reconcile, PEER_A must be in the cooldown set for `group`. We assert
        // indirectly: re-running reconcile with PEER_A back in topology should NOT
        // reassign back to PEER_A while cooldown is in effect.
        topology.setHealthy(List.of(SELF, PEER_A, PEER_B));
        var attemptsBefore = clusterNode.commandsForGroup(group).size();
        // Simulate the reassignment landed: surface the new assignment via direct put
        var current = clusterNode.lastAssignmentTo(group).unwrap();
        kvStore.directPut(TaskAssignmentKey.taskAssignmentKey(group),
                          new TaskAssignmentValue(current, System.currentTimeMillis(), AssignmentStatus.ACTIVE, ""));
        // Force a second reconcile by triggering another node-gone of a different group.
        // (Easiest path: directly call the public reassign on a different group to drive activity.)
        // Instead we re-leader to no-op then back to leader to re-trigger reconcile cleanly.
        coordinator.onLeaderChange(LeaderNotification.leaderChange(Option.some(PEER_B), false));
        coordinator.onLeaderChange(LeaderNotification.leaderChange(Option.some(SELF), true));
        Thread.sleep(50L);

        var allCommands = clusterNode.commandsForGroup(group);
        // None of the post-departure assignments should target PEER_A (still in cooldown).
        assertThat(allCommands).hasSizeGreaterThan(attemptsBefore - 1);
        assertThat(allCommands.stream().anyMatch(cmd -> targetOf(cmd).equals(PEER_A)))
            .as("PEER_A must remain in cooldown after topology departure; assignments must not loop back to it")
            .isFalse();
    }

    @Test
    void selectLeastLoadedNode_tiedLoad_doesNotPreferLowestNodeId() throws InterruptedException {
        var router = MessageRouter.mutable();
        var kvStore = new RecordingKvStore(router);
        // No prior assignments. All three peers have load 0. A purely natural-order
        // tie-breaker would assign every group to PEER_A (lowest id). We assert that
        // assignments are spread across ids, NOT all funneled to PEER_A.
        var clusterNode = new RecordingClusterNode(SELF);
        var topology = new SimpleTopology(SELF, List.of(SELF, PEER_A, PEER_B, PEER_C));
        var coordinator = TaskAssignmentCoordinator.taskAssignmentCoordinator(SELF,
                                                                              clusterNode,
                                                                              kvStore,
                                                                              topology,
                                                                              timeSpan(60).seconds());
        coordinator.onLeaderChange(LeaderNotification.leaderChange(Option.some(SELF), true));
        waitForApply(clusterNode, 1500L);

        var perGroupTargets = new HashMap<TaskGroup, NodeId>();
        for (var group : TaskGroup.values()) {
            clusterNode.lastAssignmentTo(group).onPresent(t -> perGroupTargets.put(group, t));
        }
        assertThat(perGroupTargets).as("Reconcile must produce assignments for all task groups")
                                          .hasSize(TaskGroup.values().length);

        var distinctTargets = perGroupTargets.values().stream().distinct().count();
        assertThat(distinctTargets)
            .as("Stable hash tie-breaker must spread tied-load assignments across more than one node")
            .isGreaterThan(1L);
    }

    @Test
    void writeAssignment_clearsFailedNodeCooldown_forTargetNode() throws InterruptedException {
        var router = MessageRouter.mutable();
        var kvStore = new RecordingKvStore(router);
        // Seed: group ACTIVE on PEER_A, but PEER_A absent from topology — arms cooldown.
        var group = TaskGroup.values()[0];
        kvStore.directPut(TaskAssignmentKey.taskAssignmentKey(group),
                          new TaskAssignmentValue(PEER_A, System.currentTimeMillis(), AssignmentStatus.ACTIVE, ""));
        var clusterNode = new RecordingClusterNode(SELF);
        var topology = new SimpleTopology(SELF, List.of(SELF, PEER_B));
        var coordinator = TaskAssignmentCoordinator.taskAssignmentCoordinator(SELF,
                                                                              clusterNode,
                                                                              kvStore,
                                                                              topology,
                                                                              timeSpan(60).seconds());
        coordinator.onLeaderChange(LeaderNotification.leaderChange(Option.some(SELF), true));
        waitForApply(clusterNode, 1500L);

        // Operator now wants to reassign back to PEER_A explicitly (PEER_A returned).
        topology.setHealthy(List.of(SELF, PEER_A, PEER_B));
        clusterNode.resetCommands();
        coordinator.reassign(group, PEER_A).await();

        var commands = clusterNode.commandsForGroup(group);
        assertThat(commands).hasSize(1);
        assertThat(targetOf(commands.getFirst())).isEqualTo(PEER_A);
    }

    private static NodeId targetOf(KVCommand<AetherKey> cmd) {
        if (cmd instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof TaskAssignmentValue tav) {
            return tav.assignedTo();
        }
        throw new IllegalStateException("Unexpected command shape: " + cmd);
    }

    private static void waitForApply(RecordingClusterNode node, long timeoutMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (node.applyInvocations() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    private static final class RecordingKvStore extends KVStore<AetherKey, AetherValue> {
        RecordingKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void directPut(AetherKey key, AetherValue value) {
            super.process(new KVCommand.Put<>(key, value));
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

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Integer> applyCount = new AtomicReference<>(0);

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        int applyInvocations() {return applyCount.get();}

        void resetCommands() {
            synchronized (applied) {
                applied.clear();
                applyCount.set(0);
            }
        }

        List<KVCommand<AetherKey>> commandsForGroup(TaskGroup group) {
            synchronized (applied) {
                return applied.stream()
                              .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put
                                             && put.key() instanceof TaskAssignmentKey k
                                             && k.taskGroup() == group)
                              .toList();
            }
        }

        Option<NodeId> lastAssignmentTo(TaskGroup group) {
            var matched = commandsForGroup(group);
            if (matched.isEmpty()) {return Option.none();}
            var last = matched.get(matched.size() - 1);
            if (last instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof TaskAssignmentValue tav) {
                return Option.some(tav.assignedTo());
            }
            return Option.none();
        }

        @Override public NodeId self() {return self;}
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

    private static final class SimpleTopology implements TopologyManager {
        private final NodeId self;
        private volatile List<NodeId> healthy;

        SimpleTopology(NodeId self, List<NodeId> healthy) {
            this.self = self;
            this.healthy = List.copyOf(healthy);
        }

        void setHealthy(List<NodeId> healthy) {
            this.healthy = List.copyOf(healthy);
        }

        @Override public NodeInfo self() {
            return NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        }

        @Override public Option<NodeInfo> get(NodeId id) {return Option.none();}
        @Override public int clusterSize() {return healthy.size();}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.none();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return timeSpan(1).seconds();}
        @Override public TimeSpan helloTimeout() {return timeSpan(1).seconds();}
        @Override public Option<TlsConfig> tls() {return Option.empty();}

        @Override public Option<NodeState> getState(NodeId id) {
            if (!healthy.contains(id)) {return Option.none();}
            var info = NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 5000).unwrap());
            return Option.some(NodeState.healthy(info, Instant.now()));
        }

        @Override public List<NodeId> topology() {return healthy;}
    }
}
